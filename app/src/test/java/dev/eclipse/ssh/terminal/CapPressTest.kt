package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The one rule behind a key cap's press feedback, as a table of events rather than as Compose state.
 *
 * It exists because a press used to have exactly one way to end - the gesture's own release - and the
 * shortcut bar scrolls: a drag takes the pointer stream away from the cap, so the cap is never told
 * the finger left, and its feedback is drawn on the key until something else is tapped. Reported as a
 * small round mark left on the `→` cap and then on `PGDN`, which is a Material ripple caught
 * mid-expansion. The row's own movement is the second way a press ends, and that is all this is.
 */
class CapPressTest {

    @Test
    fun `a finger on a cap presses it`() {
        assertThat(capPressed(current = false, event = CapPress.DOWN)).isTrue()
    }

    /**
     * The gesture stream is not guaranteed to be unbroken: a press whose release never arrived leaves
     * the state stuck at pressed, and the next tap on that cap still has to light it.
     */
    @Test
    fun `a cap left pressed by a lost release is pressed again by the next tap`() {
        assertThat(capPressed(current = true, event = CapPress.DOWN)).isTrue()
    }

    @Test
    fun `lifting the finger ends the press`() {
        assertThat(capPressed(current = true, event = CapPress.UP)).isFalse()
    }

    /**
     * A release can arrive for a press that is already over - the row ended it, and the finger came up
     * afterwards - and ending an ended press is not a press.
     */
    @Test
    fun `a release with nothing pressed leaves it un-pressed`() {
        assertThat(capPressed(current = false, event = CapPress.UP)).isFalse()
    }

    /**
     * The case the artifact was: the drag takes the gesture, so no release ever comes, and the press
     * has to end on the row's movement instead. The sequence is the assertion - down, movement, and no
     * release in between - because the single event alone does not say whether the press survived.
     */
    @Test
    fun `a press the drag carried away is ended by the drag`() {
        val pressed = capPressed(capPressed(current = false, event = CapPress.DOWN), CapPress.ROW_MOVED)

        assertThat(pressed).isFalse()
    }

    /** A row still moving, a frame later, does not re-press anything. */
    @Test
    fun `a row that keeps moving stays un-pressed`() {
        val pressed = capPressed(capPressed(current = true, event = CapPress.ROW_MOVED), CapPress.ROW_MOVED)

        assertThat(pressed).isFalse()
    }

    /** And the cap is not dead afterwards: the next press is a press. */
    @Test
    fun `a cap the drag cleared still lights on the next tap`() {
        val pressed = capPressed(capPressed(current = true, event = CapPress.ROW_MOVED), CapPress.DOWN)

        assertThat(pressed).isTrue()
    }

    @Test
    fun `a row moving under no finger presses nothing`() {
        assertThat(capPressed(current = false, event = CapPress.ROW_MOVED)).isFalse()
    }
}
