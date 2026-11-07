package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * [UrlConnectionDownloader] against a real HTTP server on loopback — the one test that exercises
 * the resume protocol the way an origin speaks it: a 206 that continues a fragment in append mode,
 * a 200 that means the Range header was ignored, and a pause honoured at a chunk boundary.
 *
 * A fake [HttpDownloader] cannot test any of this, because the resume semantics live in the
 * production implementation's request headers and status-code handling, not in the interface.
 */
class UrlConnectionDownloaderTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val receivedRanges: MutableList<String> = mutableListOf()

    init {
        server.createContext("/rootfs.tar.gz") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            receivedRanges.add(range ?: "none")
            val bytes = BODY
            if (range != null && range.startsWith("bytes=")) {
                val start = range.removePrefix("bytes=").trim().toLong()
                val slice = bytes.copyOfRange(start.toInt(), bytes.size)
                exchange.responseHeaders.add("Content-Range", "bytes $start-${bytes.size - 1}/${bytes.size}")
                exchange.sendResponseHeaders(206, slice.size.toLong())
                exchange.responseBody.use { it.write(slice) }
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private val url: String get() = "http://127.0.0.1:${server.address.port}/rootfs.tar.gz"

    @Test
    fun `a fresh transfer is a plain 200 with the whole body`() {
        val target = newTarget()
        UrlConnectionDownloader().download(url, target, 0L, { false }, { _, _ -> })

        assertThat(target.readBytes()).isEqualTo(BODY)
        assertThat(receivedRanges.single()).isEqualTo("none")
    }

    @Test
    fun `a resume request continues the fragment from the offset`() {
        val keep = BODY.size / 3
        val target = newTarget()
        target.writeBytes(BODY.copyOf(keep))

        UrlConnectionDownloader().download(url, target, keep.toLong(), { false }, { _, _ -> })

        assertThat(target.readBytes()).isEqualTo(BODY)
        assertWithMessage("the Range header the origin saw").that(receivedRanges.single()).isEqualTo("bytes=$keep-")
    }

    @Test
    fun `an origin that ignores the range is a failure, not a silently wrong file`() {
        val keep = BODY.size / 3
        val target = newTarget()
        target.writeBytes(BODY.copyOf(keep))
        server.createContext("/no-range") {
            receivedRanges.add(it.requestHeaders.getFirst("Range") ?: "none")
            it.sendResponseHeaders(200, BODY.size.toLong())
            it.responseBody.use { body -> body.write(BODY) }
        }

        val thrown =
            runCatching {
                UrlConnectionDownloader().download(
                    "http://127.0.0.1:${server.address.port}/no-range",
                    target,
                    keep.toLong(),
                    { false },
                    { _, _ -> },
                )
            }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(OriginIgnoredRangeException::class.java)
        // The fragment is untouched - what to do with it is the caller's call, and the installer
        // deletes it before retrying from byte zero.
        assertThat(target.readBytes()).isEqualTo(BODY.copyOf(keep))
    }

    @Test
    fun `the pause fires at a chunk boundary and keeps what was written`() {
        val target = newTarget()
        val chunks = AtomicInteger(0)
        var reportedTotal = 0L
        var reportedLast = 0L

        val thrown =
            runCatching {
                UrlConnectionDownloader().download(
                    url,
                    target,
                    0L,
                    { chunks.get() >= 2 },
                    { received, total ->
                        chunks.incrementAndGet()
                        reportedLast = received
                        reportedTotal = total
                    },
                )
            }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(DownloadPaused::class.java)
        val fragment = target.length()
        assertThat(fragment).isEqualTo(reportedLast)
        assertThat(fragment).isGreaterThan(0L)
        assertThat(fragment).isLessThan(BODY.size.toLong())
        assertThat(reportedTotal).isEqualTo(BODY.size.toLong())
        // The fragment is a prefix of the body, byte for byte: a resume appending at this offset
        // reconstructs the original.
        assertThat(target.readBytes()).isEqualTo(BODY.copyOf(fragment.toInt()))
    }

    private fun newTarget(): File =
        Files.createTempDirectory("downloader-test").toFile().resolve("rootfs.tar.gz").apply { deleteOnExit() }

    companion object {
        /** Big enough to span several 64 KiB chunks, so the pause has a boundary to fire at. */
        private val BODY = ByteArray(300 * 1024) { (it % 251).toByte() }
    }
}
