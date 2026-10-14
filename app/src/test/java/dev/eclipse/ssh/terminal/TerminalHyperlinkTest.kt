package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ESC, built from its code point so this file contains no literal control byte. */
private val ESC: String = Char(0x1B).toString()

/**
 * OSC 8 puts a hyperlink on the text that follows it, and the cell is what carries it.
 *
 * A link is a *run* of cells opened by `OSC 8 ; params ; URI` and closed by the same sequence with an
 * empty URI, and the target belongs to whatever was printed in between - including across a read
 * boundary, a scroll and a re-wrap, which is why it lives on the cell rather than in a table of runs.
 * The tests below are the whole of the rule: which cells carry a target, what closes one, what a
 * malformed sequence does, and that the sequence is read rather than acted on - nothing here launches
 * anything, and the tap that does is the app's decision and not the parser's.
 */
class TerminalHyperlinkTest {
    private val link = "${ESC}]8;;https://example.com/$ESC\\"

    private fun buffer() = AnsiTerminalBuffer(columns = 20, rows = 4)

    /** The target on each of the first [count] cells of row [row], as a list. */
    private fun targets(buffer: AnsiTerminalBuffer, row: Int = 0, count: Int = 20): List<String?> {
        val frame = buffer.frame()
        return (0 until count).map { hyperlinkAt(frame, frame.firstLine + row, it) }
    }

    @Test
    fun `text printed inside a link carries its target`() {
        val buffer = buffer()

        buffer.feed("$link click here $ESC]8;;$ESC\\ after")

        // Twelve cells - " click here " - and every one of them is the link.
        assertThat(targets(buffer).take(12))
            .containsExactlyElementsIn(List(12) { "https://example.com/" })
        // Closed, so the cells after it are ordinary text and a tap on one does nothing.
        assertThat(hyperlinkAt(buffer.frame(), 0, 13)).isNull()
    }

    @Test
    fun `only the cells printed inside the link carry it`() {
        val buffer = buffer()

        buffer.feed("before $link inside $ESC]8;;$ESC\\ after")

        val frame = buffer.frame()
        assertThat(hyperlinkAt(frame, 0, 0)).isNull()
        assertThat(hyperlinkAt(frame, 0, 7)).isEqualTo("https://example.com/")
        assertThat(hyperlinkAt(frame, 0, 13)).isEqualTo("https://example.com/")
        assertThat(hyperlinkAt(frame, 0, 15)).isNull()
    }

    @Test
    fun `a link that is never closed covers what was printed under it and no more`() {
        val buffer = buffer()

        buffer.feed("$link forever")

        // The program that opened it and died left it open, and the eight cells it printed are still a
        // link. The twelve it never printed are not: a terminal that guessed at a closing point would be
        // inventing one, and one that spread the link across the whole row would be inventing cells.
        val frame = buffer.frame()
        assertThat((0 until 8).map { hyperlinkAt(frame, 0, it) })
            .containsExactlyElementsIn(List(8) { "https://example.com/" })
        assertThat((8 until 20).map { hyperlinkAt(frame, 0, it) })
            .containsExactlyElementsIn(List(12) { null })
    }

    @Test
    fun `the parameters between the code and the target are not read, and the target keeps its semicolons`() {
        val buffer = buffer()

        buffer.feed("${ESC}]8;id=42;https://example.com/a;b$ESC\\ x")

        // The second separator is the one that matters: a target is everything after it, verbatim,
        // because a link whose address this terminal rewrote would go somewhere else.
        assertThat(hyperlinkAt(buffer.frame(), 0, 0)).isEqualTo("https://example.com/a;b")
    }

    @Test
    fun `an empty target closes the link`() {
        val buffer = buffer()

        buffer.feed("$link a$ESC]8;;$ESC\\ b")

        assertThat(hyperlinkAt(buffer.frame(), 0, 0)).isEqualTo("https://example.com/")
        assertThat(hyperlinkAt(buffer.frame(), 0, 2)).isNull()
    }

    @Test
    fun `a sequence with no separator at all leaves the open link alone`() {
        val buffer = buffer()

        buffer.feed("$link a${ESC}]8$ESC\\ b")

        // Not a link this terminal can read, and a sequence it cannot read is not evidence that the
        // program meant to end the one it was in.
        assertThat(hyperlinkAt(buffer.frame(), 0, 1)).isEqualTo("https://example.com/")
        assertThat(hyperlinkAt(buffer.frame(), 0, 3)).isEqualTo("https://example.com/")
    }

