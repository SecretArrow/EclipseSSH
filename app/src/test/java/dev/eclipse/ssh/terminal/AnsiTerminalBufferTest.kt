package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** ESC, spelled out so the sequences below stay readable and copy-pasteable. */
private const val E = "\u001B"

/**
 * What one drawn row reads as, trailing blanks dropped the way the renderer drops them.
 *
 * A mark is part of the character its cell draws, so it belongs in what the row reads as - the same
 * string [AnsiTerminalBuffer.plainText] gives for that row.
 */
private fun List<TerminalCell>.text(): String =
    joinToString("") { it.value.toString() + it.combining }.trimEnd()

/** The background of every cell of one row, so a test can say "all of them, and it is this one". */
private fun AnsiTerminalBuffer.backgrounds(row: Int): List<TerminalColor> =
    snapshot().lines[row].map { it.style.background }

class AnsiTerminalBufferTest {
    @Test
    fun `ansi control sequences do not leak into plain terminal text`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("$E[31mred$E[0m normal")

        assertThat(buffer.plainText()).contains("red normal")
        assertThat(buffer.snapshot().lines.first()[0].style.foreground.isDefault).isFalse()
    }

    @Test
    fun `cursor movement overwrites at requested position`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)

        buffer.feed("hello$E[2DXX")

        assertThat(buffer.plainText().lineSequence().first()).startsWith("helXX")
    }

    @Test
    fun `line feed retains scrollback`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2, scrollbackLimit = 10)

        buffer.feed("one\ntwo\nthree")

        assertThat(buffer.plainText()).contains("one")
        assertThat(buffer.plainText()).contains("three")
    }

    // --- Streaming: a channel delivers arbitrary chunks, not whole sequences ---

    @Test
    fun `an escape sequence split across reads is still interpreted`() {
        val whole = AnsiTerminalBuffer(columns = 20, rows = 3)
        val split = AnsiTerminalBuffer(columns = 20, rows = 3)
        val output = "$E[32mgreen$E[0m done"

        whole.feed(output)
        // The same bytes arriving one at a time must produce the same screen.
        output.forEach { split.feed(it.toString()) }

        assertThat(split.plainText()).isEqualTo(whole.plainText())
        assertThat(split.snapshot().lines.first()[0].style)
            .isEqualTo(whole.snapshot().lines.first()[0].style)
    }

    @Test
    fun `an OSC title sequence does not swallow the output that follows it`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("$E]0;my title\u0007ready")

        assertThat(buffer.plainText()).isEqualTo("ready")
    }

    @Test
    fun `a garbage escape byte is dropped without corrupting the line`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        // `ESC SP b` used to be the sample and printed its `b`; an intermediate byte now opens a
        // sequence, so the final byte belongs to it (see the full-screen `an unknown sequence with an
        // intermediate byte takes its final byte with it`).
        buffer.feed("a$E;b")

        assertThat(buffer.plainText()).isEqualTo("ab")
    }

    // --- Bounded memory: a chatty server must not grow the buffer forever ---

    @Test
    fun `scrollback is bounded so a noisy session cannot exhaust memory`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 8)

        repeat(500) { buffer.feed("line $it\n") }

        assertThat(buffer.snapshot().lines.size).isAtMost(4 + 8)
        // The newest output is what survives.
        assertThat(buffer.plainText()).contains("line 499")
        assertThat(buffer.plainText()).doesNotContain("line 0")
    }

    @Test
    fun `output wider than the terminal wraps instead of overflowing the row`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4, scrollbackLimit = 4)

        buffer.feed("x".repeat(25))

        val snapshot = buffer.snapshot()
        assertThat(snapshot.lines.all { it.size == 20 }).isTrue()
        assertThat(snapshot.lines[0].count { it.value == 'x' }).isEqualTo(20)
        assertThat(snapshot.lines[1].count { it.value == 'x' }).isEqualTo(5)
    }

    @Test
    fun `carriage return rewrites the line in place like a progress bar`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("10%\r100%")

        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("100%")
    }

    @Test
    fun `backspace stops at the left margin`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)

        buffer.feed("\b\b\bok")

        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("ok")
    }

    // --- Screen-clearing sequences used by top, htop and vim ---

    @Test
    fun `erase display clears the whole screen`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)
        buffer.feed("first\nsecond")

        buffer.feed("$E[2J")

        assertThat(buffer.plainText()).isEmpty()
    }

    @Test
    fun `erase to end of line leaves the prefix intact`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 2)
        buffer.feed("keep this away")

        buffer.feed("$E[10D$E[K")

        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("keep")
    }

    // --- bce: an erase fills with the colour the program set, not with the default ---
    //
    // `xterm-256color`, the terminal type this app reports, carries `bce`: an erase is promised to fill
    // the erased cells with the *current* background. A program that paints a background and then
    // clears is entitled to send that colour once and rely on it - and tmux does exactly that, setting
    // the colour and erasing with a bare `ESC [ K`. A shell hides a terminal that drops it by
    // rewriting the line; tmux does not, because it sends only the cells it believes have changed.

    @Test
    fun `erase to end of line fills the erased cells with the current background`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)
        buffer.feed("$E[41mX")
        val painted = buffer.snapshot().lines[0][0].style.background
        buffer.feed("$E[1;2H$E[K")

        assertThat(painted.isDefault).isFalse()
        assertThat(buffer.backgrounds(0).drop(1).toSet()).containsExactly(painted)
    }

    @Test
    fun `erase to start of line fills the erased cells with the current background`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)
        buffer.feed("$E[41mX")
        val painted = buffer.snapshot().lines[0][0].style.background
        buffer.feed("$E[1;5H$E[1K")

        assertThat(buffer.backgrounds(0).take(5).toSet()).containsExactly(painted)
        assertThat(buffer.backgrounds(0).drop(5).toSet()).containsExactly(TerminalColor())
    }

    @Test
    fun `erasing the whole line fills it with the current background`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)
        buffer.feed("$E[41mX")
        val painted = buffer.snapshot().lines[0][0].style.background

        buffer.feed("$E[2K")

        assertThat(buffer.backgrounds(0).toSet()).containsExactly(painted)
        assertThat(buffer.plainText()).isEmpty()
    }

    @Test
    fun `erasing the display fills every row with the current background`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)
        buffer.feed("$E[41mX")
        val painted = buffer.snapshot().lines[0][0].style.background

        buffer.feed("$E[2J")

        assertThat((0..2).flatMap { buffer.backgrounds(it) }.toSet()).containsExactly(painted)
    }

    @Test
    fun `erasing to the end of the display fills the rows below the cursor`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)
        buffer.feed("plain\r\n$E[41mX")
        val painted = buffer.snapshot().lines[1][0].style.background

        buffer.feed("$E[0J")

        assertThat(buffer.backgrounds(0).toSet()).containsExactly(TerminalColor())
        assertThat(buffer.backgrounds(1).drop(1).toSet()).containsExactly(painted)
        assertThat(buffer.backgrounds(2).toSet()).containsExactly(painted)
    }

    @Test
    fun `erasing to the start of the display fills the rows above the cursor`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)
        buffer.feed("$E[41mX$E[3;1H")
        val painted = buffer.snapshot().lines[0][0].style.background

        buffer.feed("$E[1J")

        assertThat((0..1).flatMap { buffer.backgrounds(it) }.toSet()).containsExactly(painted)
        assertThat(buffer.backgrounds(2).take(1).toSet()).containsExactly(painted)
        assertThat(buffer.backgrounds(2).drop(1).toSet()).containsExactly(TerminalColor())
    }

    @Test
    fun `erase characters fills only the cells it erases`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)
        buffer.feed("$E[41mX$E[1;2H")
        val painted = buffer.snapshot().lines[0][0].style.background

        buffer.feed("$E[3X")

        assertThat(buffer.backgrounds(0).drop(1).take(3).toSet()).containsExactly(painted)
        assertThat(buffer.backgrounds(0).drop(4).toSet()).containsExactly(TerminalColor())
    }

    @Test
    fun `an erase after the background is reset goes back to the default`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)
        buffer.feed("$E[41m$E[2J")
        assertThat(buffer.backgrounds(0).toSet()).hasSize(1)

        buffer.feed("$E[49m$E[2J")

        assertThat((0..1).flatMap { buffer.backgrounds(it) }.toSet()).containsExactly(TerminalColor())
    }

    @Test
    fun `an erase with no colour set leaves the default blank`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)
        buffer.feed("$E[41mX$E[0m")

        buffer.feed("$E[2J")

        assertThat((0..1).flatMap { buffer.backgrounds(it) }.toSet()).containsExactly(TerminalColor())
        assertThat(buffer.snapshot().lines[0][0]).isEqualTo(TerminalCell())
    }

    @Test
    fun `an erase under reverse video keeps the swap, which is what gets painted`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)
        buffer.feed("$E[7;31mX")
        val painted = buffer.snapshot().lines[0][0].style

        buffer.feed("$E[2J")

        // Reversed, the cell paints its foreground - so an erased cell that lost `inverse` would come
        // out in the default background instead of the colour the program is filling the screen with.
        val erased = buffer.snapshot().lines[1][0].style
        assertThat(erased.inverse).isTrue()
        assertThat(erased.foreground).isEqualTo(painted.foreground)
    }

    @Test
    fun `an erased cell keeps no attribute a blank cannot show`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)
        buffer.feed("$E[1;4;9;41mX")
        val painted = buffer.snapshot().lines[0][0].style.background

        buffer.feed("$E[1;2H$E[K")

        val erased = buffer.snapshot().lines[0][1].style
        assertThat(erased.background).isEqualTo(painted)
        assertThat(erased.bold).isFalse()
        assertThat(erased.underline).isFalse()
        assertThat(erased.strikethrough).isFalse()
    }

    @Test
    fun `erasing one half of a wide character gives the other half the current background`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)
        buffer.feed("你$E[1;1H$E[41mX")
        val painted = buffer.snapshot().lines[0][0].style.background

        assertThat(painted.isDefault).isFalse()
        assertThat(buffer.snapshot().lines[0][1].style.background).isEqualTo(painted)
    }

    @Test
    fun `erasing the scrollback leaves the cells on the screen alone`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)
        buffer.feed("one\ntwo\n")

        buffer.feed("$E[41m$E[3J")

        // The history goes; the screen it was cleared behind keeps its text. `ESC [ 3 J` is not an erase
        // of anything on screen, so it must not repaint it in the current colour either.
        assertThat(buffer.snapshot().lines).hasSize(2)
        assertThat(buffer.snapshot().lines[0].map { it.value }.joinToString("").trimEnd()).isEqualTo("two")
        assertThat(buffer.backgrounds(0).toSet()).containsExactly(TerminalColor())
    }

    @Test
    fun `a screen erased under a background is painted all the way across`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)

        buffer.feed("$E[41m$E[2J")

        // What makes the fill visible at all: a blank counts as painted only when it carries a colour
        // or a swap, so an erase that kept neither would be trimmed off with the trailing blanks and
        // the screen would look untouched however the cells were stored.
        val layout = terminalLayout(buffer.frame(), width = 10, maxRows = 3)
        assertThat(layout.rows.map { it.to }.toSet()).containsExactly(10)
    }

    @Test
    fun `a line scrolled in at the bottom keeps the default background`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 2)
        buffer.feed("$E[41ma\nb\n")

        // The deliberate edge of the erase fix: it is the *erase* sequences that fill with the current
        // background, not the row the screen scrolls into view. A cell that was never cleared is not
        // something a program asked to paint, and colouring it would tint the scrollback behind it.
        assertThat(buffer.snapshot().lines.last().toSet()).containsExactly(TerminalCell())
    }

    @Test
    fun `absolute cursor addressing writes at the requested cell`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 5)

        buffer.feed("$E[3;5HX")

        assertThat(buffer.snapshot().lines[2][4].value).isEqualTo('X')
    }

    @Test
    fun `cursor addressing beyond the screen is clamped instead of throwing`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("$E[999;999HX")

        val snapshot = buffer.snapshot()
        assertThat(snapshot.cursorRow).isAtMost(snapshot.lines.lastIndex)
        assertThat(snapshot.cursorColumn).isAtMost(snapshot.columns)
        assertThat(buffer.plainText()).contains("X")
    }

    @Test
    fun `save and restore cursor returns to the saved cell`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("abc$E[s$E[3;1Hxyz$E[uQ")

        // Q lands where the cursor was saved: row 0, column 3.
        assertThat(buffer.snapshot().lines[0][3].value).isEqualTo('Q')
    }

    // --- The same final byte, a different question, when a prefix is present ---

    @Test
    fun `a keyboard protocol probe leaves the cursor where the program put it`() {
        // `CSI ? u` asks the terminal whether it speaks the kitty keyboard protocol; `CSI < u` pops
        // and `CSI > 5 u` pushes. Read as the bare `CSI u` they answered by moving the cursor to the
        // last saved position, so every program that asks about the keyboard got a screen jump.
        for (probe in listOf("$E[?u", "$E[<u", "$E[>5u")) {
            val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
            buffer.feed("abc$E[s$E[3;1H$E[2K")

            buffer.feed(probe)

            assertWithMessage("after %s", probe).that(buffer.frame().cursorRow).isEqualTo(2)
            assertWithMessage("after %s", probe).that(buffer.frame().cursorColumn).isEqualTo(0)
        }
    }

    @Test
    fun `a keyboard protocol probe does not disturb a restore either`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        // The bare form still restores, which is what the three prefixed ones must not do.
        buffer.feed("abc$E[s$E[3;1H$E[?u$E[uQ")

        assertThat(buffer.snapshot().lines[0][3].value).isEqualTo('Q')
    }

    @Test
    fun `modifyOtherKeys is not read as a dim underlined SGR`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        // `ESC [ > 4 ; 2 m` is xterm's modifyOtherKeys. Read as SGR it set dim plus underline, and
        // every cell the program drew afterwards carried both - on a real capture of Claude Code that
        // was 543 decorated cells against 80 without the sequence.
        buffer.feed("$E[>4;2mA")
        val afterProbe = buffer.snapshot().lines[0][0].style

        assertThat(afterProbe.dim).isFalse()
        assertThat(afterProbe.underline).isFalse()
    }

    @Test
    fun `a real SGR still sets dim and underline`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("$E[4;2mA")
        val style = buffer.snapshot().lines[0][0].style

        assertThat(style.underline).isTrue()
        assertThat(style.dim).isTrue()
    }

    @Test
    fun `a private mode save does not overwrite the saved cursor`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        // `ESC [ ? Pm s` saves private modes. Read as the bare `CSI s` it saved the cursor, and the
        // restore that followed went to the mode-save's position instead of the program's.
        buffer.feed("abc$E[s$E[3;1H$E[?1000s$E[uQ")

        assertThat(buffer.snapshot().lines[0][3].value).isEqualTo('Q')
        assertThat(buffer.snapshot().lines[2][0].value).isNotEqualTo('Q')
    }

    @Test
    fun `a private mode restore does not set the scroll region`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4)
        buffer.feed("A\r\nB\r\nC\r\nD")

        // `ESC [ ? Pm r` restores private modes. Read as the bare `CSI r` it set the scroll region
        // from a mode number, which is the whole screen for any mode above the row count - and a line
        // feed at the screen's foot then scrolls row 0 away instead of stopping at the margin.
        buffer.feed("$E[2;3r$E[?1000r$E[4;1H")
        buffer.feed("\n")

        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("A")
    }

    @Test
    fun `a frame built inside a synchronized output batch says the screen is unfinished`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("$E[?2026habc")
        val midBatch = buffer.frame()
        buffer.feed("def$E[?2026l")
        val closed = buffer.frame()

        // The cells are in the grid either way - the batch is a promise about when they may be drawn,
        // not about what the buffer holds - so the flag is what carries the promise to the publisher.
        assertThat(midBatch.synchronizedOutput).isTrue()
        assertThat(midBatch.lines[0].text()).isEqualTo("abc")
        assertThat(closed.synchronizedOutput).isFalse()
        assertThat(closed.lines[0].text()).isEqualTo("abcdef")
    }

    @Test
    fun `a synchronized output batch does not hold the revision still`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        // Deliberate, and the reason is on the field: TerminalLayoutKey is built from the revision on
        // the promise that two windows of one revision hold the same cells, and the view memoizes the
        // layout on it. Freezing the revision across a batch would break that promise and draw the new
        // cells through the old row geometry - the left-arrow bug this buffer already has a test for.
        // What the batch holds back is the frame, not the number.
        buffer.feed("$E[?2026h")
        val opened = buffer.frame().revision
        buffer.feed("a")
        val afterOne = buffer.frame().revision
        buffer.feed("b")
        val afterTwo = buffer.frame().revision

        assertThat(afterOne).isGreaterThan(opened)
        assertThat(afterTwo).isGreaterThan(afterOne)
    }

    @Test
    fun `a reset ends a batch a program left open`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        // A program that dies mid-batch would otherwise hold the screen back for the life of the
        // session, with nothing able to clear it - the reset is the one thing that always arrives.
        buffer.feed("$E[?2026hpartial")
        buffer.feed("$E[!p")

        assertThat(buffer.frame().synchronizedOutput).isFalse()
    }

    @Test
    fun `the end of a session ends a batch too`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        // The case a reset cannot cover: the program was killed, or the link went, between its `h` and
        // its `l`. Nothing arrives to close the batch, and the buffer is reused by the reconnect, so
        // the collector is what ends it.
        buffer.feed("$E[?2026hpartial")
        assertThat(buffer.frame().synchronizedOutput).isTrue()

        buffer.releaseSynchronizedOutput()

        assertThat(buffer.frame().synchronizedOutput).isFalse()
    }

    @Test
    fun `the prologue Claude Code writes before it draws moves nothing`() {
        val buffer = AnsiTerminalBuffer(columns = 80, rows = 22)
        buffer.feed("$  a prompt line typed by the user\n")
        val before = buffer.frame()

        buffer.feed(
            "$E[<u$E[>5u$E[>4;2m$E[?1000h$E[?1002h$E[?1003h$E[?1006h$E[?25l$E[>0q$E[?u$E[c",
        )

        assertThat(buffer.frame().cursorRow).isEqualTo(before.cursorRow)
        assertThat(buffer.frame().cursorColumn).isEqualTo(before.cursorColumn)
    }

    @Test
    fun `full reset clears the screen and the pending style`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)
        buffer.feed("$E[31mred\nmore")

        buffer.feed("${E}cplain")

        assertThat(buffer.plainText()).isEqualTo("plain")
        assertThat(buffer.snapshot().lines[0][0].style.foreground.isDefault).isTrue()
    }

    // --- Styling ---

    @Test
    fun `256 colour and true colour sequences both set a foreground`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("$E[38;5;208mA$E[38;2;10;20;30mB")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].style.foreground.isDefault).isFalse()
        assertThat(line[1].style.foreground.isTrueColor).isTrue()
        assertThat(line[1].style.foreground.rgb).isEqualTo((10 shl 16) or (20 shl 8) or 30)
    }

    @Test
    fun `bold and underline are cleared by their reset codes`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("$E[1;4mA$E[22;24mB")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].style.bold).isTrue()
        assertThat(line[0].style.underline).isTrue()
        assertThat(line[1].style.bold).isFalse()
        assertThat(line[1].style.underline).isFalse()
    }

    @Test
    fun `a truncated true colour sequence is ignored rather than mis-parsed`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("$E[38;2;10mA")

        assertThat(buffer.snapshot().lines[0][0].style.foreground.isDefault).isTrue()
    }

    // --- Resize: happens on rotation and on soft-keyboard show/hide ---

    /**
     * The screen - and only the screen - is cut to the new width.
     *
     * This test used to require it of every row in the buffer, scrollback included, because that is
     * what [AnsiTerminalBuffer.resize] did. It is the wrong contract and it destroyed output: a
     * rotation to portrait, or the soft keyboard opening, narrowed the terminal and permanently
     * dropped every character past the new column count from lines that had already been printed and
     * would never be redrawn. What made it hard to see is that it looked like ordinary wrapping until
     * you tried to copy the text or save the log and found the tail was gone from those too.
     *
     * The screen still has to be trimmed: the emulator addresses those rows by column, and a redraw
     * at the new width would otherwise leave a stale tail beyond the last column it repaints.
     */
    @Test
    fun `a resize trims the screen to the new width and leaves the history it cannot redraw`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 5)
        val hostname = "gateway-eu-west-1b.internal.example"

        // Nine lines into a five-row screen, so the first four are genuinely history.
        buffer.feed(hostname + "\r\n" + (1..8).joinToString("\r\n") { "line $it" })
        buffer.resize(20, 5)

        val snapshot = buffer.snapshot()
        assertThat(snapshot.columns).isEqualTo(20)
        // The screen is the last five rows, and those are exactly the new width.
        assertThat(snapshot.lines.takeLast(5).all { it.size == 20 }).isTrue()
        // The history above it is untouched - still as wide as it was printed, characters and all.
        assertThat(snapshot.lines.first().size).isEqualTo(40)
        assertThat(snapshot.lines.first().text()).isEqualTo(hostname)
        assertThat(buffer.plainText()).contains(hostname)
    }

    @Test
    fun `narrowing then widening again shows the history whole, not the part that fit`() {
        val buffer = AnsiTerminalBuffer(columns = 120, rows = 6)
        val path = "/var/log/eclipse/very/deep/directory/tree/application-2026-08-22.log"
        buffer.feed(path + "\r\n" + (1..9).joinToString("\r\n") { "line $it" })

        // Keyboard opens, device rotates, keyboard closes. Two of the three narrow the terminal, and
        // under the old contract each one cut the tail off every line in the buffer.
        buffer.resize(45, 5)
        buffer.resize(120, 6)

        assertThat(buffer.plainText()).contains(path)
        // The cells themselves, not just the joined text: copying that region has to work too.
        val row = buffer.snapshot().lines.first { it.text().contains("eclipse") }
        assertThat(row.text()).isEqualTo(path)
    }

    @Test
    fun `a selection copied out of narrowed history is still the whole line`() {
        val buffer = AnsiTerminalBuffer(columns = 80, rows = 5)
        val hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        buffer.feed(hash + "\r\n" + (1..8).joinToString("\r\n") { "line $it" })

        buffer.resize(24, 5)

        // Copying that line asks for more columns than the terminal now has: the text comes from the
        // line as stored, not from the current geometry. This is what a long-press-copy of a hash,
        // a git sha or a full path does after the keyboard has narrowed the screen once.
        val copied = buffer.textIn(fromLine = 0, fromColumn = 0, toLine = 0, toColumn = hash.length)
        assertThat(copied).isEqualTo(hash)
    }

    @Test
    fun `absurd resize requests are clamped to a usable geometry`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 6)

        buffer.resize(0, 0)

        val small = buffer.snapshot()
        assertThat(small.columns).isAtLeast(20)
        assertThat(small.rows).isAtLeast(5)

        buffer.resize(100_000, 100_000)

        val large = buffer.snapshot()
        assertThat(large.columns).isEqualTo(TERMINAL_COLUMN_RANGE.last)
        assertThat(large.rows).isEqualTo(TERMINAL_ROW_RANGE.last)
    }

    /**
     * The window follows the cursor, which is the whole of what a tall pty needs from the buffer.
     *
     * With the pty taller than the output written so far the cursor sits somewhere in the middle of a
     * buffer that is mostly blank rows, and a window measured from the *bottom* of it would be a screen
     * of nothing with the prompt far above. The rows below the cursor are never drawn, so the window is
     * taken from the cursor down instead - and at offset zero it ends on the cursor's own line, which is
     * where a shell's newest output is.
     */
    @Test
    fun `a window in a mostly blank buffer is taken from the cursor and not the bottom`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 1000)
        buffer.feed((1..6).joinToString("\r\n") { "line $it" })

        val live = buffer.frame(scrollOffset = 0, viewportRows = 4)

        assertThat(live.totalLines).isEqualTo(1000)
        assertThat(live.lines.map { it.text() }).containsExactly("line 3", "line 4", "line 5", "line 6").inOrder()
        assertThat(live.firstLine).isEqualTo(2)
        // The cursor is on the last row of the window, not a thousand rows below it.
        assertThat(live.cursorRow).isEqualTo(3)

        // And the scrollback above the cursor is reachable: four rows back shows lines 1 and 2 with the
        // blank rows above them, and no further, because there is nothing further to show.
        val back = buffer.frame(scrollOffset = 2, viewportRows = 4)
        assertThat(back.firstLine).isEqualTo(0)
        assertThat(back.lines.last().text()).isEqualTo("line 4")
        assertThat(back.cursorRow).isEqualTo(-1)

        // The range of offsets is measured from the same anchor, so it is the distance to the first line
        // the terminal ever wrote - two - and not the 996 blank rows below the cursor.
        assertThat(buffer.maxScrollOffset(4)).isEqualTo(2)
    }

    /**
     * A window taller than what has been written starts at the first line, not before the buffer.
     *
     * The lower bound on the anchor: a cursor on row 3 of a hundred-row screen pulled up by a window of
     * four would otherwise ask for lines -1 to 3.
     */
    @Test
    fun `a window taller than the output starts at the first line`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 100)
        buffer.feed("only line")

        val frame = buffer.frame(scrollOffset = 0, viewportRows = 40)

        assertThat(frame.firstLine).isEqualTo(0)
        assertThat(frame.lines).hasSize(40)
        assertThat(frame.lines.first().text()).isEqualTo("only line")
        assertThat(frame.cursorRow).isEqualTo(0)
        assertThat(buffer.maxScrollOffset(40)).isEqualTo(0)
    }

    /**
     * [AnsiTerminalBuffer.cursorLine] is the anchor [AnsiTerminalBuffer.frame] is taken from.
     *
     * The caller that keeps a scrolled-back view still reads it before and after feeding output and adds
     * the difference back to the offset. It has to be this number rather than the line count, because in
     * a tall terminal output moves the cursor without adding a line at all - the buffer's growth is zero
     * and the view would slide up by one row per line printed.
     */
    @Test
    fun `cursorLine moves with the output in a tall terminal and stays put once it scrolls`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 1000)

        assertThat(buffer.cursorLine).isEqualTo(1)
        buffer.feed("one\r\ntwo\r\nthree")
        assertThat(buffer.cursorLine).isEqualTo(3)
        // No line was added to a buffer that was already a thousand rows tall.
        assertThat(buffer.lineCount()).isEqualTo(1000)

        // Once the screen is full the cursor is pinned to the last row, and the line count is what
        // grows instead - which is why the two agreed before there was such a thing as a tall terminal.
        val full = AnsiTerminalBuffer(columns = 20, rows = 4)
        full.feed((1..10).joinToString("\r\n") { "line $it" })
        assertThat(full.cursorLine).isEqualTo(full.lineCount())
    }

    /**
     * [AnsiTerminalBuffer.plainText] still renders exactly what the obvious expression renders.
     *
     * It was that expression, verbatim, until it turned out to allocate a `String` per cell and to be
     * called once per rendered frame. The replacement builds the same text into one StringBuilder, so
     * the risk is no longer performance but a subtle mismatch: where the per-line `trimEnd()` stops,
     * whether the separator survives a trim, what an entirely blank screen renders as. The reference
     * is kept here rather than described, and compared over inputs chosen to hit each of those.
     */
    @Test
    fun `plainText matches the straightforward rendering`() {
        fun reference(snapshot: TerminalSnapshot): String = snapshot.lines
            .joinToString("\n") { line ->
                line.joinToString("") { it.value.toString() + it.combining }.trimEnd()
            }
            .trimEnd()

        val cases = mapOf(
            "an untouched screen" to "",
            "one word" to "hello",
            "trailing spaces on a line" to "hello   ",
            "several lines" to "one\ntwo\nthree",
            // A gap of blank lines in the middle must survive; only the trailing run is dropped.
            "a blank line between two full ones" to "top\n\nbottom",
            // Ends on a blank line, which is what the outer trimEnd() exists for.
            "output that ends with newlines" to "text\n\n\n",
            "a line as wide as the screen" to "x".repeat(20),
            // Wider than the screen, so it wraps and the wrapped part has no trailing space to trim.
            "a line wider than the screen" to "y".repeat(45),
            // Enough to push the first lines into scrollback.
            "more lines than the screen has rows" to (1..12).joinToString("\n") { "line $it" },
            "a line of nothing but spaces" to "   \nafter",
            "tabs and non-latin text" to "kolom\tnilai\nsandi-üñïçø∂é",
            // A cell whose character is two code points: the mark is part of the line, so a rendering
            // that dropped it would be dropping ink the user can see.
            "a decomposed accent" to "cafe\u0301 x",
            "styling around the text" to "$E[1;31mbold red$E[0m plain",
            // Cursor addressing leaves untouched cells as spaces mid-line.
            "cursor addressing that leaves gaps" to "$E[2;5Hmid$E[4;1Hlow",
        )

        cases.forEach { (name, input) ->
            val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
            buffer.feed(input)

            assertWithMessage(name).that(buffer.plainText()).isEqualTo(reference(buffer.snapshot()))
        }
    }

    @Test
    fun `writing after a shrink stays inside the buffer`() {
        val buffer = AnsiTerminalBuffer(columns = 200, rows = 50)
        buffer.feed("$E[40;180HX")

        buffer.resize(20, 5)
        buffer.feed("${E}[Hafter")

        assertThat(buffer.plainText()).contains("after")
        // The screen - the rows the emulator addresses by column - is exactly the new width. The
        // history above it is not: see `a resize trims the screen to the new width`.
        assertThat(buffer.snapshot().lines.takeLast(5).all { it.size == 20 }).isTrue()
    }

    /**
     * Shrinking to fit the device keeps the output on screen instead of scrolling it into history.
     *
     * This is the first thing that happens to every session. The buffer starts at its default forty
     * rows, the shell greets it with a banner and a prompt, and then the view finishes measuring
     * itself and resizes to however many rows the device actually has. Because the screen is the tail
     * of the buffer, that shrink used to re-anchor the window several rows further down and leave the
     * banner, the prompt and the cursor above it: a session that had connected fine and authenticated
     * fine displayed an empty grid, and went on displaying one until enough output arrived to fill the
     * window. Every assertion here is about the frame rather than [AnsiTerminalBuffer.plainText],
     * because the bug was invisible in the text - the lines were all still in the buffer, just not in
     * the part of it that gets drawn.
     */
    @Test
    fun `shrinking to fit an almost empty screen keeps its output and cursor on screen`() {
        val buffer = AnsiTerminalBuffer(columns = 120, rows = 40)
        buffer.feed("eclipse-shell\r\n\$ ")

        buffer.resize(120, 34)

        val frame = buffer.frame()
        assertThat(frame.rows).isEqualTo(34)
        assertThat(frame.lines).hasSize(34)
        assertThat(frame.firstLine).isEqualTo(0)
        assertThat(frame.lines[0].text()).isEqualTo("eclipse-shell")
        assertThat(frame.lines[1].text()).isEqualTo("\$")
        assertThat(frame.cursorRow).isEqualTo(1)
        assertThat(frame.cursorColumn).isEqualTo(2)
    }

    /** The other half of the rule: a screen with output on every row does lose its topmost rows. */
    @Test
    fun `shrinking a full screen still scrolls its topmost rows into the scrollback`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 10, scrollbackLimit = 100)
        buffer.feed((1..10).joinToString("\r\n") { "line $it" })

        buffer.resize(20, 6)

        val frame = buffer.frame()
        assertThat(frame.lines).hasSize(6)
        assertThat(frame.lines.first().text()).isEqualTo("line 5")
        assertThat(frame.lines.last().text()).isEqualTo("line 10")
        assertThat(frame.cursorRow).isEqualTo(5)
        // Scrolled away, not thrown away.
        assertThat(buffer.plainText()).contains("line 1")
    }

    /**
     * A blank row with the cursor on it is not spare space - the shell is about to print there.
     *
     * A shell that has just been handed a newline sits on an empty row until it writes its next
     * prompt. Reclaiming that row on a shrink would move the cursor up onto the previous line and the
     * prompt would then overwrite the command the user had just run.
     */
    @Test
    fun `a shrink keeps the blank row the cursor is waiting on`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 10, scrollbackLimit = 100)
        buffer.feed("only line\r\n")

        buffer.resize(20, 5)
        buffer.feed("next\$ ")

        val frame = buffer.frame()
        assertThat(frame.lines).hasSize(5)
        assertThat(frame.lines[0].text()).isEqualTo("only line")
        assertThat(frame.lines[1].text()).isEqualTo("next\$")
        assertThat(frame.cursorRow).isEqualTo(1)
    }

    /** The same rule where it bites hardest: the cursor is on the very last row of a full screen. */
    @Test
    fun `a shrink keeps a cursor waiting at the bottom of a full screen`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 10, scrollbackLimit = 100)
        buffer.feed((1..9).joinToString("\r\n") { "line $it" } + "\r\n")

        buffer.resize(20, 6)
        buffer.feed("next\$ ")

        val frame = buffer.frame()
        assertThat(frame.lines).hasSize(6)
        assertThat(frame.cursorRow).isEqualTo(5)
        assertThat(frame.lines.first().text()).isEqualTo("line 5")
        assertThat(frame.lines.last().text()).isEqualTo("next\$")
    }

    /** Rotating a device shrinks and then grows again; both halves have to come out even. */
    @Test
    fun `rotating back to the taller geometry neither duplicates nor loses a line`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 40)
        buffer.feed("banner\r\n\$ ")

        buffer.resize(40, 20)
        buffer.resize(40, 40)

        val frame = buffer.frame()
        assertThat(frame.lines).hasSize(40)
        assertThat(frame.totalLines).isEqualTo(40)
        assertThat(frame.lines[0].text()).isEqualTo("banner")
        assertThat(frame.lines[1].text()).isEqualTo("\$")
        assertThat(frame.lines.drop(2).all { it.text().isEmpty() }).isTrue()
        assertThat(frame.cursorRow).isEqualTo(1)
    }

    /**
     * Rotating while a full-screen program is up must not lose the shell waiting behind it.
     *
     * The primary screen is stored away untouched while the alternate one is on display, so it needs
     * the same treatment on a shrink as the visible one - otherwise quitting `vim` after a rotation
     * dropped the user onto a shell whose prompt had been pushed into the scrollback.
     */
    @Test
    fun `leaving the alternate screen after a shrink brings the shell back on screen`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 40)
        buffer.feed("banner\r\n\$ vim")
        buffer.feed("$E[?1049h")
        buffer.feed("~\r\n~")

        buffer.resize(40, 24)
        buffer.feed("$E[?1049l")

        val frame = buffer.frame()
        assertThat(frame.alternateScreen).isFalse()
        assertThat(frame.lines).hasSize(24)
        assertThat(frame.lines[0].text()).isEqualTo("banner")
        assertThat(frame.lines[1].text()).isEqualTo("\$ vim")
        assertThat(frame.cursorRow).isEqualTo(1)
    }

    // --- Two-column characters. A CJK ideograph, a fullwidth form or an emoji the far side counts
    // two columns for occupies two cells, and the program on the other end is counting on that: its
    // cursor and this one have to move in step, or every erase it sends lands one column off. The
    // second cell is the first one's right half - a column, not a character - and the rule the whole
    // section is about is that the two are never left apart on screen: half a glyph is ink drawn
    // across a column the program believes is blank.

    @Test
    fun `a two-column character takes two cells and moves the cursor over both`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 3)

        buffer.feed("中")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].value).isEqualTo('中')
        assertThat(line[0].wide).isTrue()
        // The right half carries the blank rather than a copy of the glyph: it holds the character's
        // second column, and a copy would draw the character twice.
        assertThat(line[1].value).isEqualTo(' ')
        assertThat(line[1].wide).isFalse()
        assertThat(buffer.snapshot().cursorColumn).isEqualTo(2)
    }

    /**
     * Writing one column where the program counted two is what the user sees as overlapping text.
     *
     * A shell printing a CJK word and repainting it - `\r`, the line erased, the line written again -
     * is the everyday case. If each glyph had taken one cell, this buffer's cursor would sit one
     * column behind the shell's for every wide character written, so the erase would stop short and
     * the next paint would land on top of the ink that was supposed to be gone.
     */
    @Test
    fun `the cursor stays in step with a program counting two columns per wide character`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 3)

        buffer.feed("中文")
        // Two characters, four columns: the shell's own count, and the count the repaint depends on.
        assertThat(buffer.snapshot().cursorColumn).isEqualTo(4)

        buffer.feed("\r$E[K$E[1G" + "done")

        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("done")
        assertThat(buffer.snapshot().cursorColumn).isEqualTo(4)
    }

    @Test
    fun `a wide character with no room left at the margin goes to the next row whole`() {
        val buffer = AnsiTerminalBuffer(columns = 5, rows = 3)

        buffer.feed("abcd中")

        // One column left and two needed: the pair moves down rather than being split, which is what
        // every terminal does and what keeps half a glyph off the last column.
        val frame = buffer.frame()
        assertThat(frame.lines[0].text()).isEqualTo("abcd")
        assertThat(frame.lines[1].text()).isEqualTo("中")
        assertThat(frame.lines[1][0].wide).isTrue()
    }

    @Test
    fun `a wide character filling the last two columns arms the wrap for the next one`() {
        val buffer = AnsiTerminalBuffer(columns = 4, rows = 3)

        buffer.feed("ab中")
        buffer.feed("c")

        val frame = buffer.frame()
        assertThat(frame.lines[0].text()).isEqualTo("ab中")
        assertThat(frame.lines[1].text()).isEqualTo("c")
    }

    @Test
    fun `overwriting a wide character's right half clears the whole character`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 3)

        buffer.feed("中文")
        // Column 4 is the right half of 文. Writing there cannot leave the left half drawn.
        buffer.feed("$E[1;4HX")

        val line = buffer.snapshot().lines[0]
        assertThat(line[2].value).isEqualTo(' ')
        assertThat(line[2].wide).isFalse()
        assertThat(line[3].value).isEqualTo('X')
        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("中 X")
    }

    @Test
    fun `overwriting a wide character's left half clears the whole character`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 3)

        buffer.feed("中文")
        buffer.feed("$E[1;3HX")

        val line = buffer.snapshot().lines[0]
        assertThat(line[2].value).isEqualTo('X')
        assertThat(line[3].value).isEqualTo(' ')
        // The character is gone and the X stands where its first column was, so the row reads as the
        // X abutting 中 rather than one column further right.
        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("中X")
    }

    @Test
    fun `an erase beginning on a wide character's right half clears the whole character`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 3)

        buffer.feed("中文")
        // Column 4 is the right half of 文, so EL from here blanks both of its cells and leaves no
        // half-glyph for the renderer to draw over the column it was told to clear.
        buffer.feed("$E[1;4H$E[0K")

        val line = buffer.snapshot().lines[0]
        assertThat(line.drop(2).none { it.wide }).isTrue()
        assertThat(line[0].wide).isTrue()
        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("中")
    }

    @Test
    fun `erasing a single cell inside a wide character clears both of its cells`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 3)

        buffer.feed("中文天")
        // ECH 1 at column 2 - the right half of 中 - is the narrowest erase there is.
        buffer.feed("$E[1;2H$E[1X")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].wide).isFalse()
        assertThat(line[1].wide).isFalse()
        assertThat(line[2].wide).isTrue()
        assertThat(line[4].wide).isTrue()
        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("  文天")
    }

    @Test
    fun `an insert of two cells slides a wide character along with its own right half`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 3)

        buffer.feed("ab中文")
        buffer.feed("$E[1;3H$E[2@")

        // Two blank cells at column 3: 中's pair moves right still adjacent, so nothing was cut and
        // the word is still a word.
        val line = buffer.snapshot().lines[0]
        assertThat(line[4].wide).isTrue()
        assertThat(line[5].wide).isFalse()
        assertThat(line[6].wide).isTrue()
        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("ab  中文")
    }

    /**
     * A one-cell insert is the case that looks like it should cut a pair and does not: both cells of
     * the character are pushed along together, so the pair is still a pair, one column to the right.
     * The rule only ever has to fire where a shift can separate them, which is what the tests around
     * this one are for.
     */
    @Test
    fun `a one-cell insert moves a wide character without breaking it`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 3)

        buffer.feed("ab中")
        buffer.feed("$E[1;3H$E[1@")

        val line = buffer.snapshot().lines[0]
        assertThat(line[3].wide).isTrue()
        assertThat(line[4].wide).isFalse()
        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("ab 中")
    }

    @Test
    fun `deleting a wide character's right half destroys it rather than leaving half a glyph`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 3)

        buffer.feed("中文")
        // Column 2 is the right half of 中. DCH takes that cell, the character after it slides into
        // the gap, and 中 is left with a neighbour where its own right half used to be.
        buffer.feed("$E[1;2H$E[1P")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].wide).isFalse()
        assertThat(line[1].wide).isTrue()
        assertThat(buffer.plainText().lineSequence().first()).isEqualTo(" 文")
    }

    /**
     * The case the rule has to catch rather than tolerate: a character slides into the column a wide
     * character's right half came from.
     *
     * 中 is drawn across two columns, so a letter standing in the second of them is drawn *under* the
     * glyph - ink on top of ink, which is what a user reports as text overlapping text. The character
     * has been cut in half by the delete, so it goes.
     */
    @Test
    fun `a character shifted into a wide character's right half destroys it`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 3)

        buffer.feed("中X文")
        buffer.feed("$E[1;2H$E[1P")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].wide).isFalse()
        assertThat(line[1].value).isEqualTo('X')
        // 中 has gone, X stands where its first column was, and 文 slid down one intact - its right
        // half is the blank the delete left behind.
        assertThat(line[2].wide).isTrue()
        assertThat(buffer.plainText().lineSequence().first()).isEqualTo(" X文")
    }

    @Test
    fun `a shrink that truncates a wide character's right half drops the whole character`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 6)

        buffer.feed("a".repeat(19) + "中")
        buffer.resize(20, 6)

        // The screen is exactly the new width, so 中's right half fell off its end. What is left is a
        // glyph drawn across the last column and the blank one beside it, so the character goes.
        val line = buffer.snapshot().lines[0]
        assertThat(line.size).isEqualTo(20)
        assertThat(line.none { it.wide }).isTrue()
        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("a".repeat(19))
    }

    /**
     * The right half is a column rather than a character, so a copy of the screen must not carry the
     * space that holds the glyph's place: `中 文` is not what the screen says.
     */
    @Test
    fun `copied text has no blank inside a wide character`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 3)

        buffer.feed("中文 x")

        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("中文 x")
        assertThat(buffer.textIn(0, 0, 0, 6)).isEqualTo("中文 x")
    }

    @Test
    fun `a combining mark after a wide character attaches to the character it follows`() {
        val buffer = AnsiTerminalBuffer(columns = 24, rows = 3)

        buffer.feed("中\u0301")

        // The cursor stands past the pair, so the mark's cell is the right half and the character it
        // belongs to is the one before it. A mark on the blank cell would draw an accent on its own.
        val line = buffer.snapshot().lines[0]
        assertThat(line[0].combining).isEqualTo("\u0301")
        assertThat(line[1].combining).isEmpty()
        assertThat(buffer.snapshot().cursorColumn).isEqualTo(2)
    }

    // --- Malformed input: every byte here is the server's word, and a terminal is fed by whatever
    // is on the other end of the socket. None of these may throw, hang, or grow without bound.

    /**
     * A negative parameter cannot move the cursor outside the buffer.
     *
     * This is the crash it was: a CSI sequence ends only at a byte in `'@'..'~'`, and "-" is not one,
     * so "-5" arrived as a parameter value of minus five. Every relative move then went the wrong
     * way past a bound that was only checked on the side the sequence was supposed to travel —
     * `ESC [ -5 A` is "up", so only the top was checked, and the cursor landed five rows below the
     * last line. The next printable character indexed the line list out of bounds, inside the
     * terminal output collector, where an exception reaches the default handler and kills the app.
     *
     * Each case is asserted with the cursor already parked at an extreme, because that is where the
     * off-by-a-buffer lands, and each is followed by a write so the index is actually used.
     */
    @Test
    fun `a negative csi parameter cannot push the cursor out of the buffer`() {
        val moves = listOf("A", "B", "C", "D", "E", "F", "G", "d", "H", "f", "`")
        val corners = listOf("$E[H", "$E[999;999H")

        corners.forEach { corner ->
            moves.forEach { move ->
                val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

                buffer.feed("$corner$E[-5$move" + "X")

                val snapshot = buffer.snapshot()
                assertWithMessage("$corner ESC[-5$move row")
                    .that(snapshot.cursorRow).isIn(0..snapshot.lines.lastIndex)
                assertWithMessage("$corner ESC[-5$move column")
                    .that(snapshot.cursorColumn).isIn(0..snapshot.columns)
                assertWithMessage("$corner ESC[-5$move printed").that(buffer.plainText()).contains("X")
            }
        }
    }

    /**
     * The same, for the sequences that repeat an edit rather than move.
     *
     * A clamped-to-zero parameter reads as "absent" here, which is what the sequences mean by their
     * own default of one — so each of these performs a single edit rather than none, and the point of
     * the test is that the edit stays inside the buffer and the terminal keeps printing.
     */
    @Test
    fun `a negative csi parameter cannot corrupt an edit sequence`() {
        listOf("P", "@", "L", "M").forEach { edit ->
            val buffer = AnsiTerminalBuffer(columns = 20, rows = 4, scrollbackLimit = 10)

            buffer.feed("hello$E[-5$edit" + "!")

            val snapshot = buffer.snapshot()
            assertWithMessage("ESC[-5$edit").that(snapshot.lines.size).isAtMost(14)
            assertWithMessage("ESC[-5$edit").that(snapshot.cursorRow).isIn(0..snapshot.lines.lastIndex)
            assertWithMessage("ESC[-5$edit").that(buffer.plainText()).contains("!")
        }
    }

    /**
     * An absurd repeat count finishes promptly.
     *
     * Every iteration past the width of the buffer changes nothing, but it was still executed: a
     * billion list splices under this object's monitor, which froze the session's output for minutes
     * and was indistinguishable from a dead connection. The timeout is the assertion; the clamp
     * turns it into four hundred operations.
     */
    @Test(timeout = 10_000)
    fun `an absurd repeat count is clamped instead of run`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4, scrollbackLimit = 10)

        buffer.feed("hello$E[2147483647P$E[2147483647@$E[2147483647L$E[2147483647M" + "done")

        assertThat(buffer.snapshot().lines.size).isAtMost(14)
        assertThat(buffer.plainText()).contains("done")
    }

    /**
     * An escape sequence that never ends is abandoned rather than accumulated.
     *
     * Digits and ';' do not terminate a CSI sequence, so a server stuck mid-sequence fed a
     * StringBuilder that nothing bounded — the process died of OutOfMemoryError given long enough.
     * Feeding far more than the cap and then a legitimate line proves both halves: the buffer let go,
     * and it went back to printing.
     */
    @Test(timeout = 10_000)
    fun `an unterminated csi sequence is abandoned so printing resumes`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("$E[" + "1;".repeat(50_000))
        buffer.feed("\nrecovered")

        assertThat(buffer.plainText()).contains("recovered")
    }

    /**
     * The same for OSC, where the failure was silence rather than memory.
     *
     * An OSC string ends at BEL or ST, and until one arrived every byte was swallowed. One stray
     * `ESC ]` in a corrupted stream therefore blanked the terminal for the rest of the session.
     */
    @Test(timeout = 10_000)
    fun `an unterminated osc sequence stops swallowing output`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("$E]0;" + "title ".repeat(10_000))
        buffer.feed("recovered")

        assertThat(buffer.plainText()).contains("recovered")
    }

    /**
     * Bounded by the same cap as CSI and OSC, and the tail of the run is printed as the text it turns
     * out to be.
     */
    @Test(timeout = 10_000)
    fun `an unterminated escape intermediate is abandoned so printing resumes`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("$E" + " ".repeat(500) + "X")

        assertThat(buffer.plainText()).endsWith("X")
    }

    /** A tab at the right margin stops there, rather than wrapping the next character. */
    @Test
    fun `a tab at the right margin does not force a line feed`() {
        val buffer = AnsiTerminalBuffer(columns = 8, rows = 3)

        buffer.feed("abcdefg\tX")

        assertThat(buffer.snapshot().cursorRow).isEqualTo(0)
        assertThat(buffer.plainText().lineSequence().first()).isEqualTo("abcdefgX")
    }

    // --- contentColumns: how far the view is allowed to pan ---

    @Test
    fun `contentColumns reports the widest painted line, not the terminal width`() {
        val buffer = AnsiTerminalBuffer(columns = 80, rows = 5)
        // Trailing CR parks the cursor at column 0, so this measures the glyphs and nothing else.
        buffer.feed("short\r\n" + "x".repeat(64) + "\r")

        val frame = buffer.frame()

        assertThat(frame.columns).isEqualTo(80)
        assertThat(frame.contentColumns).isEqualTo(64)
    }

    @Test
    fun `trailing blanks are not content, but the cursor cell is`() {
        val buffer = AnsiTerminalBuffer(columns = 80, rows = 5)

        // An untouched screen is 80 blank cells per row and paints one of them: the caret. It has to
        // be inside the pannable extent or typing at the right margin would go on off-screen.
        assertThat(buffer.frame().contentColumns).isEqualTo(1)

        buffer.feed("ok")
        // Two glyphs plus the caret on the third - not the 80 the row is allocated at.
        assertThat(buffer.frame().contentColumns).isEqualTo(3)

        buffer.feed("$E[2J$E[H")
        // Erased: written once, blank now, and back to nothing but the caret.
        assertThat(buffer.frame().contentColumns).isEqualTo(1)
    }

    @Test
    fun `a blank cell with a background is painted and counts`() {
        val buffer = AnsiTerminalBuffer(columns = 80, rows = 5)

        // What a selection, a highlighted `less` match or a full-width status bar looks like: spaces
        // that are visible only because of their background, and clipping them would be visible too.
        buffer.feed("$E[44m" + " ".repeat(40) + "$E[0m")

        assertThat(buffer.frame().contentColumns).isAtLeast(40)
    }

    @Test
    fun `the cursor is reachable even when it sits past the last printed cell`() {
        val buffer = AnsiTerminalBuffer(columns = 200, rows = 5)

        // Cursor addressed to column 151 of an otherwise blank row - a full-screen editor placing its
        // caret. Typing there must not be off-screen, so the extent has to include it.
        buffer.feed("$E[1;151H")

        assertThat(buffer.frame().contentColumns).isEqualTo(151)
    }

    @Test
    fun `history printed wide stays pannable after the terminal narrows`() {
        val buffer = AnsiTerminalBuffer(columns = 120, rows = 5)
        buffer.feed("a".repeat(110) + "\r\n" + (1..8).joinToString("\r\n") { "line $it" })

        buffer.resize(45, 5)

        // That line is history now. The screen is 45 columns wide, but a frame that includes the line
        // reports the 110 columns the view has to be able to reach.
        val whole = buffer.frame(scrollOffset = 0, viewportRows = buffer.snapshot().lines.size)
        assertThat(whole.columns).isEqualTo(45)
        assertThat(whole.contentColumns).isEqualTo(110)
    }

    // ------------------------------------------------------------ the programs people actually run
    //
    // The tests above pin each sequence on its own. These replay what `top`, `vim` and `less` really
    // send, because every one of them is a *combination* whose parts can each be right while the whole
    // is wrong - and because they are the programs whose breakage is unmissable: a terminal that cannot
    // run `vim` is not a terminal.

    /**
     * A program that repaints in place does not grow the scrollback.
     *
     * `top` and `htop` address the screen: home, then each row erased and rewritten, once a second,
     * for as long as the user watches. Nothing is appended, so nothing may accumulate. The failure this
     * guards is not a wrong character but a leak - a terminal that treats each repainted row as new
     * output fills its 2 000-line scrollback in half a minute, and from then on holds two thousand
     * stale copies of one screen in memory while the user's actual session history is gone.
     */
    @Test
    fun `a program repainting in place does not grow the scrollback`() {
        val buffer = AnsiTerminalBuffer(columns = 80, rows = 10)

        repeat(120) { tick ->
            // Cursor addressed per row and the row erased first, which is exactly what `top` does and
            // why its display never scrolls.
            (1..10).forEach { row -> buffer.feed("$E[$row;1H$E[K" + "tick $tick row $row") }
        }

        val lines = buffer.snapshot().lines
        // Ten rows after two minutes of repainting, not 1 200 and not the 2 000 cap.
        assertThat(lines.size).isEqualTo(10)
        assertThat(lines.first().text()).isEqualTo("tick 119 row 1")
        assertThat(lines.last().text()).isEqualTo("tick 119 row 10")
    }

    /**
     * `vim` leaves the shell exactly where it found it.
     *
     * The alternate screen is a promise: whatever the editor draws is thrown away when it exits, and
     * the scrollback the user had is untouched underneath. Both halves matter and they fail
     * separately - drawing onto the primary screen destroys the session history, and restoring the
     * lines but not the cursor leaves the next prompt printed over the last one.
     *
     * The sequence is the real one: 1049 up, the cursor hidden, a full-screen paint including a tilde
     * column and a reverse-video status line, then 1049 down.
     */
    @Test
    fun `an editor on the alternate screen leaves the shell untouched underneath`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 6)
        buffer.feed((1..8).joinToString("") { "history $it\r\n" } + "user@host:~$ vim notes.txt")
        val before = buffer.snapshot()
        val historyBefore = before.lines.map { it.text() }
        val cursorBefore = before.cursorRow to before.cursorColumn

        buffer.feed("$E[?1049h$E[?25l$E[H$E[2J")
        buffer.feed("the quick brown fox\r\n" + (2..5).joinToString("") { "~\r\n" })
        buffer.feed("$E[6;1H$E[7m\"notes.txt\" 1L, 20C$E[27m")
        // While it is up, the editor's paint is what is on screen and none of it is history: the
        // alternate screen is a fixed window of exactly `rows`.
        val editing = buffer.snapshot()
        assertThat(buffer.frame().alternateScreen).isTrue()
        assertThat(editing.lines.size).isEqualTo(6)
        assertThat(editing.lines.first().text()).isEqualTo("the quick brown fox")

        buffer.feed("$E[?1049l$E[?25h")

        val after = buffer.snapshot()
        assertThat(buffer.frame().alternateScreen).isFalse()
        assertWithMessage("the editor's paint reached the session history")
            .that(after.lines.map { it.text() })
            .isEqualTo(historyBefore)
        assertWithMessage("the next prompt would print over the last one")
            .that(after.cursorRow to after.cursorColumn)
            .isEqualTo(cursorBefore)
        // Hidden by the editor and given back on the way out; a terminal that kept it hidden would
        // leave the user typing at a prompt with no caret.
        assertThat(buffer.frame().cursorVisible).isTrue()
    }

    /**
     * `less` scrolls its text without disturbing the status line it keeps at the bottom.
     *
     * A pager sets a scroll region over everything but the last row and then scrolls *inside* it: index
     * at the bottom to go forward, reverse index at the top to go back. A terminal that ignores DECSTBM
     * scrolls the whole screen instead, which drags the status line up into the text and leaves the
     * bottom row blank - the pager then repaints a status line on top of a line of the file.
     */
    @Test
    fun `a pager scrolls inside its region and keeps its status line`() {
        val buffer = AnsiTerminalBuffer(columns = 30, rows = 5)
        buffer.feed("$E[?1049h$E[H$E[2J")
        buffer.feed((1..4).joinToString("") { "file line $it\r\n" })
        buffer.feed("$E[5;1H$E[7m:$E[27m")
        // Rows 1-4 scroll; row 5 is the pager's own.
        buffer.feed("$E[1;4r")

        // Forward one line: park at the bottom of the region and index.
        buffer.feed("$E[4;1H" + "$E" + "D" + "file line 5")
        var lines = buffer.snapshot().lines
        assertThat(lines.map { it.text() }.take(4))
            .isEqualTo(listOf("file line 2", "file line 3", "file line 4", "file line 5"))
        assertWithMessage("the status line was dragged out of place")
            .that(lines[4].text())
            .isEqualTo(":")

        // And back again: reverse index at the top of the region.
        buffer.feed("$E[1;1H" + "$E" + "M" + "file line 1")
        lines = buffer.snapshot().lines
        assertThat(lines.map { it.text() }.take(4))
            .isEqualTo(listOf("file line 1", "file line 2", "file line 3", "file line 4"))
        assertThat(lines[4].text()).isEqualTo(":")
        // Nothing a pager displays is session output, so nothing it scrolled past is in the history.
        assertThat(lines.size).isEqualTo(5)
    }

    /**
     * A long line survives a repaint that redraws only part of it.
     *
     * The report behind this was output "cut in the middle of a hostname or a hash". One way that
     * happens without any wrapping bug: a program rewrites a row with a shorter string and does not
     * erase to the end, so the tail of the old line stays on screen and reads as a mangled version of
     * the new one. The rule being pinned is that the emulator changes exactly the cells it was told to
     * and neither pads nor truncates the rest - so `\r` alone leaves the tail, and `$E[K` removes it.
     */
    @Test
    fun `a rewrite without an erase leaves the tail, and with one does not`() {
        val hash = "9f2b" + "c".repeat(56) + "a1d4"
        val buffer = AnsiTerminalBuffer(columns = 80, rows = 4)

        buffer.feed(hash + "\r" + "short")
        assertWithMessage("the emulator invented an erase the server never sent")
            .that(buffer.snapshot().lines.first().text())
            .isEqualTo("short" + hash.substring(5))

        buffer.feed("\r$E[K" + "short")
        assertThat(buffer.snapshot().lines.first().text()).isEqualTo("short")
    }

    // --- Zero-width characters: the cursor has to stay in the program's coordinate system ---

    /**
     * The invariant the whole change is about: after every chunk, this buffer's column is the column the
     * program on the other end of the pty believes the cursor is on.
     *
     * That agreement is the only thing that makes an erase land where the program meant it to. Every
     * relative move the program sends - `ESC [ C`, `ESC [ D`, and the `ESC [ K` that redraws a line - is
     * measured from a column it counted itself, so a column this buffer counted differently is a write or
     * an erase one cell away from where it was aimed, silently: a wrong column is a legal column.
     *
     * The expected values are **counted by hand** rather than computed with [terminalCharWidth], which
     * would make the test agree with the code under test for free. They are what the far side's `wcwidth`
     * gives. None of them reaches the sixtieth column, so no wrap intervenes and the arithmetic stays
     * additive.
     */
    @Test
    fun `the cursor column is the column the program counted`() {
        val buffer = AnsiTerminalBuffer(columns = 60, rows = 5)
        // An escape sequence in a failure message would be invisible and a surrogate half unprintable, so
        // the chunk is named the way the source spells it.
        fun shown(chunk: String): String = chunk.replace(E, "<ESC>").map { char ->
            if (char.code in 0xD800..0xDFFF) "\\u%04X".format(char.code) else char.toString()
        }.joinToString("")

        val stream = listOf(
            "deploy " to 7,            // seven columns
            "\u2764\uFE0F" to 8,       // a heart is one column; the variation selector after it is none
            " done" to 13,             // five more
            "\r" to 0,                 // an absolute move, which re-syncs both sides
            "cafe\u0301 " to 5,        // four letters, an accent that takes no column, then a space
            "$E[32mok$E[0m" to 7,      // styling moves no column
            "\uD83D\uDE00" to 9,       // an emoji: two code units, two columns, which is what the far side counts
            "\uDB40\uDD01" to 9,       // a variation selector from the supplement: two code units, no column
            " fin" to 13,              // four more
        )

        stream.forEach { (chunk, expected) ->
            buffer.feed(chunk)
            assertWithMessage("after chunk %s", shown(chunk))
                .that(buffer.frame().cursorColumn)
                .isEqualTo(expected)
        }
    }

    /**
     * The reported bug at the buffer level: a mark must not move the text that follows it.
     *
     * A program that rewrites a line in place - a prompt, a progress bar, a spinner - prints the new text
     * and then erases to the end of the line, or erases and then prints. Both depend on the erase starting
     * where the program thinks the cursor is. Let a mark spend a cell of its own and this buffer's cursor
     * is one cell too far right, so the erase starts one cell too far right, and the cell the program
     * meant to clear is still standing when the new text lands beside it.
     *
     * Asserted on the cells, not on [AnsiTerminalBuffer.plainText]: the two differ only in which cell
     * holds the mark, and the whole defect is that the text after it sits on the wrong ones.
     */
    @Test
    fun `a mark in a rewritten line leaves nothing standing to its left`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("abcdefgh\r")   // an older, longer line
        buffer.feed("x\uFE0Fy")     // the new one, with a mark in it
        buffer.feed("$E[K")         // erase from where the program's cursor is
        buffer.feed("z")

        val line = buffer.snapshot().lines.first()
        assertThat(line[0].value).isEqualTo('x')
        assertThat(line[0].combining).isEqualTo("\uFE0F")
        assertWithMessage("the mark spent a cell, so the rest of the line moved right of where it belongs")
            .that(line[1].value)
            .isEqualTo('y')
        assertThat(line[2].value).isEqualTo('z')
        // None of the old line survives, and neither does anything the erase covered.
        assertThat(line.drop(3).map { it.value }.joinToString("").trimEnd()).isEmpty()
        // The two sides agree on where the cursor ended, which is what made the erase land correctly.
        assertThat(buffer.frame().cursorColumn).isEqualTo(3)
    }

    /**
     * The same statement for each way a zero-width character actually reaches a shell.
     *
     * A variation selector from an emoji in a prompt, a zero-width joiner from a composed one, a
     * byte-order mark at the head of a file `cat` is reading, and a combining accent from a name
     * written on a system that decomposes them. All four have to leave the rest of the line on the cells
     * the program put it on.
     */
    @Test
    fun `every kind of zero-width character leaves the line where the program put it`() {
        listOf("\uFE0F", "\u200D", "\uFEFF", "\u0301").forEach { mark ->
            val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

            buffer.feed("abcdefgh\r" + "x" + mark + "y" + "$E[K" + "z")

            val line = buffer.snapshot().lines.first()
            assertWithMessage("U+%04X".format(mark[0].code)).that(line[0].combining).isEqualTo(mark)
            assertWithMessage("U+%04X".format(mark[0].code)).that(line[1].value).isEqualTo('y')
            assertWithMessage("U+%04X".format(mark[0].code)).that(line[2].value).isEqualTo('z')
        }
    }

    @Test
    fun `a mark sits on the cell before it and spends none of its own`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)

        buffer.feed("e\u0301")

        val line = buffer.snapshot().lines.first()
        assertThat(line[0].value).isEqualTo('e')
        assertThat(line[0].combining).isEqualTo("\u0301")
        assertThat(line[1].value).isEqualTo(' ')
        assertThat(buffer.frame().cursorColumn).isEqualTo(1)
    }

    /**
     * The ordering trap, and the reason the width test comes before the pending wrap in `putCodePoint`.
     *
     * When a character fills the last column the wrap is armed but not taken: the cursor is still standing
     * on the cell that character is on, and it is that cell a mark arriving next belongs to. A mark that
     * consumed the wrap instead would break the line where the program did not break it, and would put the
     * mark itself on the row below.
     */
    @Test
    fun `a mark after the last column belongs to the character that armed the wrap`() {
        val buffer = AnsiTerminalBuffer(columns = 4, rows = 3)

        buffer.feed("abcd\u0301")

        val first = buffer.snapshot().lines[0]
        assertWithMessage("the mark was carried onto the next row")
            .that(first[3].combining)
            .isEqualTo("\u0301")
        assertThat(first.map { it.value }.joinToString("")).isEqualTo("abcd")

        // The next character takes the wrap exactly as it would have without the mark.
        buffer.feed("e")
        assertThat(buffer.snapshot().lines[1][0].value).isEqualTo('e')
    }

    /**
     * A mark with nothing under it is dropped, in both of the ways that happens.
     *
     * At column zero there is no cell to its left at all; on a cell the program filled with a space there
     * is no character for it to belong to. Dropping it costs nothing in the column arithmetic, which is
     * the point of the change - and inventing a home for it would put the accent on whatever happened to
     * be there, a row up or a whole screen away.
     */
    @Test
    fun `a mark with nothing under it is dropped, not carried`() {
        val atColumnZero = AnsiTerminalBuffer(columns = 10, rows = 3)
        atColumnZero.feed("\u0301")
        assertThat(atColumnZero.snapshot().lines.first()[0].combining).isEmpty()
        assertThat(atColumnZero.frame().cursorColumn).isEqualTo(0)

        val onABlank = AnsiTerminalBuffer(columns = 10, rows = 3)
        onABlank.feed(" \u0301")
        assertThat(onABlank.snapshot().lines.first()[0].combining).isEmpty()
        assertThat(onABlank.frame().cursorColumn).isEqualTo(1)
    }

    /**
     * The two readings of the same line, and the reason [terminalCellText] exists.
     *
     * [AnsiTerminalBuffer.plainText] is what a person reads and what the clipboard gets, so a cell whose
     * character is two code points contributes both. A *column* indexes the other string - the one with
     * one character per cell - which is what long-press selection is handed. The last assertion is the
     * one that makes the conversion exact rather than approximate: the two agree on how many cells the
     * line occupies.
     */
    @Test
    fun `plainText keeps a mark with its character, and terminalCellText splits the two`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("cafe\u0301 x")

        assertThat(buffer.plainText()).isEqualTo("cafe\u0301 x")
        assertThat(terminalCellText(buffer.plainText())).isEqualTo("cafe x")
        assertThat(terminalCellText(buffer.plainText()).length)
            .isEqualTo(terminalPaintedWidth(buffer.snapshot().lines.first()))
    }

    @Test
    fun `a copy keeps the mark, and CSI b repeats the base`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("e\u0301x")
        assertThat(buffer.textIn(0, 0, 0, 3)).isEqualTo("e\u0301x")

        // REP repeats "the last character printed". A mark is part of that character and not one of its
        // own, so what repeats is the base: repeating the accent would be a new way to be wrong.
        val repeated = AnsiTerminalBuffer(columns = 20, rows = 3)
        repeated.feed("e\u0301$E[3b")

        val line = repeated.snapshot().lines.first()
        assertThat(line.take(4).map { it.value }.joinToString("")).isEqualTo("eeee")
        assertThat(line[0].combining).isEqualTo("\u0301")
        assertThat(line.drop(1).map { it.combining }.joinToString("")).isEmpty()
    }

    /**
     * A pair split across two reads still draws one emoji, because both halves take a cell either way.
     *
     * The pty hands a session arbitrary chunks, so a surrogate pair can arrive in halves. The width walk
     * only joins a pair it can see whole, and on its own each half is one character wide - which is
     * exactly what the joined path spends for the two of them.
     */
    @Test
    fun `a surrogate pair split across two reads still draws the same cells`() {
        val joined = AnsiTerminalBuffer(columns = 10, rows = 3)
        val split = AnsiTerminalBuffer(columns = 10, rows = 3)

        joined.feed("\uD83D\uDE00")
        split.feed("\uD83D")
        split.feed("\uDE00")

        assertThat(split.snapshot().lines.first().map { it.value })
            .isEqualTo(joined.snapshot().lines.first().map { it.value })
        assertThat(split.frame().cursorColumn).isEqualTo(2)
    }

    /**
     * The one case the change cannot reach, pinned so that it is known rather than discovered.
     *
     * U+E0101 is a mark, but when its two code units arrive in separate reads neither one says so: a lone
     * high surrogate is a character like any other, and there is nothing in it that announces a mark. The
     * joined form spends no column; the split one spends two. Recognising it would mean holding a high
     * surrogate until the next read arrives, which is state this printer does not keep - and it is the
     * same shape as a partial escape sequence, which the parser does keep state for.
     *
     * Held here rather than fixed, and the assertion is on the limitation itself: giving a lone surrogate
     * width zero would be wrong for every non-mark astral character, which is the far more common case.
     */
    @Test
    fun `a mark whose pair is split across reads cannot be recognised, and takes a cell`() {
        val joined = AnsiTerminalBuffer(columns = 10, rows = 3)
        val split = AnsiTerminalBuffer(columns = 10, rows = 3)

        joined.feed("x\uDB40\uDD01")
        split.feed("x\uDB40")
        split.feed("\uDD01")

        assertThat(joined.frame().cursorColumn).isEqualTo(1)
        assertThat(split.frame().cursorColumn).isEqualTo(3)
    }
}
