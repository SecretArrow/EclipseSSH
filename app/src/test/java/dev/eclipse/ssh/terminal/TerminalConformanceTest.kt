package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/** ESC, spelled out so the cases below stay readable and copy-pasteable. */
private const val E = "\u001B"

/** 中 - one character, two columns. */
private const val WIDE = "中"

/** A row of twenty cells all carrying one background: what [backgroundKeys] prints for a uniform row. */
private const val P = "aaaaaaaaaaaaaaaaaaaa"

/** A row whose first three cells carry a background and whose other seventeen carry none. */
private const val P3 = "aaa................."

/** A row of nothing but unpainted cells, which is what a case that never touched the row leaves. */
private const val NONE = "...................."

/**
 * One line per case: an input sequence, and the screen it must leave behind.
 *
 * This is the sweep, not the argument. The rules the cases encode are stated with the measurement
 * behind each one in [WideCharacterBoundaryTest] (where a range may cut a wide character) and
 * [AlternateScreenSwitchTest] (what a screen switch and a resize fill with); this file exists so that
 * every *case* of the erase, insert, delete and scroll families is mapped, and so that adding one
 * costs a line. A rule that is only in a narrative test can be missed by a reader looking for the
 * family it belongs to; a rule that is only in this table has no argument behind it.
 *
 * Every expectation is the grid tmux 3.5a ends on for the same bytes, read out of `capture-pane`.
 * `testing/tmux-replay/reproductions/conformance.raw` is this table's erase, insert, delete and
 * parameter cases as one recording, one case per row of a shared 20x20 pane, and
 * `reproductions/scroll-families.raw` is the scroll cases, one per three-row band; `replay.sh --attrs`
 * diffs both against tmux, so a change that breaks a case here breaks the harness too. The table's
 * cases are self-contained where the fixtures' share a screen, so a row here reads the same text as
 * its fixture row but its background *pattern* differs: the fixture's later rows sit on the fill the
 * earlier ED cases left, where a self-contained case starts on an unpainted screen.
 *
 * Three things the table deliberately does not carry, each for a reason:
 * - **A range that cuts a wide character in half.** tmux keeps the surviving half as a cell with no
 *   glyph and this emulator takes the whole character, which is a choice between two emulators rather
 *   than a rule; it is argued and pinned in [WideCharacterBoundaryTest].
 * - **A count past the line's remaining columns for an insert.** Measured, tmux moves each cell right
 *   only when its destination is still on the line and leaves the ones that are not where they are, so
 *   `ESC [ 1 7 @` on a full line leaves `AB DEFGHIJKLMNOPQRSC` where this emulator shifts the line and
 *   blanks the gap - the reading every other emulator takes. Pinned in
 *   [InsertWithACountPastTheLineTest] rather than in this table, because a table whose premise is "the
 *   screen tmux ends on" cannot carry a case whose expectation is that it does not.
 * - **The alternate screen.** It is a family of its own with its own fixture, and
 *   [AlternateScreenSwitchTest] is where its rules live.
 */
/**
 * One case of the table: its name, its bytes, and the screen it must leave.
 *
 * Public only because JUnit's parameterized runner is handed the class as a constructor parameter and a
 * private one cannot be; nothing outside this file builds one.
 */
class ConformanceCase(
    val what: String,
    val input: String,
    val screen: List<String>,
    val painted: List<String>?,
    val columns: Int,
    val rows: Int,
    val cursor: Pair<Int, Int>?,
) {
    /** JUnit names a parameterized case by its own toString, and this is what makes a failure name it. */
    override fun toString(): String = what
}

/**
 * One case: a name, the bytes, and the screen.
 *
 * `screen` lists the rows that carry something, from the top; the rest are asserted blank and
 * unpainted, which is also the assertion that the sequence did not spill. `painted` is optional and
 * lists the same rows' background patterns, in the vocabulary [backgroundKeys] prints: `.` for a cell
 * with no background and a letter for each distinct background the row uses, in the order it first
 * uses it - the same labels the harness's own dump prints, so a case can be read against its fixture
 * row by eye.
 */
private fun c(
    what: String,
    input: String,
    vararg screen: String,
    painted: List<String>? = null,
    columns: Int = 20,
    rows: Int = 5,
    cursor: Pair<Int, Int>? = null,
): ConformanceCase {
    val pad = (rows - screen.size).coerceAtLeast(0)
    return ConformanceCase(
        what = what,
        input = input,
        screen = screen.toList() + List(pad) { "" },
        painted = painted?.let { it + List((rows - it.size).coerceAtLeast(0)) { NONE } },
        columns = columns,
        rows = rows,
        cursor = cursor,
    )
}

/**
 * One character per column: `.` for a cell with no background, and a letter for each distinct
 * background the row uses, assigned in the order it first uses one.
 *
 * The letters are labels and not colours, which is what makes them comparable: a palette entry is drawn
 * as the theme's own shade of it here and as the entry itself by tmux, so comparing colours would
 * compare two themes. What the labels compare is the pattern - which cells share a background and which
 * are unpainted - and a cell that should carry a colour and does not reads as `.` here.
 */
