package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Moving between commands, and asking how one ended, over a list of marks whose index is the line.
 *
 * The three functions here are small and each has one edge that is easy to get wrong, so the cases are
 * written out as mark lists rather than driven through the buffer: what is under test is the arithmetic
 * on the marks, and building a session to produce a given list of marks would test the parser as well
 * and obscure which of the two failed. The lists are written so that the line each mark sits on is
 * visible in the fixture, which is the whole of their meaning.
 *
 * The session the lists describe is two commands, the first of which failed:
 *
 * ```
 * 0  prompt  A B        "user@host:~$ "
 * 1                     "grep nope file"
 * 2  output  C          "grep: file: No such file or directory"
 * 3  finished D;2 A B   <- the shell's own boundary: one row, two commands' worth of fact
 * 4  output  C          ""
 * 5  finished D;0
 * ```
 */
class PromptReviewTest {
    private fun prompt(vararg column: Int): PromptMark =
        PromptMark(setOf(PromptMarkKind.PROMPT, PromptMarkKind.INPUT), inputColumn = column.firstOrNull() ?: 13)

    private fun finished(status: Int?): PromptMark =
        PromptMark(setOf(PromptMarkKind.FINISHED), exitCode = status)

    private fun session(): List<PromptMark?> = listOf(
        prompt(),
        null,
        PromptMark(setOf(PromptMarkKind.OUTPUT)),
        // The boundary row: the first command's end and the second command's prompt, on one line.
        prompt().and(finished(2)),
        null,
        finished(0),
    )

    @Test
    fun `the prompt before a line is the nearest one above it`() {
        val marks = session()

        assertThat(previousPrompt(marks, 2)).isEqualTo(0)
        assertThat(previousPrompt(marks, 4)).isEqualTo(3)
        assertThat(previousPrompt(marks, 6)).isEqualTo(3)
    }

    @Test
    fun `a prompt is not its own predecessor`() {
        val marks = session()

        // A reader standing on a prompt asking for the previous command must be sent past it, or the
        // move does nothing and the reader is stuck.
        assertThat(previousPrompt(marks, 3)).isEqualTo(0)
        assertThat(nextPrompt(marks, 3)).isNull()
    }

    @Test
    fun `there is nothing before the first prompt or after the last`() {
        val marks = session()

        assertThat(previousPrompt(marks, 0)).isNull()
        assertThat(nextPrompt(marks, 5)).isNull()
    }

    @Test
    fun `the next prompt from above the top is the first one`() {
        val marks = session()

        assertThat(nextPrompt(marks, -1)).isEqualTo(0)
        assertThat(nextPrompt(marks, 0)).isEqualTo(3)
    }

    @Test
    fun `a session with no prompts has no neighbour in either direction`() {
        val marks = listOf<PromptMark?>(null, PromptMark(setOf(PromptMarkKind.OUTPUT)), null)

        assertThat(previousPrompt(marks, 2)).isNull()
        assertThat(nextPrompt(marks, 0)).isNull()
        assertThat(previousPrompt(emptyList(), 0)).isNull()
        assertThat(nextPrompt(emptyList(), 0)).isNull()
    }

    @Test
    fun `the status behind a line is the one the shell reported for its own command`() {
        val marks = session()

        // Inside the first command, either side of its output.
        assertThat(commandEndAt(marks, 1)?.exitCode).isEqualTo(2)
        assertThat(commandEndAt(marks, 2)?.exitCode).isEqualTo(2)
        // And inside the second.
        assertThat(commandEndAt(marks, 4)?.exitCode).isEqualTo(0)
        assertThat(commandEndAt(marks, 5)?.exitCode).isEqualTo(0)
    }

    @Test
    fun `the boundary row reports the command it ends as well as the one it starts`() {
        val marks = session()

        // Line 3 carries both `D;2` and the next prompt's `A` and `B`. A rule that handed the row only
        // to the new command would lose the status of the command just finished - which is the status a
        // reader is looking at the row above it for.
        assertThat(commandEndAt(marks, 3)?.exitCode).isEqualTo(2)
    }

    @Test
    fun `a command that never reported an end does not borrow the next one's status`() {
        // The first command's `D` never arrived: the shell is still running it, or was killed.
        val marks = listOf<PromptMark?>(
            prompt(),
            PromptMark(setOf(PromptMarkKind.OUTPUT)),
            prompt(),
            finished(0),
        )

        assertThat(commandEndAt(marks, 1)).isNull()
        // The next command is fine, and is reported on its own row.
        assertThat(commandEndAt(marks, 3)?.exitCode).isEqualTo(0)
    }

    @Test
    fun `a command that finished without a status is not the same as one that said nothing`() {
        val marks = listOf<PromptMark?>(prompt(), finished(null))

        // The mark comes back and its status is null: "it finished and the shell did not say how" has to
        // be distinguishable from "the shell has not said anything", and null alone cannot carry both.
        assertThat(commandEndAt(marks, 1)).isNotNull()
        assertThat(commandEndAt(marks, 1)?.exitCode).isNull()
    }

    @Test
    fun `a line past the end of the marks is answered, not thrown at`() {
        val marks = session()

        // The reader asks from wherever the window happens to be, and the window can be past the marks.
        assertThat(commandEndAt(marks, 6)?.exitCode).isEqualTo(0)
        assertThat(previousPrompt(marks, 99)).isEqualTo(3)
        assertThat(nextPrompt(marks, 99)).isNull()
        assertThat(commandEndAt(emptyList(), 0)).isNull()
    }

    @Test
    fun `the line an end is on is the line the mark is handed back from`() {
        val marks = session()

        // The two are the same rule read for different halves, so they cannot disagree: `commandEndAt`
        // hands back the mark, `commandEndLine` the line it sits on.
        assertThat(commandEndLine(marks, 1)).isEqualTo(3)
        assertThat(commandEndAt(marks, 1)).isEqualTo(marks[3])
        assertThat(commandEndLine(marks, 4)).isEqualTo(5)
        // The boundary row again: the command above ends there, and it is also a prompt.
        assertThat(commandEndLine(marks, 3)).isEqualTo(3)
        assertThat(commandEndLine(marks, 6)).isEqualTo(5)
        assertThat(commandEndLine(emptyList(), 0)).isNull()
    }

    @Test
    fun `a line is scrolled to the top of the window it is asked to appear in`() {
        // A ten-row window whose bottom sits on line 100: lines 91 to 100 are on screen already, and the
        // answer for any of them is "do not scroll" - the view stays where it is, following the output.
        assertThat(offsetToShowLine(anchor = 100, line = 100, rows = 10)).isEqualTo(0)
        assertThat(offsetToShowLine(anchor = 100, line = 91, rows = 10)).isEqualTo(0)
        // One line above the window, so one line of scrolling puts it on the top row.
        assertThat(offsetToShowLine(anchor = 100, line = 90, rows = 10)).isEqualTo(1)
        assertThat(offsetToShowLine(anchor = 100, line = 50, rows = 10)).isEqualTo(41)
    }

    @Test
    fun `a line near the top of the buffer is asked for by an offset the buffer clamps`() {
        // The arithmetic does not know how much scrollback there is; the buffer does, and clamps what it
        // is given. Asking for the top of the window is still the right request - the line lands wherever
        // there is room, which is the top of the buffer, rather than being pushed off the bottom of the
        // window to reach the top of it.
        assertThat(offsetToShowLine(anchor = 100, line = 3, rows = 10)).isEqualTo(88)
        assertThat(offsetToShowLine(anchor = 9, line = 0, rows = 10)).isEqualTo(0)
    }
}
