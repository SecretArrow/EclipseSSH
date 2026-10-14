package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ESC, spelled out so the sequences below stay readable and copy-pasteable. */
private const val E = "\u001B"

/**
 * What the alternate screen switch does to the screen, the cursor and the pen, measured against tmux
 * 3.5a.
 *
 * `ESC [ ? 47 h`, `1047 h` and `1049 h` all swap the shell's screen for a second one to paint over.
 * The two things they do *not* agree on are the cursor and the pen: 1049 is the pair that saves both
 * on the way in and puts them back on the way out, and 47 and 1047 are one value shared by the two
 * screens, so where the alternate screen leaves them is where the primary resumes.
 *
 * Every rule here is a measurement rather than a preference, and the oracle is a tmux pane replayed
 * with the same bytes and captured with `capture-pane -p -e` and `display -p '#{cursor_y},#{cursor_x}'`.
 * `testing/tmux-replay/reproductions/screen-fill.raw` and `screen-switch.raw` are the same case set as
 * recordings, and `griddiff.py --attrs` diffs our grid and cursor against that pane's, so a change that
 * breaks one of these breaks the harness too.
 *
 * The fill is the reason this is a file of its own. A cell an erase, a scroll, an insert or a delete
 * *vacates* takes the pen's background - that is BCE, and it is pinned in [WideCharacterBoundaryTest].
 * A cell a screen switch or a resize *creates* takes none, and a fill from the pen is not a small
 * error: tmux believes those cells are default, so it never repaints them, and the tint stays for as
 * long as the screen does.
 */
class AlternateScreenSwitchTest {
    private fun buffer(columns: Int = 20, rows: Int = 8) =
        AnsiTerminalBuffer(columns = columns, rows = rows, scrollbackLimit = 100)

    /** The background of every cell of every row on the current screen, as one string per row. */
    private fun backgrounds(buffer: AnsiTerminalBuffer): List<String> = buffer.snapshot().lines.map { line ->
        line.joinToString("") { if (it.style.background.isDefault) "." else "#" }
    }

    private fun text(buffer: AnsiTerminalBuffer): List<String> =
        buffer.snapshot().lines.map { line -> line.joinToString("") { it.value.toString() }.trimEnd() }

    // --- what a switch fills with -----------------------------------------------------------------

    @Test
    fun `entering the alternate screen with a pen background leaves it unpainted`() {
        val buffer = buffer()
        buffer.feed("$E[2J$E[H$E[44m$E[?1049h")

        // Measured: tmux reads an empty capture here, with no `ESC [ 4 4 m` on it at all. The blue is
        // the shell's pen, and the alternate screen has never been painted with anything.
        assertThat(backgrounds(buffer).toSet()).containsExactly("....................")
    }

    @Test
    fun `entering the alternate screen does not move the cursor`() {
        val buffer = buffer()
        buffer.feed("$E[3;5H$E[?1049h")

        // Measured: tmux reports the cursor at row 2, column 4 after this, for all three of 47, 1047
        // and 1049. A program that switches and paints without positioning first starts where the shell
        // left off; resetting to 0,0 is what put a full-screen program's first line in the wrong place.
        val snapshot = buffer.snapshot()
        assertThat(snapshot.cursorRow).isEqualTo(2)
        assertThat(snapshot.cursorColumn).isEqualTo(4)
    }

    @Test
    fun `a second entry is a fresh screen, not the one that was left`() {
        val buffer = buffer()
        buffer.feed("$E[?1049h$E[42mCYAN$E[?1049l")
        buffer.feed("$E[45m$E[?1049h")

        assertThat(text(buffer).all { it.isEmpty() }).isTrue()
        assertThat(backgrounds(buffer).toSet()).containsExactly("....................")
    }

    @Test
    fun `the scroll region does not survive a switch`() {
        val buffer = buffer()
        buffer.feed("$E[3;6r$E[?1049h$E[8;1H$E[41mX")

        // Measured: the X lands on row 7 rather than being clamped into the primary's region, so the
        // region the shell set is not the one the alternate screen paints in.
        val lines = text(buffer)
        assertThat(lines[7]).isEqualTo("X")
        assertThat(lines.take(7).all { it.isEmpty() }).isTrue()
    }

    // --- what the way out puts back -----------------------------------------------------------------

    @Test
    fun `1049 puts the pen and the cursor back where the switch found them`() {
        val buffer = buffer()
        buffer.feed("$E[41m$E[3;5H$E[?1049h$E[42m$E[?1049l")
        buffer.feed("X")

        // Measured: the X is red, not the green set inside the alternate screen, and it is on row 2,
        // column 4 - the row and column the switch saved.
        val snapshot = buffer.snapshot()
        assertThat(snapshot.cursorRow).isEqualTo(2)
        assertThat(snapshot.cursorColumn).isEqualTo(5)
        assertThat(snapshot.lines[2][4].value).isEqualTo('X')
        assertThat(backgrounds(buffer)[2]).isEqualTo("....#...............")
    }

    @Test
    fun `47 and 1047 leave the pen where the alternate screen left it`() {
        for (mode in listOf("47", "1047")) {
            val buffer = buffer()
            // A green cell on the primary, to compare against: the pen is one value, so what the
            // alternate screen set is what the primary resumes with.
            buffer.feed("$E[42mG$E[41m$E[1;3H")
            buffer.feed("$E[?${mode}h$E[42m$E[?${mode}l")
            buffer.feed("Y")

            val row = buffer.snapshot().lines[0]
            assertThat(row[2].value).isEqualTo('Y')
            assertThat(row[2].style.background).isEqualTo(row[0].style.background)
        }
    }

    @Test
    fun `47 and 1047 leave the cursor where the alternate screen left it`() {
        for (mode in listOf("47", "1047")) {
            val buffer = buffer()
            buffer.feed("$E[3;5H$E[?${mode}h$E[6;9H$E[?${mode}l")
            buffer.feed("Z")

            // Measured: the Z lands on row 5, column 8 - where the alternate screen moved the cursor to.
            // Restoring the primary's saved position instead is what put a program's next write back up
            // at the row the switch started from.
            val snapshot = buffer.snapshot()
            assertThat(snapshot.cursorRow).isEqualTo(5)
            assertThat(snapshot.cursorColumn).isEqualTo(9)
            assertThat(snapshot.lines[5][8].value).isEqualTo('Z')
        }
    }

    // --- what a resize fills with -------------------------------------------------------------------

    @Test
    fun `a resize pads new rows with unpainted cells rather than the pen's background`() {
        // 20 columns is the narrowest this buffer takes, so the padding is read at 20 and the extra
        // rows are what the test is about.
        val buffer = buffer(columns = 20, rows = 5)
        buffer.feed("$E[41mABC")
        buffer.resize(20, 8)

        // Measured: growing a tmux pane from 10x3 to 10x6 with `ESC [ 41 m` in force leaves the three
        // new rows with nothing on them, and the padded columns of the line that was there carry
        // `ESC [ 49 m`. A resize creates cells; it does not vacate them.
        val rows = backgrounds(buffer)
        assertThat(rows[0]).isEqualTo("###.................")
        assertThat(rows.drop(1)).hasSize(7)
        assertThat(rows.drop(1).toSet()).containsExactly("....................")
    }

    @Test
    fun `a resize pads a widened line the same way`() {
        val buffer = buffer(columns = 20, rows = 5)
        buffer.feed("$E[41mABC")
        buffer.resize(30, 5)

        assertThat(backgrounds(buffer)[0]).isEqualTo("###" + ".".repeat(27))
    }
}
