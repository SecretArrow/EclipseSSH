package dev.eclipse.ssh.ui.terminal

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.terminal.MouseMode
import dev.eclipse.ssh.terminal.TerminalVisualRow
import org.junit.Test

/**
 * The arithmetic between a finger and the scrollback: when a drag becomes a step, how much screen a
 * step is worth, and how far a page goes.
 *
 * The unit is the point of the whole file. A step is priced in the *rendered* height of the line it
 * passes, which is two rows for a wrapped line on a phone and one for a short one, so the same travel
 * buys different distances - which is what makes the screen move with the finger instead of ahead of it.
 */
class TerminalScrollTest {

    // --- ScrollAccumulator -------------------------------------------------

    @Test
    fun `travel shorter than a step is silent but kept`() {
        val accumulator = ScrollAccumulator()

        assertThat(accumulator.dragBy(10f, 40f)).isEqualTo(0)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(10f)
    }

    @Test
    fun `travel that adds up across calls becomes one step and leaves nothing`() {
        val accumulator = ScrollAccumulator()

        assertThat(accumulator.dragBy(10f, 40f)).isEqualTo(0)
        assertThat(accumulator.dragBy(30f, 40f)).isEqualTo(1)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(0f)
    }

    @Test
    fun `travel of exactly one step is one step, and repeating it is not a second`() {
        val accumulator = ScrollAccumulator()

        assertThat(accumulator.dragBy(40f, 40f)).isEqualTo(1)
        assertThat(accumulator.dragBy(0f, 40f)).isEqualTo(0)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(0f)
    }

    @Test
    fun `several steps in one call are reported together so the caller publishes once`() {
        val accumulator = ScrollAccumulator()

        assertThat(accumulator.dragBy(100f, 40f)).isEqualTo(2)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(20f)
    }

    @Test
    fun `travel the other way is the other sign`() {
        val accumulator = ScrollAccumulator()

        assertThat(accumulator.dragBy(-100f, 40f)).isEqualTo(-2)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(-20f)
    }

    @Test
    fun `a wrapped line's step costs double, so the same travel buys half the lines`() {
        val narrow = ScrollAccumulator()
        val wrapped = ScrollAccumulator()

        assertThat(narrow.dragBy(80f, 40f)).isEqualTo(2)
        assertThat(wrapped.dragBy(80f, 80f)).isEqualTo(1)
        assertThat(wrapped.pendingPx).isWithin(0.001f).of(0f)
    }

    @Test
    fun `a step that cannot be priced is inert and does not eat the remainder`() {
        val accumulator = ScrollAccumulator()
        accumulator.dragBy(50f, 40f)

        assertThat(accumulator.dragBy(50f, 0f)).isEqualTo(0)
        assertThat(accumulator.dragBy(50f, -1f)).isEqualTo(0)
        assertThat(accumulator.dragBy(50f, Float.NaN)).isEqualTo(0)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(10f)
    }

    @Test
    fun `travel that is not a number is ignored`() {
        val accumulator = ScrollAccumulator()

        assertThat(accumulator.dragBy(Float.NaN, 40f)).isEqualTo(0)
        assertThat(accumulator.dragBy(Float.NEGATIVE_INFINITY, 40f)).isEqualTo(0)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(0f)
    }

    @Test
    fun `a new drag continues the travel the last one did not spend`() {
        val accumulator = ScrollAccumulator()
        accumulator.dragBy(39f, 40f)

        // The finger stopped a hair short of a line, and a second touch picks up from there rather
        // than starting the line over - which is what makes a resumed slow drag feel continuous.
        assertThat(accumulator.dragBy(1f, 40f)).isEqualTo(1)
    }

    // --- pageSteps ---------------------------------------------------------

    @Test
    fun `a page of rows over single-row lines is that many lines`() {
        val steps = pageSteps(anchor = 100, fromOffset = 0, pageRows = 5, ceiling = 100) { 1 }

        assertThat(steps).isEqualTo(5)
    }

    @Test
    fun `a wrapped line spends two of the page's rows`() {
        // Line 7 is the third line walked back (9, 8, 7) and is two rows tall.
        val steps = pageSteps(anchor = 10, fromOffset = 0, pageRows = 5, ceiling = 100) { line ->
            if (line == 7) 2 else 1
        }

        assertThat(steps).isEqualTo(4)
    }

    @Test
    fun `the walk asks for the lines the content actually crosses`() {
        val asked = mutableListOf<Int>()

        pageSteps(anchor = 10, fromOffset = 2, pageRows = 3, ceiling = 100) { line ->
            asked += line
            1
        }

        assertThat(asked).isEqualTo(listOf(7, 6, 5))
    }

    @Test
    fun `paging forward asks for the lines below the window`() {
        val asked = mutableListOf<Int>()

        pageSteps(anchor = 10, fromOffset = 5, pageRows = -3, ceiling = 100) { line ->
            asked += line
            1
        }

        assertThat(asked).isEqualTo(listOf(5, 6, 7))
    }

    @Test
    fun `a page stops at the far end instead of overshooting it`() {
        assertThat(pageSteps(anchor = 50, fromOffset = 0, pageRows = 20, ceiling = 2) { 1 })
            .isEqualTo(2)
    }

    @Test
    fun `a page forward stops at the live end`() {
        assertThat(pageSteps(anchor = 50, fromOffset = 3, pageRows = -20, ceiling = 50) { 1 })
            .isEqualTo(-3)
    }

