package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ESC, built from its code point so this file contains no literal control byte. */
private val ESC: String = Char(0x1B).toString()

/**
 * `OSC 133` is the shell telling the terminal which line each of its own prompts and commands is on,
 * and the line index is the whole of what the terminal keeps.
 *
 * Two halves of the rule are tested here and they fail differently. The first is *reading*: which
 * payloads become marks, which are refused, and what a mark carries - the exit status of a command, the
 * column the user's typing starts at. The second is *keeping*: the marks are stored beside the lines
 * and have to move with them through every operation that adds, drops or reorders a line, so each of
 * those gets a case below, including the ones that move a line out of the screen and into the history
 * where the reader is most likely to want it.
 *
 * The sequences are spelled out rather than driven through a shell, because the point is what the
 * terminal does with the bytes a shell sends and not what any particular shell chooses to send.
 */
class PromptMarkTest {
    private fun buffer(rows: Int = 4, scrollback: Int = 2_000) =
        AnsiTerminalBuffer(columns = 20, rows = rows, scrollbackLimit = scrollback)

    /** One `OSC 133` with [body] as its payload, terminated with ST. */
    private fun mark(body: String): String = "$ESC]133;$body$ESC\\"

    private fun row(frame: TerminalFrame, line: Int): String =
        frame.lines[line - frame.firstLine].map { it.value }.joinToString("").trimEnd()

    private fun kindsAt(frame: TerminalFrame, line: Int): Set<PromptMarkKind>? =
        promptMarkAt(frame, line)?.kinds

    @Test
    fun `a session's marks land on the lines its own text did`() {
        val buffer = buffer()

        buffer.feed(
            mark("A") + "user@host:~$ " + mark("B") + "ls\r\n" +
                mark("C") + "notes.txt\r\n" + mark("D;0"),
        )

        val frame = buffer.frame()
        assertThat(row(frame, 0)).isEqualTo("user@host:~$ ls")
        assertThat(kindsAt(frame, 0)).containsExactly(PromptMarkKind.PROMPT, PromptMarkKind.INPUT)
        assertThat(row(frame, 1)).isEqualTo("notes.txt")
        assertThat(kindsAt(frame, 1)).containsExactly(PromptMarkKind.OUTPUT)
        // The command finished on the line below its output, and nothing was printed there.
        assertThat(kindsAt(frame, 2)).containsExactly(PromptMarkKind.FINISHED)
        assertThat(promptMarkAt(frame, 2)?.exitCode).isEqualTo(0)
        assertThat(promptMarkAt(frame, 3)).isNull()
    }

    @Test
    fun `the input column is where the prompt ended`() {
        val buffer = buffer()

        buffer.feed(mark("A") + "user@host:~$ " + mark("B"))

        // Thirteen characters of prompt, so the typing starts at column 13 - which is what lets a reader
        // be handed `ls` rather than `user@host:~$ ls`.
        assertThat(promptMarkAt(buffer.frame(), 0)?.inputColumn).isEqualTo(13)
    }

    @Test
    fun `a prompt on two lines marks each of them`() {
        val buffer = buffer()

        buffer.feed(mark("A") + "~/src\r\n$ " + mark("B"))

        // A: the prompt started on the first line. B: it ended on the second, which is the line to type
        // on. They are separate lines here, so neither mark may be folded into the other.
        val frame = buffer.frame()
        assertThat(kindsAt(frame, 0)).containsExactly(PromptMarkKind.PROMPT)
        assertThat(kindsAt(frame, 1)).containsExactly(PromptMarkKind.INPUT)
        assertThat(promptMarkAt(frame, 1)?.inputColumn).isEqualTo(2)
    }

    @Test
    fun `two marks on one line keep both facts and the later column wins`() {
        val first = PromptMark(setOf(PromptMarkKind.PROMPT), inputColumn = 5)
        val second = PromptMark(setOf(PromptMarkKind.INPUT), inputColumn = 9)

        val merged = first.and(second)

        assertThat(merged.kinds).containsExactly(PromptMarkKind.PROMPT, PromptMarkKind.INPUT)
        assertThat(merged.inputColumn).isEqualTo(9)
    }

    @Test
    fun `a mark with no column or status of its own does not clear the one it joins`() {
        val finished = PromptMark(setOf(PromptMarkKind.FINISHED), exitCode = 3)

        // A shell that reuses a line for its next prompt is still describing the command that ended
        // there, and dropping the status would report a failure as an ordinary line.
        assertThat(finished.and(PromptMark(setOf(PromptMarkKind.PROMPT))).exitCode).isEqualTo(3)
    }

    @Test
    fun `a bare D says the command finished without saying how`() {
        val buffer = buffer()

        buffer.feed(mark("D"))

        assertThat(kindsAt(buffer.frame(), 0)).containsExactly(PromptMarkKind.FINISHED)
        // Not zero: a reader that reported this as a success would be inventing a status.
        assertThat(promptMarkAt(buffer.frame(), 0)?.exitCode).isNull()
    }

