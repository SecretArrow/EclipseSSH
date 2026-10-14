package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** ESC, built from its code point so this file contains no literal control byte. */
private val ESC: String = Char(0x1B).toString()

/**
 * DECSCUSR picks the cursor's shape, and the shape is the mode indicator a modal editor draws with.
 *
 * `ESC [ Ps SP q` is one parameter answering two questions, and the space before the `q` is what
 * separates it from DECLL, which is the same final byte with no intermediate at all. A terminal that
 * treats the two as one sequence, or that reads only the first of the two questions, leaves `vim`'s
 * insert mode indistinguishable from its command mode - which is the whole reason the sequence exists.
 *
 * Every parameter from 0 to 6 has a case below, the two that are not parameters at all (omitted, and a
 * value above 6) have one each, and the two ways this can be got wrong - reading `ESC [ 5 q` as DECSCUSR
 * and reading `ESC [ 5 q` as nothing - are both pinned.
 */
class CursorShapeTest {
    private fun buffer() = AnsiTerminalBuffer(columns = 20, rows = 4)

    @Test
    fun `each parameter is the shape and the blink the standard says it is`() {
        // The parameter's own low bit is the blink: 0 and 1 are the same blinking block, and every
        // shape above them comes in a blinking and a steady form.
        val expected = listOf(
            0 to (TerminalCursorShape.BLOCK to true),
            1 to (TerminalCursorShape.BLOCK to true),
            2 to (TerminalCursorShape.BLOCK to false),
            3 to (TerminalCursorShape.UNDERLINE to true),
            4 to (TerminalCursorShape.UNDERLINE to false),
            5 to (TerminalCursorShape.BAR to true),
            6 to (TerminalCursorShape.BAR to false),
        )

        expected.forEach { (ps, want) ->
            val buffer = buffer()
            buffer.feed("$ESC[$ps q")
            val frame = buffer.frame()
            assertWithMessage("ESC [ $ps SP q")
                .that(frame.cursorShape to frame.cursorBlink).isEqualTo(want)
        }
    }

    @Test
    fun `an omitted parameter is the default cursor`() {
        val buffer = buffer()

        buffer.feed("$ESC[5 q$ESC[ q")

        assertThat(buffer.frame().cursorShape).isEqualTo(TerminalCursorShape.BLOCK)
        assertThat(buffer.frame().cursorBlink).isTrue()
    }

    @Test
    fun `a parameter this terminal does not know leaves the cursor alone`() {
        val buffer = buffer()
        buffer.feed("$ESC[5 q")

        // 7 to 9 are shapes some terminals draw and this one does not, and a value past those is not a
        // shape at all. Inventing one would be drawing something the program never asked for.
        listOf(7, 8, 9, 99, 1000).forEach { ps ->
            buffer.feed("$ESC[$ps q")
            assertWithMessage("ESC [ $ps SP q")
                .that(buffer.frame().cursorShape).isEqualTo(TerminalCursorShape.BAR)
            assertThat(buffer.frame().cursorBlink).isTrue()
        }
    }

    @Test
    fun `a malformed parameter is not a cursor shape`() {
        val buffer = buffer()
        buffer.feed("$ESC[5 q")

        // Two parameters, and a non-numeric one: neither is a shape this sequence takes, and the
        // second is what a parser that simply drops the space and reads the body would see.
        buffer.feed("$ESC[1;2 q$ESC[; q")

        assertThat(buffer.frame().cursorShape).isEqualTo(TerminalCursorShape.BAR)
        assertThat(buffer.frame().cursorBlink).isTrue()
    }

    @Test
    fun `DECLL is not DECSCUSR, and only the space tells them apart`() {
        val buffer = buffer()

        // The same final byte, the same parameter, and no intermediate: this is DECLL, which this
        // terminal does not implement, so it is not a cursor shape either.
        buffer.feed("$ESC[5q")

        assertThat(buffer.frame().cursorShape).isEqualTo(TerminalCursorShape.BLOCK)
        assertThat(buffer.frame().cursorBlink).isTrue()
    }

    @Test
    fun `the sequence that names a shape names a complete cursor, so the later one wins`() {
        val buffer = buffer()

        buffer.feed("$ESC[?12l$ESC[5 q")
        // A program asking for a blinking bar has asked for it to blink, whatever the mode was before.
        assertThat(buffer.frame().cursorShape).isEqualTo(TerminalCursorShape.BAR)
        assertThat(buffer.frame().cursorBlink).isTrue()

        buffer.feed("$ESC[?12l")
        // And the blink mode is still its own switch afterwards: it turns the blink off without
        // touching the shape, which is the half DECSCUSR does not own.
        assertThat(buffer.frame().cursorShape).isEqualTo(TerminalCursorShape.BAR)
        assertThat(buffer.frame().cursorBlink).isFalse()
    }

    @Test
    fun `a soft reset brings the default cursor back`() {
        val buffer = buffer()

        buffer.feed("$ESC[3 q$ESC[!p")

        assertThat(buffer.frame().cursorShape).isEqualTo(TerminalCursorShape.BLOCK)
        assertThat(buffer.frame().cursorBlink).isTrue()
    }

    @Test
    fun `the cursor's shape outlives the alternate screen, like its blink`() {
        val buffer = buffer()

        buffer.feed("$ESC[5 q$ESC[?1000h$ESC[?1049h$ESC[?1049l")

        // The mouse modes go back to off because a program that dies without undoing them would leave
        // the shell reporting every drag. The cursor's own shape is not a mode the screen owns, so it
        // stands - and the shell that gets the screen back is the one that set it.
        assertThat(buffer.frame().mouseMode).isEqualTo(MouseMode.OFF)
        assertThat(buffer.frame().cursorShape).isEqualTo(TerminalCursorShape.BAR)
    }

    @Test
    fun `a full reset brings the default cursor back`() {
        val buffer = buffer()

        buffer.feed("$ESC[4 q$ESC[1;1H${ESC}c")

        assertThat(buffer.frame().cursorShape).isEqualTo(TerminalCursorShape.BLOCK)
        assertThat(buffer.frame().cursorBlink).isTrue()
    }
}
