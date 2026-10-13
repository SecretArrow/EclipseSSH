package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The screen the reader sees after scrolling, produced by the real pipeline rather than by a mock of it.
 *
 * This class exists because of a bug that only appears when three layers disagree. The buffer hard-wraps
 * at the *pty* width, the view re-wraps those lines at the *window* width, and the buffer's window used
 * to be a fixed number of buffer *lines*, chosen without knowing the second width. At 80 columns on a
 * phone fitting 44, one line of output is two screen rows, so a window of seventeen lines was eighteen
 * or more rows - and [terminalLayout] dropped the overflow off the top. The screen's first row was then
 * the *middle* of a wrapped line: a word fragment at column 0, changing shape with every scroll, with
 * the beginning of the line - and of the command being typed into it - off the top of the screen.
 *
 * So every test here asserts on the *image*, through [imageOf], which is [AnsiTerminalBuffer.frame] and
 * [terminalLayout] exactly as the view calls them and nothing else. A test that asked the buffer what
 * its window was could not have caught this: the buffer's answer was self-consistent and the screen was
 * still wrong. The invariant that catches it is [assertNoRowStartsMidLine], and almost every test below
 * applies it to every screen it produces.
 */
class TerminalScrollViewportTest {

    @Test
    fun `a session that has not been scrolled draws its bottom rows and nothing else`() {
        val buffer = session()
        feedLines(buffer)

        val image = imageOf(buffer, offset = 0)

        // The end of the output and the prompt under it, which is the live state of any terminal: the
        // window is anchored on the cursor, so the newest rows are the ones on screen.
        assertThat(image).hasSize(WINDOW_ROWS)
        assertThat(image.last()).isEqualTo("$")
        assertThat(image.joinToString("\n")).contains("output line 39")
        assertThat(image.joinToString("\n")).doesNotContain("output line 30")
        assertNoRowStartsMidLine(buffer, offset = 0)
    }

    @Test
    fun `one page up shows the line above the live bottom`() {
        val buffer = session()
        feedLines(buffer)
        val live = imageOf(buffer, offset = 0)

        val scrolled = imageOf(buffer, offset = 1)

        // One page up is one *line* up, whatever that line wraps to, so the window's end moved by one
        // line and the screen is that window's rows and no others.
        assertThat(scrolled).isNotEqualTo(live)
        assertThat(windowEnd(buffer, 1)).isEqualTo(windowEnd(buffer, 0) - 1)
        assertNoRowStartsMidLine(buffer, offset = 1)
    }

    @Test
    fun `the first page up gives up the blank line under the prompt`() {
        val buffer = session()
        feedLines(buffer, lines = 60)

        val live = imageOf(buffer, offset = 0)
        val once = imageOf(buffer, offset = 1)

        // The window moves by one line, and the line it gives up is the prompt's - the last row of the
        // live screen. The output above it does not move at all: one page up from the bottom is one
        // line, and the row the prompt leaves is the screen's own background rather than a row of
        // something else. (The row budget then cannot hold another whole line of output in its place,
        // so the next step is the one that brings an older line onto the screen.)
        assertThat(live.last()).isEqualTo("$")
        assertThat(once.dropLast(1)).isEqualTo(live.dropLast(1))
        assertThat(once.last()).isEmpty()
        assertThat(windowEnd(buffer, 1)).isEqualTo(windowEnd(buffer, 0) - 1)
        assertThat(imageOf(buffer, offset = 2).first()).isEqualTo("output line 51 with some words on it here")
        assertNoRowStartsMidLine(buffer, offset = 1)
    }

    @Test
    fun `many pages up walk back through the scrollback one line at a time`() {
        val buffer = session()
        feedLines(buffer, lines = 60, tail = "")
        val ceiling = buffer.maxScrollOffset(WINDOW_ROWS, WINDOW_COLUMNS)

        var previous = imageOf(buffer, offset = 8)
        for (offset in 9..20) {
            val image = imageOf(buffer, offset = offset)
            assertThat(image).isNotEqualTo(previous)
            // Every step moves the window by exactly one line, and its content is entirely lines the
            // screen before it was already showing: the bottom line has left and, at most, one older
            // line has come in at the top to fill the row it freed. Nothing is invented and nothing
            // that was on the screen is anywhere but where it was.
            assertThat(windowEnd(buffer, offset)).isEqualTo(windowEnd(buffer, offset - 1) - 1)
            assertThat(windowStart(buffer, offset)).isAtMost(windowStart(buffer, offset - 1))
            assertNoRowStartsMidLine(buffer, offset = offset)
            previous = image
        }
        assertThat(ceiling).isAtLeast(20)
    }

    @Test
    fun `many pages down come back to the live bottom`() {
        val buffer = session()
        feedLines(buffer, lines = 60)
        val live = imageOf(buffer, offset = 0)

        for (offset in 8 downTo 1) imageOf(buffer, offset = offset)
        val back = imageOf(buffer, offset = 0)

        assertThat(back).isEqualTo(live)
        assertThat(back.last()).isEqualTo("$")
    }

