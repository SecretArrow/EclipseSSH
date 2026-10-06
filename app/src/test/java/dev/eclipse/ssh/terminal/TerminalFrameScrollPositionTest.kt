package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The scroll position a frame carries, which is what a fling reads to know when it has run out of
 * scrollback.
 *
 * The view cannot work this out for itself. How much more the window can scroll depends on how many
 * *wrapped rows* the window fits at the width it is being drawn at, and only the view model knows both
 * widths at once - so the frame brings the answer with it, and the gesture reads it from the same value
 * it is drawing rather than asking mid-flight and racing the frames already in queue.
 *
 * The number that matters to a fling is `scrollCeiling - scrollOffset`: the room left in the direction
 * of travel. It has to be independent of where the window currently is, and it has to be exactly what
 * `scrollTerminal` would clamp to, because a view that believes it has room it does not have is a fling
 * that runs on past the top of the scrollback doing nothing.
 */
class TerminalFrameScrollPositionTest {

    @Test
    fun `a frame that has not been scrolled says so and knows its ceiling`() {
        val buffer = session()
        feedLines(buffer)

        val frame = buffer.frame(scrollOffset = 0, viewportRows = WINDOW_ROWS, windowColumns = WINDOW_COLUMNS)

        assertThat(frame.scrollOffset).isEqualTo(0)
        assertThat(frame.scrollCeiling).isEqualTo(buffer.maxScrollOffset(WINDOW_ROWS, WINDOW_COLUMNS))
        assertThat(frame.scrollCeiling).isGreaterThan(0)
    }

    @Test
    fun `a scrolled frame reports where it is`() {
        val buffer = session()
        feedLines(buffer)

        val frame = buffer.frame(scrollOffset = 5, viewportRows = WINDOW_ROWS, windowColumns = WINDOW_COLUMNS)

        assertThat(frame.scrollOffset).isEqualTo(5)
    }

    @Test
    fun `the room left is the ceiling less the offset, wherever the window is`() {
        val buffer = session()
        feedLines(buffer)
        val ceiling = buffer.maxScrollOffset(WINDOW_ROWS, WINDOW_COLUMNS)

        for (offset in 0..ceiling) {
            val frame = buffer.frame(offset, WINDOW_ROWS, WINDOW_COLUMNS)

            assertThat(frame.scrollCeiling - frame.scrollOffset).isEqualTo(ceiling - offset)
        }
    }

    @Test
    fun `an offset past the end is reported as the end, so the room never goes negative`() {
        val buffer = session()
        feedLines(buffer)
        val ceiling = buffer.maxScrollOffset(WINDOW_ROWS, WINDOW_COLUMNS)

        val frame = buffer.frame(ceiling + 50, WINDOW_ROWS, WINDOW_COLUMNS)

        assertThat(frame.scrollOffset).isEqualTo(ceiling)
        assertThat(frame.scrollCeiling - frame.scrollOffset).isEqualTo(0)
    }

    @Test
    fun `an offset before the live end is reported as the live end`() {
        val buffer = session()
        feedLines(buffer)

        val frame = buffer.frame(-3, WINDOW_ROWS, WINDOW_COLUMNS)

        assertThat(frame.scrollOffset).isEqualTo(0)
    }

    @Test
    fun `a screen a program is painting has no scrollback to fling through`() {
        val buffer = session()
        feedLines(buffer)
        buffer.feed("\u001B[?1049h")
        feedLines(buffer, lines = 40)

        val frame = buffer.frame(scrollOffset = 0, viewportRows = WINDOW_ROWS, windowColumns = WINDOW_COLUMNS)

        assertThat(frame.alternateScreen).isTrue()
        assertThat(frame.scrollCeiling).isEqualTo(0)
        assertThat(frame.scrollCeiling - frame.scrollOffset).isEqualTo(0)
    }

    @Test
    fun `a program painting the primary screen keeps the history behind it`() {
        val buffer = session()
        feedLines(buffer)
        // `top`'s tell: it homes somewhere above where output had got to and rewrites the rows it
        // stands on, without ever asking for the alternate screen. The lines it painted over are gone,
        // but everything before them is the session's own output and stays reachable.
        buffer.feed("\u001B[30Hpainted")

        val frame = buffer.frame(scrollOffset = 0, viewportRows = WINDOW_ROWS, windowColumns = WINDOW_COLUMNS)

        assertThat(frame.positionalScreen).isTrue()
        assertThat(frame.alternateScreen).isFalse()
        assertThat(frame.scrollCeiling).isGreaterThan(0)
        assertThat(frame.scrollCeiling).isEqualTo(buffer.maxScrollOffset(WINDOW_ROWS, WINDOW_COLUMNS))
    }

    @Test
    fun `a frame that has never been drawn at a width still reports a position`() {
        val buffer = session()
        feedLines(buffer)

        // The snapshot and export paths build frames with no window width, where the window is a count
        // of lines rather than a fit to wrapped rows. The fields have to hold there too.
        val frame = buffer.frame(scrollOffset = 2, viewportRows = WINDOW_ROWS)

        assertThat(frame.scrollOffset).isEqualTo(2)
        assertThat(frame.scrollCeiling).isEqualTo(buffer.maxScrollOffset(WINDOW_ROWS))
    }

    private fun session(columns: Int = 80, rows: Int = 60, scrollback: Int = 500): AnsiTerminalBuffer =
        AnsiTerminalBuffer(columns = columns, rows = rows, scrollbackLimit = scrollback)

    /** Long enough to wrap at both the pty's 80 columns and the window's 44, as the reader's lines are. */
    private fun feedLines(buffer: AnsiTerminalBuffer, from: Int = 0, lines: Int = 40, tail: String = "$ ") {
        for (index in from until from + lines) {
            buffer.feed("output line $index with some words on it here now plus a tail\r\n")
        }
        buffer.feed(tail)
    }

    private companion object {
        const val WINDOW_COLUMNS = 44
        const val WINDOW_ROWS = 17
    }
}
