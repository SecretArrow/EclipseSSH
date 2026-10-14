package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ESC, spelled out so the sequences below stay readable and copy-pasteable. */
private const val E = "\u001B"

/** 中 - one character, two columns: cell [4] carries it and cell [5] is its right half. */
private const val WIDE = "中"

/**
 * What one drawn row reads as, trailing blanks dropped the way the renderer drops them.
 *
 * This walks *cells*, so a wide character's right half is skipped and never reads as a space of its
 * own. tmux's own capture does the same thing, which is what makes the two readings comparable.
 */
private fun List<TerminalCell>.text(): String {
    val text = StringBuilder()
    forEachIndexed { index, cell ->
        if (index > 0 && this[index - 1].wide) return@forEachIndexed
        text.append(cell.value).append(cell.combining)
    }
    return text.toString().trimEnd()
}

/**
 * Where an erase, an insert and a delete may cut, measured against tmux 3.5a.
 *
 * Every rule here is a measurement rather than a preference. The oracle is a tmux pane replayed with
 * the same bytes and captured with `capture-pane`; `testing/tmux-replay/reproductions/wide-boundaries.raw`
 * is the same case set as a recording, and `griddiff.py` diffs our grid against that pane's, so a
 * change that breaks one of these breaks the harness too.
 *
 * The row every case is built on is `0123中67` - eight columns, the character's two halves on cells
 * 4 and 5 - because it puts an ordinary cell on either side of the pair and so tells a widened range
 * apart from a shifted one.
 */
