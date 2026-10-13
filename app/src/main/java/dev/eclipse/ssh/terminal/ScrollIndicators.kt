package dev.eclipse.ssh.terminal

/**
 * Which edges of a horizontally scrolling row have content past them.
 *
 * Drawn as a fade at the edge with content behind it: a cap that is clipped by the viewport otherwise
 * reads as a control that was drawn wrong, which is exactly how the shortcut bar's last key was
 * reported - "EN" where "ENTER" was, with nothing to say the rest of it exists off the side of the
 * screen. The fade says the row continues, and which way.
 */
data class RowOverflow(val atStart: Boolean, val atEnd: Boolean)

/**
 * The edges of a row scrolled to [scrollValue] out of [maxScrollValue].
 *
 * Both comparisons are one-sided on purpose. Both numbers come from a layout that may not have been
 * measured yet, and a one-sided test answers correctly for every value either can hold: a scroll
 * before the start is a row with nothing to its left, one past the end is a row with nothing to its
 * right, and a range of zero or less is a row that fits, so both edges are the same edge and neither
 * is hidden. Clamping first would only be a second way to say the same thing, and the version that
 * did clamp threw on a negative range.
 */
fun rowOverflow(scrollValue: Int, maxScrollValue: Int): RowOverflow =
    RowOverflow(atStart = scrollValue > 0, atEnd = scrollValue < maxScrollValue)

/**
 * How many buffer lines sit between the bottom of this window and the live output; zero at the bottom.
 *
 * Zero is the only value a caller acts on - it is the scrolled-back badge's whole test - and that is
 * why this is not a subtraction at the call site. The window is cut from the buffer by whoever asked
 * for the frame, so a tall window on a short buffer, or a frame built before the first line arrived,
 * subtracts to a negative; and a badge reading "-5 lines below" is not a state the user can be in.
 *
 * Measured to [TerminalFrame.liveEnd] rather than to [TerminalFrame.totalLines], which is the buffer's
 * *height* and not the end of anything: a 60-row pty with five lines of output has 55 blank rows after
 * the last one it has written, and counting those as lines below the window put a permanent "49 lines
 * below" over the grid of a session that had nothing to scroll back into at all. At the bottom the
 * window's own lower edge is the live end, so this is the scroll offset in lines - and only the
 * offset, which is what the badge is trying to say.
 */
fun TerminalFrame.linesBelow(): Int = (liveEnd - (firstLine + lines.size)).coerceAtLeast(0)
