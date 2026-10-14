package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** ESC, spelled out so the sequences below stay readable and copy-pasteable. */
private const val E = "\u001B"

/**
 * Damage names every row that changed, and this is what says so.
 *
 * [TerminalFrame.damageFrom] and [TerminalFrame.damageTo] are what tells the renderer which rows to
 * erase before it draws the next frame. tmux sends only the cells it believes changed, so a row the app
 * failed to repaint is never resent: the damage is the app's own copy of that belief, and a row missing
 * from it keeps whatever ink was there for the life of the session. That is the shape of the ghost the
 * erase families were fixed for, and it is why this test exists as a *property* rather than as a list of
 * expectations - the range has to cover what changed, and a case it misses is a case nobody wrote down.
 *
 * So each case feeds a sequence, takes a frame after every step, and asks the same question of every
 * step: of the buffer lines the frame shows, which ones hold different cells from the frame before it,
 * and are they all inside the range? A `damageFull` frame is exempt because it claims everything. The
 * comparison is by absolute buffer line rather than by position in the window, because a step that
 * appends a line moves the window without moving the text.
 *
 * What this does not claim: that the range is *tight*. Over-reporting only costs a repaint, so a range
 * wider than the rows that changed is not a defect and is not looked for here. Neither is the initial
 * full-screen damage, nor the cursor - the renderer paints the cursor from the frame's own position, not
 * from the damage range.
 */
class TerminalDamagePrecisionTest {
    /**
     * Feed each step, frame after each, and assert every changed row is inside the damage range.
     *
     * The frame before the first step is taken and discarded, so that the first step is compared against
     * a real window rather than against the buffer's initial all-dirty state.
     */
    private fun damageCoversChanges(vararg steps: String) {
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 5, scrollbackLimit = 1000)
        var before = buffer.frame()
        steps.forEachIndexed { index, step ->
            buffer.feed(step)
            val after = buffer.frame()
            val changed = changedLines(before, after)
            if (!after.damageFull) {
                val uncovered = changed.filter { it < after.damageFrom || it > after.damageTo }
                assertWithMessage(
                    "step ${index + 1} (`${step.replace(E, "ESC")}`) changed $changed and left " +
                        "$uncovered outside ${after.damageFrom}..${after.damageTo}",
                ).that(uncovered).isEmpty()
            }
            before = after
        }
    }

    /** The lines the frame shows, by absolute buffer line, so two frames are comparable across a scroll. */
    private fun absoluteRows(frame: TerminalFrame): Map<Int, List<TerminalCell>> =
        frame.lines.withIndex().associate { (index, line) -> frame.firstLine + index to line }

    /** The absolute lines the second frame shows holding different cells than the first did. */
    private fun changedLines(before: TerminalFrame, after: TerminalFrame): Set<Int> {
        val was = absoluteRows(before)
        return absoluteRows(after).filter { (line, cells) -> was[line] != cells }.keys
    }

    @Test
    fun `printing a character marks the row it lands on`() {
        damageCoversChanges("$E[1;1Habc", "def", "$E[1;10HX", "Y")
    }

    @Test
    fun `a line that wraps marks both rows`() {
        damageCoversChanges("$E[1;1H" + "x".repeat(20), "y")
    }

    @Test
    fun `moving the cursor alone changes nothing, so nothing needs marking`() {
        damageCoversChanges("$E[1;1Habc", "$E[5;9H", "$E[1;1H", "$E[3;3H")
    }

    @Test
    fun `the erase families mark the row they blank`() {
        damageCoversChanges(
            "$E[1;1HABCDEFGH",
            "$E[1;3H$E[0K",
            "$E[1;1HABCDEFGH",
            "$E[1;3H$E[1K",
            "$E[1;1HABCDEFGH",
            "$E[1;3H$E[2K",
            "$E[1;1HABCDEFGH",
            "$E[1;3H$E[2X",
            "$E[1;1HABCDEFGH",
            "$E[1;3H$E[0J",
        )
    }

    @Test
    fun `the insert and delete families mark the row they shift`() {
        damageCoversChanges(
            "$E[1;1HABCDEFGH",
            "$E[1;3H$E[2P",
            "$E[1;1HABCDEFGH",
            "$E[1;3H$E[2@",
        )
    }

    @Test
    fun `a scroll marks every row of its region`() {
        damageCoversChanges(
            "$E[1;1HAAA$E[2;1HBBB$E[3;1HCCC$E[4;1HDDD$E[5;1HEEE",
            "$E[1;5r$E[1;1H$E[1S",
            "$E[1;1H$E[1T",
            "$E[1;3r$E[1;1H$E[1L",
            "$E[1;1H$E[1M",
        )
    }

    @Test
    fun `a line scrolled in at the bottom of the screen marks the new line`() {
        damageCoversChanges("$E[5;1H$E[5;20H", "\n", "more")
    }

    @Test
    fun `a screen switch marks everything, because everything moved`() {
        damageCoversChanges("$E[1;1Habc", "$E[?1049h", "xyz", "$E[?1049l")
    }

    @Test
    fun `an erase that reaches past the end of its line marks the row`() {
        damageCoversChanges("$E[1;1HABCDE", "$E[1;3H$E[99X", "$E[1;1H$E[99P")
    }

    @Test
    fun `a pen change with no cell change marks nothing, and is not asked to`() {
        damageCoversChanges("$E[1;1Habc", "$E[41m", "$E[4m", "$E[0m")
    }

    @Test
    fun `a wide character marks the row it spans`() {
        damageCoversChanges("$E[1;1H0123中67", "$E[1;5H$E[1P", "$E[1;1H$E[1;3H$E[1@")
    }

    @Test
    fun `a title or a mode change marks nothing, and the grid is what is asked about`() {
        damageCoversChanges("$E[1;1Habc", "$E]0;a title$E\\", "$E[?25l", "$E[?25h")
    }
}
