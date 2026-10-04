package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Where every row of a frame lands on screen, which is the whole of the word-wrap feature.
 *
 * A terminal on a phone fits somewhere around forty-five columns of legible monospace while the pty is
 * eighty wide, so more than a third of every long line used to be off-screen, reachable only by dragging
 * sideways. The fix is not the emulator's business - the buffer keeps hard-wrapping at the pty width, so
 * every existing coordinate keeps its meaning - it is this mapping, and being a pure function of a frame
 * and a width is what makes it checkable without a Canvas.
 *
 * Two properties matter more than any single case and are asserted repeatedly below: the rows of a line
 * are *contiguous*, so no column is drawn twice or lost, and a token is *never* split, because a hash or
 * a path broken across two rows reads as two different values and nothing on screen says otherwise.
 */
class TerminalLayoutTest {

    @Test
    fun `a line that fits is one row and nothing is wrapped`() {
        // A banner and a prompt with the cursor sitting after it, which is the screen a user sees the
        // moment a shell is ready. The prompt's row reaches column 2 because the cursor is drawn there:
        // the space after `$` is blank, and a blank is only worth a cell if something occupies it.
        val frame = frameOf(listOf("ready", "$ "), cursorRow = 1, cursorColumn = 2)
        val layout = terminalLayout(frame, width = 20, maxRows = 24)

        assertThat(layout.rows).containsExactly(
            TerminalVisualRow(line = 0, from = 0, to = 5),
            TerminalVisualRow(line = 1, from = 0, to = 3),
        ).inOrder()
        assertThat(layout.cursorRow).isEqualTo(1)
        assertThat(layout.cursorColumn).isEqualTo(2)
        // Nothing to pan to: this is what stops the view offering a drag into empty space.
        assertThat(layout.contentColumns).isEqualTo(5)
    }

    @Test
    fun `a long line breaks at a space and keeps the space on the row it ends`() {
        val layout = layoutOf(listOf("hello world"), width = 6)

        assertThat(layout.rows).containsExactly(
            TerminalVisualRow(0, 0, 6),
            TerminalVisualRow(0, 6, 11),
        ).inOrder()
        assertThat(textOf(layout, "hello world")).containsExactly("hello ", "world").inOrder()
    }

    @Test
    fun `a break that would land inside a word walks back to the space before it`() {
        val layout = layoutOf(listOf("hello worldly"), width = 8)

        // Not "hello wo" / "rldly": the margin falls inside `worldly`, so the row ends early.
        assertThat(textOf(layout, "hello worldly")).containsExactly("hello ", "worldly").inOrder()
        // And every row still fits the window, which is the point of walking back rather than forward.
        assertThat(layout.rows.map { it.width }.max()).isAtMost(8)
        assertThat(layout.contentColumns).isAtMost(8)
    }

