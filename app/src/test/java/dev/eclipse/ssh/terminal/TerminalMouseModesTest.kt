package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The mouse tracking a program turns on with DECSET, and where the terminal's memory of it is cleared.
 *
 * What the *gesture* then does with the answer is [dev.eclipse.ssh.ui.terminal.dragTarget]; this is
 * only about reading the program's request correctly and forgetting it when the program is gone. The
 * failure that matters is a mode outliving the app that set it: a terminal still reporting the mouse
 * to `bash` after `vim` crashed reads every drag as a burst of escape sequences typed at the prompt.
 */
class TerminalMouseModesTest {

    @Test
    fun `a session that has been asked for nothing reports no mouse`() {
        val buffer = session()

        assertThat(buffer.mouseMode()).isEqualTo(MouseMode.OFF)
        assertThat(buffer.mouseSgrEnabled()).isFalse()
    }

    @Test
    fun `each tracking mode is reported as itself`() {
        val cases = mapOf(
            MouseModes.X10 to MouseMode.X10,
            MouseModes.NORMAL to MouseMode.NORMAL,
            MouseModes.BUTTON to MouseMode.BUTTON,
            MouseModes.ANY to MouseMode.ANY,
        )

        for ((mode, expected) in cases) {
            val buffer = session()

            buffer.feed("\u001B[?${mode}h")

            assertThat(buffer.mouseMode()).isEqualTo(expected)
        }
    }

    @Test
    fun `the highest tracking mode wins when a program leaves more than one on`() {
        val buffer = session()

        buffer.feed("\u001B[?1000h")
        buffer.feed("\u001B[?1003h")

        assertThat(buffer.mouseMode()).isEqualTo(MouseMode.ANY)

        // Turning the newer one off falls back to the one still on, rather than losing the mouse.
        buffer.feed("\u001B[?1003l")

        assertThat(buffer.mouseMode()).isEqualTo(MouseMode.NORMAL)
    }

    @Test
    fun `SGR is reported separately, because it is an encoding and not a tracking mode`() {
        val buffer = session()

        buffer.feed("\u001B[?1006h")

        assertThat(buffer.mouseSgrEnabled()).isTrue()
        assertThat(buffer.mouseMode()).isEqualTo(MouseMode.OFF)

        buffer.feed("\u001B[?1000h")

        assertThat(buffer.mouseMode()).isEqualTo(MouseMode.NORMAL)
        assertThat(buffer.mouseSgrEnabled()).isTrue()
    }

    @Test
    fun `a mode this terminal does not implement leaves the ones it does alone`() {
        val buffer = session()
        buffer.feed("\u001B[?1006h")

        // 1005 is UTF-8 coordinates, which this terminal does not send. Asking for it must not read as
        // "turn the mouse off" - the program asked for *more*, not less.
        buffer.feed("\u001B[?1005h")
        buffer.feed("\u001B[?1000h")

        assertThat(buffer.mouseMode()).isEqualTo(MouseMode.NORMAL)
        assertThat(buffer.mouseSgrEnabled()).isTrue()

        buffer.feed("\u001B[?1005l")

        assertThat(buffer.mouseSgrEnabled()).isTrue()
    }

    @Test
    fun `a soft reset turns the mouse off`() {
        val buffer = session()
        buffer.feed("\u001B[?1000h\u001B[?1006h")

        buffer.feed("\u001B[!p")

        assertThat(buffer.mouseMode()).isEqualTo(MouseMode.OFF)
        assertThat(buffer.mouseSgrEnabled()).isFalse()
    }

    @Test
    fun `leaving the alternate screen turns the mouse off, so a crash cannot leave it on`() {
        val buffer = session()
        buffer.feed("\u001B[?1049h")
        buffer.feed("\u001B[?1000h\u001B[?1006h")

        buffer.feed("\u001B[?1049l")

        assertThat(buffer.mouseMode()).isEqualTo(MouseMode.OFF)
        assertThat(buffer.mouseSgrEnabled()).isFalse()
    }

    @Test
    fun `the frame carries the mode and the encoding it was built with`() {
        val buffer = session()
        buffer.feed("\u001B[?1002h\u001B[?1006h")

        val frame = buffer.frame(0, 10, 40)

        assertThat(frame.mouseMode).isEqualTo(MouseMode.BUTTON)
        assertThat(frame.mouseSgr).isTrue()
    }

    @Test
    fun `a frame built before any program asked has no mouse on it`() {
        val frame = session().frame(0, 10, 40)

        assertThat(frame.mouseMode).isEqualTo(MouseMode.OFF)
        assertThat(frame.mouseSgr).isFalse()
    }

    private fun session(columns: Int = 80, rows: Int = 24): AnsiTerminalBuffer =
        AnsiTerminalBuffer(columns = columns, rows = rows, scrollbackLimit = 200)
}
