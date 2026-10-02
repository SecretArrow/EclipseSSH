package dev.eclipse.ssh.feature.bandwidth

import com.google.common.truth.Truth.assertThat
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `unlimited limiter charges immediately`() = runTest {
        val lim = RateLimiter.UNLIMITED
        // 1 GiB worth of charges in zero time.
        val elapsed = measureTimeMillis { lim.await(1024L * 1024 * 1024) }
        assertThat(elapsed).isLessThan(50L)
    }

    @Test
    fun `zero or negative bytes are a noop`() = runTest {
        val lim = RateLimiter(bytesPerSecond = 1000)
        lim.await(0)
        lim.await(-1)
        // No throw, no state corruption.
    }

    @Test
    fun `a 1 KB-per-second limiter holds a 1 KB chunk for roughly one second`() = runTest {
        val lim = RateLimiter(bytesPerSecond = 1024, bucketSize = 1024)
        val elapsed = measureTimeMillis { lim.await(1024) }
        // The bucket starts full, so the first charge is free; the second
        // 1 KB has to wait the ~1s it takes to refill 1024 tokens at
        // 1 KB/s. Generous bound for a busy CI host.
        val elapsedSecond = measureTimeMillis { lim.await(1024) }
        assertThat(elapsedSecond).isAtLeast(800L)
    }

    @Test
    fun `the bucket refills over time without any explicit charge`() = runTest {
        val lim = RateLimiter(bytesPerSecond = 4096, bucketSize = 4096)
        // Drain the bucket.
        lim.await(4096)
        // Wait for it to refill.
        kotlinx.coroutines.delay(1100L)
        // Now a 1 KB charge should be essentially free. Generous bound
        // because the test JVM is shared with other tenants.
        val elapsed = measureTimeMillis { lim.await(1024) }
        assertThat(elapsed).isLessThan(500L)
    }

    @Test
    fun `a charge larger than the bucket terminates instead of spinning`() = runTest {
        // The exact shape that used to hang: more bytes than the bucket can
        // ever hold. refill() clamps `available` at bucketSize, so the old
        // single `while (available >= bytes)` loop could never be satisfied —
        // it spun on the transfer thread forever, which is every whole-file
        // transfer over DEFAULT_BUCKET once the SFTP read size passed it. The
        // slice fix charges in bucket-sized pieces, so it must complete.
        val lim = RateLimiter(bytesPerSecond = 10_000_000, bucketSize = 1024)
        val elapsed = measureTimeMillis { lim.await(1024L * 3) }
        // Reaching this line at all is the regression proof — before the fix
        // the coroutine never returned and the suite hung. The bound only
        // guards against a new way to stall; at 10 MB/s the two refills past
        // the first full bucket cost well under a millisecond.
        assertThat(elapsed).isLessThan(2_000L)
    }
}