private fun backgroundKeys(line: List<TerminalCell>): String {
    val labels = HashMap<Int, Char>()
    val keys = StringBuilder(line.size)
    line.forEach { cell ->
        val background = cell.style.background.value
        if (background == TerminalColor.COLOR_DEFAULT) {
            keys.append('.')
        } else {
            check(labels.size < LABELS.length) { "a row with more than ${LABELS.length} backgrounds" }
            keys.append(labels.getOrPut(background) { LABELS[labels.size] })
        }
    }
    return keys.toString()
}

private const val LABELS = "abcdefghijklmnopqrstuvwxyz"

/** What one drawn row reads as: trailing blanks dropped, a wide character's right half not printed. */
private fun List<TerminalCell>.text(): String {
    val text = StringBuilder()
    forEachIndexed { index, cell ->
        if (index > 0 && this[index - 1].wide) return@forEachIndexed
        text.append(cell.value).append(cell.combining)
    }
    return text.toString().trimEnd()
}

@RunWith(Parameterized::class)
class TerminalConformanceTest(private val case: ConformanceCase) {
    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun cases(): List<ConformanceCase> = CASES
    }

    @Test
    fun `the screen is the one tmux ends on`() {
        val buffer = AnsiTerminalBuffer(columns = case.columns, rows = case.rows, scrollbackLimit = 100)
        buffer.feed(case.input)

        val lines = buffer.snapshot().lines
        assertThat(lines.map { it.text() }).isEqualTo(case.screen)
        case.painted?.let { assertThat(lines.map { backgroundKeys(it) }).isEqualTo(it) }
        case.cursor?.let { (row, column) ->
            val frame = buffer.frame()
            assertThat(frame.cursorRow to frame.cursorColumn).isEqualTo(row to column)
        }
    }
}

/**
 * The cases, grouped by family. Every row is one line so that a family reads as a block and a new case
 * is an insertion rather than an edit.
 */
