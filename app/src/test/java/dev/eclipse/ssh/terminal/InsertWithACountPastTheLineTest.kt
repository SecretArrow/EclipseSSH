package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ESC, spelled out so the sequences below stay readable and copy-pasteable. */
private const val E = "\u001B"

/**
 * The one case of the insert family where this emulator and tmux 3.5a part company, stated rather than
 * hidden.
 *
 * Measured against tmux 3.5a on a twenty-column line: `ESC [ 1 7 @` with the cursor on the third cell
 * leaves tmux holding `AB DEFGHIJKLMNOPQRSC` - every cell whose destination is still on the line moved
 * right by seventeen, every cell whose destination is not stayed where it was, and the one that did
 * move landed on the last column over the top of what was there. `ESC [ 9 9 @` therefore leaves the line
 * untouched, which is what tmux does with a count larger than the line has cells left.
 *
 * That is a scatter rather than an insert, and the reason it is not followed is that no other emulator
 * does it: an insert shifts the range [cursor, margin), the cells past the margin fall off, and the gap
 * takes blanks. This emulator takes that reading. The difference is worth a file of its own because it
 * is invisible to the replay harness - a client is sent the cells tmux's grid holds, never the sequence
 * that produced them - so nothing else here would notice if this reading changed.
 *
 * [TerminalConformanceTest] holds the rest of the family, including the counts that fit, which both
 * sides agree on.
 */
class InsertWithACountPastTheLineTest {
    private fun row(input: String): List<TerminalCell> {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 3)
        buffer.feed(input)
        return buffer.snapshot().lines[0]
    }

    @Test
    fun `a count past the remaining columns shifts the line and blanks the gap`() {
        val row = row("$E[41mABCDEFGH$E[1;3H$E[17@")

        // tmux reads `AB DEFGHIJKLMNOPQRSC` here. The shift-and-blank reading inserts seventeen blanks
        // from the cursor and pushes the rest off the line, so the `C` that was on the third cell is
        // the only one of them still on the line - on the last cell.
        assertThat(row.joinToString("") { it.value.toString() }.trimEnd()).isEqualTo("AB                 C")
    }

    @Test
    fun `a count larger than the line still shifts it and blanks the gap`() {
        val row = row("$E[41mABCDEFGH$E[1;3H$E[99@")

        // tmux reads `ABCDEFGH` here - its scatter finds no destination left on the line, so it moves
        // nothing at all.
        assertThat(row.joinToString("") { it.value.toString() }.trimEnd()).isEqualTo("AB")
    }

    @Test
    fun `the gap an insert past the line leaves takes the pen`() {
        val row = row("$E[41mABCDEFGH$E[1;3H$E[99@")

        // BCE applies to the cells the shift vacated, the same as any other insert.
        assertThat(row.all { !it.style.background.isDefault }).isTrue()
    }
}