    /**
     * The case the whole feature exists for: the things people actually print in a terminal.
     *
     * A path, a URL, a checksum, an IP address and a container id are single tokens by the same
     * definition long-press-to-select uses, and every one of them is meaningless once it has been cut in
     * half by a display that ran out of width.
     */
    @Test
    fun `a path a url and a hash are never broken`() {
        val samples = listOf(
            "/usr/local/share/doc/eclipse-ssh/README.md",
            "https://github.com/connectbot/connectbot/releases/latest",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "192.168.100.14:22022",
            "sha256:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        )
        samples.forEach { token ->
            val layout = layoutOf(listOf("out $token done"), width = 20)
            val rows = textOf(layout, "out $token done")
            // Whole, on a row of its own, exactly once.
            assertThat(rows.count { it.trim() == token }).isEqualTo(1)
            assertThat(rows.joinToString("")).isEqualTo("out $token done")
        }
    }

    @Test
    fun `a token wider than the window keeps its own row and stays pannable`() {
        val line = "x".repeat(30)
        val layout = layoutOf(listOf(line), width = 10)

        assertThat(layout.rows).containsExactly(TerminalVisualRow(0, 0, 30))
        // Wider than the window on purpose: the view pans to the rest rather than cutting the token.
        assertThat(layout.contentColumns).isEqualTo(30)
    }

    @Test
    fun `an over-wide token is followed by the rest of the line on later rows`() {
        val line = "${"a".repeat(24)} tail end"
        val layout = layoutOf(listOf(line), width = 10)

        assertThat(textOf(layout, line)).containsExactly("a".repeat(24), " tail end").inOrder()
    }

    @Test
    fun `the server's own spacing survives a wrap`() {
        // A `docker ps` style table: the columns are made of runs of spaces, and shifting them by one
        // would misalign every row against the ones above and below it.
        val line = "NAMES        STATUS         PORTS          IMAGE"
        val layout = layoutOf(listOf(line), width = 26)

        // The two remaining spaces of that run start the second row rather than being swallowed, so
        // every column is still where the server put it relative to the ones around it.
        assertThat(textOf(layout, line)).containsExactly(
            "NAMES        STATUS       ",
            "  PORTS          IMAGE",
        ).inOrder()
    }

    @Test
    fun `rows of a wrapped line are contiguous and cover every column exactly once`() {
        val line = "the quick brown fox jumps over the lazy dog and keeps running for a while yet"
        listOf(7, 12, 20, 33, 46).forEach { width ->
            val layout = layoutOf(listOf(line), width = width)
            var expected = 0
            layout.rows.forEach { row ->
                assertThat(row.from).isEqualTo(expected)
                expected = row.to
            }
            assertThat(expected).isEqualTo(line.length)
            assertThat(textOf(layout, line).joinToString("")).isEqualTo(line)
        }
    }

    /**
     * `htop`, `vim`, `nano` and `less` are the reason wrapping is not unconditional. `top` is the reason
     * one flag was not enough; that case is below.
     *
     * Every cell on the alternate screen is positional: the program drew its header on row 1 and its
     * status line on the last row because it was told how many rows and columns it had. Wrapping one row
     * into two pushes everything below it down by one, so the header moves, the status line lands in the
     * middle of the display, and a redraw of "row 12" repaints the wrong text. There the existing
     * horizontal pan is the right answer, and the frame says which mode it is in.
     */
    @Test
    fun `the alternate screen is never wrapped`() {
        val line = "  PID USER      PR  NI    VIRT    RES    SHR S  %CPU  %MEM     TIME+ COMMAND"
        val normal = layoutOf(listOf(line), width = 40)
        val alternate = layoutOf(listOf(line), width = 40, alternateScreen = true)

        assertThat(normal.rows.size).isGreaterThan(1)
        assertThat(alternate.rows).containsExactly(TerminalVisualRow(0, 0, line.length))
        // One row per line, so a full-screen program's rows stay where it put them, and the pan extent
        // is the whole line - which is how the rest of it is reached.
        assertThat(alternate.contentColumns).isEqualTo(line.length)
    }

    /**
     * The same protection for a screen painted in place, which is the only kind `top` draws.
     *
     * Its header row is the case: `top` was in the list above as an example of the alternate screen, and
     * probing the real binary through a real pty showed it never asks for one - it homes the cursor and
     * repaints the primary screen. With the shipped default of an eighty-column pty on a phone that
     * fits about forty-six, every row of it reflowed, so its summary rows moved down the display on
     * every refresh and a redraw of "row 12" landed somewhere else. The emulator marks such a screen
     * and this is where the mark is honoured.
     */
    @Test
    fun `a screen painted in place is never wrapped either`() {
        val line = "  PID USER      PR  NI    VIRT    RES    SHR S  %CPU  %MEM     TIME+ COMMAND"
        val painted = layoutOf(listOf(line), width = 40, positionalScreen = true)

        assertThat(painted.rows).containsExactly(TerminalVisualRow(0, 0, line.length))
        assertThat(painted.contentColumns).isEqualTo(line.length)
    }

    @Test
    fun `trailing blanks are not laid out`() {
        val frame = frameOf(listOf("hi" + " ".repeat(78)), columns = 80)
        val layout = terminalLayout(frame, width = 20, maxRows = 24)

        // Two columns, not eighty: a mostly empty 80-column line is one row and not four.
        assertThat(layout.rows).containsExactly(TerminalVisualRow(0, 0, 2))
    }

    @Test
    fun `a blank line still occupies a row`() {
        val layout = layoutOf(listOf("first", "", "third"), width = 20)

        assertThat(layout.rows).containsExactly(
            TerminalVisualRow(0, 0, 5),
            TerminalVisualRow(1, 0, 0),
            TerminalVisualRow(2, 0, 5),
        ).inOrder()
    }

    @Test
    fun `a coloured blank counts as painted so a status bar is not clipped`() {
        val line = MutableList(20) { TerminalCell(' ') }
        // How `less` and every shell prompt theme draw a bar: blanks with a background.
        for (index in 0 until 12) {
            line[index] = TerminalCell(' ', TerminalStyle(background = TerminalColor(0x00FF0000)))
        }
        val frame = cellFrameOf(cells = listOf(line), columns = 20)

        val layout = terminalLayout(frame, width = 20, maxRows = 24)

        assertThat(layout.rows).containsExactly(TerminalVisualRow(0, 0, 12))
    }

    @Test
    fun `the cursor is placed on the row holding the column it is in`() {
        val line = "the quick brown fox jumps over the lazy dog"
        // Typing at the end of a line that has already wrapped: the cursor is one past the text.
        val frame = frameOf(listOf(line), columns = 80, cursorRow = 0, cursorColumn = line.length)
        val layout = terminalLayout(frame, width = 20, maxRows = 24)

        val row = layout.rows[layout.cursorRow]
        assertThat(row.line).isEqualTo(0)
        assertThat(layout.cursorRow).isEqualTo(layout.rows.lastIndex)
        // Its column *within the row*, which is what the view draws and pans to.
        assertThat(layout.cursorColumn).isEqualTo(line.length - row.from)
        assertThat(layout.contentColumns).isAtLeast(layout.cursorColumn + 1)
    }

    @Test
    fun `an invisible cursor is not placed anywhere`() {
        val frame = frameOf(listOf("hi"), columns = 20, cursorRow = 0, cursorColumn = 2, cursorVisible = false)
        val layout = terminalLayout(frame, width = 20, maxRows = 24)

        assertThat(layout.cursorRow).isEqualTo(-1)
    }

    @Test
    fun `a cursor scrolled out of the window is not placed anywhere`() {
        // What the frame reports once the user scrolls back into the history: cursorRow is -1.
        val frame = frameOf(listOf("older output"), columns = 20, cursorRow = -1, cursorColumn = 4)
        val layout = terminalLayout(frame, width = 20, maxRows = 24)

        assertThat(layout.cursorRow).isEqualTo(-1)
        assertThat(layout.contentColumns).isEqualTo(12)
    }

    /**
     * A screen whose wrapped rows outnumber its real ones keeps the newest, because that is where the
     * cursor and the output are; the rest is one flick of the scrollback away.
     */
    @Test
    fun `wrapping past the height of the window drops the oldest rows`() {
        val lines = (1..6).map { "line $it ${"word".repeat(6)}" }
        val frame = frameOf(lines, columns = 80, cursorRow = 5, cursorColumn = 4)
        val unbounded = terminalLayout(frame, width = 20, maxRows = 100)
        val bounded = terminalLayout(frame, width = 20, maxRows = 6)

        assertThat(unbounded.rows.size).isGreaterThan(6)
        assertThat(bounded.rows).hasSize(6)
        // The tail of the full layout, so the bottom of the screen is the bottom of the output.
        assertThat(bounded.rows).isEqualTo(unbounded.rows.takeLast(6))
        // And the cursor came with it, re-numbered against the rows that are actually drawn: column 4
        // of the last line, which is on the first of that line's two rows.
        assertThat(bounded.cursorRow).isEqualTo(bounded.rows.indexOfFirst { it.line == 5 })
        assertThat(bounded.cursorColumn).isEqualTo(4)
    }

    @Test
    fun `an empty frame and a zero width are handled without wrapping anything`() {
        assertThat(terminalLayout(frameOf(emptyList()), width = 40, maxRows = 24).rows).isEmpty()
        assertThat(terminalLayout(frameOf(listOf("hi")), width = 40, maxRows = 0).rows).isEmpty()
        // Width 0 arrives from a window that has not been measured yet; laying out one row per line is
        // the answer that draws something rather than dividing by nothing.
        val unmeasured = terminalLayout(frameOf(listOf("hello world")), width = 0, maxRows = 24)
        assertThat(unmeasured.rows).containsExactly(TerminalVisualRow(0, 0, 11))
    }

    @Test
    fun `wrapping agrees with selection about where a word ends`() {
        // The characters that make `wordAt` return a whole path are the characters that keep it whole
        // here; if the two ever disagree, a long press and the wrap tell the user different things.
        val token = "/etc/nginx/sites-enabled/default.conf"
        val line = "cat $token"
        val layout = layoutOf(listOf(line), width = 16)
        val whole = TerminalSelection.wordAt(0, 8, line)

        assertThat(whole).isNotNull()
        val selected = line.substring(whole!!.startColumn, whole.endColumn + 1)
        assertThat(selected).isEqualTo(token)
        assertThat(textOf(layout, line)).contains(token)
    }

    // --- Wide characters at a row boundary. A pair of cells is one character, and a row boundary is
    // the last place the two could be parted: the glyph is drawn from its left column across both, so
    // half on each of two rows would hang ink off the end of one of them.

    @Test
    fun `a margin that falls inside a wide character moves it to the next row whole`() {
        val cells = wideCellsOf("abc中def", "中")
        val layout = terminalLayout(cellFrameOf(listOf(cells), cells.size), width = 4, maxRows = 24)

        // Not `abc中` / ` def`: the row ends one column early so that 中 keeps both of its columns.
        assertThat(layout.rows).containsExactly(
            TerminalVisualRow(line = 0, from = 0, to = 3),
            TerminalVisualRow(line = 0, from = 3, to = 5),
            TerminalVisualRow(line = 0, from = 5, to = 8),
        ).inOrder()
    }

    @Test
    fun `no row boundary ever falls between a wide character's two cells`() {
        val cells = wideCellsOf("ab中文天ef", "中文天")
        val frame = cellFrameOf(listOf(cells), cells.size)

        // Every width the view can have, because the boundary is what moves and the width is what the
        // user's window decides. The assertion is on the cells rather than on the text: a row that ends
        // on a wide base has left that character's second column to the row below.
        for (width in 1..cells.size) {
            val layout = terminalLayout(frame, width = width, maxRows = 24)
            for (row in layout.rows) {
                assertWithMessage("width $width, row $row")
                    .that(cells[row.to - 1].wide).isFalse()
            }
            // And the rows are still contiguous, so no column was dropped to achieve it.
            assertWithMessage("width $width").that(layout.rows.first().from).isEqualTo(0)
            assertWithMessage("width $width").that(layout.rows.last().to).isEqualTo(cells.size)
            layout.rows.zipWithNext { left, right ->
                assertWithMessage("width $width").that(right.from).isEqualTo(left.to)
            }
        }
    }

    @Test
    fun `the second column of a wide character is painted so the pan can reach it`() {
        val cells = wideCellsOf("ab中", "中")

        // Three cells of text, four columns of ink: stopping at the base would leave the half of the
        // glyph that hangs over the fourth column unpannable, and so unreadable at the window's edge.
        assertThat(terminalPaintedWidth(cells)).isEqualTo(4)
        assertThat(terminalLayout(cellFrameOf(listOf(cells), cells.size), width = 4, maxRows = 24).contentColumns)
            .isEqualTo(4)
    }

    // ---- the memo key ------------------------------------------------------------------------------

    @Test
    fun `every input the layout is a function of is in the key`() {
        // The view memoizes [terminalLayout], and the key it memoizes on has to be the inputs and not a
        // proxy for them: a key that is nearly right serves the layout of one frame for another one, and
        // the new frame's lines are then drawn through the old frame's row geometry - rows that no longer
        // hold that text still showing it. One case per field, so dropping one is a failing test.
        val frame = frameOf(listOf("ready", "$ "), cursorRow = 1, cursorColumn = 2)
        val key = layoutKey(frame, columns = 20, rows = 24)

        assertThat(layoutKey(frame, columns = 20, rows = 24)).isEqualTo(key)
        assertThat(layoutKey(frame, columns = 21, rows = 24)).isNotEqualTo(key) // the window widened
        assertThat(layoutKey(frame, columns = 20, rows = 23)).isNotEqualTo(key) // the screen lost a row
        assertThat(layoutKey(frame.copy(revision = 2), columns = 20, rows = 24)).isNotEqualTo(key)
        assertThat(layoutKey(frame.copy(firstLine = 1), columns = 20, rows = 24)).isNotEqualTo(key)
        assertThat(layoutKey(frame.copy(lines = frame.lines + listOf(listOf(TerminalCell('x')))), 20, 24))
            .isNotEqualTo(key)
        assertThat(layoutKey(frame.copy(cursorRow = 0), columns = 20, rows = 24)).isNotEqualTo(key)
        assertThat(layoutKey(frame.copy(cursorColumn = 3), columns = 20, rows = 24)).isNotEqualTo(key)
        assertThat(layoutKey(frame.copy(cursorVisible = false), columns = 20, rows = 24)).isNotEqualTo(key)
        assertThat(layoutKey(frame.copy(alternateScreen = true), columns = 20, rows = 24)).isNotEqualTo(key)
        assertThat(layoutKey(frame.copy(positionalScreen = true), columns = 20, rows = 24)).isNotEqualTo(key)
    }

    @Test
    fun `the two screen flags are in the key because they change the layout`() {
        // Not every field is a change worth memoizing on, and these two are the ones that are: the same
        // frame and the same width, wrapped on the flowing screen and left over-wide on the painted one.
        val prose = "hello world and some more words"
        val flowing = frameOf(listOf(prose), cursorRow = 0, cursorColumn = 0)
        val painted = frameOf(listOf(prose), cursorRow = 0, cursorColumn = 0, alternateScreen = true)

        assertThat(layoutKey(flowing, columns = 8, rows = 24))
            .isNotEqualTo(layoutKey(painted, columns = 8, rows = 24))
        assertThat(terminalLayout(flowing, width = 8, maxRows = 24).rows.size)
            .isGreaterThan(terminalLayout(painted, width = 8, maxRows = 24).rows.size)
    }

    @Test
    fun `a scroll the window clamps away produces the same key, so the memo holds`() {
        // The window is anchored on the cursor and fitted to the screen in wrapped rows, so the offset the
        // view records is clamped by the buffer: a scroll that runs past the top describes the window it
        // is already on, and the key of that window has to be the key it already had. Differing here would
        // lay the frame out again on every gesture frame and redraw a screen that had not changed.
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 6)
        buffer.feed((1..40).joinToString("\r\n") { "line $it" } + "\r\n")
        val ceiling = buffer.maxScrollOffset(viewportRows = 6, windowColumns = 20)
        val atTop = buffer.frame(scrollOffset = ceiling, viewportRows = 6, windowColumns = 20)
        val past = buffer.frame(scrollOffset = ceiling + 5, viewportRows = 6, windowColumns = 20)

        assertThat(past).isEqualTo(atTop)
        assertThat(layoutKey(past, columns = 20, rows = 6)).isEqualTo(layoutKey(atTop, columns = 20, rows = 6))
    }
}