private val CASES = listOf(
    // --- ED: the whole screen, and every cell it vacates takes the pen ---------------------------

    c(
        "ED 2 fills the screen with the pen, wherever the cursor is",
        "$E[44m$E[1;1HHELLO$E[46m$E[3;4H$E[2J",
        "", "", "", "", "",
        painted = listOf(P, P, P, P, P),
    ),
    c(
        "ED 0 fills from the cursor to the end of the screen with the pen",
        "$E[44m$E[2J$E[46m$E[2;5H$E[0J",
        "", "", "", "", "",
        painted = listOf(P, "aaaabbbbbbbbbbbbbbbb", P, P, P),
    ),
    c(
        "ED 1 fills from the start of the screen to the cursor inclusive",
        "$E[44m$E[2J$E[43m$E[3;5H$E[1J",
        "", "", "", "", "",
        painted = listOf(P, P, "aaaaabbbbbbbbbbbbbbb", P, P),
    ),

    // --- EL: one line, the same three ranges -----------------------------------------------------

    c("EL 0 blanks from the cursor to the margin", "$E[44mABCDEFGH$E[1;5H$E[0K", "ABCD", painted = listOf(P)),
    c("EL 1 blanks from the start of the line to the cursor inclusive", "$E[42mABCDEFGH$E[1;4H$E[1K", "    EFGH", painted = listOf("aaaaaaaa............")),
    c("EL 2 blanks the whole line", "$E[43mABCDEFGH$E[1;4H$E[2K", "", painted = listOf(P)),
    c("EL 0 with the cursor on a wide character's left half takes the whole character", "$E[46m0123${WIDE}67$E[1;5H$E[0K", "0123", painted = listOf(P)),
    c("EL 1 with the cursor on a wide character's right half takes the whole character", "$E[46m0123${WIDE}67$E[1;6H$E[1K", "      67", painted = listOf("aaaaaaaa............")),
    c("EL 1 with the cursor before a wide character leaves it whole", "$E[46m0123${WIDE}67$E[1;2H$E[1K", "  23${WIDE}67", painted = listOf("aaaaaaaa............")),

    // --- ECH: exactly the cells it names, and no widening ---------------------------------------

    c("ECH 1 blanks the one cell it names", "$E[41mABCDEFGH$E[1;3H$E[1X", "AB DEFGH", painted = listOf("aaaaaaaa............")),
    c("ECH 0 is ECH 1", "$E[41mABCDEFGH$E[1;3H$E[0X", "AB DEFGH", painted = listOf("aaaaaaaa............")),
    c("ECH past the text blanks the cells it names", "$E[41mABCDEFGH$E[1;9H$E[3X", "ABCDEFGH", painted = listOf("aaaaaaaaaaa.........")),
    c("ECH past the last column stops at the margin", "$E[41mABCDEFGH$E[1;18H$E[10X", "ABCDEFGH", painted = listOf("aaaaaaaa.........aaa")),
    c("ECH 99 from the third column blanks to the margin", "$E[41mABCDEFGH$E[1;3H$E[99X", "AB", painted = listOf(P)),
    c("ECH 2 covering a whole wide character takes it", "$E[41m0123${WIDE}67$E[1;5H$E[2X", "0123  67", painted = listOf("aaaaaaaa............")),

    // --- DCH: the range is widened to whole characters, and the tail takes the pen ---------------

    c("DCH 1 on an ordinary cell shifts the line one cell", "$E[41mABCDEFGH$E[1;3H$E[1P", "ABDEFGH", painted = listOf("aaaaaaa............a")),
    c("DCH 0 is DCH 1", "$E[41mABCDEFGH$E[1;3H$E[0P", "ABDEFGH", painted = listOf("aaaaaaa............a")),
    c("DCH on a full line shifts it and blanks the last cell", "$E[42mABCDEFGHIJKLMNOPQRST$E[1;5H$E[1P", "ABCDFGHIJKLMNOPQRST", painted = listOf(P)),
    c("DCH on a wide character's left half takes the whole character", "$E[41m0123${WIDE}67$E[1;5H$E[1P", "012367", painted = listOf("aaaaaa............aa")),
    c("DCH 2 on a wide character's left half is the same delete", "$E[41m0123${WIDE}67$E[1;5H$E[2P", "012367", painted = listOf("aaaaaa............aa")),
    c("DCH on a wide character's right half leaves the line alone", "$E[44m0123${WIDE}67$E[1;6H$E[1P", "0123${WIDE}67", painted = listOf("aaaaaaaa............")),
    c("DCH 3 from a wide character's right half spends one cell on the half", "$E[41m0123${WIDE}67$E[1;6H$E[3P", "0123${WIDE}", painted = listOf("aaaaaa............aa")),

    // --- ICH: the cells shift, and a whole character moves with them -----------------------------

    c("ICH 1 on an ordinary cell shifts the line one cell", "$E[41mABCDEFGH$E[1;3H$E[1@", "AB CDEFGH", painted = listOf("aaaaaaaaa...........")),
    c("ICH 0 is ICH 1", "$E[41mABCDEFGH$E[1;3H$E[0@", "AB CDEFGH", painted = listOf("aaaaaaaaa...........")),
    c("ICH on a full line drops the last cell", "$E[44mABCDEFGHIJKLMNOPQRST$E[1;5H$E[1@", "ABCD EFGHIJKLMNOPQRS", painted = listOf(P)),
    c("ICH on a wide character's left half pushes the pair along", "$E[43m0123${WIDE}67$E[1;5H$E[1@", "0123 ${WIDE}67", painted = listOf("aaaaaaaaa...........")),

    // --- IL, DL, SU and SD: what a scroll vacates takes the pen, and the region is the bounds ----

    c(
        "IL 1 at the top margin drops the region's last line",
        "$E[41mAAA$E[2;1HBBB$E[3;1HCCC$E[1;3r$E[1;1H$E[1L$E[r",
        "", "AAA", "BBB",
        painted = listOf(P, P3, P3),
        cursor = 0 to 0,
    ),
    c(
        "IL 2 drops the region's last two lines",
        "$E[41mAAA$E[2;1HBBB$E[3;1HCCC$E[1;3r$E[1;1H$E[2L$E[r",
        "", "", "AAA",
        painted = listOf(P, P, P3),
        cursor = 0 to 0,
    ),
    c(
        "DL 1 at the top margin drops the region's first line",
        "$E[41mAAA$E[2;1HBBB$E[3;1HCCC$E[1;3r$E[1;1H$E[1M$E[r",
        "BBB", "CCC", "",
        painted = listOf(P3, P3, P),
        cursor = 0 to 0,
    ),
    c(
        "DL 2 drops the region's first two lines",
        "$E[41mAAA$E[2;1HBBB$E[3;1HCCC$E[1;3r$E[1;1H$E[2M$E[r",
        "CCC", "", "",
        painted = listOf(P3, P, P),
        cursor = 0 to 0,
    ),
    c(
        "SU 1 scrolls the region up",
        "$E[41mAAA$E[2;1HBBB$E[3;1HCCC$E[1;3r$E[1;1H$E[1S$E[r",
        "BBB", "CCC", "",
        painted = listOf(P3, P3, P),
        cursor = 0 to 0,
    ),
    c(
        "SD 1 scrolls the region down",
        "$E[41mAAA$E[2;1HBBB$E[3;1HCCC$E[1;3r$E[1;1H$E[1T$E[r",
        "", "AAA", "BBB",
        painted = listOf(P, P3, P3),
        cursor = 0 to 0,
    ),
    c(
        "IL 1 with the cursor on the bottom margin blanks only that line",
        "$E[41mAAA$E[2;1HBBB$E[1;2r$E[2;1H$E[1L$E[r",
        "AAA", "",
        painted = listOf(P3, P),
        cursor = 0 to 0,
    ),
    c(
        "IL past the region's height blanks the region",
        "$E[41mAAA$E[2;1HBBB$E[3;1HCCC$E[1;3r$E[1;1H$E[99L$E[r",
        "", "", "",
        painted = listOf(P, P, P),
        cursor = 0 to 0,
    ),
)
