package dev.eclipse.ssh.terminal

/**
 * The hyperlink target on one cell of a frame, or null when the cell is ordinary text.
 *
 * [line] is an *absolute buffer line* - the coordinate a selection and a long press are already
 * addressed in, and the one a tap resolves to - so the window's own offset comes off here rather than
 * at every call site. A line or a column the frame does not have is null and not a throw: the gesture
 * that asks is a finger on a screen that may have scrolled or resized since the frame was built, and a
 * tap that lands nowhere is a tap that does nothing.
 */
fun hyperlinkAt(frame: TerminalFrame, line: Int, column: Int): String? {
    if (column < 0) return null
    val row = frame.lines.getOrNull(line - frame.firstLine) ?: return null
    return row.getOrNull(column)?.hyperlink
}
