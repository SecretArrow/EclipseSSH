package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

/** ESC, built from its code point so this file contains no literal control byte. */
private val ESCAPE: String = Char(0x1B).toString()

private fun chars(vararg codes: Int): String = codes.map { Char(it) }.joinToString("")

/**
 * The line typed in the two wrapping tests, character for character as it was typed into a real pty.
 *
 * Long enough to overflow both the 46-column pty and a 20-column view, and made of ordinary words so
 * that a break has somewhere to land other than inside a token.
 */
private const val LONG_COMMAND =
    "echo hello world this is a fairly long command line with several words in it"

/**
 * The capabilities that separate a terminal from a log view: the alternate screen, scroll regions,
 * the modes a full-screen program negotiates before it draws, the replies it waits for, and the
 * viewport the renderer is actually handed.
 *
 * Kept apart from [AnsiTerminalBufferTest], which pins parsing, clamping and the memory bounds. The
 * split is by question rather than by size: everything here is about a program *driving* the
 * terminal, and every case is one that made the difference between `htop` working and `htop`
 * scribbling over the user's history.
 */
class AnsiTerminalFullScreenTest {
    private fun lines(buffer: AnsiTerminalBuffer): List<String> =
        buffer.snapshot().lines.map { line -> line.joinToString("") { it.value.toString() }.trimEnd() }

    private fun lines(frame: TerminalFrame): List<String> =
        frame.lines.map { line -> line.joinToString("") { it.value.toString() }.trimEnd() }

    // --- The alternate screen: what keeps vim, less and htop out of the scrollback ---

