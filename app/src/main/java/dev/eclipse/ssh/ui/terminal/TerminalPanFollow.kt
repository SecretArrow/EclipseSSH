package dev.eclipse.ssh.ui.terminal

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
 * What is followed is [cursorColumn] only for as long as the cursor is inside the text of its own row.
 * A full-screen program may park the terminal's cursor away from the text it draws the user's caret in
 * - Claude Code leaves it on the corner of its input box, fifty columns past the line being typed - and
 * following a parked cursor drags the window onto the empty half of the row, taking the user's message
 * off the left edge. Past the row's paint the cursor is therefore read as [cursorRowInk], which is where
 * a program drawing its own caret puts that caret: at the end of what the user typed.
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
    val followed = cursorColumn.coerceAtMost(cursorRowInk)
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
 * Whether a follow should be tweened, or assigned outright, given how long it has been since the last.
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
 * it is tweened. [sinceLastFollowNanos] is the interval the caller measured, in nanoseconds; it is a
 * parameter rather than a clock read here so that the rule is a function of the interval and nothing else.
 */
internal fun followShouldAnimate(sinceLastFollowNanos: Long): Boolean =
    sinceLastFollowNanos >= PAN_FOLLOW_MS.toLong() * NANOS_PER_MILLI

private const val NANOS_PER_MILLI = 1_000_000L
