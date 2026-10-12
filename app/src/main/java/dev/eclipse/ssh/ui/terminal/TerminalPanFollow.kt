package dev.eclipse.ssh.ui.terminal

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How far the grid may be panned to the left, in pixels.
 *
 * Only *painted* columns are pannable. A shell screen is mostly empty on the right, and being able to
 * drag the text away to stare at that emptiness is a way to lose the output rather than a feature - so
 * the extent comes from [dev.eclipse.ssh.terminal.TerminalLayout.contentColumns], which counts what is
 * actually on the widest drawn row (including the cursor's own cell), and not from the width the pty was
 * given. After wrapping that is usually the window itself and there is nothing to pan; what is left is
 * the alternate screen, and the single token too wide to break.
 */
internal fun maxPanPx(contentColumns: Int, visibleColumns: Int, cellWidth: Float): Float =
    ((maxOf(contentColumns, visibleColumns) - visibleColumns) * cellWidth).coerceAtLeast(0f)

/**
 * The pan offset that keeps the cursor on screen, given where it is now.
 *
 * The cursor is the one thing a terminal may never hide. With an 80-column grid on a phone that shows
 * forty-five, a command long enough to pass the right edge would otherwise be typed out of sight -
 * the characters arrive, the line is correct, and the user cannot see what they are editing. So the
 * view follows: it pans just far enough to bring the cursor's cell inside the window, from whichever
 * side it left, and otherwise leaves the offset exactly where it was. That "otherwise" is what makes a
 * deliberate pan sideways stick while output is arriving - it is given up only when the cursor moves
 * somewhere it cannot be seen.
 *
 * What is followed is [cursorColumn] only for as long as the cursor is at the text of its own row. A
 * full-screen program may park the terminal's cursor away from the text it draws the user's caret in
 * - Claude Code leaves it on the corner of its input box, fifty columns past the line being typed - and
 * following a parked cursor drags the window onto the empty half of the row, taking the user's message
 * off the left edge. Past the row's paint the cursor is therefore read as [cursorRowInk], which is where
 * a program drawing its own caret puts that caret: at the end of what the user typed.
 *
 * "At the text" includes the one blank cell *after* it, and that cell is why the grace is one past
 * [cursorRowInk] rather than [cursorRowInk] itself. A cursor one past the row's paint is the ordinary
 * case - it is where every keystroke of a command leaves it - and the view draws it at its own column,
 * not at the row's end. Following the paint alone parks the window with the cursor's cell exactly
 * outside the right edge, and the cursor is then drawn nowhere at all: measured on a real [CC] stream,
 * once in 378 reads, a frame in which the block the user is looking for is simply absent.
 *
 * [current] is re-clamped on every call, because the extent shrinks under it: a narrower frame, or
 * scrolling back to a region of short lines, can leave a stored offset past the end of what exists.
 */
internal fun panForCursor(
    current: Float,
    cursorColumn: Int,
    cursorRowInk: Int,
    cursorOnScreen: Boolean,
    cellWidth: Float,
    visibleWidthPx: Float,
    maxPan: Float,
): Float {
    val ceiling = maxPan.coerceAtLeast(0f)
    val clamped = current.coerceIn(0f, ceiling)
    if (!cursorOnScreen || cellWidth <= 0f || visibleWidthPx <= 0f) return clamped
    val followed = cursorColumn.coerceAtMost(cursorRowInk + 1)
    val left = followed * cellWidth
    val right = left + cellWidth
    // The least pan that puts the followed cell inside the window, which is the only place it moves to:
    // a cursor leaving to the right is caught at the right edge, and one that comes back left of the
    // window is met where it is rather than pushed to the left edge of a window that scrolled further.
    val wanted = (right - visibleWidthPx).coerceIn(0f, ceiling)
    return if (right > clamped + visibleWidthPx || left < clamped) wanted else clamped
}

/**
 * How long the view takes to slide sideways when the cursor runs past the edge of the window.
 *
 * Linear and short on purpose: the follow restarts on every cursor move, and an eased tween would spend
 * its slow beginning again each keystroke.
 */
internal const val PAN_FOLLOW_MS = 90

/**
 * Whether a follow should be tweened, or assigned outright, given how long it has been since the last
 * one and how far this one moves, in cells.
 *
 * The tween is a courtesy: it turns the one-cell jump of a keystroke crossing the right edge into a
 * slide. It is not a guarantee, and it must not be treated as one, because the follow *is* a guarantee -
 * the cursor is never to be off the screen - and a tween only reaches its target [PAN_FOLLOW_MS] later.
 *
 * What that costs is arithmetic rather than taste. A linear tween restarted every `T` ms covers `T / 90`
 * of the gap before the next restart, while a program printing a long line moves the target one cell per
 * frame, so the gap settles at `90 / T` cells: at 30 fps that is **2.7 cells**, and the cursor and the
 * characters just written are drawn past the right edge of the window for as long as the output lasts.
 * The tween is then not smoothing anything - it is the only reason the newest text cannot be seen.
 *
 * So a follow that arrives inside the tween's own duration is assigned, and one with a quiet gap before
 * it is tweened - unless it is not a courtesy at all. A follow of more than one cell is a jump the user
 * did not make: a program that prints after a pause, or the end of a scroll back to the live screen,
 * moves the cursor many cells at once, and sliding that over [PAN_FOLLOW_MS] leaves the cursor - and the
 * cell it is on - off the window for the whole of the slide, which is the one thing the follow exists to
 * prevent. A jump is therefore assigned whatever the gap before it, and only a single cell is tweened.
 *
 * [sinceLastFollowNanos] is the interval the caller measured, in nanoseconds, and [cellsMoved] is the
 * distance between the current offset and the target in cells: parameters rather than measurements taken
 * here, so that the rule is a function of the two numbers and nothing else.
 */
internal fun followShouldAnimate(sinceLastFollowNanos: Long, cellsMoved: Int): Boolean =
    cellsMoved <= 1 && sinceLastFollowNanos >= PAN_FOLLOW_MS.toLong() * NANOS_PER_MILLI

/**
 * How far apart two pan offsets are, in cells, which is what [followShouldAnimate] is handed.
 *
 * Here rather than in the view because it is the same kind of arithmetic as the rest of this file and it
 * has the same two edges. An unmeasured cell width has no cells to count and the answer is "as far as it
 * can be", so the follow is assigned rather than spread out; and a distance is rounded rather than
 * truncated, so the float arithmetic of a one-cell move still reads one and does not lose its tween.
 */
internal fun cellsBetween(fromPx: Float, toPx: Float, cellWidth: Float): Int =
    if (cellWidth <= 0f) Int.MAX_VALUE else (abs(toPx - fromPx) / cellWidth).roundToInt()

private const val NANOS_PER_MILLI = 1_000_000L