class WideCharacterBoundaryTest {
    private fun row(): AnsiTerminalBuffer {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)
        buffer.feed("0123$WIDE" + "67")
        return buffer
    }

    // --- DCH: the count is cells, and the range is widened to whole characters ------------------

    @Test
    fun `a delete on a wide character's left half takes the whole character`() {
        val buffer = row()
        buffer.feed("$E[1;5H$E[P")

        // Both cells go and the tail shifts by two. Measured: tmux reads `012367` for this, so the
        // two halves are one unit and the count addresses units, not columns.
        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("012367")
    }

    @Test
    fun `a delete on a wide character's right half leaves the row alone`() {
        val buffer = row()
        buffer.feed("$E[1;6H$E[P")

        // Measured: tmux reads `0123中67` - the character is untouched. Deleting the half would
        // orphan the other one, which is the one thing a delete must not leave behind.
        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("0123${WIDE}67")
    }

    @Test
    fun `a delete starting on a left half spends its cells on whole characters`() {
        val buffer = row()
        buffer.feed("$E[1;5H$E[2P")

        // Two cells, one character: the pair is two of them, so nothing else moves. Measured: tmux
        // reads `012367` here as well - `2P` and `1P` are the same delete on this row.
        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("012367")
    }

    @Test
    fun `a delete starting on a right half spends one cell leaving the half alone`() {
        val buffer = row()
        buffer.feed("$E[1;6H$E[3P")

        // The cursor stands on the right half, so the first of the three cells is spent leaving it
        // in place; the remaining two come from the next character on. Measured: tmux reads `0123中`
        // - the pair survives whole and the two cells after it go.
        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("0123$WIDE")
    }

    @Test
    fun `a delete on an ordinary cell shifts one cell`() {
        val buffer = row()
        buffer.feed("$E[1;4H$E[P")

        // Measured: tmux reads `012中67`. No character is cut, so no widening applies.
        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("012${WIDE}67")
    }

    @Test
    fun `a delete past the last column does nothing`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)
        buffer.feed("abc")
        buffer.feed("$E[1;20H$E[P")

        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("abc")
    }

    @Test
    fun `a delete of no cells is a delete of one`() {
        val buffer = row()
        buffer.feed("$E[1;5H$E[0P")

        // A parameter of zero means "the default", which for a count is one - so `ESC [ 0 P` is
        // `ESC [ P`. Measured: tmux reads `012367`, the same as the bare form.
        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("012367")
    }

    @Test
    fun `the cells a delete vacates at the end of the line wear the pen's background`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)
        // Keep a background active across the delete: BCE fills with the *current* background, and
        // measured against tmux 3.5a the vacated cells are at the *end of the line*, not at the
        // cursor - the whole line shifts, trailing blanks included, and the tail takes the pen.
        buffer.feed("$E[41m" + "ABCDEFGHIJKLMNOPQRST")
        buffer.feed("$E[1;5H$E[P")

        val cells = buffer.snapshot().lines[0]
        assertThat(cells.map { it.value }.joinToString("")).isEqualTo("ABCDFGHIJKLMNOPQRST ")
        assertThat(cells.all { !it.style.background.isDefault }).isTrue()
    }

    // --- ICH ------------------------------------------------------------------------------------

    @Test
    fun `an insert on a wide character's left half pushes the pair along`() {
        val buffer = row()
        buffer.feed("$E[1;5H$E[1@")

        // Measured: tmux reads `0123 中67` on this row - a blank goes in front of the pair and both
        // of its halves move together, so the character survives the shift.
        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("0123 ${WIDE}67")
    }

    @Test
    fun `an insert inside a wide character pushes its right half along`() {
        val buffer = row()
        buffer.feed("$E[1;6H$E[1@")

        // The cursor stands on the right half, which is a cell like any other: a blank goes in and
        // the half moves with it, leaving the pair still whole and still adjacent.
        val row = buffer.snapshot().lines[0]
        assertThat(row.text()).isEqualTo("0123${WIDE} 67")
        assertThat(row.count { it.wide }).isEqualTo(1)
    }

    // --- ECH and EL: the range is the cells it names, and a whole character in it goes whole -----

    @Test
    fun `an erase of chars that covers a whole wide character takes it`() {
        val buffer = row()
        buffer.feed("$E[1;5H$E[2X")

        // Measured: tmux reads `0123  67` - the two cells named are the two halves.
        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("0123  67")
    }

    @Test
    fun `erasing from the cursor to the end of the line takes a whole character at the cursor`() {
        val buffer = row()
        buffer.feed("$E[1;5H$E[K")

        // Measured: tmux reads `0123` - the erase runs from the cursor to the margin, so both halves
        // are inside it and nothing of the character is left.
        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("0123")
    }

    @Test
    fun `erasing to the cursor takes a whole character the cursor stands on`() {
        val buffer = row()
        buffer.feed("$E[1;6H$E[1K")

        // Measured: tmux reads `      67` - `1K` runs from the start of the row to the cursor
        // *inclusive*, so the pair is inside the range and goes whole, and the two cells after it do
        // not move.
        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("      67")
    }

    /**
     * The one boundary the differential harness cannot arbitrate, and the reason it cannot.
     *
     * A range that cuts a wide character in half - `ECH 1` with the cursor on the right half - leaves
     * tmux holding a continuation cell with no left half. tmux's own capture renders that cell as no
     * glyph at all, so the two sides of a text diff stop agreeing about which column anything after it
     * is in, and the diff reports the shift rather than the defect. Feeding the *recording* of that
     * state through the harness is the case it can answer, and it answers IDENTICAL: what tmux sends
     * its client here, this emulator reproduces cell for cell.
     *
     * What this emulator does with the sequence itself is what GNU screen 4.9 does - both take the
     * whole character - where tmux keeps the half-glyph drawn across a cell the program has just
     * cleared. Two emulators disagree, so this is a choice and not a rule: a half-character is not
     * something a screen can draw, and taking the pair is the reading that leaves no ink over a column
     * the program believes it erased. [AnsiTerminalBuffer.clearUnit] is where that choice is made.
     */
    @Test
    fun `an erase that cuts a wide character in half takes the whole character`() {
        val buffer = row()
        buffer.feed("$E[1;6H$E[1X")

        assertThat(buffer.snapshot().lines[0].text()).isEqualTo("0123  67")
    }

    @Test
    fun `an erase of no cells is an erase of one`() {
        val buffer = row()
        // On an ordinary cell, so this measures the count and not the cut-a-character rule below.
        buffer.feed("$E[1;1H$E[0X")

        // A zero parameter means the default of one. Measured: tmux reads ` 123中67` for `ESC [ 0 X`
        // and for the bare `ESC [ X` alike.
        assertThat(buffer.snapshot().lines[0].text()).isEqualTo(" 123${WIDE}67")
    }
}