    @Test
    fun `paging up and down again lands on the same screen`() {
        val buffer = session()
        feedLines(buffer, lines = 60)
        val start = imageOf(buffer, offset = 0)
        val once = imageOf(buffer, offset = 6)

        repeat(3) {
            assertThat(imageOf(buffer, offset = 6)).isEqualTo(once)
            assertThat(imageOf(buffer, offset = 0)).isEqualTo(start)
        }
        // A round trip that leaves the buffer different is not a round trip, so the offset is the only
        // thing that may have moved.
        assertThat(imageOf(buffer, offset = 1)).isNotEqualTo(start)
    }

    @Test
    fun `scrolling to the very top fills the screen from the first line the terminal ever wrote`() {
        val buffer = session()
        feedLines(buffer, lines = 60, tail = "")
        val ceiling = buffer.maxScrollOffset(WINDOW_ROWS, WINDOW_COLUMNS)

        val image = imageOf(buffer, offset = ceiling)

        // The top of the scrollback is a *line* boundary, not wherever the row arithmetic ran out: the
        // first line the terminal wrote starts the screen and the screen is full of the oldest output.
        assertThat(image.first()).startsWith("output line 0")
        assertThat(buffer.frame(ceiling, WINDOW_ROWS, WINDOW_COLUMNS).firstLine).isEqualTo(0)
        // Every row the lines filled is content; the row or two the line-fit could not fill stays the
        // screen's own background, because filling it with half a line is the fragment this prevents.
        assertThat(image.dropLastWhile { it.isBlank() }.all { it.isNotBlank() }).isTrue()
        assertNoRowStartsMidLine(buffer, offset = ceiling)
        // And the ceiling is the frame's own ceiling, rather than a larger number the frame clamps
        // away - which would be a scroll that stops responding at the point the reader wants most.
        assertThat(imageOf(buffer, offset = ceiling + 5)).isEqualTo(image)
    }

    @Test
    fun `a viewport taller than the output keeps the prompt where the shell put it`() {
        val buffer = session()
        buffer.feed("ready\r\n$ ")

        val image = imageOf(buffer, offset = 0)

        // The grid is sixty rows tall and holds two lines of output, and this is the screen a terminal
        // shows for that: the shell's two lines where it wrote them and blank rows under them. That is
        // the normal terminal model, and a fitted window has to agree with it rather than centring the
        // text in the screen or pushing the prompt to the bottom.
        assertThat(image.first()).isEqualTo("ready")
        assertThat(image[1]).isEqualTo("$")
        assertThat(image.drop(2).all { it.isEmpty() }).isTrue()
        assertThat(buffer.maxScrollOffset(WINDOW_ROWS, WINDOW_COLUMNS)).isEqualTo(0)
    }

    @Test
    fun `a typed line longer than the window stays whole on the screen`() {
        val buffer = session(columns = 48)
        buffer.feed("user@host:~$ ")
        buffer.feed("echo 1234567890 2234567890 3234567890 4234567890 5234567890")

        val image = imageOf(buffer, offset = 0)
        val layout = terminalLayout(buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS), WINDOW_COLUMNS, WINDOW_ROWS)