    @Test
    fun `an erase leaves a blank that is not a link`() {
        val buffer = buffer()

        buffer.feed("$link abc$ESC[1;1H$ESC[0K")

        // The blank an erase leaves keeps the pen's background - BCE - but a link is not something a
        // blank can be: there is no text on it for the reader to have tapped.
        assertThat(targets(buffer).take(3)).containsExactly(null, null, null)
    }

    @Test
    fun `a link survives a scroll, because it is on the cell and not on the screen`() {
        val buffer = buffer()

        // Opened on a line that scrolls off, and read back out of the scrollback.
        buffer.feed("$link one${ESC}]8;;$ESC\\\r\ntwo\r\nthree\r\nfour\r\nfive")

        val live = buffer.frame()
        assertThat(live.firstLine).isEqualTo(1)
        assertThat(hyperlinkAt(live, 1, 0)).isNull()
        // Scrolled back to the line the link is on, which is a different window of the same cells.
        val back = buffer.frame(scrollOffset = 1)
        assertThat(back.firstLine).isEqualTo(0)
        assertThat(hyperlinkAt(back, 0, 0)).isEqualTo("https://example.com/")
    }

    @Test
    fun `the right half of a wide character carries the link too`() {
        val buffer = buffer()

        buffer.feed("$link 中")

        // The two cells are one character to the reader, and the half a finger lands on is not a
        // choice the reader made.
        val frame = buffer.frame()
        assertThat(hyperlinkAt(frame, 0, 1)).isEqualTo("https://example.com/")
        assertThat(hyperlinkAt(frame, 0, 2)).isEqualTo("https://example.com/")
    }

    @Test
    fun `a reset forgets the open link`() {
        val buffer = buffer()

        buffer.feed("$link${ESC}c")

        assertThat(targets(buffer)).containsExactlyElementsIn(List(20) { null })
    }

    @Test
    fun `a target too long to keep is refused rather than clipped`() {
        // A program can open a link on every cell it prints, and each run's target is one string the
        // buffer holds for as long as its cells live, so a target has to be bounded. What it must not
        // be is *shortened* to fit: half an address is a different destination from the one the
        // program printed, and the reader would be opening something the program never sent.
        val longest = "https://example.com/" + "a".repeat(2028)

        val kept = buffer()
        kept.feed("${ESC}]8;;$longest$ESC\\ x")
        assertThat(hyperlinkAt(kept.frame(), 0, 0)).isEqualTo(longest)

        val refused = buffer()
        refused.feed("${ESC}]8;;${longest}a$ESC\\ x")
        assertThat(hyperlinkAt(refused.frame(), 0, 0)).isNull()

        // And a target it refuses does not leave the *previous* link standing over cells the program
        // meant to point somewhere else.
        val replaced = buffer()
        replaced.feed("${ESC}]8;;https://example.com/$ESC\\a${ESC}]8;;${longest}a$ESC\\b")
        assertThat(hyperlinkAt(replaced.frame(), 0, 0)).isEqualTo("https://example.com/")
        assertThat(hyperlinkAt(replaced.frame(), 0, 1)).isNull()
    }

    @Test
    fun `a link opened across two reads is still one link`() {
        val buffer = buffer()

        // The sequence is split at the byte, which is what a pty does to it - the parser is stateful
        // across reads, and the target has to be too.
        buffer.feed("${ESC}]8;;https://exa")
        buffer.feed("mple.com/$ESC\\ hello")

        assertThat(hyperlinkAt(buffer.frame(), 0, 0)).isEqualTo("https://example.com/")
    }

    @Test
    fun `the lookup answers nothing for a line or a column the frame does not have`() {
        val buffer = buffer()
        buffer.feed("hi")
        val frame = buffer.frame()

        assertThat(hyperlinkAt(frame, -1, 0)).isNull()
        assertThat(hyperlinkAt(frame, frame.firstLine + 99, 0)).isNull()
        assertThat(hyperlinkAt(frame, frame.firstLine, 99)).isNull()
        assertThat(hyperlinkAt(frame, frame.firstLine, -1)).isNull()
    }
}
