package dev.eclipse.ssh.ui.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The pan that keeps the cursor on screen, and the decision whether to slide or to jump there.
 *
 * Pure, and separated from the pixel geometry next door because it is a different question: the
 * geometry asks how much of the screen the text gets, and this asks where the window has to sit to
 * keep the one thing a terminal may never hide - the cursor - inside it. Both are arithmetic with no
 * device, no font and no Compose tree in them, which is the point: the follow is a guarantee, and a
 * guarantee that can only be checked by looking at a phone is not one.
 */
class TerminalPanFollowTest {

    @Test
    fun `only painted columns are pannable`() {
        // 46 on screen, 46 painted: nothing to pan to, and being able to drag the text off the edge to
        // look at blank space would only be a way to lose it.
        assertThat(maxPanPx(contentColumns = 46, visibleColumns = 46, cellWidth = 10f)).isEqualTo(0f)
        assertThat(maxPanPx(contentColumns = 20, visibleColumns = 46, cellWidth = 10f)).isEqualTo(0f)
        // 80 painted on a 46-column screen: exactly the 34 columns that do not fit.
        assertThat(maxPanPx(contentColumns = 80, visibleColumns = 46, cellWidth = 10f)).isEqualTo(340f)
    }

    @Test
    fun `history printed at a wider terminal is reachable`() {
        // The rotation case, and the reason contentColumns is not simply the grid width: the lines are
        // 120 cells long because that is what they were printed at, and every one of those characters
        // has to be reachable on a screen that now fits 46.
        assertThat(maxPanPx(contentColumns = 120, visibleColumns = 46, cellWidth = 23.4f))
            .isWithin(TOLERANCE).of(74 * 23.4f)
    }

    @Test
    fun `the pan follows the cursor off either edge and nowhere else`() {
        val cellWidth = 10f
        val window = 460f
        // The row the cursor is on paints all eighty columns, so the cursor is inside its row's text and
        // is followed exactly as it is.
        val painted = 80

        // Cursor at column 79 of an 80-column grid, window showing 46: the pan settles so the cursor's
        // cell ends exactly at the right edge.
        assertThat(
            panForCursor(0f, cursorColumn = 79, cursorRowInk = painted, cursorOnScreen = true, cellWidth = cellWidth, visibleWidthPx = window, maxPan = 340f),
        ).isEqualTo(340f)

        // Already inside the window: left exactly where it was, which is what makes a deliberate pan
        // stick while output arrives.
        assertThat(
            panForCursor(200f, cursorColumn = 25, cursorRowInk = painted, cursorOnScreen = true, cellWidth = cellWidth, visibleWidthPx = window, maxPan = 340f),
        ).isEqualTo(200f)

        // Carriage return to column 0 while panned right: comes back to the start of the line.
        assertThat(
            panForCursor(340f, cursorColumn = 0, cursorRowInk = painted, cursorOnScreen = true, cellWidth = cellWidth, visibleWidthPx = window, maxPan = 340f),
        ).isEqualTo(0f)
    }

    @Test
    fun `a cursor in the cell after its row's text is followed into the window`() {
        // The measured case, from a real [CC] stream: the input row paints 47 columns and the cursor sits
        // in the 48th, on a phone whose window is 44 columns. Following the row's paint alone settled the
        // pan at 4 - which parks the cursor's own cell, [480,490), entirely outside the window [40,480),
        // and a cell drawn outside the window is a frame with no cursor block in it at all.
        val pan = panForCursor(
            current = 0f,
            cursorColumn = 48,
            cursorRowInk = 47,
            cursorOnScreen = true,
            cellWidth = 10f,
            visibleWidthPx = 440f,
            maxPan = 100f,
        )
        assertThat(pan).isEqualTo(50f)
        // And the cell the cursor is drawn in is inside: [480,490) against the window [50,490).
        assertThat(48 * 10f).isAtLeast(pan)
        assertThat(49 * 10f).isAtMost(pan + 440f)
    }

