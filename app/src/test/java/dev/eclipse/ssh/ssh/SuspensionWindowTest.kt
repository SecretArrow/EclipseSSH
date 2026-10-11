package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the app is allowed to conclude from a wait that may not have happened.
 *
 * The hold before a drop is a wall-clock deadline, and a phone that is asleep is a phone whose clock
 * keeps running while nothing else does. A device suspended at 04:00 and woken at 04:12 by the user
 * picking it up has, to a timer, waited twelve minutes with no network - and dropping a session on that
 * evidence ends a connection that was never tested. So the wait is measured twice: once by the clock
 * that sleeps with the device, once by the one that does not, and the difference between them is what
 * the hold is actually worth.
 */
class SuspensionWindowTest {

    private fun reading(elapsed: Long, uptime: Long) = ClockReading(elapsedRealtimeMs = elapsed, uptimeMs = uptime)

    @Test
    fun `a wait the device stayed awake for is all awake time`() {
        val slice = waitSlice(reading(1_000, 900), reading(61_000, 60_900))
        assertThat(slice.awakeMs).isEqualTo(60_000L)
        assertThat(slice.sleptMs).isEqualTo(0L)
    }

    @Test
    fun `a device suspended for the whole wait spent it asleep`() {
        // The case this exists for: the uptime clock did not move at all while the realtime clock ran
        // the full minute, so the hold expired without a second of it being observed.
        val slice = waitSlice(reading(1_000, 500), reading(61_000, 500))
        assertThat(slice.awakeMs).isEqualTo(0L)
        assertThat(slice.sleptMs).isEqualTo(60_000L)
    }

    @Test
    fun `a wait that was part sleep and part awake counts only the awake part`() {
        val slice = waitSlice(reading(0, 0), reading(90_000, 30_000))
        assertThat(slice.awakeMs).isEqualTo(30_000L)
        assertThat(slice.sleptMs).isEqualTo(60_000L)
    }

    @Test
    fun `readings that go backwards produce no time at all rather than negative time`() {
        // Not expected, and not allowed to become a negative slice that would *add* to the remaining
        // hold and spin the loop: the clocks are read from a device that can be reset under us.
        val slice = waitSlice(reading(61_000, 60_900), reading(1_000, 900))
        assertThat(slice.awakeMs).isEqualTo(0L)
        assertThat(slice.sleptMs).isEqualTo(0L)
    }

    @Test
    fun `an uptime that outran the realtime clock is not sleep in reverse`() {
        // The one reading that would subtract: an awake interval longer than the interval it sits in.
        // No device produces it, and a negative sleep would re-arm a hold that has already expired.
        val slice = waitSlice(reading(0, 5_000), reading(1_000, 9_000))
        assertThat(slice.awakeMs).isEqualTo(4_000L)
        assertThat(slice.sleptMs).isEqualTo(0L)
    }

    @Test
    fun `a device that slept through the wait keeps the session instead of concluding`() {
        val slice = waitSlice(reading(1_000, 500), reading(61_000, 500))
        assertThat(holdDecision(NETWORK_GRACE_MS, slice)).isEqualTo(HoldDecision.DEVICE_SLEPT)
    }

    @Test
    fun `a wait that really elapsed awake expires the hold`() {
        val slice = waitSlice(reading(0, 0), reading(60_000, 60_000))
        assertThat(holdDecision(NETWORK_GRACE_MS, slice)).isEqualTo(HoldDecision.OUTAGE_EXPIRED)
    }

    @Test
    fun `a hold with awake time still to run keeps waiting`() {
        val slice = waitSlice(reading(0, 0), reading(10_000, 10_000))
        assertThat(holdDecision(NETWORK_GRACE_MS, slice)).isEqualTo(HoldDecision.KEEP_WAITING)
    }

    @Test
    fun `awake time exactly equal to what was left expires the hold`() {
        val slice = waitSlice(reading(0, 0), reading(10_000, 10_000))
        assertThat(holdDecision(10_000, slice)).isEqualTo(HoldDecision.OUTAGE_EXPIRED)
    }

    @Test
    fun `awake time a millisecond short of what was left keeps waiting`() {
        val slice = waitSlice(reading(0, 0), reading(9_999, 9_999))
        assertThat(holdDecision(10_000, slice)).isEqualTo(HoldDecision.KEEP_WAITING)
    }

    @Test
    fun `a sleep at exactly the threshold counts as a suspension`() {
        // The boundary is deliberately inclusive: a sleep that reaches the threshold is long enough that
        // the deadline it consumed was not a deadline anyone was awake for.
        assertThat(SUSPENSION_MIN_MS).isEqualTo(2_000L)
        val slice = WaitSlice(awakeMs = 0, sleptMs = SUSPENSION_MIN_MS)
        assertThat(holdDecision(remainingMs = 1, slice = slice)).isEqualTo(HoldDecision.DEVICE_SLEPT)
    }

    @Test
    fun `a sleep a millisecond under the threshold is treated as ordinary scheduling`() {
        val slice = WaitSlice(awakeMs = 10_000, sleptMs = SUSPENSION_MIN_MS - 1)
        assertThat(holdDecision(remainingMs = 10_000, slice = slice)).isEqualTo(HoldDecision.OUTAGE_EXPIRED)
    }

    @Test
    fun `a suspension outranks an expiry inside one slice`() {
        // Both are true at once on a device that slept and then woke: five seconds awake and half a
        // minute asleep. The sleep is what makes the expiry meaningless, so it decides.
        val slice = waitSlice(reading(0, 0), reading(35_000, 5_000))
        assertThat(holdDecision(remainingMs = 1_000, slice = slice)).isEqualTo(HoldDecision.DEVICE_SLEPT)
    }
}
