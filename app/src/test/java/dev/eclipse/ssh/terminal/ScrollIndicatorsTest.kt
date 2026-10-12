package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The two numbers the scroll indicators are drawn from, as functions rather than as arithmetic at
 * the call site.
 *
 * Both of them exist because the call sites had the same arithmetic twice - the badge's visibility
 * was a comparison and its count was the subtraction that comparison had already made - and because
 * both have boundary cases where the plain version lights an indicator for a motion that cannot
 * happen: a key row that fits its screen has nothing past either edge, and an empty grid is not
 * "behind" anything.
 */
class ScrollIndicatorsTest {

    // The key row: which edges have a cap the user cannot see, given how far the row is scrolled.

    @Test
    fun `a row that fits shows no edge`() {
        val overflow = rowOverflow(scrollValue = 0, maxScrollValue = 0)

        assertThat(overflow.atStart).isFalse()
        assertThat(overflow.atEnd).isFalse()
    }

    @Test
    fun `an unscrolled row hides only its right edge`() {
        val overflow = rowOverflow(scrollValue = 0, maxScrollValue = 100)

        assertThat(overflow.atStart).isFalse()
        assertThat(overflow.atEnd).isTrue()
    }

    @Test
    fun `a row between its ends hides both`() {
        val overflow = rowOverflow(scrollValue = 50, maxScrollValue = 100)

        assertThat(overflow.atStart).isTrue()
        assertThat(overflow.atEnd).isTrue()
    }

    @Test
    fun `a row scrolled to its end hides only its left edge`() {
        val overflow = rowOverflow(scrollValue = 100, maxScrollValue = 100)

        assertThat(overflow.atStart).isTrue()
        assertThat(overflow.atEnd).isFalse()
    }

    /**
     * The scroll range comes from a layout, and a layout that has not been measured yet reports
     * whatever it was initialised with. A negative range must read as "nothing to scroll" rather
     * than as an edge fade painted over a row that is entirely on screen.
     */
    @Test
    fun `a row with a negative scroll range shows no edge`() {
        val overflow = rowOverflow(scrollValue = 0, maxScrollValue = -5)

        assertThat(overflow.atStart).isFalse()
        assertThat(overflow.atEnd).isFalse()
    }

    /**
     * A fling can carry a scroll past the end for a frame. Reading that as "still in the middle"
     * would keep the right-hand fade on a row whose last cap is on screen.
     */
    @Test
    fun `a scroll past the maximum reads as the end of the row`() {
        val overflow = rowOverflow(scrollValue = 150, maxScrollValue = 100)

        assertThat(overflow.atStart).isTrue()
        assertThat(overflow.atEnd).isFalse()
    }

    // The badge: how far behind the live output the window is.

    @Test
    fun `a window ending at the live output is behind nothing`() {
        val frame = frame(firstLine = 4, visibleLines = 6, totalLines = 10)

        assertThat(frame.linesBelow()).isEqualTo(0)
    }

    @Test
    fun `the lines under the window are counted`() {
        val frame = frame(firstLine = 0, visibleLines = 6, totalLines = 10)

        assertThat(frame.linesBelow()).isEqualTo(4)
    }

    @Test
    fun `a buffer with no lines is behind nothing`() {
        val frame = frame(firstLine = 0, visibleLines = 0, totalLines = 0)

        assertThat(frame.linesBelow()).isEqualTo(0)
    }

    /**
     * The window is cut from the buffer by the caller, so a frame can be built for a grid that has
     * scrolled back past the first line it still holds - and `-2 lines below` is not a state the
     * user can be in.
     */
    @Test
    fun `a window past the last line is behind nothing`() {
        val frame = frame(firstLine = 5, visibleLines = 2, totalLines = 3)

        assertThat(frame.linesBelow()).isEqualTo(0)
    }

    /**
     * The buffer is the pty's height and starts full of blank rows, so the end of the buffer is not the
     * end of the output. A session with five lines printed on a sixty-row pty is at its live bottom,
     * and the rows below the last one written are not lines the user has scrolled past.
     */
    @Test
    fun `blank rows the shell has not written yet are not lines below`() {
        val frame = frame(firstLine = 0, visibleLines = 11, totalLines = 60, liveEnd = 5)

        assertThat(frame.linesBelow()).isEqualTo(0)
    }

    @Test
    fun `the count is measured to the live output, not to the end of the buffer`() {
        // Scrolled one line back from a live end at 41, in a buffer 60 rows tall: one line below, and
        // not the twenty the buffer's own height would have said.
        val frame = frame(firstLine = 29, visibleLines = 11, totalLines = 60, liveEnd = 41)

        assertThat(frame.linesBelow()).isEqualTo(1)
    }

    private fun frame(firstLine: Int, visibleLines: Int, totalLines: Int, liveEnd: Int = totalLines) = TerminalFrame(
        lines = List(visibleLines) { emptyList() },
        firstLine = firstLine,
        totalLines = totalLines,
        liveEnd = liveEnd,
        cursorVisible = true,
        revision = 1,
        columns = 80,
        rows = visibleLines,
        cursorRow = 0,
        cursorColumn = 0,
    )
}