    @Test
    fun `only the status field of D is read`() {
        val buffer = buffer()

        buffer.feed(mark("D;1;the command failed"))

        assertThat(promptMarkAt(buffer.frame(), 0)?.exitCode).isEqualTo(1)
    }

    @Test
    fun `a status that is not a number is no status`() {
        val buffer = buffer()

        buffer.feed(mark("D;oops"))

        assertThat(kindsAt(buffer.frame(), 0)).containsExactly(PromptMarkKind.FINISHED)
        assertThat(promptMarkAt(buffer.frame(), 0)?.exitCode).isNull()
    }

    @Test
    fun `a payload that is not one of the four letters is not a mark`() {
        val buffer = buffer()

        buffer.feed(mark("A"))
        // The extension points around 133 are open-ended. A terminal that guessed at a meaning for any of
        // these would be reporting a mark the shell never made.
        buffer.feed(mark("P;kind=secondary"))
        buffer.feed(mark("k"))
        buffer.feed(mark("s"))
        buffer.feed(mark("i"))
        buffer.feed(mark("X"))
        buffer.feed(mark(""))

        val frame = buffer.frame()
        assertThat(kindsAt(frame, 0)).containsExactly(PromptMarkKind.PROMPT)
        assertThat(frame.promptMarks.count { it != null }).isEqualTo(1)
    }

    @Test
    fun `an OSC 133 with no payload at all is not a mark`() {
        val buffer = buffer()

        buffer.feed("$ESC]133$ESC\\")
        buffer.feed(mark("A"))

        // The first has no separator, so it is not a sequence this terminal can read - and it must not
        // have stopped the one that follows from being read.
        assertThat(kindsAt(buffer.frame(), 0)).containsExactly(PromptMarkKind.PROMPT)
    }

    @Test
    fun `a mark is a new frame but not a repaint`() {
        val buffer = buffer()
        buffer.feed("hello")
        buffer.frame()

        buffer.feed(mark("A"))
        val frame = buffer.frame()

        // The frame changes, because what the screen reports has changed...
        assertThat(frame.promptMarks[0]?.kinds).containsExactly(PromptMarkKind.PROMPT)
        // ...but no cell did, so the renderer has nothing to erase. A mark is not pixels.
        assertThat(frame.damageFull).isFalse()
        assertThat(frame.damageFrom).isEqualTo(-1)
    }

    @Test
    fun `a mark rides its line into the scrollback`() {
        val buffer = buffer()

        buffer.feed(mark("A") + "prompt\r\n" + "1\r\n2\r\n3\r\n4\r\n")

        // Four rows and five lines of text: the prompt is history now and the screen has moved past it.
        val live = buffer.frame()
        assertThat(live.firstLine).isEqualTo(2)
        assertThat(live.promptMarks.count { it != null }).isEqualTo(0)

        // Scrolled back, the mark is on the line it was made on, and the row that scrolled in below has
        // none of its own.
        val back = buffer.frame(scrollOffset = live.scrollCeiling)
        assertThat(row(back, 0)).isEqualTo("prompt")
        assertThat(kindsAt(back, 0)).containsExactly(PromptMarkKind.PROMPT)
        assertThat(promptMarkAt(back, 4)).isNull()
    }

    @Test
    fun `inserting lines moves the marks with their text, and deleting them moves it back`() {
        val buffer = buffer()
        buffer.feed(mark("A") + "aaa\r\n" + mark("A") + "bbb\r\n" + mark("A") + "ccc\r\n")

        buffer.feed("$ESC[2;4r$ESC[2;1H$ESC[L")
        val opened = buffer.frame()
        assertThat(row(opened, 1)).isEqualTo("")
        assertThat(promptMarkAt(opened, 1)).isNull()
        assertThat(row(opened, 2)).isEqualTo("bbb")
        assertThat(kindsAt(opened, 2)).containsExactly(PromptMarkKind.PROMPT)
        assertThat(row(opened, 3)).isEqualTo("ccc")
        assertThat(kindsAt(opened, 3)).containsExactly(PromptMarkKind.PROMPT)

        buffer.feed("$ESC[M")
        val closed = buffer.frame()
        assertThat(row(closed, 1)).isEqualTo("bbb")
        assertThat(kindsAt(closed, 1)).containsExactly(PromptMarkKind.PROMPT)
        assertThat(row(closed, 2)).isEqualTo("ccc")
        assertThat(kindsAt(closed, 2)).containsExactly(PromptMarkKind.PROMPT)
        assertThat(promptMarkAt(closed, 3)).isNull()
    }

    @Test
    fun `a whole-line erase drops the mark, and a partial one does not`() {
        // Each of `ESC [ K`'s three modes, because they differ in exactly this.
        val erased = buffer()
        erased.feed(mark("A") + "prompt" + "$ESC[2K")
        assertThat(promptMarkAt(erased.frame(), 0)).isNull()

        val toEnd = buffer()
        toEnd.feed(mark("A") + "prompt" + "$ESC[1;3H$ESC[K")
        assertThat(kindsAt(toEnd.frame(), 0)).containsExactly(PromptMarkKind.PROMPT)

        val toStart = buffer()
        toStart.feed(mark("A") + "prompt" + "$ESC[1;3H$ESC[1K")
        assertThat(kindsAt(toStart.frame(), 0)).containsExactly(PromptMarkKind.PROMPT)
    }

