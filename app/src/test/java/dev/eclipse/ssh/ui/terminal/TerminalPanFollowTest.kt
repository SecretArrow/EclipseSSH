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
        assertThat(followShouldAnimate(33_000_000L)).isFalse()   // 30 fps of streaming output
        assertThat(followShouldAnimate(16_000_000L)).isFalse()   // 60 fps, the same case harder
        assertThat(followShouldAnimate(0L)).isFalse()            // two frames in one tick

        // A keystroke, which is the case the tween exists for: the interval is longer than the tween, so
        // the slide finishes and there is nothing to fall behind. One cell of movement, animated.
        assertThat(followShouldAnimate(100_000_000L)).isTrue()
        assertThat(followShouldAnimate(tween)).isTrue()

        // The boundary is the tween's own duration, inclusive: a follow that arrives exactly one tween
        // after the last one has, by construction, nothing left to finish.
        assertThat(followShouldAnimate(tween - 1L)).isFalse()
        assertThat(followShouldAnimate(tween + 1L)).isTrue()

        // The first follow of a session, which is what an unset clock produces: the elapsed time is
        // everything since the device booted, so the first crossing is animated like any other.
        assertThat(followShouldAnimate(Long.MAX_VALUE)).isTrue()
    }

    private companion object {
        /** A pan is a float multiplication, so the pixel it produces is not exact. */
        const val TOLERANCE = 0.001f
    }
}