    @Test
    fun `the cursor's own cell stays in the window wherever it goes along its row`() {
        // The invariant, carried across a whole sweep rather than asserted one call at a time: each call
        // is handed the pan the last one returned, which is what the view does. The row's paint stops at
        // 69 and the cursor runs to 70, so both halves of the rule are walked - at the text, and in the
        // one cell after it.
        val cellWidth = 10f
        val window = 44 * cellWidth
        val ink = 69
        var pan = 0f
        for (column in (0..ink + 1) + (ink + 1 downTo 0)) {
            pan = panForCursor(pan, column, ink, true, cellWidth, window, maxPan = 1000f)
            assertThat(column * cellWidth).isAtLeast(pan)
            assertThat((column + 1) * cellWidth).isAtMost(pan + window)
        }
    }

    @Test
    fun `a cursor parked past its row's text does not drag the view with it`() {
        val cellWidth = 10f
        val window = 460f

        // The reported bug, and the capture it came from: a full-screen program parks the terminal's
        // cursor on the corner of its input box, well past the line being typed. Following it put
        // columns 17 to 60 on a 44-column phone - the user's message off the left edge, and a hollow
        // cursor block floating in the blank half of the box. Nothing is out there to keep in view.
        assertThat(
            panForCursor(0f, cursorColumn = 60, cursorRowInk = 9, cursorOnScreen = true, cellWidth = cellWidth, visibleWidthPx = window, maxPan = 340f),
        ).isEqualTo(0f)

        // The same column, on a row whose text really does reach it: still followed, because a command
        // typed out to the edge is exactly what this rule exists for.
        assertThat(
            panForCursor(0f, cursorColumn = 60, cursorRowInk = 60, cursorOnScreen = true, cellWidth = cellWidth, visibleWidthPx = window, maxPan = 340f),
        ).isEqualTo(150f)
    }

    @Test
    fun `a cursor that comes back to short text is met where it is`() {
        // Panned right for a long line, then the text on that row is deleted back to nine columns. The
        // row's own end is followed, and nine columns fit the window whole - so the window returns to
        // the start of the text rather than parking the row's end at its left edge, which is what left
        // the reader looking at the blank half of a row they had just typed.
        assertThat(
            panForCursor(340f, cursorColumn = 60, cursorRowInk = 9, cursorOnScreen = true, cellWidth = 10f, visibleWidthPx = 460f, maxPan = 340f),
        ).isEqualTo(0f)

        // Text still wider than the window: the window comes back only as far as it must to show where
        // the cursor is, which is the follow column at its right edge - not all the way to the left one.
        assertThat(
            panForCursor(900f, cursorColumn = 70, cursorRowInk = 120, cursorOnScreen = true, cellWidth = 10f, visibleWidthPx = 460f, maxPan = 900f),
        ).isEqualTo(250f)
    }

    @Test
    fun `a cursor the user has scrolled away from does not yank the view back`() {
        // Scrolled into the history, so the frame reports no cursor row. The pan is the user's.
        assertThat(
            panForCursor(120f, cursorColumn = 0, cursorRowInk = 0, cursorOnScreen = false, cellWidth = 10f, visibleWidthPx = 460f, maxPan = 340f),
        ).isEqualTo(120f)
    }

    @Test
    fun `an offset stranded past the end of a narrower frame is pulled back`() {
        // Scrolling from long lines to short ones shrinks the extent under a stored pan; without the
        // clamp the view would be looking at nothing at all.
        assertThat(
            panForCursor(340f, cursorColumn = 0, cursorRowInk = 0, cursorOnScreen = false, cellWidth = 10f, visibleWidthPx = 460f, maxPan = 0f),
        ).isEqualTo(0f)
        // And a negative extent - a window wider than its content - is not a crash.
        assertThat(
            panForCursor(10f, cursorColumn = 0, cursorRowInk = 80, cursorOnScreen = true, cellWidth = 10f, visibleWidthPx = 460f, maxPan = -50f),
        ).isEqualTo(0f)
    }

    @Test
    fun `unmeasured metrics cannot make the pan a NaN`() {
        // gridIn already survives a zero cell; this is the other half of the same divide-by-zero.
        assertThat(
            panForCursor(0f, cursorColumn = 10, cursorRowInk = 80, cursorOnScreen = true, cellWidth = 0f, visibleWidthPx = 0f, maxPan = 0f),
        ).isEqualTo(0f)
    }

