package dev.eclipse.ssh.terminal

/**
 * One drawable row of the view: the columns `[from, to)` of [line] within a [TerminalFrame]'s window.
 *
 * [line] is an index into [TerminalFrame.lines], not an absolute buffer line, because that is what the
 * renderer holds; a caller that needs the absolute one adds [TerminalFrame.firstLine], exactly as it
 * already does for a selection.
 */
data class TerminalVisualRow(val line: Int, val from: Int, val to: Int) {
    /** How many columns this row paints - the number the pan extent is measured in. */
    val width: Int get() = to - from
}

/**
 * Where every drawable row of a frame goes, once lines too wide for the window have been wrapped.
 *
 * The emulator is deliberately not involved. [AnsiTerminalBuffer] keeps hard-wrapping at the pty width,
 * so `contentColumns`, selection coordinates, resize and every existing emulator test keep their
 * meaning; this is a display layer that decides which columns of which line land on which row of the
 * screen, and nothing else. That separation is what makes the whole feature a pure function of a frame
 * and a width, testable without a Canvas, and reversible: drop it and the view pans as before.
 *
 * [rows] is bottom-anchored when wrapping produces more rows than fit. The bottom of a terminal is
 * where the cursor and the newest output are, so a screen that has to lose rows loses the oldest ones,
 * which are one flick of the scrollback away.
 *
 * What it never does is lose *content* while rows it could have given up sit blank below it, or leave
 * a wrapped line's middle row at the top of the screen. Both used to happen, and both are the same
 * fault seen from two sides: the window arriving here holds a fixed number of *buffer lines*, chosen
 * without knowing how wide the window is, so wrapping turns it into more rows than the screen has.
 * The top row then became the continuation of a line whose beginning was cut off - which draws the
 * tail of a word at column 0 - and, when the cursor stood near the top of a tall screen, a row was
 * dropped while fifteen blank rows waited below it.
 *
 * The repair for that is upstream, and it is the interesting half: [AnsiTerminalBuffer.frame] is told
 * the window's width and fits its line window to the screen in wrapped rows, so a frame and a layout
 * agree on the count and nothing has to be dropped at all. The bottom-anchoring here is kept for the
 * one case that route cannot cover - a frame built for a window size the view has since left, which is
 * the frame or two either side of a rotation - and it drops blank rows before it drops text, so even
 * a stale frame cannot cut a line the user is reading in half while blank space remains below it.
 */
class TerminalLayout(
    val rows: List<TerminalVisualRow>,
    /**
     * The widest row here, and therefore how far the view may pan.
     *
     * Usually the window width or less - that is the point of wrapping - and larger only where a single
     * token was too wide to break. Replaces [TerminalFrame.contentColumns] at the view, because after
     * wrapping the frame's number describes a line that is no longer drawn as one row.
     */
    val contentColumns: Int,
    /** Index into [rows] of the row holding the cursor, or -1 when the cursor is not on screen. */
    val cursorRow: Int,
    /** The cursor's column *within* its row, which is not its column within the line once wrapped. */
    val cursorColumn: Int,
) {
    companion object {
        val EMPTY = TerminalLayout(rows = emptyList(), contentColumns = 0, cursorRow = -1, cursorColumn = 0)
    }
}

/**
 * Lays [frame] out for a window [width] columns wide and [maxRows] rows tall.
 *
 * Wrapping is on for output that flowed and off for a screen that was painted, and that is the whole
 * rule - there is no setting, because there is no answer that suits both. Flowed output is a stream of
 * sentences and paths that the reader wants whole: a URL, a sha256 or a `/very/long/path` printed at 80
 * columns on a phone that fits 46 is currently reachable only by dragging the text sideways, one screen
 * at a time, which is the complaint this exists to answer. A painted screen is the opposite: `htop`,
 * `vim`, `nano`, `less` and `top` address every cell positionally and draw their own borders, status
 * lines and columns to the width they were told they had, so wrapping row 3 into two rows shifts
 * everything below it and the display is simply wrong. There the existing horizontal pan is right.
 *
 * Two flags say "painted", because one of them was not enough. [TerminalFrame.alternateScreen] catches
 * the four programs that switch screens, and it was originally the whole condition here; probing the
 * real binaries through a real pty showed that `top` is not one of them - procps repaints the *primary*
 * screen in place - so the emulator now also reports [TerminalFrame.positionalScreen], and the program
 * this file's own documentation used as its example of the alternate screen is the reason it exists.
 *
 * A token wider than the window is never broken. It goes on a row of its own, over-wide, and stays
 * reachable by panning - because breaking it is exactly the damage being avoided: a hostname, a hash, a
 * JSON blob or a numeric column split across rows reads as two different values, and a reader has no way
 * to tell that break from one the server sent.
 */