    @Test
    fun `a full screen program leaves the shell history exactly as it found it`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4, scrollbackLimit = 20)
        buffer.feed("shell one\nshell two\n")

        // What vim sends on startup, then what it draws.
        buffer.feed("${ESCAPE}[?1049h${ESCAPE}[2J${ESCAPE}[HEDITOR")

        assertThat(buffer.plainText()).isEqualTo("EDITOR")

        // And what it sends on exit.
        buffer.feed("${ESCAPE}[?1049l")

        assertThat(buffer.plainText()).contains("shell one")
        assertThat(buffer.plainText()).contains("shell two")
        assertThat(buffer.plainText()).doesNotContain("EDITOR")
    }

    /**
     * A redraw is not history.
     *
     * Without a separate screen every frame `htop` painted was pushed into the scrollback, so a user
     * who quit it was left scrolling through a few hundred half-overwritten copies of its display
     * instead of the commands they had actually run.
     */
    @Test
    fun `the alternate screen never grows the scrollback`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 100)
        buffer.feed("${ESCAPE}[?1049h")

        repeat(50) { buffer.feed("frame $it\n") }

        assertThat(buffer.lineCount()).isEqualTo(4)
        assertThat(buffer.frame().alternateScreen).isTrue()
    }

    @Test
    fun `the older 47 and 1047 screen switches are honoured too`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3, scrollbackLimit = 20)
        buffer.feed("primary\n")

        buffer.feed("${ESCAPE}[?47halt")
        assertThat(buffer.plainText()).isEqualTo("alt")

        buffer.feed("${ESCAPE}[?47l")
        assertThat(buffer.plainText()).isEqualTo("primary")
    }

    @Test
    fun `a rotation while a full screen program is open resizes the shell underneath it`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 6)
        buffer.feed("a shell line that is quite long here\n")
        buffer.feed("${ESCAPE}[?1049h")

        buffer.resize(20, 4)
        buffer.feed("${ESCAPE}[?1049l")

        val snapshot = buffer.snapshot()
        assertThat(snapshot.columns).isEqualTo(20)
        assertThat(snapshot.lines.all { it.size == 20 }).isTrue()
    }

    // --- Scroll regions: DECSTBM, which is how less and tmux hold a status line still ---

    @Test
    fun `a scroll region scrolls only its own rows`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 5, scrollbackLimit = 20)
        buffer.feed("${ESCAPE}[1;1Htop${ESCAPE}[2;1Ha${ESCAPE}[3;1Hb${ESCAPE}[4;1Hc${ESCAPE}[5;1Hbottom")

        buffer.feed("${ESCAPE}[2;4r")
        buffer.feed("${ESCAPE}[4;1H\n")

        // "a" left the region at the top; "top" and "bottom" never moved.
        assertThat(lines(buffer)).containsExactly("top", "b", "c", "", "bottom").inOrder()
        // And nothing was pushed into the history: a region scroll discards, it does not archive.
        assertThat(buffer.lineCount()).isEqualTo(5)
    }

    @Test
    fun `a reverse index at the top of a region scrolls it back down`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4)
        buffer.feed("${ESCAPE}[1;1Hone${ESCAPE}[2;1Htwo${ESCAPE}[3;1Hthree")

        buffer.feed("${ESCAPE}[1;3r${ESCAPE}[1;1H${ESCAPE}M")

        assertThat(lines(buffer)).containsExactly("", "one", "two", "").inOrder()
    }

    @Test
    fun `an inverted or degenerate region falls back to the whole screen`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 10)

        buffer.feed("${ESCAPE}[4;2r")
        repeat(8) { buffer.feed("row $it\n") }

        // A full-screen region archives, so the history grew rather than the rows being discarded.
        assertThat(buffer.lineCount()).isGreaterThan(4)
        assertThat(buffer.plainText()).contains("row 7")
    }

    // --- Wrapping ---

    @Test
    fun `autowrap off keeps overwriting the last column`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)

        buffer.feed("${ESCAPE}[?7l0123456789ABC")

        assertThat(buffer.plainText()).isEqualTo("012345678C")
        assertThat(buffer.snapshot().cursorRow).isEqualTo(0)
    }

    @Test
    fun `a line exactly as wide as the screen does not consume the next row early`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4)

        buffer.feed("0123456789\nnext")

        assertThat(buffer.plainText()).isEqualTo("0123456789\nnext")
        // The cursor stayed on a cell that exists, which is what lets the caret be drawn at all.
        assertThat(buffer.snapshot().cursorColumn).isAtMost(9)
    }

    // --- Keyboard modes, which decide what bytes a key press has to send ---

    @Test
    fun `application cursor key mode is reported to the key mapper`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        assertThat(buffer.frame().applicationCursorKeys).isFalse()

        buffer.feed("${ESCAPE}[?1h")
        assertThat(buffer.frame().applicationCursorKeys).isTrue()

        buffer.feed("${ESCAPE}[?1l")
        assertThat(buffer.frame().applicationCursorKeys).isFalse()
    }

    @Test
    fun `the application keypad switch is tracked`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("${ESCAPE}=")
        assertThat(buffer.frame().applicationKeypad).isTrue()

        buffer.feed("${ESCAPE}>")
        assertThat(buffer.frame().applicationKeypad).isFalse()
    }

    /**
     * Bracketed paste is a safety feature, not a nicety: without it a pasted block containing a
     * newline is executed line by line the moment it lands, with no chance to read it first.
     */
    @Test
    fun `bracketed paste mode is reported so a paste can be wrapped`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        assertThat(buffer.bracketedPasteEnabled()).isFalse()

        buffer.feed("${ESCAPE}[?2004h")
        assertThat(buffer.bracketedPasteEnabled()).isTrue()
        assertThat(buffer.frame().bracketedPaste).isTrue()

        buffer.feed("${ESCAPE}[?2004l")
        assertThat(buffer.bracketedPasteEnabled()).isFalse()
    }

    @Test
    fun `cursor visibility follows DECTCEM`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        assertThat(buffer.frame().cursorVisible).isTrue()

        buffer.feed("${ESCAPE}[?25l")
        assertThat(buffer.frame().cursorVisible).isFalse()

        buffer.feed("${ESCAPE}[?25h")
        assertThat(buffer.frame().cursorVisible).isTrue()
    }

    // --- Replies: a terminal that never answers is a terminal that hangs the shell ---

    @Test
    fun `a cursor position request is answered with the cursor position`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 5)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[3;7H${ESCAPE}[6n")

        assertThat(replies).containsExactly("${ESCAPE}[3;7R")
    }

    @Test
    fun `device attributes and device status are answered`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[c${ESCAPE}[5n${ESCAPE}[>c")

        assertThat(replies).containsExactly("${ESCAPE}[?1;2c", "${ESCAPE}[0n", "${ESCAPE}[>0;95;0c").inOrder()
    }

    @Test
    fun `ordinary output asks for no reply at all`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        var calls = 0
        buffer.responder = { calls++ }

        buffer.feed("${ESCAPE}[31mjust some coloured text${ESCAPE}[0m\n")

        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `a reply is dispatched only after the input it answers has been applied`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        var seen: String? = null
        buffer.responder = { seen = buffer.plainText() }

        buffer.feed("prompt${ESCAPE}[6n")

        assertThat(seen).isEqualTo("prompt")
    }

    /**
     * The reply must not be written while the buffer's monitor is held.
     *
     * A real responder writes to the SSH channel, which blocks. If that happened inside the parse,
     * the thread draining that same channel could not feed or render while it blocked - the write
     * waits for the reader and the reader waits for the monitor, and the session freezes with no
     * error anywhere. The timeout is the assertion.
     */
    @Test(timeout = 10_000)
    fun `a reply does not hold the buffer lock while it is written`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val readerFinished = CountDownLatch(1)
        buffer.responder = {
            Thread {
                buffer.snapshot()
                readerFinished.countDown()
            }.start()
            readerFinished.await(5, TimeUnit.SECONDS)
        }

        buffer.feed("${ESCAPE}[6n")

        assertThat(readerFinished.count).isEqualTo(0L)
    }

    // --- The two probes a program uses to find out what it may send ---

    @Test
    fun `xtversion is answered with this emulator's own name`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        // Both spellings a program uses: Claude Code writes `CSI > 0 q`, xterm's own docs say
        // `CSI > Ps q` with the parameter optional.
        buffer.feed("${ESCAPE}[>0q${ESCAPE}[>q")

        assertThat(replies).containsExactly(
            "${ESCAPE}P>|$TERMINAL_IDENTITY${ESCAPE}\\",
            "${ESCAPE}P>|$TERMINAL_IDENTITY${ESCAPE}\\",
        )
    }

    @Test
    fun `the q forms that are not xtversion are left unanswered`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        // DECLL and a secondary request with a parameter are not XTVERSION, and answering either with
        // a version string would put a DCS into a stream the program reads as something else.
        buffer.feed("${ESCAPE}[5q${ESCAPE}[>5q${ESCAPE}[q")

        assertThat(replies).isEmpty()
    }

    @Test
    fun `decrqm reports a mode that is on and one that is off`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[?25${'$'}p")
        buffer.feed("${ESCAPE}[?25l${ESCAPE}[?25${'$'}p")

        assertThat(replies).containsExactly("${ESCAPE}[?25;1${'$'}y", "${ESCAPE}[?25;2${'$'}y").inOrder()
    }

    @Test
    fun `decrqm reports a mode this terminal does not implement as unrecognized`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        // 1048 saves a cursor and is not a switch; 7727 is a mode nothing here has heard of. Both
        // answer 0, so a program falls back rather than being told a feature it asked for is there.
        // a program falls back rather than being told a feature it asked for is there.
        buffer.feed("${ESCAPE}[?1048${'$'}p${ESCAPE}[?7727${'$'}p")

        assertThat(replies).containsExactly(
            "${ESCAPE}[?1048;0${'$'}y",
            "${ESCAPE}[?7727;0${'$'}y",
        ).inOrder()
    }

    @Test
    fun `decrqm reports synchronized output as the state it is actually in`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        // 2026 is what the app under test asks about before wrapping a repaint in a batch. Answered
        // from the latch rather than claimed: telling it 1 is what makes it go on to use the mode.
        buffer.feed("${ESCAPE}[?2026${'$'}p${ESCAPE}[?2026h${ESCAPE}[?2026${'$'}p${ESCAPE}[?2026l${ESCAPE}[?2026${'$'}p")

        assertThat(replies).containsExactly(
            "${ESCAPE}[?2026;2${'$'}y",
            "${ESCAPE}[?2026;1${'$'}y",
            "${ESCAPE}[?2026;2${'$'}y",
        ).inOrder()
    }

    @Test
    fun `decrqm follows the mode a program sets`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[?1000h${ESCAPE}[?1000${'$'}p${ESCAPE}[?1000l${ESCAPE}[?1000${'$'}p")

        assertThat(replies).containsExactly("${ESCAPE}[?1000;1${'$'}y", "${ESCAPE}[?1000;2${'$'}y").inOrder()
    }

    @Test
    fun `decrqm answers an ansi mode without the private marker`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        // 4 is IRM, the one ANSI mode implemented here; 20 is LNM, which the display has no say in.
        buffer.feed("${ESCAPE}[4h${ESCAPE}[4${'$'}p${ESCAPE}[20${'$'}p")

        assertThat(replies).containsExactly("${ESCAPE}[4;1${'$'}y", "${ESCAPE}[20;0${'$'}y").inOrder()
    }

    @Test
    fun `decrqm reports the three alternate screen modes as the one state they share`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[?47${'$'}p${ESCAPE}[?1049h${ESCAPE}[?47${'$'}p${ESCAPE}[?1047${'$'}p${ESCAPE}[?1049${'$'}p")

        assertThat(replies).containsExactly(
            "${ESCAPE}[?47;2${'$'}y",
            "${ESCAPE}[?47;1${'$'}y",
            "${ESCAPE}[?1047;1${'$'}y",
            "${ESCAPE}[?1049;1${'$'}y",
        ).inOrder()
    }

    @Test
    fun `decrqm answers every mode it is asked about in one sequence`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[?25;1006;2026${'$'}p")

        assertThat(replies).containsExactly(
            "${ESCAPE}[?25;1${'$'}y",
            "${ESCAPE}[?1006;2${'$'}y",
            "${ESCAPE}[?2026;2${'$'}y",
        ).inOrder()
    }

    @Test
    fun `decrqm asked with no mode number answers for mode zero`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[?${'$'}p")

        assertThat(replies).containsExactly("${ESCAPE}[?0;0${'$'}y")
    }

    @Test
    fun `a dollar with nothing after it is not decrqm either`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        // `$` leads the soft-reset and request-mode families, so this parses as one of those with no
        // body at all rather than as a DECRQM. Nothing is answered, and nothing is drawn.
        buffer.feed("${ESCAPE}[${'$'}p")

        assertThat(replies).isEmpty()
    }

    @Test
    fun `a soft reset is still a reset and not a mode report`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }
        buffer.feed("${ESCAPE}[?25l")

        buffer.feed("${ESCAPE}[!p")

        assertThat(replies).isEmpty()
        assertThat(buffer.frame().cursorVisible).isTrue()
    }

    @Test
    fun `neither probe moves anything on the screen`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        buffer.responder = { }
        buffer.feed("abc${ESCAPE}[2;2H")
        val before = buffer.frame()

        buffer.feed("${ESCAPE}[>0q${ESCAPE}[?2026${'$'}p${ESCAPE}[?25${'$'}p")

        val after = buffer.frame()
        assertThat(after.cursorRow).isEqualTo(before.cursorRow)
        assertThat(after.cursorColumn).isEqualTo(before.cursorColumn)
        assertThat(buffer.plainText()).isEqualTo("abc")
    }

    // --- The size, title and colour questions, and the requests that go with them ---

    /** The dark theme's colours, which are the ones these tests hand the buffer. */
    private fun darkTheme() = TerminalColors(0xFFB3C1D9.toInt(), 0xFF090D16.toInt())

    @Test
    fun `xtwinops reports the text area size`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[18t")

        assertThat(replies).containsExactly("${ESCAPE}[8;4;20t")
    }

    @Test
    fun `xtwinops reports the screen size separately from the text area`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[19t")

        assertThat(replies).containsExactly("${ESCAPE}[9;4;20t")
    }

    @Test
    fun `a size report is of the size the terminal has now`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.resize(30, 6)
        buffer.feed("${ESCAPE}[18t")

        assertThat(replies).containsExactly("${ESCAPE}[8;6;30t")
    }

    @Test
    fun `a resize request is refused and the terminal keeps its size`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        // Every operation that asks this terminal to act on the user's window, plus the reports whose
        // answers would have to be invented. The screen is the user's, and the pty was sized to match
        // it, so a program asking for 80 columns here must not get them.
        buffer.feed(
            "${ESCAPE}[1t${ESCAPE}[2t${ESCAPE}[3;0;0t${ESCAPE}[4;400;800t${ESCAPE}[5t${ESCAPE}[6t" +
                "${ESCAPE}[7t${ESCAPE}[8;24;80t${ESCAPE}[9t${ESCAPE}[11t${ESCAPE}[13t${ESCAPE}[14t" +
                "${ESCAPE}[16t${ESCAPE}[20t${ESCAPE}[24t",
        )
        buffer.feed("${ESCAPE}[18t")

        assertThat(replies).containsExactly("${ESCAPE}[8;4;20t")
    }

    @Test
    fun `a prefixed t is not xtwinops`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        // `CSI ? Pm t` and `CSI > Pm t` are other sequences that happen to end in the same byte.
        buffer.feed("${ESCAPE}[?18t${ESCAPE}[>18t")

        assertThat(replies).isEmpty()
    }

    @Test
    fun `xtwinops saves and restores the window title`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("${ESCAPE}]0;first${chars(0x07)}")
        buffer.feed("${ESCAPE}[22;0t")
        buffer.feed("${ESCAPE}]0;second${chars(0x07)}")
        buffer.feed("${ESCAPE}[23;0t")

        assertThat(buffer.frame().title).isEqualTo("first")
    }

    @Test
    fun `xtwinops reports the title it holds`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}]0;user@host${chars(0x07)}${ESCAPE}[21t")

        assertThat(replies).containsExactly("${ESCAPE}]luser@host${ESCAPE}\\")
    }

    @Test
    fun `a restore with nothing saved leaves the title alone`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        buffer.feed("${ESCAPE}]0;keep me${chars(0x07)}")

        buffer.feed("${ESCAPE}[23;0t")

        assertThat(buffer.frame().title).isEqualTo("keep me")
    }

    @Test
    fun `the icon and window title stacks are kept apart`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("${ESCAPE}]0;icon copy${chars(0x07)}")
        buffer.feed("${ESCAPE}[22;1t")
        buffer.feed("${ESCAPE}]0;replaced${chars(0x07)}")

        // Saved on the icon stack and restored from the window one: that stack is empty, so nothing
        // happens - a program cannot reach a title it never saved by picking the wrong stack.
        buffer.feed("${ESCAPE}[23;2t")
        assertThat(buffer.frame().title).isEqualTo("replaced")

        buffer.feed("${ESCAPE}[23;1t")
        assertThat(buffer.frame().title).isEqualTo("icon copy")
    }

    @Test
    fun `a title saved on the window stack is not restored from the icon one`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("${ESCAPE}]0;window copy${chars(0x07)}")
        buffer.feed("${ESCAPE}[22;2t")
        buffer.feed("${ESCAPE}]0;replaced${chars(0x07)}")

        buffer.feed("${ESCAPE}[23;1t")
        assertThat(buffer.frame().title).isEqualTo("replaced")

        buffer.feed("${ESCAPE}[23;2t")
        assertThat(buffer.frame().title).isEqualTo("window copy")
    }

    @Test
    fun `a title saved before any was set restores to no title`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("${ESCAPE}[22;0t")
        buffer.feed("${ESCAPE}]0;borrowed${chars(0x07)}")
        buffer.feed("${ESCAPE}[23;0t")

        assertThat(buffer.frame().title).isNull()
    }

    @Test
    fun `the title stack is bounded and refuses what will not fit`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        // Twelve saves of ten distinct titles. xterm's stack holds ten and ignores the rest, so the
        // first ten are kept and the last two were never made - which is the direction to fail in,
        // since a dropped save costs a title and an overwritten one loses the caller's.
        repeat(12) { index ->
            buffer.feed("${ESCAPE}]0;s$index${chars(0x07)}${ESCAPE}[22;0t")
        }

        val restored = mutableListOf<String?>()
        repeat(11) {
            buffer.feed("${ESCAPE}[23;0t")
            restored += buffer.frame().title
        }

        assertThat(restored).isEqualTo(
            listOf("s9", "s8", "s7", "s6", "s5", "s4", "s3", "s2", "s1", "s0", "s0"),
        )
    }

    @Test
    fun `an osc colour query is answered with the theme colour`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }
        buffer.themeColors = { darkTheme() }

        buffer.feed("${ESCAPE}]10;?${ESCAPE}\\${ESCAPE}]11;?${ESCAPE}\\")

        // Each 8-bit component doubled rather than zero-padded into place, and the low components are
        // the interesting ones: 0x09 and 0x0D are one digit in hex and have to print as two.
        assertThat(replies).containsExactly(
            "${ESCAPE}]10;rgb:B3B3/C1C1/D9D9${ESCAPE}\\",
            "${ESCAPE}]11;rgb:0909/0D0D/1616${ESCAPE}\\",
        )
    }

    @Test
    fun `a colour query with no theme to report is not answered`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}]10;?${ESCAPE}\\${ESCAPE}]11;?${ESCAPE}\\")

        assertThat(replies).isEmpty()
    }

    @Test
    fun `a colour query is answered with what the screen is painting now`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }
        var background = 0xFF090D16.toInt()
        buffer.themeColors = { TerminalColors(0xFFFFFFFF.toInt(), background) }

        buffer.feed("${ESCAPE}]11;?${ESCAPE}\\")
        background = 0xFFFDF6E3.toInt()
        buffer.feed("${ESCAPE}]11;?${ESCAPE}\\")

        // The theme is read per question, not copied in, so a user switching themes mid-session is
        // answered with the colour the screen has actually changed to.
        assertThat(replies).containsExactly(
            "${ESCAPE}]11;rgb:0909/0D0D/1616${ESCAPE}\\",
            "${ESCAPE}]11;rgb:FDFD/F6F6/E3E3${ESCAPE}\\",
        )
    }

    @Test
    fun `an osc colour is a set and not a query, and this terminal does not take one`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }
        buffer.themeColors = { darkTheme() }

        buffer.feed("${ESCAPE}]10;#FF0000${ESCAPE}\\${ESCAPE}]11;rgb:00/00/00${ESCAPE}\\")

        // Answering a set would be a reply to something that was not a question, and honouring it
        // would be a remote repainting the display it was given for the length of a session.
        assertThat(replies).isEmpty()
    }

    @Test
    fun `the palette and cursor colour queries are not answered`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }
        buffer.themeColors = { darkTheme() }

        // Same family, no true answer: this terminal has no per-session palette of its own and no
        // cursor colour to report.
        buffer.feed("${ESCAPE}]4;1;?${ESCAPE}\\${ESCAPE}]12;?${ESCAPE}\\")

        assertThat(replies).isEmpty()
    }

    @Test
    fun `none of the new questions draws anything`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        buffer.responder = { }
        buffer.themeColors = { darkTheme() }
        buffer.feed("abc${ESCAPE}[2;2H")
        val before = buffer.frame()

        buffer.feed(
            "${ESCAPE}[18t${ESCAPE}[19t${ESCAPE}[21t${ESCAPE}[22;0t${ESCAPE}[23;0t" +
                "${ESCAPE}]10;?${ESCAPE}\\${ESCAPE}]11;?${ESCAPE}\\${ESCAPE}]0;title${chars(0x07)}",
        )

        val after = buffer.frame()
        assertThat(after.cursorRow).isEqualTo(before.cursorRow)
        assertThat(after.cursorColumn).isEqualTo(before.cursorColumn)
        assertThat(buffer.plainText()).isEqualTo("abc")
    }

    // --- Character sets, colours and the smaller editing sequences ---

    @Test
    fun `dec special graphics draws box rules instead of letters`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        // What ncurses sends for the top edge of a dialog.
        buffer.feed("${ESCAPE}(0lqqk${ESCAPE}(B done")

        assertThat(buffer.plainText()).isEqualTo(chars(0x250C, 0x2500, 0x2500, 0x2510) + " done")
    }

    /**
     * Bright and normal used to be the same 16 values, and "bright black" - which is what almost
     * everything reaches for to dim text - came out as pure black on a black background.
     */
    @Test
    fun `bright colours are distinguishable from their normal counterparts`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("${ESCAPE}[30mA${ESCAPE}[90mB${ESCAPE}[31mC${ESCAPE}[91mD")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].style.foreground).isNotEqualTo(line[1].style.foreground)
        assertThat(line[2].style.foreground).isNotEqualTo(line[3].style.foreground)
        assertThat(line[1].style.foreground.rgb).isGreaterThan(line[0].style.foreground.rgb)
    }

    @Test
    fun `the 256 colour palette keeps its bright block distinct as well`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("${ESCAPE}[38;5;0mA${ESCAPE}[38;5;8mB")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].style.foreground).isNotEqualTo(line[1].style.foreground)
    }

    @Test
    fun `the extended styles a modern shell prompt uses are recorded`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("${ESCAPE}[2mA${ESCAPE}[3mB${ESCAPE}[9mC${ESCAPE}[8mD${ESCAPE}[0mE")

        val line = buffer.snapshot().lines[0]
        assertThat(line[0].style.dim).isTrue()
        assertThat(line[1].style.italic).isTrue()
        assertThat(line[2].style.strikethrough).isTrue()
        assertThat(line[3].style.hidden).isTrue()
        assertThat(line[4].style).isEqualTo(TerminalStyle())
    }

    @Test
    fun `index keeps the column and a newline does not`() {
        val stepped = AnsiTerminalBuffer(columns = 10, rows = 3)
        stepped.feed("abc${ESCAPE}Dx")
        assertThat(stepped.plainText()).isEqualTo("abc\n   x")

        val plain = AnsiTerminalBuffer(columns = 10, rows = 3)
        plain.feed("abc\nx")
        assertThat(plain.plainText()).isEqualTo("abc\nx")
    }

    @Test
    fun `erase characters blanks in place without shifting the rest of the line`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)
        buffer.feed("abcdefgh")

        buffer.feed("${ESCAPE}[1;3H${ESCAPE}[3X")

        assertThat(buffer.plainText()).isEqualTo("ab   fgh")
    }

    @Test
    fun `insert mode pushes the rest of the line to the right`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)
        buffer.feed("world")

        buffer.feed("${ESCAPE}[1;1H${ESCAPE}[4hhello ${ESCAPE}[4l")

        assertThat(buffer.plainText()).isEqualTo("hello world")
        assertThat(buffer.snapshot().lines[0]).hasSize(20)
    }

    @Test
    fun `repeat draws the last character again`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("-${ESCAPE}[9b")

        assertThat(buffer.plainText()).isEqualTo("-".repeat(10))
    }

    @Test
    fun `a custom tab stop replaces the default eight column grid`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        buffer.feed("${ESCAPE}[3g")
        buffer.feed("${ESCAPE}[1;5H${ESCAPE}H")
        buffer.feed("${ESCAPE}[1;1HA\tB")

        assertThat(buffer.plainText()).isEqualTo("A   B")
    }

    @Test
    fun `an osc title is captured for the session tab`() {
        val buffer = AnsiTerminalBuffer(columns = 30, rows = 3)

        buffer.feed("${ESCAPE}]0;user@host: ~${chars(0x07)}ready")

        assertThat(buffer.frame().title).isEqualTo("user@host: ~")
        assertThat(buffer.plainText()).isEqualTo("ready")
    }

    @Test
    fun `a soft reset returns the terminal to a usable state`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        buffer.feed("${ESCAPE}[?1049h${ESCAPE}[?7l${ESCAPE}[?25l${ESCAPE}[31mconfused")

        buffer.feed("${ESCAPE}[!p")

        val frame = buffer.frame()
        assertThat(frame.alternateScreen).isFalse()
        assertThat(frame.cursorVisible).isTrue()
        assertThat(buffer.plainText()).isEmpty()
    }

    // --- Painting in place on the primary screen, which is what `top` actually does ---

    /**
     * `top` never asks for the alternate screen, and the display has to notice anyway.
     *
     * Probed against the real binary through a real pty at 80x12: procps emits `ESC [ H` and rewrites
     * the screen where it stands, with exactly eleven newlines per frame on a twelve-row screen so that
     * it never scrolls, and `ESC [ ? 1049 h` appears nowhere in its output. That makes every one of its
     * rows positional on the *primary* screen - the load averages are on row one because row one is
     * where they were put - and re-wrapping row three into two rows on a phone whose view is narrower
     * than the pty would push the rest of its screen down. `alternateScreen` cannot see this, so the
     * buffer reports the absolute addressing instead: a sequence that names a row is stating where the
     * whole screen belongs, and no shell doing ordinary work ever names one.
     *
     * The complementary test is the one below, which pins what is deliberately *not* a repaint.
     */
    @Test
    fun `homing the cursor to repaint marks the primary screen as painted`() {
        val buffer = AnsiTerminalBuffer(columns = 80, rows = 12, scrollbackLimit = 50)
        buffer.feed("$ top\n")

        assertThat(buffer.frame().positionalScreen).isFalse()

        // One of top's frames, ending where the real one ends: on the last row, with no newline after
        // it, which is how it avoids scrolling itself off the screen.
        buffer.feed("${ESCAPE}[Htop - 09:41:02 up 3 days\n${ESCAPE}[KTasks: 84 total\n")
        repeat(9) { buffer.feed("${ESCAPE}[Krow $it\n") }
        buffer.feed("${ESCAPE}[K  PID USER")

        assertThat(buffer.frame().positionalScreen).isTrue()
        assertThat(buffer.frame().alternateScreen).isFalse()
    }

    /**
     * And it has to let go, or wrapping would be off for the rest of the session.
     *
     * The release is the opposite tell: output that reaches the bottom row and pushes the screen up is a
     * stream behaving like one. `top`'s own exit does it - it addresses the row below its display and
     * newlines.
     */
    @Test
    fun `output that scrolls the screen takes the mark off again`() {
        val buffer = AnsiTerminalBuffer(columns = 80, rows = 4, scrollbackLimit = 50)
        buffer.feed("one\ntwo\n")
        buffer.feed("${ESCAPE}[Hpainted")

        assertThat(buffer.frame().positionalScreen).isTrue()

        // Four rows, so the fourth newline is the one that scrolls.
        buffer.feed("a\nb\nc\nd\n")

        assertThat(buffer.frame().positionalScreen).isFalse()
    }

    /**
     * `clear` takes the mark off at once, rather than after a screenful.
     *
     * Measured at 80x12 on a real pty: `clear` writes `ESC[H ESC[2J ESC[3J` and `top`'s startup writes
     * `ESC[H ESC[2J`, one ED2 and never an ED3 in a whole run. The home is common to both and the erase
     * is common to both, so only the scrollback drop separates them - and it is the right signal to read,
     * because a program that means to keep repainting does not throw the history away first. Without this
     * every line of the screenful after a `clear` was laid out unwrapped.
     */
    @Test
    fun `clearing the screen and its scrollback takes the mark off at once`() {
        val buffer = AnsiTerminalBuffer(columns = 80, rows = 12, scrollbackLimit = 50)
        buffer.feed("one\ntwo\nthree\n")

        // Exactly what `clear` sends, in that order.
        buffer.feed("${ESCAPE}[H${ESCAPE}[2J${ESCAPE}[3J")

        assertThat(buffer.frame().positionalScreen).isFalse()

        // And the shell's next line, well short of the bottom row that would have scrolled the mark off.
        buffer.feed("$ echo hello\nhello\n")

        assertThat(buffer.frame().positionalScreen).isFalse()
    }

    /**
     * The erase on its own is not enough, which is why the rule reads the scrollback drop instead.
     *
     * This is `top`'s startup byte for byte: home, erase, then a frame painted downward. If ED2 released
     * the mark, this frame - a grid - would be laid out wrapped on a phone until the next repaint.
     */
    @Test
    fun `erasing the screen without dropping the scrollback keeps the mark`() {
        val buffer = AnsiTerminalBuffer(columns = 80, rows = 12, scrollbackLimit = 50)
        buffer.feed("$ top\n")
        buffer.feed("${ESCAPE}[H${ESCAPE}[2J")

        assertThat(buffer.frame().positionalScreen).isTrue()

        buffer.feed("top - 09:41:02 up 3 days\n${ESCAPE}[KTasks: 84 total")

        assertThat(buffer.frame().positionalScreen).isTrue()
    }

    /** Ordinary output is never mistaken for a repaint, however long it runs. */
    @Test
    fun `flowing output never marks the screen as painted`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 6, scrollbackLimit = 50)

        // Colour, erase-to-end-of-line and a carriage return: what a progress bar and a coloured `ls`
        // send, none of which moves the cursor up a row.
        repeat(12) { buffer.feed("${ESCAPE}[32mfile $it${ESCAPE}[0m${ESCAPE}[K\n") }
        buffer.feed("  10%\r  20%\r 100%\n")
        buffer.feed("${ESCAPE}[2Bdown two${ESCAPE}[4Gcolumn four")

        assertThat(buffer.frame().positionalScreen).isFalse()
    }

    /** Leaving the alternate screen hands back a primary screen that is the shell's again. */
    @Test
    fun `the two screens do not inherit each other's mark`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 6, scrollbackLimit = 50)
        buffer.feed("$ vi notes\n")
        // What vi does on the way in: take the alternate screen, paint two rows, then home and paint
        // again - the home being the repaint this test is about.
        buffer.feed("${ESCAPE}[?1049h~ one\n~ two\n${ESCAPE}[H~ file")

        assertThat(buffer.frame().positionalScreen).isTrue()

        buffer.feed("${ESCAPE}[?1049l")

        assertThat(buffer.frame().positionalScreen).isFalse()
    }

    /**
     * The line editor putting a wrapped command back is not a program painting a screen, however much
     * the cursor moves look like one.
     *
     * This is the regression the flag was reading wrongly. The rule used to be "an upward move", and
     * the one thing that moves up while a shell is on screen is readline: a command longer than the
     * window is redrawn by stepping back up over the rows it occupies and writing them again, so
     * *editing* any long command - a backspace, a Ctrl-A, a completion - marked the screen painted and
     * switched word wrapping off for the rest of the session. The user sees the result as a long line
     * clipped at the right edge instead of flowing onto the row below, with the cursor drawn past the
     * edge and the view panning after it.
     *
     * Measured through a real pty at 46 columns: editing one long line emits ten `ESC [ A`; `top`,
     * `less`, `nano`, `vi` and `watch`, run the same way, emit none between them and home with `ESC [ H`
     * instead. The tell is therefore the shape of the sequence and not its direction - and the shell's
     * own line editor is what proves it.
     */
    @Test
    fun `the line editor rewriting a wrapped command never marks the screen as painted`() {
        val buffer = AnsiTerminalBuffer(columns = 46, rows = 20, scrollbackLimit = 50)
        buffer.feed("$ ")
        buffer.feed(LONG_COMMAND)
        assertThat(buffer.frame().positionalScreen).isFalse()

        // Ctrl-A on a line that has wrapped, byte for byte from a real bash at 46 columns: up one row,
        // carriage return, then forward to the column the cursor belongs on.
        buffer.feed("${ESCAPE}[A\r${ESCAPE}[C${ESCAPE}[C")
        assertThat(buffer.frame().positionalScreen).isFalse()

        // And the rest of the relative row moves: the two that go up, which each used to set the mark,
        // and the two that go down, which never did and still must not.
        buffer.feed("${ESCAPE}[2A") // CUU, up
        buffer.feed("${ESCAPE}[F") // CPL, up and to column zero
        buffer.feed("${ESCAPE}[B") // CUD, down
        buffer.feed("${ESCAPE}[E") // CNL, down and to column zero
        buffer.feed("${ESCAPE}OA") // the SS3 arrow, which is the same move an arrow key sends
        assertThat(buffer.frame().positionalScreen).isFalse()
    }

    /**
     * And the consequence, which is what the user reported: the long line still flows onto the row
     * below instead of being clipped at the right edge.
     *
     * The pty is 46 columns and the view is 20, which is the shipped situation on a phone - a command
     * that fits the pty does not fit the screen. [terminalLayout] is where the wrapping happens and the
     * only layer the reader sees, so the frame is read twice from the same buffer: once as it comes,
     * and once as the bug used to present it, marked painted. The two must not agree.
     */
    @Test
    fun `an edited command line still flows onto the next row`() {
        val buffer = AnsiTerminalBuffer(columns = 46, rows = 20, scrollbackLimit = 50)
        buffer.feed("$ ")
        buffer.feed(LONG_COMMAND)
        buffer.feed("${ESCAPE}[A\r${ESCAPE}[K${ESCAPE}[C")

        val frame = buffer.frame()
        val flowing = terminalLayout(frame, width = 20, maxRows = 24)
        val clipped = terminalLayout(frame.copy(positionalScreen = true), width = 20, maxRows = 24)

        // Every row fits the view, so nothing needs panning to be readable...
        assertThat(flowing.rows.map { it.width }.max()).isAtMost(20)
        // ...and the line took more than one row to do it, which is the wrapping itself.
        assertThat(flowing.rows.size).isGreaterThan(clipped.rows.size)
        // The bug's reading of the same frame: one row per grid line, reaching past the view's edge.
        assertThat(clipped.rows.size).isEqualTo(frame.lines.size)
        assertThat(clipped.contentColumns).isGreaterThan(20)
    }

    /**
     * A home to the row the cursor is already on still marks, because that is `top`'s first frame.
     *
     * procps' startup is `ESC [ H ESC [ 2 J` followed by its grid, sent when the cursor is wherever the
     * shell left it - and on a screen with nothing above it, that is row one. Reading the tell as "a move
     * up" rather than "a row named" misses that frame and lays it out reflowed, which is the same damage
     * §28.2 was about, arriving through the one program that section is named for.
     */
    @Test
    fun `homing to the row the cursor is already on still marks the screen`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 6, scrollbackLimit = 50)

        // The top row of a fresh screen, with nothing above the cursor to move up from.
        buffer.feed("${ESCAPE}[H")

        assertThat(buffer.frame().positionalScreen).isTrue()
    }

    /**
     * And the direction that is not honoured, which is the pair to the test above.
     *
     * A name for a row *below* the cursor is a jump forward into space nothing has been written to, so it
     * is output resuming somewhere else rather than a screen being rewritten. Nothing observed does this
     * - it is here so that dropping the direction test altogether is a change a test notices.
     */
    @Test
    fun `a jump to a row below the cursor is not a repaint`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 6, scrollbackLimit = 50)

        buffer.feed("${ESCAPE}[5;1H")

        assertThat(buffer.frame().positionalScreen).isFalse()
    }

    // --- Scrollback ownership: clear must not throw away what the user scrolled back to read ---

    @Test
    fun `clearing the screen keeps the history`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 50)
        repeat(20) { buffer.feed("line $it\n") }

        buffer.feed("${ESCAPE}[H${ESCAPE}[2J")

        assertThat(buffer.plainText()).contains("line 3")
        assertThat(buffer.lineCount()).isGreaterThan(4)
    }

    @Test
    fun `erase saved lines is the one sequence allowed to drop the history`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 50)
        repeat(20) { buffer.feed("line $it\n") }
        assertThat(buffer.lineCount()).isGreaterThan(4)

        buffer.feed("${ESCAPE}[3J")

        assertThat(buffer.lineCount()).isEqualTo(4)
    }

    // --- The viewport the renderer is handed ---

    @Test
    fun `a frame draws only the viewport and addresses it absolutely`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 50)
        repeat(20) { buffer.feed("line $it\n") }
        buffer.feed("tail")

        val live = buffer.frame(scrollOffset = 0, viewportRows = 4)

        assertThat(live.lines).hasSize(4)
        assertThat(live.totalLines).isEqualTo(buffer.lineCount())
        assertThat(live.firstLine).isEqualTo(live.totalLines - 4)
        assertThat(lines(live).last()).isEqualTo("tail")
        assertThat(live.cursorRow).isEqualTo(3)

        val back = buffer.frame(scrollOffset = 4, viewportRows = 4)

        assertThat(back.firstLine).isEqualTo(live.firstLine - 4)
        assertThat(back.lines).hasSize(4)
        // The cursor is off-screen once the user has scrolled away from the live output.
        assertThat(back.cursorRow).isEqualTo(-1)
        assertThat(buffer.maxScrollOffset(4)).isEqualTo(buffer.lineCount() - 4)
    }

    @Test
    fun `a scroll offset past the top of the buffer is clamped rather than throwing`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4, scrollbackLimit = 10)
        buffer.feed("only one line")

        val frame = buffer.frame(scrollOffset = 10_000, viewportRows = 4)

        assertThat(frame.firstLine).isEqualTo(0)
        assertThat(frame.lines).hasSize(4)
    }

    @Test
    fun `a viewport taller than the buffer is served with what exists`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 5)

        val frame = buffer.frame(scrollOffset = 0, viewportRows = 500)

        assertThat(frame.lines).hasSize(buffer.lineCount())
        assertThat(frame.firstLine).isEqualTo(0)
    }

    @Test
    fun `the revision advances on output and stands still otherwise`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4)
        val before = buffer.frame().revision

        assertThat(buffer.frame().revision).isEqualTo(before)

        buffer.feed("x")

        assertThat(buffer.frame().revision).isGreaterThan(before)
    }

    // --- Selection, which is what copy reads ---

    @Test
    fun `a selection is extracted by absolute buffer coordinates`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        buffer.feed("first line\nsecond line\nthird")

        assertThat(buffer.textIn(0, 6, 1, 6)).isEqualTo("line\nsecond")
        // Padding inside the screen is trimmed per line, so a copied block carries no trailing spaces.
        assertThat(buffer.textIn(0, 0, 2, 20)).isEqualTo("first line\nsecond line\nthird")
    }

    @Test
    fun `an out of range selection is clamped to the buffer`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        buffer.feed("only")

        assertThat(buffer.textIn(-5, -5, 9_999, 9_999)).isEqualTo("only")
        assertThat(buffer.textIn(3, 0, 1, 0)).isEmpty()
    }

    // --- Escape intermediates, the cursor blink mode, and the modes that stay refused ---

    @Test
    fun `an escape intermediate opens a sequence instead of printing its final byte`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        // One shape, eight sequences, and every one used to print its own final byte: `ESC % G` a `G`,
        // `ESC * 0` a `0`, `ESC  F` an `F`. The defect `ESC O` had, one byte further along.
        buffer.feed(
            "${ESCAPE}%G${ESCAPE}%@${ESCAPE}*0${ESCAPE}+0${ESCAPE} F${ESCAPE}-A${ESCAPE}.A${ESCAPE}/A",
        )

        assertThat(buffer.plainText()).isEmpty()
        assertThat(buffer.frame().cursorColumn).isEqualTo(0)
    }

    @Test
    fun `a double width line attribute is consumed and changes nothing`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)
        buffer.feed("abc")

        // #3 and #4 are double-height, #5 and #6 double-width, and this display has one size of cell.
        // A double-width line still takes one cell per column, so obeying them here would draw a lie.
        buffer.feed("${ESCAPE}#3${ESCAPE}#4${ESCAPE}#5${ESCAPE}#6")

        assertThat(buffer.plainText()).isEqualTo("abc")
        assertThat(buffer.frame().cursorColumn).isEqualTo(3)
    }

    @Test
    fun `the screen alignment test fills every cell with an E and homes the cursor`() {
        val buffer = AnsiTerminalBuffer(columns = 6, rows = 3, scrollbackLimit = 20)
        buffer.feed("old one\nold two\nold three\nold four\n")
        buffer.feed("${ESCAPE}[2;5H")

        buffer.feed("${ESCAPE}#8")

        assertThat(buffer.snapshot().lines.takeLast(3).map { line -> line.map { it.value }.joinToString("") })
            .containsExactly("EEEEEE", "EEEEEE", "EEEEEE")
        // The screen, not the history: the pattern is a test card, and the lines that have already
        // scrolled off it are still the user's output.
        assertThat(buffer.plainText()).startsWith("old on\ne\nold tw")
        assertThat(buffer.plainText()).endsWith("EEEEEE")
        // And the cursor is home, which is what the pattern is read from: the next character lands on
        // the top-left cell of the screen rather than wherever the program left it.
        buffer.feed("Z")
        assertThat(buffer.snapshot().lines.takeLast(3).first()[0].value).isEqualTo('Z')
    }

    @Test
    fun `the screen alignment test fills in the rendition the program set`() {
        val buffer = AnsiTerminalBuffer(columns = 6, rows = 3)
        buffer.feed("${ESCAPE}[41mX")
        val painted = buffer.snapshot().lines[0][0].style.background

        buffer.feed("${ESCAPE}#8")

        assertThat(buffer.snapshot().lines[0].map { it.style.background }.toSet()).containsExactly(painted)
        assertThat(buffer.snapshot().lines[2][5].style.background).isEqualTo(painted)
    }

    @Test
    fun `the g1 designation reaches the glyph table the same way g0 does`() {
        val buffer = AnsiTerminalBuffer(columns = 8, rows = 3)

        // `ESC ) 0` is the G1 half of the same table, reached through SO and left through SI.
        buffer.feed("${ESCAPE})0${chars(0x0E)}q${chars(0x0F)}q")

        assertThat(buffer.plainText()).isEqualTo(chars(0x2500) + "q")
    }

    @Test
    fun `an unknown sequence with an intermediate byte takes its final byte with it`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)

        // `ESC SP b` implements nothing here, and an unimplemented sequence is ignored whole: the `b`
        // is its final byte, not text. Letting it print puts a stray letter in the user's output.
        buffer.feed("a${ESCAPE} b")

        assertThat(buffer.plainText()).isEqualTo("a")
    }

    @Test
    fun `a control byte abandons an escape sequence and designates nothing`() {
        val buffer = AnsiTerminalBuffer(columns = 8, rows = 3)

        // A program killed mid-designation. The newline it was about to be followed by still breaks the
        // line, and the `q` after it is an ordinary letter because no set was ever selected - the
        // alternative is a screen full of line-drawing characters.
        buffer.feed("${ESCAPE}(${chars(0x0A)}q")

        assertThat(buffer.plainText()).isEqualTo("\nq")
        assertThat(buffer.snapshot().lines[1].map { it.value }.joinToString("").trimEnd()).isEqualTo("q")
    }

    @Test
    fun `a second escape inside an escape sequence takes over`() {
        val buffer = AnsiTerminalBuffer(columns = 8, rows = 3)

        buffer.feed("${ESCAPE}(${ESCAPE}[3mA")

        assertThat(buffer.plainText()).isEqualTo("A")
        assertThat(buffer.snapshot().lines[0][0].style.italic).isTrue()
    }

    @Test
    fun `the cursor blink mode is honoured and reported`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[?12${'$'}p")
        assertThat(buffer.frame().cursorBlink).isTrue()

        buffer.feed("${ESCAPE}[?12l${ESCAPE}[?12${'$'}p")
        assertThat(buffer.frame().cursorBlink).isFalse()

        buffer.feed("${ESCAPE}[?12h${ESCAPE}[?12${'$'}p")
        assertThat(buffer.frame().cursorBlink).isTrue()

        assertThat(replies).containsExactly(
            "${ESCAPE}[?12;1${'$'}y",
            "${ESCAPE}[?12;2${'$'}y",
            "${ESCAPE}[?12;1${'$'}y",
        ).inOrder()
    }

    @Test
    fun `a soft reset brings the blinking cursor back`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }

        buffer.feed("${ESCAPE}[?12l${ESCAPE}[!p${ESCAPE}[?12${'$'}p")

        assertThat(buffer.frame().cursorBlink).isTrue()
        assertThat(replies).containsExactly("${ESCAPE}[?12;1${'$'}y")
    }

    @Test
    fun `the cursor blink mode outlives the alternate screen, unlike the mouse modes`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)

        buffer.feed("${ESCAPE}[?12l${ESCAPE}[?1000h${ESCAPE}[?1049h${ESCAPE}[?1049l")

        // The mouse modes are dropped when the program hands the screen back, because one that dies
        // without undoing them would leave the shell reporting every drag. The blink mode is the
        // cursor's own property, so it stands - which is what xterm does.
        assertThat(buffer.frame().mouseMode).isEqualTo(MouseMode.OFF)
        assertThat(buffer.frame().cursorBlink).isFalse()
    }

    @Test
    fun `the modes this terminal refuses stay unrecognized and change nothing`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }
        buffer.feed("abc${ESCAPE}[1;1H")

        buffer.feed(
            // DECCOLM (3) would clear the screen and resize it to 132 columns, which this app sizes to
            // the device instead; DECSCNM (5) would invert every cell; DECSLRM (69) would set left and
            // right margins, which nothing here draws.
            "${ESCAPE}[?3h${ESCAPE}[?5h${ESCAPE}[?69h" +
                "${ESCAPE}[?3${'$'}p${ESCAPE}[?5${'$'}p${ESCAPE}[?69${'$'}p",
        )

        assertThat(replies).containsExactly(
            "${ESCAPE}[?3;0${'$'}y",
            "${ESCAPE}[?5;0${'$'}y",
            "${ESCAPE}[?69;0${'$'}y",
        ).inOrder()
        assertThat(buffer.plainText()).isEqualTo("abc")
        assertThat(buffer.snapshot().lines[0][0].style).isEqualTo(TerminalStyle())
    }

    @Test
    fun `none of the refused window operations or attributes draws anything`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        val replies = mutableListOf<String>()
        buffer.responder = { replies += it }
        buffer.feed("abc${ESCAPE}[2;2H")
        val before = buffer.frame()

        buffer.feed(
            // XTWINOPS 11, 13, 14, 16 and 20 ask for window state, screen pixels and an icon label,
            // and 24 wants a different number of rows: none of them exists here, and none is answered.
            "${ESCAPE}[11t${ESCAPE}[13t${ESCAPE}[14t${ESCAPE}[16t${ESCAPE}[20t${ESCAPE}[24t" +
                "${ESCAPE}#3${ESCAPE}%G${ESCAPE}*0${ESCAPE}+0${ESCAPE}-A",
        )

        assertThat(replies).isEmpty()
        assertThat(buffer.plainText()).isEqualTo("abc")
        assertThat(buffer.frame().cursorRow).isEqualTo(before.cursorRow)
        assertThat(buffer.frame().cursorColumn).isEqualTo(before.cursorColumn)
    }
}