    @Test
    fun `a follow that arrives before the tween could finish is assigned instead`() {
        // The two regimes, and the interval is the whole of the difference between them. A tween takes
        // PAN_FOLLOW_MS to reach its target, so a follow asked for again inside that window cannot be
        // one the tween finishes: the pan is left short of where the cursor is, and the cursor and the
        // newest characters are drawn off the right edge of the window for as long as output lasts.
        // Measured, not asserted by feel: with the target moving one cell per frame and the tween
        // covering T/90 of the gap per restart, the shortfall settles at 90/T cells - 2.7 of them at
        // 30 fps, which is most of a phone's screen margin and every character the user just wrote.
        val tween = PAN_FOLLOW_MS.toLong() * 1_000_000L
        assertThat(followShouldAnimate(33_000_000L, cellsMoved = 1)).isFalse()   // 30 fps of streaming
        assertThat(followShouldAnimate(16_000_000L, cellsMoved = 1)).isFalse()   // 60 fps, harder
        assertThat(followShouldAnimate(0L, cellsMoved = 1)).isFalse()            // two frames in one tick

        // A keystroke, which is the case the tween exists for: the interval is longer than the tween, so
        // the slide finishes and there is nothing to fall behind. One cell of movement, animated.
        assertThat(followShouldAnimate(100_000_000L, cellsMoved = 1)).isTrue()
        assertThat(followShouldAnimate(tween, cellsMoved = 1)).isTrue()

        // The boundary is the tween's own duration, inclusive: a follow that arrives exactly one tween
        // after the last one has, by construction, nothing left to finish.
        assertThat(followShouldAnimate(tween - 1L, cellsMoved = 1)).isFalse()
        assertThat(followShouldAnimate(tween + 1L, cellsMoved = 1)).isTrue()

        // The first follow of a session, which is what an unset clock produces: the elapsed time is
        // everything since the device booted, so the first crossing is animated like any other.
        assertThat(followShouldAnimate(Long.MAX_VALUE, cellsMoved = 1)).isTrue()
    }

    @Test
    fun `a follow of more than one cell is assigned however long the quiet was`() {
        // The gap is not the only thing that decides. A program that prints after a pause, or a scroll
        // back to the live screen, moves the cursor many cells at once, and the tween only reaches its
        // target PAN_FOLLOW_MS later - so a jump spread over the slide leaves the cursor outside the
        // window for the whole of it, which is the one thing the follow is not allowed to do.
        val quiet = Long.MAX_VALUE
        assertThat(followShouldAnimate(quiet, cellsMoved = 2)).isFalse()
        assertThat(followShouldAnimate(quiet, cellsMoved = 30)).isFalse()
        // One cell is the courtesy the tween was added for, and the first follow of a session is one.
        assertThat(followShouldAnimate(quiet, cellsMoved = 1)).isTrue()
    }

    @Test
    fun `the distance a follow covers is counted in cells, and rounded rather than truncated`() {
        val cellWidth = 10f
        // The ordinary keystroke: one cell, and the float arithmetic of a pan is not exact, so a
        // truncating count would read 0 and a rounding one reads 1 - which is what keeps its tween.
        assertThat(cellsBetween(100f, 110f, cellWidth)).isEqualTo(1)
        assertThat(cellsBetween(0f, 10.000001f, cellWidth)).isEqualTo(1)
        assertThat(cellsBetween(0f, 9.999999f, cellWidth)).isEqualTo(1)
        // A jump, in either direction.
        assertThat(cellsBetween(0f, 340f, cellWidth)).isEqualTo(34)
        assertThat(cellsBetween(340f, 0f, cellWidth)).isEqualTo(34)
        // Unmeasured metrics: there are no cells to count, so the answer is the largest distance there
        // is and the follow is assigned rather than slid - the same divide-by-zero the pan itself
        // survives, answered once instead of at each of its callers.
        assertThat(cellsBetween(0f, 100f, 0f)).isEqualTo(Int.MAX_VALUE)
        assertThat(cellsBetween(0f, 100f, -1f)).isEqualTo(Int.MAX_VALUE)
    }

    private companion object {
        /** A pan is a float multiplication, so the pixel it produces is not exact. */
        const val TOLERANCE = 0.001f
    }
}