    @Test
    fun `erasing the screen drops its marks and keeps the history's`() {
        val buffer = buffer()
        buffer.feed(mark("A") + "prompt\r\n1\r\n2\r\n3\r\n4\r\n" + mark("A"))

        buffer.feed("$ESC[2J")

        // Everything on the screen is gone, and the shell has not yet said anything about what replaced
        // it, so no visible row is a marked one.
        val live = buffer.frame()
        assertThat(live.promptMarks.count { it != null }).isEqualTo(0)
        // The scrollback was not erased, and neither was the mark that describes it.
        assertThat(kindsAt(buffer.frame(scrollOffset = live.scrollCeiling), 0))
            .containsExactly(PromptMarkKind.PROMPT)
    }

    @Test
    fun `erasing the scrollback takes its marks with it`() {
        val buffer = buffer()
        buffer.feed(mark("A") + "prompt\r\n1\r\n2\r\n3\r\n4\r\n")

        buffer.feed("$ESC[3J")

        val frame = buffer.frame()
        assertThat(frame.firstLine).isEqualTo(0)
        assertThat(frame.promptMarks.count { it != null }).isEqualTo(0)
    }

    @Test
    fun `the oldest marks go when the scrollback is trimmed away`() {
        val buffer = buffer(rows = 4, scrollback = 2)

        // Eight lines through a four-row screen with a two-line scrollback: the prompt is long gone.
        buffer.feed(mark("A") + "prompt\r\n" + (1..8).joinToString("\r\n"))

        val frame = buffer.frame()
        assertThat(frame.totalLines).isEqualTo(6)
        assertThat(frame.promptMarks.count { it != null }).isEqualTo(0)
        // Two lines went and no more: the six that survive are the last six of the nine written.
        assertThat(row(buffer.frame(scrollOffset = frame.scrollCeiling), 0)).isEqualTo("3")
    }

    @Test
    fun `the alternate screen has no marks of its own, and gives the primary's back`() {
        val buffer = buffer()
        buffer.feed(mark("A") + "shell prompt\r\n")

        buffer.feed("$ESC[?1049h")
        assertThat(buffer.frame().promptMarks.count { it != null }).isEqualTo(0)

        buffer.feed("$ESC[?1049l")
        assertThat(kindsAt(buffer.frame(), 0)).containsExactly(PromptMarkKind.PROMPT)
    }

    @Test
    fun `a reset drops every mark`() {
        val buffer = buffer()
        buffer.feed(mark("A") + "prompt\r\n")

        buffer.feed("$ESC[!p")

        assertThat(buffer.frame().promptMarks.count { it != null }).isEqualTo(0)
    }

    @Test
    fun `growing the screen keeps the marks on the lines they were made on`() {
        val buffer = buffer()
        buffer.feed(mark("A") + "prompt")

        buffer.resize(30, 8)

        val frame = buffer.frame()
        assertThat(promptMarkAt(frame, 0)?.kinds).containsExactly(PromptMarkKind.PROMPT)
        assertThat(row(frame, 0)).isEqualTo("prompt")
    }

    @Test
    fun `shrinking away a blank row takes the mark that described it`() {
        // Six rows, because five is the shortest screen a resize will make.
        val buffer = buffer(rows = 6)
        buffer.feed(mark("A") + "$ESC[6;1H" + mark("D;7") + "$ESC[1;1H")

        buffer.resize(20, 5)

        // The last row was blank and the cursor was above it, so the shrink discarded it - and the status
        // of a command that finished on it goes with the line rather than being left pointing at nothing.
        val frame = buffer.frame()
        assertThat(frame.totalLines).isEqualTo(5)
        assertThat(frame.promptMarks.count { it != null }).isEqualTo(1)
        assertThat(promptMarkAt(frame, 0)?.kinds).containsExactly(PromptMarkKind.PROMPT)
    }

    @Test
    fun `the marks stay one per line however the screen is rearranged`() {
        val buffer = buffer(rows = 4, scrollback = 3)
        val script = listOf(
            mark("A") + "one\r\n",
            "two\r\nthree\r\nfour\r\nfive\r\n",
            mark("C") + "out\r\n" + mark("D;0"),
            "$ESC[2;4r$ESC[1;1H$ESC[L$ESC[M",
            mark("B") + "$ESC[2K",
            "$ESC[?1049h" + mark("A") + "alt" + "$ESC[?1049l",
            "$ESC[1;1H$ESC[4J" + mark("A"),
            "$ESC[1;1H$ESC[3K",
            "$ESC[!p",
        )

        script.forEach { step ->
            buffer.feed(step)
            val frame = buffer.frame()
            assertThat(frame.promptMarks).hasSize(frame.lines.size)
            assertThat(buffer.frame(scrollOffset = frame.scrollCeiling).promptMarks)
                .hasSize(buffer.frame(scrollOffset = frame.scrollCeiling).lines.size)
        }
    }
}
