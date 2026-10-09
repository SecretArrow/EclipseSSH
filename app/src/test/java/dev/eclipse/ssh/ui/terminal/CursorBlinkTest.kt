package dev.eclipse.ssh.ui.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The blink decision, every branch of it.
 *
 * All eight combinations are here because the one that is wrong is exactly the one nobody types into a
 * device by hand.
 */
class CursorBlinkTest {

    @Test
    fun `the cursor blinks only when it is visible, asked for and focused`() {
        assertThat(cursorBlinks(cursorVisible = true, blinkMode = true, focused = true)).isTrue()
    }

    @Test
    fun `a cursor the program hid never blinks`() {
        // `ESC [ ? 25 l`, which is how a full-screen program blanks the cursor while it redraws.
        assertThat(cursorBlinks(cursorVisible = false, blinkMode = true, focused = true)).isFalse()
        assertThat(cursorBlinks(cursorVisible = false, blinkMode = false, focused = true)).isFalse()
        assertThat(cursorBlinks(cursorVisible = false, blinkMode = true, focused = false)).isFalse()
        assertThat(cursorBlinks(cursorVisible = false, blinkMode = false, focused = false)).isFalse()
    }

    @Test
    fun `a steady cursor mode keeps the cursor solid`() {
        // `ESC [ ? 12 l`: terminfo's `cnorm`, sent by nano on every refresh and tmux on every repaint.
        assertThat(cursorBlinks(cursorVisible = true, blinkMode = false, focused = true)).isFalse()
    }

    @Test
    fun `a window without focus draws a solid cursor`() {
        // Solid rather than dark: frozen mid-blink reads as a rendering bug, and the caret has to be
        // findable in a split screen.
        assertThat(cursorBlinks(cursorVisible = true, blinkMode = true, focused = false)).isFalse()
        assertThat(cursorBlinks(cursorVisible = true, blinkMode = false, focused = false)).isFalse()
    }
}