    @Test
    fun `paging with no scrollback moves nothing`() {
        assertThat(pageSteps(anchor = 0, fromOffset = 0, pageRows = 20, ceiling = 0) { 1 })
            .isEqualTo(0)
    }

    @Test
    fun `a page of no rows moves nothing`() {
        assertThat(pageSteps(anchor = 50, fromOffset = 7, pageRows = 0, ceiling = 50) { 1 })
            .isEqualTo(0)
    }

    @Test
    fun `a line reported as no rows is still worth one, so the walk ends`() {
        assertThat(pageSteps(anchor = 50, fromOffset = 0, pageRows = 5, ceiling = 50) { 0 })
            .isEqualTo(5)
    }

    @Test
    fun `an offset already past the end is read as the end`() {
        assertThat(pageSteps(anchor = 50, fromOffset = 99, pageRows = 5, ceiling = 4) { 1 })
            .isEqualTo(0)
    }

    @Test
    fun `paging forward from before the start is read as the start`() {
        assertThat(pageSteps(anchor = 50, fromOffset = -9, pageRows = -5, ceiling = 4) { 1 })
            .isEqualTo(0)
    }

    // --- tallestRowsPerLine ------------------------------------------------

    @Test
    fun `a line wrapped over two rows is the estimate for the lines beside it`() {
        val rows = listOf(row(line = 4), row(line = 4), row(line = 5), row(line = 5))

        assertThat(tallestRowsPerLine(rows)).isEqualTo(2)
    }

    @Test
    fun `a window of short lines is one row per line`() {
        val rows = listOf(row(line = 1), row(line = 2), row(line = 3))

        assertThat(tallestRowsPerLine(rows)).isEqualTo(1)
    }

    @Test
    fun `one wrapped line among short ones is the estimate, because it is the safe one`() {
        val rows = listOf(row(line = 1), row(line = 2), row(line = 2), row(line = 3))

        assertThat(tallestRowsPerLine(rows)).isEqualTo(2)
    }

    @Test
    fun `a line too wide for the window is charged all of its rows`() {
        val rows = listOf(row(line = 7), row(line = 7), row(line = 7), row(line = 8))

        assertThat(tallestRowsPerLine(rows)).isEqualTo(3)
    }

    @Test
    fun `a frame with nothing laid out is one row per line`() {
        assertThat(tallestRowsPerLine(emptyList())).isEqualTo(1)
    }

    // --- dragTarget --------------------------------------------------------

    @Test
    fun `a program reading SGR mouse reports gets the wheel`() {
        for (mode in listOf(MouseMode.NORMAL, MouseMode.BUTTON, MouseMode.ANY)) {
            assertThat(dragTarget(mode, mouseSgr = true, alternateScreen = true))
                .isEqualTo(DragTarget.Wheel)
            assertThat(dragTarget(mode, mouseSgr = true, alternateScreen = false))
                .isEqualTo(DragTarget.Wheel)
        }
    }

    @Test
    fun `tracking without SGR is not reported in an encoding this terminal cannot send`() {
        // The program asked for the old format; it gets the keys a terminal with no mouse would send
        // rather than SGR bytes it would read as text.
        assertThat(dragTarget(MouseMode.NORMAL, mouseSgr = false, alternateScreen = true))
            .isEqualTo(DragTarget.Arrows)
        assertThat(dragTarget(MouseMode.ANY, mouseSgr = false, alternateScreen = false))
            .isEqualTo(DragTarget.Scrollback)
    }

    @Test
    fun `X10 alone is not a wheel, because a wheel is not a press`() {
        assertThat(dragTarget(MouseMode.X10, mouseSgr = true, alternateScreen = true))
            .isEqualTo(DragTarget.Arrows)
        assertThat(dragTarget(MouseMode.X10, mouseSgr = true, alternateScreen = false))
            .isEqualTo(DragTarget.Scrollback)
    }

    @Test
    fun `a program that took the alternate screen and no mouse gets the arrow keys`() {
        assertThat(dragTarget(MouseMode.OFF, mouseSgr = false, alternateScreen = true))
            .isEqualTo(DragTarget.Arrows)
    }

    @Test
    fun `the scrollback takes the drag wherever there is one, mouse or not`() {
        assertThat(dragTarget(MouseMode.OFF, mouseSgr = false, alternateScreen = false))
            .isEqualTo(DragTarget.Scrollback)
        assertThat(dragTarget(MouseMode.OFF, mouseSgr = true, alternateScreen = false))
            .isEqualTo(DragTarget.Scrollback)
    }

    @Test
    fun `no combination of mode and screen goes unanswered`() {
        val reading = listOf(MouseMode.NORMAL, MouseMode.BUTTON, MouseMode.ANY)

        for (mode in MouseMode.entries) {
            for (sgr in listOf(false, true)) {
                for (alternate in listOf(false, true)) {
                    val target = dragTarget(mode, sgr, alternate)

                    // A wheel is only ever what a program that reads the mouse in SGR asked for.
                    if (target == DragTarget.Wheel) {
                        assertThat(sgr).isTrue()
                        assertThat(mode in reading).isTrue()
                    } else {
                        // Everything else follows from the screen: a scrollback off the alternate
                        // screen, arrow keys on it.
                        assertThat(target).isEqualTo(if (alternate) DragTarget.Arrows else DragTarget.Scrollback)
                    }
                }
            }
        }
    }

    private fun row(line: Int) = TerminalVisualRow(line = line, from = 0, to = 10)
}