fun terminalLayout(frame: TerminalFrame, width: Int, maxRows: Int): TerminalLayout {
    if (frame.lines.isEmpty() || maxRows <= 0) return TerminalLayout.EMPTY
    val wrap = wrapsForDisplay(frame.alternateScreen, frame.positionalScreen, width)
    val rows = ArrayList<TerminalVisualRow>(frame.lines.size)
    frame.lines.forEachIndexed { index, line ->
        val extent = paintedExtent(frame, index, line)
        if (!wrap || extent <= width) {
            rows += TerminalVisualRow(index, 0, extent)
        } else {
            var start = 0
            while (start < extent) {
                val end = wrapBreak(line, start, extent, width)
                rows += TerminalVisualRow(index, start, end)
                start = end
            }
        }
    }
    val shown = fittedRows(rows, frame, maxRows)
    var cursorRow = -1
    var cursorColumn = 0
    if (frame.cursorVisible && frame.cursorRow >= 0) {
        // The last row that starts at or before the cursor's column, so a cursor sitting one past the
        // end of the text - where it is during every keystroke of a command - lands on the row holding
        // that text rather than nowhere.
        val index = shown.indexOfLast { it.line == frame.cursorRow && frame.cursorColumn >= it.from }
        if (index >= 0) {
            cursorRow = index
            cursorColumn = frame.cursorColumn - shown[index].from
        }
    }
    var content = 0
    for (row in shown) if (row.width > content) content = row.width
    // The cell being typed into is blank until the character lands, and the view has to be able to
    // follow the cursor there - the same reason [TerminalFrame.contentColumns] counts it.
    if (cursorRow >= 0) content = maxOf(content, cursorColumn + 1)
    return TerminalLayout(
        rows = if (shown === rows) shown else shown.toList(),
        contentColumns = content,
        cursorRow = cursorRow,
        cursorColumn = cursorColumn,
    )
}

/**
 * Whether [width] is a width this frame's lines may be re-wrapped for.
 *
 * The one place the rule is written, because two callers need it and they see the screen from opposite
 * ends: this file, which holds the frame, and [AnsiTerminalBuffer.frame], which is asked for a window
 * fitted to wrapped rows and has to know whether the view will wrap it at all. A frame that is not
 * wrapped must not be fitted either - a fitted window of a painted screen would be a `vim` whose top
 * half is off-screen.
 *
 * `0` means "no window to fit", and it is the state of every caller with no screen: a snapshot, the
 * PNG export renderer, and every test that drives the buffer directly.
 */
internal fun wrapsForDisplay(alternateScreen: Boolean, positionalScreen: Boolean, width: Int): Boolean =
    width > 0 && !alternateScreen && !positionalScreen

/**
 * How many rows [line]'s columns `[0, extent)` occupy in a window [width] columns wide.
 *
 * Walks [wrapBreak] exactly as [terminalLayout] does, and that is the whole point of it existing as a
 * function: [AnsiTerminalBuffer.frame] has to know the count *before* a frame exists, and a second
 * implementation of the rule would be a second thing to keep in step. The two agreeing is asserted
 * rather than assumed - see `TerminalScrollViewportTest.theLayoutAndTheWindowCountRowsTheSame`.
 */
internal fun displayRowCount(line: List<TerminalCell>, extent: Int, width: Int): Int {
    if (width <= 0 || extent <= width) return 1
    var rows = 0
    var start = 0
    while (start < extent) {
        start = wrapBreak(line, start, extent, width)
        rows++
    }
    return rows
}

/**
 * [rows], cut to [maxRows] with as little lost as the count allows.
 *
 * Two rules, in order. Rows that paint nothing go first, and only from the bottom: a blank row costs a
 * screen row and shows nothing, and dropping it moves no text, because every row above it keeps its
 * index. That rule is what stops the case that made a typed line disappear - the cursor's line one row
 * over the budget while fifteen blank rows sat underneath it - from eating the line instead.
 *
 * Only then are rows dropped from the top, which is the documented bottom-anchoring: the newest output
 * and the cursor are at the bottom, so the oldest rows are the ones a reader is least likely to be
 * looking at, and they are one flick of the scrollback away.
 *
 * A row is blank when its columns are blank, which is not the same as a *line* being blank: a wrapped
 * line whose second row is empty still has that row drawn, and it is dropped here like any other.
 */
