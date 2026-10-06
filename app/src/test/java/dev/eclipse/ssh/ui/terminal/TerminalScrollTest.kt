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

        assertThat(accumulator.dragBy(10f, 40f, 40f)).isEqualTo(0)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(10f)
    }

    @Test
    fun `travel that adds up across calls becomes one step and leaves nothing`() {
        val accumulator = ScrollAccumulator()

        assertThat(accumulator.dragBy(10f, 40f, 40f)).isEqualTo(0)
        assertThat(accumulator.dragBy(30f, 40f, 40f)).isEqualTo(1)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(0f)
    }

    @Test
    fun `travel of exactly one step is one step, and repeating it is not a second`() {
        val accumulator = ScrollAccumulator()

        assertThat(accumulator.dragBy(40f, 40f, 40f)).isEqualTo(1)
        assertThat(accumulator.dragBy(0f, 40f, 40f)).isEqualTo(0)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(0f)
    }

    @Test
    fun `several steps in one call are reported together so the caller publishes once`() {
        val accumulator = ScrollAccumulator()

        assertThat(accumulator.dragBy(100f, 40f, 40f)).isEqualTo(2)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(20f)
    }

    @Test
    fun `travel the other way is the other sign`() {
        val accumulator = ScrollAccumulator()

        assertThat(accumulator.dragBy(-100f, 40f, 40f)).isEqualTo(-2)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(-20f)
    }

    @Test
    fun `a wrapped line's step costs double, so the same travel buys half the lines`() {
        val narrow = ScrollAccumulator()
        val wrapped = ScrollAccumulator()

        assertThat(narrow.dragBy(80f, 40f, 40f)).isEqualTo(2)
        assertThat(wrapped.dragBy(80f, 80f, 80f)).isEqualTo(1)
        assertThat(wrapped.pendingPx).isWithin(0.001f).of(0f)
    }

    @Test
    fun `a step that cannot be priced is inert and keeps the travel`() {
        val accumulator = ScrollAccumulator()
        accumulator.dragBy(50f, 40f, 40f)

        assertThat(accumulator.dragBy(50f, 0f, 0f)).isEqualTo(0)
        assertThat(accumulator.dragBy(50f, -1f, -1f)).isEqualTo(0)
        assertThat(accumulator.dragBy(50f, Float.NaN, Float.NaN)).isEqualTo(0)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(160f)
    }

    @Test
    fun `travel that is not a number is ignored`() {
        val accumulator = ScrollAccumulator()

        assertThat(accumulator.dragBy(Float.NaN, 40f, 40f)).isEqualTo(0)
        assertThat(accumulator.dragBy(Float.NEGATIVE_INFINITY, 40f, 40f)).isEqualTo(0)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(0f)
    }

    @Test
    fun `a new drag continues the travel the last one did not spend`() {
        val accumulator = ScrollAccumulator()
        accumulator.dragBy(39f, 40f, 40f)

        // The finger stopped a hair short of a line, and a second touch picks up from there rather
        // than starting the line over - which is what makes a resumed slow drag feel continuous.
        assertThat(accumulator.dragBy(1f, 40f, 40f)).isEqualTo(1)
    }

    @Test
    fun `the two directions are priced separately`() {
        // An empty screen above the window and two rows of it below: a step back is free, a step
        // forward costs two rows. One price for both is what would make the screen translate on the
        // first drag of a session and then snap back.
        val back = ScrollAccumulator()
        val forward = ScrollAccumulator()

        assertThat(back.dragBy(80f, 0f, 80f)).isEqualTo(0)
        assertThat(back.pendingPx).isWithin(0.001f).of(80f)
        assertThat(forward.dragBy(-80f, 0f, 80f)).isEqualTo(-1)
        assertThat(forward.pendingPx).isWithin(0.001f).of(0f)
    }

    @Test
    fun `travel the free direction has no step to buy is kept for the step that follows`() {
        // The step that moved nothing is taken by the caller without spending the finger's pixels, so
        // the same travel has to still be here for the step that does move the window - otherwise the
        // screen stands still for a step's worth of drag every time the offset passes the live end.
        val accumulator = ScrollAccumulator()

        assertThat(accumulator.dragBy(35f, 0f, 40f)).isEqualTo(0)
        assertThat(accumulator.dragBy(5f, 40f, 40f)).isEqualTo(1)
    }

    @Test
    fun `a remainder carried the other way is charged the other direction's price`() {
        val accumulator = ScrollAccumulator()
        accumulator.dragBy(30f, 80f, 40f)

        // 30 back, then 50 forward: the net 20 belongs to the forward direction and is priced by it.
        assertThat(accumulator.dragBy(-50f, 80f, 40f)).isEqualTo(0)
        assertThat(accumulator.pendingPx).isWithin(0.001f).of(-20f)
    }

    @Test
    fun `resetting drops the travel held for a step`() {
        val accumulator = ScrollAccumulator()
        accumulator.dragBy(30f, 40f, 40f)

        accumulator.reset()

        assertThat(accumulator.pendingPx).isWithin(0.001f).of(0f)
        assertThat(accumulator.dragBy(10f, 40f, 40f)).isEqualTo(0)
    }

    // --- freeStep ----------------------------------------------------------

    @Test
    fun `a step that costs nothing is taken free while there is somewhere to go`() {
        // The fit's walk stalls between these two offsets - a budget an odd number of rows does not
        // divide - so the top of the screen would not move and a priced drag would never take a step at
        // all. The travel stays, because the step after this one is the one that moves something.
        assertThat(freeStep(pendingPx = 3f, backStepPx = 0f, forwardStepPx = 40f, offset = 0, ceiling = 9))
            .isEqualTo(1)
    }

    @Test
    fun `the same free step at the end of the history is not taken`() {
        assertThat(freeStep(pendingPx = 3f, backStepPx = 0f, forwardStepPx = 40f, offset = 9, ceiling = 9))
            .isEqualTo(0)
    }

    @Test
    fun `a free step towards the live end stops at the live end`() {
        assertThat(freeStep(pendingPx = -3f, backStepPx = 40f, forwardStepPx = 0f, offset = 1, ceiling = 9))
            .isEqualTo(-1)
        assertThat(freeStep(pendingPx = -3f, backStepPx = 40f, forwardStepPx = 0f, offset = 0, ceiling = 9))
            .isEqualTo(0)
    }

    @Test
    fun `a step with a price is left to the price rather than taken free`() {
        assertThat(freeStep(pendingPx = 3f, backStepPx = 40f, forwardStepPx = 40f, offset = 4, ceiling = 9))
            .isEqualTo(0)
        assertThat(freeStep(pendingPx = -3f, backStepPx = 40f, forwardStepPx = 40f, offset = 4, ceiling = 9))
            .isEqualTo(0)
    }

    @Test
    fun `a free step is only free in the direction that is free`() {
        // Backwards is free here and forwards is not: a drag the other way must wait for its price.
        assertThat(freeStep(pendingPx = -3f, backStepPx = 0f, forwardStepPx = 40f, offset = 4, ceiling = 9))
            .isEqualTo(0)
    }

    @Test
    fun `travel in the free direction that has not been made is not a step`() {
        assertThat(freeStep(pendingPx = 0f, backStepPx = 0f, forwardStepPx = 40f, offset = 4, ceiling = 9))
            .isEqualTo(0)
        assertThat(freeStep(pendingPx = Float.NaN, backStepPx = 0f, forwardStepPx = 40f, offset = 4, ceiling = 9))
            .isEqualTo(0)
    }

    @Test
    fun `an unpriceable price is not a free one`() {
        assertThat(freeStep(pendingPx = 3f, backStepPx = Float.NaN, forwardStepPx = 40f, offset = 4, ceiling = 9))
            .isEqualTo(0)
    }

    @Test
    fun `an offset outside the scrollback is read as its nearest end`() {
        assertThat(freeStep(pendingPx = 3f, backStepPx = 0f, forwardStepPx = 40f, offset = 99, ceiling = 9))
            .isEqualTo(0)
        assertThat(freeStep(pendingPx = -3f, backStepPx = 40f, forwardStepPx = 0f, offset = -4, ceiling = 9))
            .isEqualTo(0)
    }

    @Test
    fun `a buffer with no history takes no free step either way`() {
        assertThat(freeStep(pendingPx = 3f, backStepPx = 0f, forwardStepPx = 0f, offset = 0, ceiling = 0))
            .isEqualTo(0)
        assertThat(freeStep(pendingPx = -3f, backStepPx = 0f, forwardStepPx = 0f, offset = 0, ceiling = 0))
            .isEqualTo(0)
    }

    // --- flingRoomPx -------------------------------------------------------

    @Test
    fun `a fling's room is the steps left priced at what they cost there`() {
        assertThat(flingRoomPx(stepsLeft = 4, stepPx = 40f, cellPx = 20f)).isWithin(0.001f).of(160f)
    }

    @Test
    fun `a step that costs nothing prices the fling at a cell rather than at nothing`() {
        // The fit's walk stalls here, so the step is free - but the ones after it are not, and a room
        // of zero is the fling's "nowhere to go": the flick would die instead of taking the free step.
        assertThat(flingRoomPx(stepsLeft = 4, stepPx = 0f, cellPx = 20f)).isWithin(0.001f).of(80f)
    }

    @Test
    fun `a fling with nowhere to go has no room`() {
        assertThat(flingRoomPx(stepsLeft = 0, stepPx = 40f, cellPx = 20f)).isWithin(0.001f).of(0f)
        assertThat(flingRoomPx(stepsLeft = -3, stepPx = 40f, cellPx = 20f)).isWithin(0.001f).of(0f)
    }

    @Test
    fun `a fling priced by nothing at all has no room`() {
        // Neither a price nor a cell height to fall back on - there is no distance to measure the
        // decay against, and a room invented here would be a fling over a screen nobody measured.
        assertThat(flingRoomPx(stepsLeft = 4, stepPx = 0f, cellPx = 0f)).isWithin(0.001f).of(0f)
        assertThat(flingRoomPx(stepsLeft = 4, stepPx = Float.NaN, cellPx = Float.NaN)).isWithin(0.001f).of(0f)
        assertThat(flingRoomPx(stepsLeft = 4, stepPx = -5f, cellPx = -5f)).isWithin(0.001f).of(0f)
    }

    // --- scrollShiftPx -----------------------------------------------------

    @Test
    fun `a fraction of a step draws that fraction`() {
        assertThat(scrollShiftPx(12f, 40f, 40f)).isWithin(0.001f).of(12f)
        assertThat(scrollShiftPx(-12f, 40f, 40f)).isWithin(0.001f).of(-12f)
    }

    @Test
    fun `the shift never opens more screen than the frame can fill`() {
        // Past the price there is a whole step to draw, not a taller one than the margins hold.
        assertThat(scrollShiftPx(120f, 40f, 25f)).isWithin(0.001f).of(40f)
        assertThat(scrollShiftPx(-120f, 40f, 25f)).isWithin(0.001f).of(-25f)
    }

    @Test
    fun `a direction with no step to take draws no shift`() {
        // At the live end the first step back moves the window's top nowhere, so there is nothing for a
        // shift to reveal; the same at the other end going forward.
        assertThat(scrollShiftPx(20f, 0f, 40f)).isWithin(0.001f).of(0f)
        assertThat(scrollShiftPx(-20f, 40f, 0f)).isWithin(0.001f).of(0f)
        assertThat(scrollShiftPx(20f, Float.NaN, Float.NaN)).isWithin(0.001f).of(0f)
    }

    @Test
    fun `a shift asked of a travel that is not a number is none`() {
        assertThat(scrollShiftPx(Float.NaN, 40f, 40f)).isWithin(0.001f).of(0f)
        assertThat(scrollShiftPx(Float.POSITIVE_INFINITY, 40f, 40f)).isWithin(0.001f).of(0f)
    }

    // --- touchedRow --------------------------------------------------------

    @Test
    fun `a touch names the row its own cell sits on`() {
        assertThat(touchedRow(y = 100f, originY = 0f, cellHeight = 20f, shiftPx = 0f, marginAbove = 0, rows = 10))
            .isEqualTo(5)
        assertThat(touchedRow(y = 119f, originY = 0f, cellHeight = 20f, shiftPx = 0f, marginAbove = 0, rows = 10))
            .isEqualTo(5)
        assertThat(touchedRow(y = 120f, originY = 0f, cellHeight = 20f, shiftPx = 0f, marginAbove = 0, rows = 10))
            .isEqualTo(6)
    }

    @Test
    fun `the grid's own offset is taken off first`() {
        assertThat(touchedRow(y = 100f, originY = 40f, cellHeight = 20f, shiftPx = 0f, marginAbove = 0, rows = 10))
            .isEqualTo(3)
    }

    @Test
    fun `a touch mid-drag names the row under the finger as it is drawn, not as it rests`() {
        // The strip is drawn 15 px below where it rests, so the row the finger is on is the one whose
        // resting position is 15 px higher - which is the row above, until a whole cell has passed.
        assertThat(touchedRow(y = 100f, originY = 0f, cellHeight = 20f, shiftPx = 15f, marginAbove = 0, rows = 10))
            .isEqualTo(4)
    }

    @Test
    fun `a touch on the rows a drag uncovered belongs to the nearest row of the window`() {
        // Three margin rows are above the window. A finger on them is naming a row the layout does not
        // hold, so it is answered with the first row of the window rather than a negative index.
        assertThat(touchedRow(y = 20f, originY = 0f, cellHeight = 20f, shiftPx = 0f, marginAbove = 3, rows = 10))
            .isEqualTo(0)
        assertThat(touchedRow(y = 79f, originY = 0f, cellHeight = 20f, shiftPx = 0f, marginAbove = 3, rows = 10))
            .isEqualTo(0)
        assertThat(touchedRow(y = 80f, originY = 0f, cellHeight = 20f, shiftPx = 0f, marginAbove = 3, rows = 10))
            .isEqualTo(1)
    }

    @Test
    fun `a touch past either edge of the window is clamped into it`() {
        assertThat(touchedRow(y = -500f, originY = 0f, cellHeight = 20f, shiftPx = 0f, marginAbove = 0, rows = 10))
            .isEqualTo(0)
        assertThat(touchedRow(y = 5000f, originY = 0f, cellHeight = 20f, shiftPx = 0f, marginAbove = 0, rows = 10))
            .isEqualTo(9)
    }

    @Test
    fun `a screen with no rows has no row to name`() {
        assertThat(touchedRow(y = 100f, originY = 0f, cellHeight = 20f, shiftPx = 0f, marginAbove = 0, rows = 0))
            .isEqualTo(-1)
    }

    @Test
    fun `a cell with no height names the first row rather than dividing by nothing`() {
        assertThat(touchedRow(y = 100f, originY = 0f, cellHeight = 0f, shiftPx = 0f, marginAbove = 0, rows = 10))
            .isEqualTo(0)
        assertThat(touchedRow(y = 100f, originY = 0f, cellHeight = Float.NaN, shiftPx = 0f, marginAbove = 0, rows = 10))
            .isEqualTo(0)
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
