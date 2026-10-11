package dev.eclipse.ssh.background

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which foreground-service type the app asks for, and when it stops asking.
 *
 * `dataSync` is the right type for what this service was written for - fetching and pushing in the
 * background - and it is the wrong one for a terminal session, because the platform gives `dataSync` a
 * six-hour budget and then refuses to promote it again. A user who keeps a session open for a day does
 * not need a quarter of one, and when the budget ran out the service promoted nothing, stopped itself,
 * and left the session held by nobody at all: the failure the budget was supposed to be about became
 * every session the user had open.
 *
 * `specialUse` has no such budget, and the app is not choosing it for a transfer - it is declaring
 * "interactive session must stay alive", which is what the subtype property in the manifest says and
 * what the terminal actually needs.
 */
class ForegroundTypePolicyTest {

    @Test
    fun `live sessions on a fresh budget use the type that matches the work`() {
        assertThat(foregroundNeed(budgetExhausted = false, liveSessions = 1, transferInFlight = false))
            .isEqualTo(ForegroundNeed.DATA_SYNC)
    }

    @Test
    fun `live sessions on an exhausted budget are re-promoted as special use`() {
        // The whole point: the budget running out is a fact about `dataSync`, not a fact about the
        // sessions, and it must not end them.
        assertThat(foregroundNeed(budgetExhausted = true, liveSessions = 3, transferInFlight = false))
            .isEqualTo(ForegroundNeed.SPECIAL_USE)
    }

    @Test
    fun `a transfer with no terminal open uses data sync`() {
        assertThat(foregroundNeed(budgetExhausted = false, liveSessions = 0, transferInFlight = true))
            .isEqualTo(ForegroundNeed.DATA_SYNC)
    }

    @Test
    fun `a transfer still running when the budget is gone is re-promoted as well`() {
        // Narrower than the session case and deliberately not dropped: a half-written file that loses its
        // service is a file the user finds truncated.
        assertThat(foregroundNeed(budgetExhausted = true, liveSessions = 0, transferInFlight = true))
            .isEqualTo(ForegroundNeed.SPECIAL_USE)
    }

    @Test
    fun `nothing to keep alive stops the service`() {
        assertThat(foregroundNeed(budgetExhausted = false, liveSessions = 0, transferInFlight = false))
            .isEqualTo(ForegroundNeed.STOP)
    }

    @Test
    fun `an exhausted budget with nothing to keep alive also stops the service`() {
        // The one case where the old behaviour was right, and it stays: a service with no session and no
        // transfer is a process held for no reason.
        assertThat(foregroundNeed(budgetExhausted = true, liveSessions = 0, transferInFlight = false))
            .isEqualTo(ForegroundNeed.STOP)
    }

    @Test
    fun `a session count that has gone wrong does not by itself keep a process alive`() {
        assertThat(foregroundNeed(budgetExhausted = false, liveSessions = -1, transferInFlight = false))
            .isEqualTo(ForegroundNeed.STOP)
        assertThat(foregroundNeed(budgetExhausted = true, liveSessions = -1, transferInFlight = false))
            .isEqualTo(ForegroundNeed.STOP)
    }
}