private fun layoutOf(
    lines: List<String>,
    width: Int,
    alternateScreen: Boolean = false,
    positionalScreen: Boolean = false,
): TerminalLayout = terminalLayout(
    frameOf(lines, alternateScreen = alternateScreen, positionalScreen = positionalScreen),
    width = width,
    maxRows = 24,
)

/** The text of each visual row, which is what a reader would see. */
private fun textOf(layout: TerminalLayout, line: String): List<String> =
    layout.rows.map { line.substring(it.from, it.to.coerceAtMost(line.length)) }

/**
 * The same line as cells, with each character named in [wide] written as the pair the buffer keeps: its
 * own cell marked, and the blank cell that is its right half.
 */
private fun wideCellsOf(text: String, wide: String): List<TerminalCell> =
    text.flatMap { char ->
        if (char in wide) {
            listOf(TerminalCell(char, wide = true), TerminalCell(' '))
        } else {
            listOf(TerminalCell(char))
        }
    }

private fun frameOf(
    lines: List<String>,
    columns: Int = 200,
    cursorRow: Int = -1,
    cursorColumn: Int = 0,
    cursorVisible: Boolean = true,
    alternateScreen: Boolean = false,
    positionalScreen: Boolean = false,
): TerminalFrame = cellFrameOf(
    cells = lines.map { text -> (text + " ".repeat((columns - text.length).coerceAtLeast(0))).map { TerminalCell(it) } },
    columns = columns,
    cursorRow = cursorRow,
    cursorColumn = cursorColumn,
    cursorVisible = cursorVisible,
    alternateScreen = alternateScreen,
    positionalScreen = positionalScreen,
)

/**
 * The same, for the one test that needs styled cells rather than plain text.
 *
 * Named apart from [frameOf] rather than overloading it: `List<String>` and `List<List<TerminalCell>>`
 * erase to the same JVM signature, so the two would be a platform declaration clash.
 */
private fun cellFrameOf(
    cells: List<List<TerminalCell>>,
    columns: Int,
    cursorRow: Int = -1,
    cursorColumn: Int = 0,
    cursorVisible: Boolean = true,
    alternateScreen: Boolean = false,
    positionalScreen: Boolean = false,
): TerminalFrame = TerminalFrame(
    lines = cells,
    firstLine = 0,
    totalLines = cells.size,
    cursorRow = cursorRow,
    cursorColumn = cursorColumn,
    columns = columns,
    rows = cells.size,
    contentColumns = cells.maxOfOrNull { terminalPaintedWidth(it) } ?: 0,
    cursorVisible = cursorVisible,
    revision = 1,
    alternateScreen = alternateScreen,
    positionalScreen = positionalScreen,
)
