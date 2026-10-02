package dev.eclipse.ssh.ssh

import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.feature.bandwidth.RateLimiter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import org.apache.sshd.sftp.client.SftpClient

/**
 * Moves file bytes over SFTP, and is the one place the bandwidth cap is enforced.
 *
 * [settings] is nullable so a test can construct this the way it always did — the call sites in
 * `SshIntegrationTest` and `TransferProgressPersistenceTest` pass nothing — and a null repository
 * means [limiter] answers `UNLIMITED`, which is the same object the "limit disabled" setting
 * produces.
 *
 * Deliberately **not** `@Inject`-annotated, and provided by `AppModule` instead. A `@Inject`
 * constructor whose parameter has a Kotlin default is a trap: the default is a Kotlin-only concept,
 * so the generated factory must still be handed a real [SettingsRepository], and KSP emits a
 * provider that reads the parameter as a normal injection point. That works, but it makes the
 * nullable default above read as "injectable without a repository" when it is not, and the
 * production graph would be one missing `@Provides` away from a compile error whose message points
 * at `SettingsRepository` rather than at this class. An explicit provider states the one wiring that
 * exists.
 */
@Singleton
class SftpTransferManager(
    private val settings: SettingsRepository? = null,
) {
    /**
     * The limiter for one transfer, from the current setting.
     *
     * Built per transfer rather than held as a field for two reasons. The setting is a preference the
     * user can change between two transfers, and a cached limiter would honour the old value until
     * the process restarted; and a limiter carries a full bucket and a refill clock, so sharing one
     * across concurrent transfers would let each of them spend the others' tokens and move up to N×
     * the configured rate. One limiter per transfer is also what makes the token-bucket's total
     * "burst" the per-transfer budget the setting's string describes.
     *
     * The read is the first value of a DataStore flow, and every caller already runs this inside
     * `withContext(Dispatchers.IO)` — [upload] and [download] enter it there — so the read is
     * off-main, and a blocked read cannot park the transfer on the main thread the way a Settings
     * write could.
     */
    private suspend fun limiter(): RateLimiter {
        val bytesPerSecond = settings?.settings?.first()?.transferBytesPerSecond ?: 0L
        return if (bytesPerSecond <= 0L) RateLimiter.UNLIMITED else RateLimiter(bytesPerSecond)
    }

    /**
     * Uploads [source] to [remotePath].
     *
     * The body is inside `withContext(Dispatchers.IO)` because `sftp.write(remotePath)` is a blocking
     * round-trip to the server that opens a remote handle, and as an argument expression it used to
     * be evaluated on whatever dispatcher the caller was on — [copy] only switched to IO once it was
     * already holding the result. Every caller today launches on IO, so this was latent rather than
     * live, but the one that did not would have blocked the main thread on the network.
     *
     * `source.use` even though [copy] closes it as well: that same `sftp.write` throws for an
     * unwritable path, a missing parent directory or a full remote disk, and on that path [copy] is
     * never entered, so the caller's `ContentResolver` descriptor stayed open. Closing twice is a
     * no-op.
     */
    suspend fun upload(
        sftp: SftpClient,
        source: InputStream,
        remotePath: String,
        totalBytes: Long? = null,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        // Resolved once, outside the loop: the limiter is a per-transfer object (see [limiter]), and
        // reading the setting on every chunk would both re-read DataStore thousands of times and let
        // a mid-transfer change swap the bucket out from under a transfer already in progress.
        val limiter = limiter()
        source.use { copy(it, sftp.write(remotePath), totalBytes, onProgress, limiter) }
    }

    /**
     * Downloads [remotePath] into [destination].
     *
     * `destination.use` even though [copy] closes it as well, for the same reason [upload] wraps its
     * source: `sftp.read` throws for a file that has been deleted or made unreadable since the
     * transfer was queued, and on that path [copy] is never entered, so the caller's
     * `ContentResolver` descriptor stayed open. One leaked descriptor per failed download, on the
     * path the retry ladder walks most often. Closing twice is a no-op.
     */
    suspend fun download(
        sftp: SftpClient,
        remotePath: String,
        destination: OutputStream,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val limiter = limiter()
        val total = runCatching { sftp.stat(remotePath).size }.getOrNull()
        destination.use { target -> copy(sftp.read(remotePath), target, total, onProgress, limiter) }
    }

    suspend fun resumeDownload(
        sftp: SftpClient,
        remotePath: String,
        destination: OutputStream,
        existingBytes: Long,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val limiter = limiter()
        val total = runCatching { sftp.stat(remotePath).size }.getOrNull()
        // Outermost, so the destination is released even when the open below throws — which is what
        // happens whenever the remote file was deleted or made unreadable while the transfer sat
        // queued. That path used to leak a descriptor per failed resume.
        destination.use { target ->
            val handle = sftp.open(remotePath, EnumSet.of(SftpClient.OpenMode.Read))
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var offset = existingBytes
                while (true) {
                    coroutineContext.ensureActive()
                    // Charged before the read, against the buffer's full capacity: the read below may
                    // return fewer bytes at the end of the file, so charging what was actually read
                    // would need the charge to sit after a call that can block on the network — and
                    // the transfer's whole cost is that round-trip. A slight over-charge on the last
                    // partial buffer is the honest direction to be wrong in.
                    limiter.await(buffer.size.toLong())
                    val count = sftp.read(handle, offset, buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                    offset += count
                    onProgress(offset, total)
                }
                target.flush()
            } finally {
                handle.close()
            }
        }
    }

    /**
     * Continues an interrupted upload.
     *
     * The offset is taken from the remote file's real size when the server can report it, so
     * a stale [existingBytes] (progress is persisted at most every 200 ms) cannot re-send a
     * region that already arrived. Writes are absolute-offset, so the untouched prefix
     * survives — the handle is opened without Truncate.
     */
    suspend fun resumeUpload(
        sftp: SftpClient,
        source: InputStream,
        remotePath: String,
        existingBytes: Long,
        totalBytes: Long? = null,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val limiter = limiter()
        val startAt = runCatching { sftp.stat(remotePath).size }.getOrNull()?.takeIf { it >= 0 } ?: existingBytes
        // Outermost for the same reason as in resumeDownload: sftp.open throws for a path that has
        // become unwritable, and the source descriptor has to be released on that path too.
        source.use { input ->
            input.advanceExactly(startAt)
            val handle = sftp.open(remotePath, EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create))
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var offset = startAt
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    // Charged after the read and for what was actually read, unlike the download
                    // sibling above: a local read costs nothing to wait on, so measuring exactly the
                    // bytes this loop is about to push is free here. It is also what keeps the last
                    // partial buffer from being charged as a full one.
                    limiter.await(count.toLong())
                    sftp.write(handle, offset, buffer, 0, count)
                    offset += count
                    onProgress(offset, totalBytes)
                }
            } finally {
                handle.close()
            }
        }
    }

    /**
     * Advances this stream by exactly [bytes], or throws.
     *
     * [InputStream.skip] is the fast path — on a local file descriptor it is one `lseek` — but it is
     * permitted to return short, or 0, *without* being at EOF, and on Android it throws outright for
     * a stream that cannot seek. A resumed upload always reads its source from
     * `ContentResolver.openInputStream`, and a document served by a cloud provider rather than by
     * local storage arrives as a pipe, which is precisely that stream. Reading and discarding is the
     * fallback that works everywhere.
     *
     * The previous version gave up quietly on a short skip and then wrote whatever the stream handed
     * it at the resumed offset, so the file on the server got a hole followed by a duplicated block
     * and the transfer was still reported COMPLETE. Silent corruption of the user's data, on the one
     * code path whose entire purpose is a network that is already misbehaving.
     *
     * Running out of input means the remote file is longer than the source, so the bytes the server
     * is missing do not exist locally and there is nothing honest to continue with. That is raised
     * rather than swallowed: the caller's retry ladder handles it and, if it keeps happening, tells
     * the user — where the old behaviour was to return normally and mark the transfer complete.
     */
    private suspend fun InputStream.advanceExactly(bytes: Long) {
        var remaining = bytes
        // Only allocated when there is something to skip, and only when skip() will not do it.
        var scratch: ByteArray? = null
        while (remaining > 0) {
            coroutineContext.ensureActive()
            // A stream that cannot seek reports it by throwing here; that is not a failure of the
            // transfer, just of the shortcut, so fall through to reading. A genuine read error is
            // still raised by the read below rather than hidden.
            val skipped = runCatching { skip(remaining) }.getOrDefault(0L).coerceIn(0L, remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val buffer = scratch ?: ByteArray(DEFAULT_BUFFER_SIZE).also { scratch = it }
            val count = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (count < 0) {
                throw IOException(
                    "Cannot resume this upload: the source ends $remaining byte(s) short of the " +
                        "$bytes byte(s) already on the server.",
                )
            }
            remaining -= count
        }
    }

    private suspend fun copy(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long?,
        onProgress: suspend (bytes: Long, total: Long?) -> Unit,
        limiter: RateLimiter,
    ) = withContext(Dispatchers.IO) {
        input.use { source ->
            output.use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                // Always from zero: this is the whole-file path. Resuming from an offset is
                // resumeUpload/resumeDownload, which position their own handles and never come
                // through here - an `initialBytes` parameter lived here for that case and no caller
                // could reach it, so a reader could believe this function knew how to resume.
                var copied = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    // Charged after the read and for what was actually read. The read on the source
                    // side is a local ContentResolver stream or a buffer the SFTP client already
                    // holds, so waiting on the limiter here is waiting on the limiter and nothing
                    // else — and charging before the read would spend a full buffer's tokens on the
                    // final short read (or the -1 that ends the loop) and report a rate below the
                    // configured one. [resumeDownload] charges before its read for the opposite
                    // reason: there the read *is* the network round-trip.
                    limiter.await(count.toLong())
                    target.write(buffer, 0, count)
                    copied += count
                    onProgress(copied, totalBytes)
                }
                target.flush()
            }
        }
    }
}