        // The command is 72 columns long and the pty wrapped it at 48, so it is two buffer lines and
        // three screen rows. All three are on the screen, starting at its first row: the beginning of
        // the command the user is typing is visible, which is the whole point of following the cursor.
        assertThat(image).hasSize(WINDOW_ROWS)
        assertThat(image[0]).startsWith("user@host:~$ echo 1234567890")
        assertThat(image[1]).isEqualTo("32345678")
        assertThat(image[2]).startsWith("90 4234567890")
        assertThat(layout.cursorRow).isEqualTo(2)
        assertThat(layout.rows[layout.cursorRow].line).isEqualTo(1)
        assertThat(image.drop(3).all { it.isEmpty() }).isTrue()
        assertNoRowStartsMidLine(buffer, offset = 0)
    }

    @Test
    fun `a line wider than the screen starts at the top of it and ends on the cursor's row`() {
        val buffer = session(columns = TERMINAL_COLUMN_RANGE.last)
        // Words rather than one long run: a single unbreakable token is deliberately *not* wrapped - it
        // gets an over-wide row of its own to be panned, which `TerminalLayoutTest` covers - so a line
        // that fills ten rows is a line with places to break in it.
        buffer.feed("word ".repeat(79) + "word")
        val wide = "word ".repeat(79) + "word"

        val frame = buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS)
        val layout = terminalLayout(frame, WINDOW_COLUMNS, WINDOW_ROWS)

        // Nearly the widest line a pty can be given, on a forty-four column screen. Every row of it fits
        // the window and the screen shows the line from its first row: the row above a line's first row
        // is blank or nothing at all, and the fit would rather start there than cut the line's head off.
        assertThat(layout.rows.size).isAtMost(WINDOW_ROWS)
        assertThat(layout.rows.first()).isEqualTo(TerminalVisualRow(line = 0, from = 0, to = WINDOW_COLUMNS))
        // Contiguous, so every column of the line is drawn exactly once and none is drawn twice: the
        // rows of a line tile it, and the last of them ends where the cursor is.
        val rows = layout.rows.takeWhile { it.line == 0 }
        assertThat(rows.zipWithNext().all { (above, below) -> above.to == below.from }).isTrue()
        assertThat(rows.last().to).isEqualTo(frame.lines[0].size)
        assertThat(layout.rows.all { it.width <= WINDOW_COLUMNS }).isTrue()
        assertThat(wide.length).isAtLeast(TERMINAL_COLUMN_RANGE.last - 1)
        assertThat(layout.rows[layout.cursorRow].line).isEqualTo(frame.cursorRow)
        assertThat(layout.cursorColumn).isLessThan(WINDOW_COLUMNS)
        assertNoRowStartsMidLine(buffer, offset = 0)
    }

    @Test
    fun `a single line taller than the whole screen shows its end where the cursor is`() {
        // The one case a fit cannot help with: two rows of screen cannot hold three rows of one line, so
        // the part that is shown has to be the part the cursor is on - the end - rather than the
        // beginning the reader has already read past. The top row is then a continuation, deliberately.
        val line = ("word ".repeat(20)).trimEnd().map { TerminalCell(it) }
        val layout = terminalLayout(lineFrame(line, cursorColumn = line.size), WINDOW_COLUMNS, maxRows = 2)

        assertThat(layout.rows).hasSize(2)
        assertThat(layout.rows.first().from).isNotEqualTo(0)
        assertThat(layout.rows.last().to).isEqualTo(line.size)
        assertThat(layout.cursorRow).isEqualTo(1)
        assertThat(layout.cursorColumn).isEqualTo(line.size - layout.rows.last().from)
    }

    @Test
    fun `the layout never returns more rows than the screen has`() {
        val buffer = session(columns = 300)
        buffer.feed(("a".repeat(299) + "\r\n").repeat(30))

        val frame = buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS)
        val layout = terminalLayout(frame, WINDOW_COLUMNS, WINDOW_ROWS)

        // The view stops drawing at the screen's last row, so rows past it are content the reader is
        // never shown. Fitting the window means the layout never has any to drop.
        assertThat(layout.rows.size).isAtMost(WINDOW_ROWS)
    }

    @Test
    fun `the window is fitted in wrapped rows at a pty of 48 columns and a screen of 44`() {
        val buffer = session(columns = 48)
        feedLines(buffer)

        val frame = buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS)
        val layout = terminalLayout(frame, WINDOW_COLUMNS, WINDOW_ROWS)

        // The number the old code could not see. The window is *not* seventeen lines, because at 44
        // columns the lines the pty wrapped at 48 take two rows each; what it is fitted to is seventeen
        // rows, which is ten lines of this output and a row of the prompt.
        assertThat(layout.rows).hasSize(WINDOW_ROWS)
        assertThat(frame.lines.size).isLessThan(WINDOW_ROWS)
        assertThat(frame.lines.size).isAtLeast(WINDOW_ROWS / 4)
        assertNoRowStartsMidLine(buffer, offset = 0)
    }

    @Test
    fun `the window is the full height when the screen is as wide as the pty`() {
        val buffer = session(columns = 48)
        feedLines(buffer)

        val frame = buffer.frame(0, WINDOW_ROWS, windowColumns = 48)
        val layout = terminalLayout(frame, 48, WINDOW_ROWS)

        // Nothing to fit: with no re-wrapping a row is a line, and the reader gets every row of the
        // screen. The fit costs nothing where it has nothing to do.
        assertThat(frame.lines.size).isEqualTo(WINDOW_ROWS)
        assertThat(layout.rows).hasSize(WINDOW_ROWS)
    }

    @Test
    fun `a window with no width reported behaves exactly as it did before`() {
        val buffer = session()
        feedLines(buffer)

        // Zero is "not wrapped for a window", which is what a snapshot, the export renderer and every
        // caller that drives the buffer without a view pass. The window is the full height, in lines.
        val frame = buffer.frame(0, WINDOW_ROWS)

        assertThat(frame.lines.size).isEqualTo(WINDOW_ROWS)
        assertThat(buffer.maxScrollOffset(WINDOW_ROWS)).isEqualTo(buffer.maxScrollOffset(WINDOW_ROWS, 0))
        assertThat(buffer.maxScrollOffset(WINDOW_ROWS)).isEqualTo(buffer.maxScrollOffset(WINDOW_ROWS, -1))
    }

    @Test
    fun `a grid of sixty rows still shows a window of the screen, not of the grid`() {
        val buffer = session(columns = 48, rows = 60)
        feedLines(buffer, lines = 40)

        val images = (0..4).map { imageOf(buffer, offset = it) }

        // Every screen is the height of the window the reader has, never the grid's height: the tall
        // grid is what makes `ls` print a thousand entries without paging, and it is not a screen.
        images.forEach { assertThat(it).hasSize(WINDOW_ROWS) }
        images.zipWithNext().forEach { (older, newer) -> assertThat(newer).isNotEqualTo(older) }
        (0..4).forEach { assertNoRowStartsMidLine(buffer, offset = it) }
    }

    @Test
    fun `a style survives the scroll that carries its line onto the screen`() {
        val buffer = session()
        buffer.feed("\u001b[1;31mred and bold\u001b[0m\r\n")
        feedLines(buffer, lines = 30)

        val scrolled = buffer.frame(30, WINDOW_ROWS, WINDOW_COLUMNS)
        val styled = scrolled.lines.first { line -> line.any { it.style.bold } }

        // The window is a copy of the cells, style included, so a line does not lose its colour by
        // being scrolled to. The red is the SGR 31 every terminal means by red, not the default the
        // rest of the screen is drawn in.
        assertThat(styled.take(12).joinToString("") { it.value.toString() }).isEqualTo("red and bold")
        assertThat(styled[0].style.bold).isTrue()
        assertThat(styled[0].style.foreground.isDefault).isFalse()
        // And the colour is the line's own: the plain line under it is still the default one.
        val plain = scrolled.lines.first { line -> line.any { it.value == 'p' } }
        assertThat(plain.first { it.value == 'p' }.style.foreground.isDefault).isTrue()
        assertThat(plain.first { it.value == 'p' }.style.bold).isFalse()
        assertThat(imageOf(buffer, offset = 30).joinToString("\n")).contains("red and bold")
    }

    @Test
    fun `the cursor returns to the line it is typing on after the scrollback is left`() {
        val buffer = session()
        feedLines(buffer, lines = 30)
        buffer.feed("half a command")
        val typed = "$ half a command"

        val live = layoutOf(buffer, offset = 0)
        imageOf(buffer, offset = 12)
        val back = layoutOf(buffer, offset = 0)

        // The cursor is on the screen, on the row of the text it is typing into, one column past the
        // last character - and leaving the scrollback puts it exactly where it was.
        assertThat(back.cursorRow).isEqualTo(live.cursorRow)
        assertThat(back.rows[back.cursorRow].line).isEqualTo(buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS).cursorRow)
        assertThat(back.cursorColumn).isEqualTo(typed.length)
        assertThat(imageOf(buffer, offset = 0).last()).isEqualTo(typed)
    }

    @Test
    fun `resizing the grid after scrolling keeps the window on the screen`() {
        val buffer = session()
        feedLines(buffer, lines = 60)
        imageOf(buffer, offset = 10)

        buffer.resize(48, 60)
        val afterNarrow = imageOf(buffer, offset = 10)
        buffer.resize(120, 60)
        val afterWide = imageOf(buffer, offset = 10)
        buffer.resize(80, 200)
        val afterTall = imageOf(buffer, offset = 10)
        buffer.resize(80, 40)
        val afterShort = imageOf(buffer, offset = 10)

        // What a resize must not do is break the window: it is still the height of the screen rather
        // than of the grid, no row starts mid-line, and the start and end of the window still add up.
        // The buffer does not re-flow its lines on a width change - the pty is told the new width and the
        // far end re-wraps - so the text is the same one, and the screen showing it is the same screen.
        listOf(afterNarrow, afterWide, afterTall, afterShort).forEach { assertThat(it).hasSize(WINDOW_ROWS) }
        assertThat(afterWide).isEqualTo(afterNarrow)
        assertThat(afterTall).isEqualTo(afterWide)
        // And the window is still anchored where it was: the last line of the live window is the line
        // the cursor is on, whatever the grid was resized to around it.
        assertThat(windowEnd(buffer, 0)).isEqualTo(buffer.cursorLine)
        val ceiling = buffer.maxScrollOffset(WINDOW_ROWS, WINDOW_COLUMNS)
        assertThat(imageOf(buffer, offset = ceiling + 10)).isEqualTo(imageOf(buffer, offset = ceiling))
        (0..10).forEach { assertNoRowStartsMidLine(buffer, offset = it) }
    }

    @Test
    fun `output arriving under a scrolled-back view leaves the reader on the line they were reading`() {
        val buffer = session()
        feedLines(buffer, lines = 60)
        val offset = 20
        val anchor = buffer.cursorLine
        val reading = imageOf(buffer, offset = offset)

        // The offset is measured from the cursor's line, so every line the shell prints moves the window
        // out from under the reader. [MainViewModel.pinScrollback] gives the anchor's movement back, and
        // this is the arithmetic it does: read the anchor before, feed, read it after.
        feedLines(buffer, from = 60, lines = 10, tail = "")
        val growth = buffer.cursorLine - anchor
        assertThat(growth).isEqualTo(10)

        val held = imageOf(buffer, offset = offset + growth)

        // The same screen, because the same lines are in the window: the new output went in below it and
        // nothing the reader was looking at moved. A view that had not compensated would show the ten
        // new lines and the text would have slid ten lines away from under them.
        assertThat(held).isEqualTo(reading)
        assertThat(imageOf(buffer, offset = offset)).isNotEqualTo(reading)
        assertNoRowStartsMidLine(buffer, offset = offset + growth)
    }

    @Test
    fun `scrolling never writes to the scrollback`() {
        val buffer = session()
        feedLines(buffer, lines = 80)
        val before = buffer.plainText()
        val lines = buffer.lineCount()
        val revision = buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS).revision

        val ceiling = buffer.maxScrollOffset(WINDOW_ROWS, WINDOW_COLUMNS)
        for (offset in 0..ceiling) imageOf(buffer, offset = offset)
        for (offset in ceiling downTo 0) imageOf(buffer, offset = offset)

        // Moves the window and nothing else: no line added, none removed, not one cell rewritten, and
        // no revision advanced. A scroll that reflowed or normalized the buffer would be a scroll that
        // destroyed history, and a revision bump per scroll would redraw a screen that had not changed.
        assertThat(buffer.lineCount()).isEqualTo(lines)
        assertThat(buffer.plainText()).isEqualTo(before)
        assertThat(buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS).revision).isEqualTo(revision)
    }

    @Test
    fun `nothing from the frame before the scroll is left on the screen`() {
        val buffer = session()
        feedLines(buffer, lines = 60)
        val ceiling = buffer.maxScrollOffset(WINDOW_ROWS, WINDOW_COLUMNS)

        // Every row of the screen is a row of the window and nothing else. The view paints the whole
        // grid from the frame it is handed and clears nothing, because there is nothing outside the
        // grid to clear - so a row that is neither would be a row left over from the frame before.
        for (offset in intArrayOf(0, 1, 7, ceiling / 2, ceiling - 1, ceiling)) {
            val frame = buffer.frame(offset, WINDOW_ROWS, WINDOW_COLUMNS)
            val layout = terminalLayout(frame, WINDOW_COLUMNS, WINDOW_ROWS)
            val painted = layout.rows.map { row ->
                val line = frame.lines[row.line]
                (row.from until row.to.coerceAtMost(line.size)).joinToString("") { line[it].value.toString() }.trimEnd()
            }
            val expected = painted + List((WINDOW_ROWS - painted.size).coerceAtLeast(0)) { "" }
            assertThat(imageOf(buffer, offset = offset)).isEqualTo(expected)
            assertThat(imageOf(buffer, offset = offset)).hasSize(WINDOW_ROWS)
        }
    }

    @Test
    fun `the layout and the window count the same rows`() {
        // [AnsiTerminalBuffer.frame] fits its window with [displayRowCount] and [terminalLayout] builds
        // the rows it is fitted to. Two implementations of one rule is one too many, so they are
        // asserted to agree over the shapes a screen actually meets: plain prose, one unbreakable token,
        // a word repeated to the margin, wide characters, a hash, and every width from one column to
        // wider than the line.
        val shapes = listOf(
            "ordinary shell output with words in it",
            "a".repeat(120),
            "/usr/local/share/a-long-path/with/segments ".repeat(4),
            "中文中文中文中文中文中文中文中文中文中文中文中文中文",
            "a中b文c中d文e中f文g中h文i中j文k中l文m中n文o中p",
            "24f3ab9c1d0e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8",
            "mixed 中文 and latin words that wrap somewhere in the middle of it all",
            "",
            " ",
            "  leading and trailing  ",
        )
        for (text in shapes) {
            val line = text.padEnd(160).take(160).map { TerminalCell(it) }
            for (width in 1..80) {
                val frame = singleLineFrame(line)
                val layout = terminalLayout(frame, width, maxRows = Int.MAX_VALUE)
                assertWithMessage("width=$width text=${text.take(24)}")
                    .that(layout.rows.size)
                    .isEqualTo(displayRowCount(line, terminalPaintedWidth(line), width))
            }
        }
    }

    @Test
    fun `a painted screen is never fitted for wrapping`() {
        val wide = "top - 12:04:01 up 3 days, 4:12, 1 user, load average: 0.04 0.10 0.09"
        val buffer = session(columns = 200)
        buffer.feed("$wide\r\n$wide\r\n$wide\r\n")
        buffer.feed("\u001b[?1049h")
        buffer.feed("$wide\r\n$wide\r\n")

        val frame = buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS)
        val layout = terminalLayout(frame, WINDOW_COLUMNS, WINDOW_ROWS)

        // A program that addresses its cells positionally draws to the width it was told, and re-wrapping
        // it shifts every row below the one that wrapped. A fitted window here would be a `vim` showing
        // its bottom ten rows and losing its top half, so the window is the full height and the rows are
        // as wide as the program drew them - reachable by panning, which is what that screen has always
        // done. `top` is the reason [TerminalFrame.positionalScreen] exists as well as this flag.
        assertThat(frame.alternateScreen).isTrue()
        assertThat(frame.lines.size).isEqualTo(WINDOW_ROWS)
        assertThat(layout.rows).hasSize(WINDOW_ROWS)
        assertThat(layout.rows.first().width).isEqualTo(wide.length)
    }

    @Test
    fun `a cursor parked above its own output still shows the newest output`() {
        val buffer = session()
        feedLines(buffer, lines = 60)
        // The tell of every program that paints its own screen: it names a row *above* where its output
        // had got to and leaves the cursor there, with its status line drawn below it. `top` does it on
        // the primary screen and an Ink TUI does it on every frame. Anchoring the window on the cursor
        // alone put that status line, and everything else under the cursor, past the bottom of the
        // window - measured on a real [CC] session at 52x60 with a 30-row viewport, 52 of 55 reads.
        buffer.feed("\u001B[30;1H")

        val frame = buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS)

        assertThat(frame.firstLine + frame.lines.size).isEqualTo(buffer.lineCount())
        assertThat(imageOf(buffer, offset = 0).joinToString("\n")).contains("$")
        assertNoRowStartsMidLine(buffer, offset = 0)
    }

    @Test
    fun `the window ends on the newest output for every row a program may park its cursor on`() {
        // The structural form of the rule, over every row rather than only the one the bug was seen on:
        // the screen has to contain the last thing the program drew, whatever the cursor is doing. It is
        // also the test that says the anchor, the ceiling and the reported anchor line stay one number.
        for (row in 1..60) {
            val buffer = session()
            feedLines(buffer, lines = 60)
            buffer.feed("\u001B[${row};1H")

            val frame = buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS)

            assertWithMessage("cursor parked on screen row $row")
                .that(frame.firstLine + frame.lines.size)
                .isEqualTo(buffer.lineCount())
        }
    }

    @Test
    fun `a screen that has scrolled ends its window at the end of the buffer, blank tail and all`() {
        val buffer = session()
        feedLines(buffer, lines = 60)
        // The state the divergence was measured in: the screen has scrolled, so the buffer is taller than
        // the pty, and the program now clears it and homes its cursor. Every row the ink had reached is
        // blank, and the window still has to end where the live output does - at the end of the buffer -
        // rather than at the last line that paints.
        buffer.feed("\u001b[2J\u001b[H")

        val frame = buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS)

        assertThat(buffer.lineCount()).isGreaterThan(buffer.viewportRows)
        assertThat(frame.firstLine + frame.lines.size).isEqualTo(buffer.lineCount())
        assertThat(frame.liveEnd).isEqualTo(buffer.lineCount())
        // And what the reader sees is the cleared screen. An ink floor puts the session's oldest output
        // back on the screen here, one row lower than the program drew it, which is the shape of the
        // reported fragments and the whole of the divergence `reproductions/cursor-moves.py` exposes.
        assertThat(imageOf(buffer, offset = 0).all { it.isEmpty() }).isTrue()
    }

    @Test
    fun `the live window ends where the live output does at every length of output`() {
        // The anchor's two regimes, walked through the line where they change hands rather than asserted
        // at one point on either side of it. The buffer is sized to the pty and filled with blank lines
        // from the start, so until the screen scrolls its tail is rows the shell has not written and the
        // window ends at the ink; from the first scroll on, the tail *is* the screen and the window ends
        // at the buffer, whatever those rows happen to hold.
        var sawScrolled = false
        var sawUnscrolled = false
        for (lines in 0..80) {
            val buffer = session()
            feedLines(buffer, lines = lines)

            val frame = buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS)
            val end = frame.firstLine + frame.lines.size
            val note = "$lines lines of output"

            if (buffer.lineCount() > buffer.viewportRows) {
                sawScrolled = true
                assertWithMessage(note).that(end).isEqualTo(buffer.lineCount())
            } else {
                sawUnscrolled = true
                assertWithMessage(note).that(end).isAtMost(buffer.lineCount())
            }
            // Either way the newest output is one of the rows drawn, which is the guarantee the tall pty
            // is allowed to have in place of a shrinking one.
            assertWithMessage(note)
                .that(frame.lines.any { line -> line.any { it != TerminalCell() } })
                .isTrue()
        }
        // Both regimes really were walked, so neither half of the rule can pass by never being asked.
        assertThat(sawScrolled).isTrue()
        assertThat(sawUnscrolled).isTrue()
    }

    @Test
    fun `the newest output is on screen at every width the window may be fitted to`() {
        // The guarantee the tall terminal is allowed to have instead of a shrinking pty: whatever the
        // window's width, the bottommost inked row of the pane is one of the rows drawn. Measured over
        // widths rather than at one of them because the width is what decides how many rows the window
        // takes: a window that fits at 52 columns is more rows than the screen at 20, and the row that
        // falls off the top is the one this rule protects.
        for (width in intArrayOf(1, 8, 20, 33, 44, 52, 80, 200)) {
            val buffer = session()
            feedLines(buffer, lines = 40)

            val frame = buffer.frame(0, WINDOW_ROWS, width)
            val layout = terminalLayout(frame, width, WINDOW_ROWS)
            val full = buffer.frame(0, buffer.lineCount(), 0)
            val inked = full.firstLine + full.lines.indexOfLast { line -> line.any { it != TerminalCell() } }

            assertWithMessage("width $width")
                .that(layout.rows.map { frame.firstLine + it.line })
                .contains(inked)
        }
    }

    @Test
    fun `a cut that moves to a line boundary never takes the cursor off the screen`() {
        // [fittedRows] has no case for the cursor because it needs none - moving the cut down to a line
        // boundary stops at or above the first row of the cursor's own line - and this is that claim
        // asserted rather than argued, over every screen height. The comparison is against where the
        // plain row-wise cut would have put the cursor: wherever that kept it, this must too.
        val buffer = session()
        feedLines(buffer)
        val frame = buffer.frame(0, WINDOW_ROWS, windowColumns = 0)
        val whole = terminalLayout(frame, WINDOW_COLUMNS, Int.MAX_VALUE)
        assertThat(whole.cursorRow).isAtLeast(0)

        for (screen in 1..WINDOW_ROWS) {
            val layout = terminalLayout(frame, WINDOW_COLUMNS, screen)
            val plain = whole.rows.size - screen

            assertWithMessage("a screen of $screen rows")
                .that(layout.cursorRow >= 0 || whole.cursorRow < plain)
                .isTrue()
            if (layout.cursorRow >= 0) {
                assertThat(layout.rows[layout.cursorRow].line).isEqualTo(frame.cursorRow)
            }
        }
    }

    @Test
    fun `a window the view has to cut never starts the screen in the middle of a line`() {
        // The state a session is in before its view has reported a size: `visibleColumnsOf` is zero until
        // the first measurement, so the window is built in *lines* while the view lays it out at the
        // width it actually has - and at 44 columns a line of the pty's 80 is two rows, so the frame is
        // more rows than the screen and [fittedRows] has to drop some. Dropping them one *row* at a time
        // from the top left the screen's first row the middle of a line, which reads at the left edge as
        // text starting in the middle of a word: the reported one-character fragment.
        //
        // Every screen height, rather than the one the phone happens to have: whether the cut lands on a
        // line boundary is a matter of parity, so a single case passes or fails by luck.
        val buffer = session()
        feedLines(buffer)
        val frame = buffer.frame(0, WINDOW_ROWS, windowColumns = 0)
        val whole = terminalLayout(frame, WINDOW_COLUMNS, Int.MAX_VALUE).rows

        // The frame really is over the screen's budget, which is what makes the cut happen at all:
        // without this the test would pass against a layout that never had to drop anything.
        assertThat(frame.lines.size).isEqualTo(WINDOW_ROWS)
        assertThat(whole.size).isGreaterThan(WINDOW_ROWS)

        for (screen in 1..WINDOW_ROWS) {
            val layout = terminalLayout(frame, WINDOW_COLUMNS, screen)
            val top = layout.rows.first()
            // One exception, and it is the case [fittedRows] documents: a single line taller than the
            // whole screen has no line boundary to start at, and showing its end is the only way to show
            // the cursor. Anything else starts the screen at the beginning of a line.
            val tallerThanTheScreen = whole.count { it.line == top.line } > screen
            assertWithMessage("a screen of $screen rows")
                .that(top.from == 0 || tallerThanTheScreen)
                .isTrue()
            // And the rows below it either continue the row above or begin a line, so the screen reads
            // as whole lines from its first row down.
            layout.rows.zipWithNext().forEach { (above, below) ->
                assertWithMessage("screen $screen: row starting line ${below.line} at column ${below.from}")
                    .that(below.from == 0 || (above.line == below.line && above.to == below.from))
                    .isTrue()
            }
        }
    }

    // ---- the pipeline, exactly as the view calls it ----------------------------------------------

    /**
     * The screen: what [TerminalView] would paint, minus the pixels.
     *
     * [AnsiTerminalBuffer.frame] with the window's width and height, then [terminalLayout] with the same
     * two numbers, then each visual row's columns as text. The view draws a row at `(column - row.from)
     * * cellWidth`, so a row whose `from` is not zero starts at the *left edge* of the screen - which is
     * how a fragment of a line comes to look like a line of its own, and why these tests assert on the
     * image rather than on the frame.
     */
    /**
     * What the scrolled-back badge is told, measured on the real buffer rather than by hand.
     *
     * The badge is drawn whenever `linesBelow()` is positive, and the number it prints is that value, so
     * both are supposed to mean "how far behind the live output this window is" - zero at the bottom.
     * The buffer is sized to the pty's height and starts full of blank rows, so measuring to the end of
     * the buffer instead of the end of the output counted rows the shell had not written yet: a session
     * with three lines printed on a 60-row pty reported fifty-odd lines below while sitting at its own
     * live bottom, and the badge then drew over the grid permanently, printing the buffer's un-inked
     * tail as if the user had scrolled past it.
     */
    @Test
    fun `a buffer taller than what has been printed is behind nothing`() {
        val buffer = session()
        feedLines(buffer, lines = 3)

        val frame = buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS)

        assertThat(frame.linesBelow()).isEqualTo(0)
        // The buffer really is taller than its own ink, which is what made the old measure wrong:
        // without this the test would pass against either definition of "below".
        assertThat(frame.totalLines).isGreaterThan(frame.firstLine + frame.lines.size)
    }

    @Test
    fun `the count below the window is the distance back`() {
        val buffer = session()
        feedLines(buffer)

        // At the bottom there is nothing below, whatever the buffer's height is.
        assertThat(buffer.frame(0, WINDOW_ROWS, WINDOW_COLUMNS).linesBelow()).isEqualTo(0)

        // And scrolled back it is the offset in lines - the same number the gesture moved the window
        // by - which is the one thing the badge is trying to say.
        assertThat(buffer.frame(1, WINDOW_ROWS, WINDOW_COLUMNS).linesBelow()).isEqualTo(1)
        assertThat(buffer.frame(5, WINDOW_ROWS, WINDOW_COLUMNS).linesBelow()).isEqualTo(5)
    }

    private fun imageOf(buffer: AnsiTerminalBuffer, offset: Int): List<String> {
        val frame = buffer.frame(offset, WINDOW_ROWS, WINDOW_COLUMNS)
        val layout = terminalLayout(frame, WINDOW_COLUMNS, WINDOW_ROWS)
        val painted = layout.rows.take(WINDOW_ROWS).map { row ->
            val line = frame.lines.getOrNull(row.line).orEmpty()
            (row.from until row.to.coerceAtMost(line.size)).joinToString("") { column ->
                val cell = line[column]
                cell.value.toString() + cell.combining
            }.trimEnd()
        }
        // The rows below the window are the screen's own background, which is what the view draws there:
        // the window is fitted in *whole* lines, so a row or two at the bottom is often left over. Left
        // blank deliberately - filling it with a partial line is the fragment this class exists to stop.
        return painted + List((WINDOW_ROWS - painted.size).coerceAtLeast(0)) { "" }
    }

    private fun layoutOf(buffer: AnsiTerminalBuffer, offset: Int): TerminalLayout =
        terminalLayout(buffer.frame(offset, WINDOW_ROWS, WINDOW_COLUMNS), WINDOW_COLUMNS, WINDOW_ROWS)

    /** The index one past the last line of the window at [offset], which is the line the offset measures. */
    private fun windowEnd(buffer: AnsiTerminalBuffer, offset: Int): Int {
        val frame = buffer.frame(offset, WINDOW_ROWS, WINDOW_COLUMNS)
        return frame.firstLine + frame.lines.size
    }

    /** The index of the first line of the window at [offset]. */
    private fun windowStart(buffer: AnsiTerminalBuffer, offset: Int): Int =
        buffer.frame(offset, WINDOW_ROWS, WINDOW_COLUMNS).firstLine

    /**
     * That no row of the screen begins in the middle of a line - the invariant the reported bug broke.
     *
     * A row with a non-zero `from` is a *continuation*: drawn from the left edge of the screen it reads
     * as text starting in the middle of a word, which is what made `AL`, `No`, `Wo-` and `Th-` appear at
     * column 0 and change shape as the viewport moved. It is legal only for a row to continue the row
     * directly above it, and never for the *first* row of the screen to continue anything - the one
     * exception being a single line taller than the whole screen, which cannot be shown any other way and
     * is the case where the reader is deliberately looking at the end of a line.
     */
    private fun assertNoRowStartsMidLine(
        buffer: AnsiTerminalBuffer,
        offset: Int,
        allowFullScreenLine: Boolean = false,
    ) {
        val rows = layoutOf(buffer, offset).rows.take(WINDOW_ROWS)
        rows.forEachIndexed { index, row ->
            if (row.from == 0) return@forEachIndexed
            if (index == 0) {
                if (allowFullScreenLine) return@forEachIndexed
                assertWithMessage("the top row of the screen starts at column ${row.from} of line ${row.line}")
                    .fail()
            }
            val above = rows[index - 1]
            assertWithMessage("row $index continues line ${row.line} at column ${row.from}, after line ${above.line}")
                .that(above.line == row.line && above.to == row.from)
                .isTrue()
        }
    }

    /** The buffer's width, height and scrollback for a phone-sized window reading a wider pty. */
    private fun session(
        columns: Int = 80,
        rows: Int = 60,
        scrollback: Int = 500,
    ): AnsiTerminalBuffer = AnsiTerminalBuffer(columns = columns, rows = rows, scrollbackLimit = scrollback)

    /**
     * Output the shell would have printed: [lines] lines of prose, and the prompt that follows them
     * unless [tail] says otherwise.
     *
     * Every line is long enough to wrap at both widths that matter - the pty's 80 (or 48 in the tests
     * that narrow it) and the window's 44 - because a line that does not wrap is a line whose window is
     * its row, and the fit has nothing to say about it.
     */
    private fun feedLines(
        buffer: AnsiTerminalBuffer,
        from: Int = 0,
        lines: Int = 40,
        tail: String = "$ ",
    ) {
        for (index in from until from + lines) buffer.feed("output line $index with some words on it here now plus a tail\r\n")
        buffer.feed(tail)
    }

    /** A frame holding one line of a screen that is painting it, cursor at [cursorColumn]. */
    private fun lineFrame(line: List<TerminalCell>, cursorColumn: Int): TerminalFrame = TerminalFrame(
        lines = listOf(line),
        firstLine = 0,
        totalLines = 1,
        cursorRow = 0,
        cursorColumn = cursorColumn,
        columns = line.size,
        rows = 1,
        cursorVisible = true,
        revision = 0L,
    )

    /** A frame holding one line, padded to at least [MIN_LINE_COLUMNS] columns the way the buffer pads. */
    private fun singleLineFrame(line: List<TerminalCell>): TerminalFrame {
        val padded = if (line.size < MIN_LINE_COLUMNS) line + List(MIN_LINE_COLUMNS - line.size) { TerminalCell() } else line
        return TerminalFrame(
            lines = listOf(padded),
            firstLine = 0,
            totalLines = 1,
            cursorRow = -1,
            cursorColumn = 0,
            columns = padded.size,
            rows = 1,
            cursorVisible = false,
            revision = 0L,
        )
    }

    private companion object {
        /** How many columns of the grid fit on the phone this bug was reported from. */
        const val WINDOW_COLUMNS = 44

        /** How many rows of it fit. */
        const val WINDOW_ROWS = 17

        /** Wide enough for every shape [singleLineFrame] is asked about. */
        const val MIN_LINE_COLUMNS = 160
    }
}