private fun fittedRows(rows: List<TerminalVisualRow>, frame: TerminalFrame, maxRows: Int): List<TerminalVisualRow> {
    if (rows.size <= maxRows) return rows
    val cursorLine = if (frame.cursorVisible) frame.cursorRow else -1
    var end = rows.size
    while (end > maxRows && rows[end - 1].line != cursorLine && rowIsBlank(frame, rows[end - 1])) end--
    return if (end > maxRows) rows.subList(end - maxRows, end) else rows.subList(0, end)
}

/** Whether the row paints nothing at all: every column of it outside the line, or blank within it. */
private fun rowIsBlank(frame: TerminalFrame, row: TerminalVisualRow): Boolean {
    val line = frame.lines.getOrNull(row.line) ?: return true
    for (column in row.from until row.to) {
        val cell = line.getOrNull(column) ?: continue
        if (cell.value != ' ' || cell.combining.isNotEmpty()) return false
    }
    return true
}

/**
 * How many columns of `frame.lines[index]` this layout has to place.
 *
 * The painted width, plus the cursor's own cell when the cursor is on this line: trailing blanks are
 * not laid out at all, so a 200-column line with forty characters on it is one row and not five.
 * Bounded by the line's own length, because a frame captured across a resize can report a cursor
 * column the line does not have.
 */
private fun paintedExtent(frame: TerminalFrame, index: Int, line: List<TerminalCell>): Int {
    val painted = terminalPaintedWidth(line)
    val withCursor = if (index == frame.cursorRow && frame.cursorVisible) {
        maxOf(painted, frame.cursorColumn + 1)
    } else {
        painted
    }
    return withCursor.coerceAtMost(line.size)
}

/**
 * The exclusive end of the row that starts at [start], never splitting a word and never leaving a gap.
 *
 * Greedy from the right: the row is filled to the margin if the margin happens to fall between words,
 * and otherwise walks back to the last place a break does not land inside one. Walking back rather than
 * forward is what keeps the wrap at or before the margin, so a wrapped row never needs panning.
 *
 * Consecutive rows are contiguous - one row's [to] is the next row's `from` - so every column of the
 * line is drawn exactly once. Spaces at a break stay at the end of the row they belong to, and nothing
 * is swallowed: the server's indentation is the only indentation on screen, which is what keeps a
 * wrapped table or a `tree` listing readable.
 */
private fun wrapBreak(line: List<TerminalCell>, start: Int, extent: Int, width: Int): Int {
    if (extent - start <= width) return extent
    // The first column that does not fit, and therefore the first candidate: breaking here fills the row.
    val limit = start + width
    // A wide character's two columns belong to one row. A margin falling between them moves the
    // character down whole - one column early - and it does so even when that lands inside a word,
    // because the alternative is half a glyph hanging off each row. That is why the right edge of a
    // column of CJK text is ragged by one where a column of Latin text is not.
    if (splitsCharacter(line, limit)) {
        if (limit - 1 > start) return limit - 1
        // And where the character starts the row, the row is narrower than the character itself: the
        // pair takes the row whole rather than being parted for the sake of a margin it cannot fill.
        return (limit + 1).coerceAtMost(extent)
    }
    if (!splitsWord(line, limit)) return limit
    var candidate = limit - 1
    while (candidate > start) {
        if (!splitsWord(line, candidate) && !splitsCharacter(line, candidate)) return candidate
        candidate--
    }
    // Every break from here to the margin would land inside one token, so the token is wider than the
    // window: give it a row of its own, whole, and let the pan reach the rest of it.
    var end = limit + 1
    while (end < extent && (splitsWord(line, end) || splitsCharacter(line, end))) end++
    return end
}

/**
 * Whether a break before column [at] would land between the two cells of one wide character.
 *
 * The cells of a pair are written and cleared together (see `AnsiTerminalBuffer.putWide`), and a row
 * boundary is the one place left where they could be parted: the left half on one row and its own blank
 * on the next, with the glyph's ink drawn across the gap between them.
 */
private fun splitsCharacter(line: List<TerminalCell>, at: Int): Boolean =
    at in 1 until line.size && line[at - 1].wide

/**
 * Whether a break before column [at] would land inside a word.
 *
 * Deliberately the same notion of "word" that long-press-to-select uses - see
 * [TerminalSelection.isWordCharacter] - so a token that selects as one unit also wraps as one unit. A
 * user who long-presses a path and gets the whole path, then sees that path split across two rows,
 * has been told two different things about the same characters.
 */
private fun splitsWord(line: List<TerminalCell>, at: Int): Boolean =
    TerminalSelection.isWordCharacter(line[at - 1].value) && TerminalSelection.isWordCharacter(line[at].value)
