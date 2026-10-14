package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ESC, spelled out so the sequences below stay readable and copy-pasteable. */
private const val E = "\u001B"

private fun List<TerminalCell>.rowText(): String =
    joinToString("") { it.value.toString() + it.combining }.trimEnd()

/**
 * Regression tests for the tmux+Claude ghost-text fixes, ported from libvterm's ideas rather than
 * its code: per-row damage, BCE-consistent blanks, and a BCE-aware blank-row test in the layout.
 *
 * Each test feeds the byte pattern a real tmux pane emits and asserts the grid holds no stale
 * cells afterwards - because tmux sends only changed cells, one stale cell is a permanent ghost.
 */
class TmuxGhostRegressionTest {
    @Test
    fun `rewriting a row with shorter text leaves no tail`() {
        val buffer = AnsiTerminalBuffer(columns = 30, rows = 4)
        buffer.feed("Thought for 30 secondsxx\r")
        // tmux redraws the progress line in place via CR, then clears to end of line.
        buffer.feed("Thought for 4s$E[K")

        val frame = buffer.frame()
        assertThat(frame.lines[frame.cursorRow].rowText()).isEqualTo("Thought for 4s")
        // The tail past the new end must be truly blank, not stale glyphs.
        assertThat(frame.lines[frame.cursorRow].drop(14).all { it.value == ' ' }).isTrue()
    }

    @Test
    fun `insert and delete inside a coloured status bar keep the background`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        // Paint a status bar and keep its background active: BCE fills with the *current* bg.
        buffer.feed("$E[42mstatus bar running ok")
        // Move back onto the bar and shift cells: ICH then DCH.
        buffer.feed("$E[1;8H$E[2@$E[2P")

        val bar = buffer.snapshot().lines[0]
        val painted = bar.take(20)
        // No inserted/deleted cell may punch a default-background hole into the bar.
        assertThat(painted.all { !it.style.background.isDefault }).isTrue()
    }

    @Test
    fun `insert and delete lines inside a scroll region keep the background`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 6)
        // Keep the blue background active: BCE fills IL/DL lines with the *current* bg.
        buffer.feed("$E[44m" + "row0\nrow1\nrow2\nrow3\nrow4\nrow5")
        // A tmux pane redraw: scroll region over rows 2-4, then IL at its top.
        buffer.feed("$E[2;4r$E[2;1H$E[L")

        // The line inserted at the top of the region is blank but carries the region's blue.
        val inserted = buffer.snapshot().lines[1]
        assertThat(inserted.all { it.value == ' ' }).isTrue()
        assertThat(inserted.all { !it.style.background.isDefault }).isTrue()

        // And the matching DL restores the text row without leaving a hole behind.
        buffer.feed("$E[M$E[r$E[0m")
        assertThat(buffer.snapshot().lines[1].rowText()).isEqualTo("row1")
    }

    @Test
    fun `scrolling a coloured region inserts coloured blanks`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 4)
        // Keep the red background active across the scroll: BCE fills with the *current* bg.
        buffer.feed("$E[41mAAAA\nBBBB\nCCCC\nDDDD")
        buffer.feed("$E[2;3r$E[2;1H$E[S$E[r")

        // The line scrolled in at the bottom of the region wears the active background (red).
        val snap = buffer.snapshot()
        val inserted = snap.lines[2]
        assertThat(inserted.all { it.value == ' ' }).isTrue()
        assertThat(inserted.all { !it.style.background.isDefault }).isTrue()
    }

    @Test
    fun `damage covers the rewritten row and resets on frame`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 4)
        buffer.feed("hello\n")

        // Fresh buffer starts fully damaged (initial paint).
        val first = buffer.frame()
        assertThat(first.damageFull).isTrue()

        buffer.feed("world")
        val second = buffer.frame()
        assertThat(second.damageFull).isFalse()
        assertThat(second.damageFrom).isAtLeast(0)
        assertThat(second.damageTo).isAtLeast(second.damageFrom)
        // The rewritten row (absolute line of "world") is inside the damage range.
        val worldLine = second.firstLine + second.lines.indexOfFirst { it.rowText() == "world" }
        assertThat(worldLine in second.damageFrom..second.damageTo).isTrue()

        // Republishing an untouched buffer carries no damage.
        val third = buffer.frame()
        assertThat(third.damageFull).isFalse()
        assertThat(third.damageFrom).isEqualTo(-1)
    }

    @Test
    fun `a coloured blank row is not blank for the layout`() {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 6)
        // Fill the screen so the window is full, then paint the last row as coloured padding.
        repeat(5) { buffer.feed("line $it\n") }
        buffer.feed("$E[42m                    $E[0m")
        // Park the cursor back at the top: the padding row is not the cursor's line, so the fit
        // is free to treat it as droppable - and must not, because it paints a background.
        buffer.feed("$E[1;1H")

        val frame = buffer.frame()
        // One row over budget: the fit must keep the coloured padding and drop plain text from
        // the top instead. Before the fix the padding read as blank and was the row dropped,
        // which is how the tmux status line ended up overwriting content.
        val layout = terminalLayout(frame, width = 20, maxRows = 5)
        assertThat(layout.rows.any { it.line == 5 }).isTrue()
    }

    @Test
    fun `cutting a wide character leaves no orphaned half behind`() {
        val buffer = AnsiTerminalBuffer(columns = 10, rows = 3)
        buffer.feed("$E[43m\u4e2d\u6587test$E[0m")
        // Delete the first column (the left half of 中): the cut pair must not survive as overlap.
        buffer.feed("$E[1;1H$E[P")

        val row = buffer.snapshot().lines[0]
        // The whole character goes and the tail shifts by two. Measured against tmux 3.5a: `ab\u4e2d\u6587test`
        // with a DCH 1 on \u4e2d's left half reads `ab\u6587test` there. Removing the single cell instead leaves
        // a blank the program never asked for - a gap in a row that has visibly shifted.
        //
        // The space before `test` is \u6587's own right half, which [rowText] counts because it walks cells:
        // tmux's capture drops a continuation cell, so its reading of the same row has no space there.
        assertThat(row.rowText()).isEqualTo("\u6587 test")
        // Invariant: every remaining wide cell still has its blank right half beside it.
        row.forEachIndexed { index, cell ->
            if (cell.wide) {
                val next = row.getOrNull(index + 1)
                assertThat(next).isNotNull()
                assertThat(next!!.wide).isFalse()
                assertThat(next.value).isEqualTo(' ')
            }
        }
    }
}
