package dev.eclipse.ssh.background

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * When the app holds the CPU awake, and when it lets go.
 *
 * [android.permission.WAKE_LOCK] has been declared since 1.0.0 and never once acquired, which is why a
 * session that is fine when the phone is in the user's hand is a session the platform may suspend the
 * moment the screen goes off: no process holds the CPU, so the socket sees nothing, the heartbeat fires
 * late, and a liveness probe can come back empty on a session that was never tested. The lock is not
 * free - it is the battery, and it is the user's - so it is held for exactly one situation: a session
 * that is live, on an app the user is not looking at, that is not already shutting down.
 */
class SessionWakePolicyTest {

    @Test
    fun `a hidden app with a live session holds the CPU`() {
        assertThat(wakeNeed(appVisible = false, liveSessions = 1, stopping = false)).isEqualTo(WakeNeed.PARTIAL)
    }

    @Test
    fun `a hidden app with several live sessions still holds exactly one lock`() {
        assertThat(wakeNeed(appVisible = false, liveSessions = 4, stopping = false)).isEqualTo(WakeNeed.PARTIAL)
    }

    @Test
    fun `a visible app does not hold a lock it does not need`() {
        // Foreground and on screen: the platform is already keeping the app's process running, and the
        // lock would only add the battery cost of a screen that is on anyway.
        assertThat(wakeNeed(appVisible = true, liveSessions = 1, stopping = false)).isEqualTo(WakeNeed.NONE)
    }

    @Test
    fun `a visible app with no sessions holds nothing`() {
        assertThat(wakeNeed(appVisible = true, liveSessions = 0, stopping = false)).isEqualTo(WakeNeed.NONE)
    }

    @Test
    fun `a hidden app with no live sessions holds nothing`() {
        // Nothing to keep alive. The service is on its way out and the lock would outlive its reason.
        assertThat(wakeNeed(appVisible = false, liveSessions = 0, stopping = false)).isEqualTo(WakeNeed.NONE)
    }

    @Test
    fun `a service that is stopping holds nothing whatever else is true`() {
        assertThat(wakeNeed(appVisible = false, liveSessions = 1, stopping = true)).isEqualTo(WakeNeed.NONE)
        assertThat(wakeNeed(appVisible = true, liveSessions = 1, stopping = true)).isEqualTo(WakeNeed.NONE)
        assertThat(wakeNeed(appVisible = false, liveSessions = 0, stopping = true)).isEqualTo(WakeNeed.NONE)
        assertThat(wakeNeed(appVisible = true, liveSessions = 0, stopping = true)).isEqualTo(WakeNeed.NONE)
    }

    @Test
    fun `a session count that has gone wrong is not a reason to hold the CPU`() {
        // The count is a size of a registry; a negative one cannot happen, and if it ever did, the safe
        // reading is the one that does not drain the battery.
        assertThat(wakeNeed(appVisible = false, liveSessions = -1, stopping = false)).isEqualTo(WakeNeed.NONE)
    }
}
