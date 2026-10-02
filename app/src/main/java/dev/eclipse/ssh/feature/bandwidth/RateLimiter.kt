package dev.eclipse.ssh.feature.bandwidth

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * A token-bucket rate limiter for SFTP transfers.
 *
 * Limits a stream of bytes to a long-run average of [bytesPerSecond] bytes per
 * second, with bursts up to [bucketSize] bytes. The bucket refills at the
 * configured rate; every byte that arrives drains one token, and a request
 * that finds the bucket empty waits the time it takes to accumulate the
 * tokens it needs.
 *
 * One way to use it:
 *  - **[await]** (and its alias **[charge]**): call before each read/write with the number of bytes
 *    that operation will move. The coroutine suspends if the budget for this moment is exhausted,
 *    which is the right shape for an SFTP transfer loop: "I want to push N bytes, give me a green
 *    light". [bytes] may exceed the bucket; see [await] for why it still terminates.
 *
 * This class had a second entry point in an earlier draft — a `wrap()` returning a
 * `RateLimitedInputStream` for byte sources the transfer loop does not drive — and it was never
 * written; every transfer this app makes goes through [await] on a loop it owns, so a stream
 * adapter has no caller. The KDoc promised it anyway, which is why the paragraph is gone rather
 * than left as a description of code that is not here.
 *
 * `bytesPerSecond = 0` (or negative) is the "no limit" sentinel: [await] returns immediately. The
 * transfer coordinator uses this so a "limit disabled" setting costs nothing.
 *
 * The bucket is not thread-safe; the only coroutine that touches a transfer
 * is the one driving the upload or download, so the lack of locking is
 * deliberate.
 */
class RateLimiter(
    private val bytesPerSecond: Long,
    private val bucketSize: Long = DEFAULT_BUCKET,
) {
    init {
        require(bucketSize > 0) { "bucketSize must be > 0" }
    }

    private var available: Double = bucketSize.toDouble()
    private var lastRefillNanos: Long = System.nanoTime()

    /**
     * Suspends until [bytes] tokens are available, then consumes them.
     *
     * [bytes] may be larger than the bucket. The charge is taken in [bucketSize]-sized pieces, each
     * of which fits, and it is that which makes the wait terminate: [refill] clamps the bucket at
     * [bucketSize], so one request for more than the bucket can never be satisfied by any amount of
     * waiting — the single `while (available >= bytes)` loop this replaces spun on the main
     * transfer thread forever, and for the whole-file path that was every transfer of a file over
     * [DEFAULT_BUCKET] once the SFTP read size was raised past it. Slicing also keeps the long-run
     * average honest, which charging the whole amount only at the end would not: the wait for the
     * last slice still happens.
     *
     * Cooperative cancellation: a cancelled transfer wakes from the delay
     * the next time the bucket would refill. The poll loop's [ensureActive]
     * is the only guarantee here; the underlying [delay] is itself
     * cancellable, so the wake-up latency is bounded by the chosen
     * [POLL_INTERVAL_MS].
     */
    suspend fun await(bytes: Long) {
        if (bytesPerSecond <= 0L) return
        if (bytes <= 0) return
        var remaining = bytes
        while (remaining > 0) {
            coroutineContext.ensureActive()
            refill()
            // The most that can ever be taken at once. Sized against the bucket rather than against
            // the start of [remaining], because a request that began larger than the bucket must
            // still be paid in pieces — and the final piece is whittled down to what is left.
            val slice = minOf(remaining, bucketSize)
            if (available >= slice) {
                available -= slice
                remaining -= slice
                continue
            }
            // Wait for the whole slice, not for what is left of a partial bucket: charging a piece
            // the moment it is affordable would let a series of sub-bucket reads pay nothing once
            // the bucket is drained, and the average would drift above the configured rate. The
            // floor of 1 ms matters — a fractional deficit that rounds to 0 would busy-spin here.
            val deficit = slice - available
            val waitMs = ((deficit * 1_000.0) / bytesPerSecond).toLong().coerceIn(1L, POLL_INTERVAL_MS)
            delay(waitMs)
        }
    }

    /**
     * Convenience for the common case of "I just transferred `bytes` bytes,
     * charge them against the bucket".
     */
    suspend fun charge(bytes: Long) = await(bytes)

    private fun refill() {
        val now = System.nanoTime()
        val elapsedNanos = now - lastRefillNanos
        if (elapsedNanos <= 0) return
        val earned = (elapsedNanos / 1_000_000_000.0) * bytesPerSecond
        available = (available + earned).coerceAtMost(bucketSize.toDouble())
        lastRefillNanos = now
    }

    companion object {
        const val DEFAULT_BUCKET: Long = 256 * 1024  // 256 KiB burst
        private const val POLL_INTERVAL_MS: Long = 100

        /**
         * No-op limiter for the "rate limiting off" case. Awaiting a charge
         * returns immediately. The transfer coordinator does not special-case
         * this object — it always goes through [await] / [charge].
         */
        val UNLIMITED: RateLimiter = RateLimiter(0L)
    }
}
