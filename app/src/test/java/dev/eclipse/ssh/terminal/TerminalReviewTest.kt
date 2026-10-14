package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cutting a session into the commands a reader reads.
 *
 * The fixtures are written as lines rather than driven through the buffer, for the reason
 * [PromptReviewTest] gives: what is under test is how a list of lines and marks is cut, and building a
 * session to produce a given list would test the parser as well and obscure which of the two failed. One
 * case at the end does go through the buffer, because the cut has to agree with the marks a real feed
 * produces and that agreement is the one thing a hand-written fixture cannot show.
 *
 * The session most of these describe is two commands, the first of which failed:
 *
 * ```
 * 0  prompt     A B     "user@host:~$ ls"
 * 1  output     C       "notes.txt"
 * 2  finished   D;0
 * 3  prompt     A B     "user@host:~$ grep nope file"
 * 4  output     C       "grep: file: No such file or directory"
 * 5  finished   D;2
 * ```
 */
class TerminalReviewTest {
    private fun prompt(column: Int? = 13): PromptMark =
        PromptMark(setOf(PromptMarkKind.PROMPT, PromptMarkKind.INPUT), inputColumn = column)

    private fun accepted(): PromptMark = PromptMark(setOf(PromptMarkKind.OUTPUT))

    private fun finished(status: Int?): PromptMark =
        PromptMark(setOf(PromptMarkKind.FINISHED), exitCode = status)

    /** One line per pair, so the line each mark sits on is visible in the fixture. */
    private fun lines(vararg line: Pair<String, PromptMark?>): List<SessionLine> =
        line.map { SessionLine(it.first, it.second) }

    private fun session(): List<SessionLine> = lines(
        "user@host:~$ ls" to prompt(),
        "notes.txt" to accepted(),
        "" to finished(0),
        "user@host:~$ grep nope file" to prompt(),
        "grep: file: No such file or directory" to accepted(),
        "" to finished(2),
    )

    @Test
    fun `a session is cut into one command per prompt, in the order they ran`() {
        val commands = reviewCommands(session())

        assertThat(commands).hasSize(2)
        val first = commands[0]
        assertThat(first.promptLine).isEqualTo(0)
        assertThat(first.outputFrom).isEqualTo(1)
        assertThat(first.endLine).isEqualTo(2)
        assertThat(first.exitCode).isEqualTo(0)
        // The prompt is cut off, so a reader is handed the command and not the shell that prompted for it.
        assertThat(first.command).isEqualTo("ls")
        assertThat(first.output).isEqualTo("notes.txt")

        val second = commands[1]
        assertThat(second.promptLine).isEqualTo(3)
        assertThat(second.exitCode).isEqualTo(2)
        assertThat(second.command).isEqualTo("grep nope file")
        assertThat(second.output).isEqualTo("grep: file: No such file or directory")
    }

    @Test
    fun `how a command ended is reported in words that keep the three states apart`() {
        assertThat(reviewCommands(session()).map { it.end })
            .containsExactly("exited 0", "exited 2").inOrder()

        // A `D` with no status: the command finished and the shell did not say how.
        assertThat(reviewCommands(lines("$ " to prompt(2), "" to finished(null))).single().end)
            .isEqualTo("finished, status not reported")
        // No `D` at all: the command may still be running, which is not the same claim.
        assertThat(reviewCommands(lines("$ " to prompt(2))).single().end)
            .isEqualTo("no end reported")
    }

    @Test
    fun `the row carrying one command's end and the next prompt belongs to both, and its status to neither but the first`() {
        // The boundary row: `D;0` and the next prompt's `A`/`B` on one line, which is what a shell emits
        // when its prompt hook reports the end and its prompt is printed on the row it reported from.
        val commands = reviewCommands(
            lines(
                "user@host:~$ ls" to prompt(),
                "notes.txt" to accepted(),
                "user@host:~$ " to prompt().and(finished(0)),
            ),
        )

        // The command above it ends there, which is the status a reader looks at that row for.
        assertThat(commands[0].endLine).isEqualTo(2)
        assertThat(commands[0].exitCode).isEqualTo(0)
        assertThat(commands[0].output).isEqualTo("notes.txt")
        // And the command below it must not inherit that status: asked of its own prompt line, the
        // line-scoped question would have handed back the command above's `D`, and a review that showed
        // a command as failed because the one before it failed is the defect this pins.
        assertThat(commands[1].promptLine).isEqualTo(2)
        assertThat(commands[1].endLine).isNull()
        assertThat(commands[1].exitCode).isNull()
        assertThat(commands[1].end).isEqualTo("no end reported")
    }

    @Test
    fun `a command that never reported an end does not borrow the next one's status`() {
        val commands = reviewCommands(
            lines(
                "user@host:~$ sleep 999" to prompt(),
                "user@host:~$ " to prompt(),
                "done" to accepted(),
                "" to finished(0),
            ),
        )

        assertThat(commands).hasSize(2)
        assertThat(commands[0].endLine).isNull()
        assertThat(commands[0].end).isEqualTo("no end reported")
        assertThat(commands[1].endLine).isEqualTo(3)
        assertThat(commands[1].exitCode).isEqualTo(0)
    }

    @Test
    fun `a multi-line command's continuations are part of the command, not of its output`() {
        // The shell accepts the command line only once the last continuation is entered, so the
        // acceptance mark lands on the line *after* it - the line the command's output starts on, which
        // is why the three lines above it are the command and not output.
        val command = reviewCommands(
            lines(
                "user@host:~$ for f in a b; do" to prompt(),
                "> echo \$f" to null,
                "> done" to null,
                "a" to accepted(),
                "b" to null,
                "" to finished(0),
            ),
        ).single()

        assertThat(command.command).isEqualTo("for f in a b; do\n> echo \$f\n> done")
        assertThat(command.outputFrom).isEqualTo(3)
        assertThat(command.output).isEqualTo("a\nb")
        assertThat(command.exitCode).isEqualTo(0)
    }

    @Test
    fun `a shell that never says where the output begins still yields the command line`() {
        // No acceptance mark: the prompt line is the whole of the command and everything under it is
        // output. A shell that sends `A`/`B`/`D` and not `C` is configured, not broken.
        val command = reviewCommands(
            lines(
                "user@host:~$ ls" to prompt(),
                "notes.txt" to null,
                "" to finished(0),
            ),
        ).single()

        assertThat(command.command).isEqualTo("ls")
        assertThat(command.outputFrom).isEqualTo(1)
        assertThat(command.output).isEqualTo("notes.txt")
    }

    @Test
    fun `a prompt announced without a column leaves the prompt in the command`() {
        // `A` without `B`: nothing said where the typing starts, so there is nothing to cut by and the
        // prompt stays - honest, rather than a boundary guessed out of the prompt's own characters.
        val command = reviewCommands(lines("user@host:~$ ls" to prompt(null))).single()

        assertThat(command.command).isEqualTo("user@host:~$ ls")
        assertThat(command.inputColumn).isNull()
    }

    @Test
    fun `the prompt the user is typing at is the last command, with no output and no end`() {
        val commands = reviewCommands(
            session() + listOf(SessionLine("user@host:~$ ec", prompt())),
        )

        assertThat(commands).hasSize(3)
        val typing = commands.last()
        assertThat(typing.promptLine).isEqualTo(6)
        assertThat(typing.command).isEqualTo("ec")
        assertThat(typing.output).isEmpty()
        assertThat(typing.endLine).isNull()
        assertThat(typing.end).isEqualTo("no end reported")
    }

    @Test
    fun `a session whose shell announced nothing has no commands`() {
        assertThat(reviewCommands(emptyList())).isEmpty()
        assertThat(reviewCommands(lines("just output" to null, "" to finished(0)))).isEmpty()
    }

    @Test
    fun `the review of a real session agrees with the marks a feed produces`() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 6, scrollbackLimit = 100)
        val esc = Char(0x1B).toString()
        fun mark(body: String) = "$esc]133;$body$esc\\"

        buffer.feed(
            mark("A") + "user@host:~$ " + mark("B") + "ls\r\n" +
                mark("C") + "notes.txt\r\n" + mark("D;0") +
                mark("A") + "user@host:~$ " + mark("B") + "grep nope file\r\n" +
                mark("C") + "grep: file: No such file or directory\r\n" + mark("D;2"),
        )

        val commands = reviewCommands(buffer.sessionLines())
        assertThat(commands.map { it.command }).containsExactly("ls", "grep nope file").inOrder()
        assertThat(commands.map { it.output })
            .containsExactly("notes.txt", "grep: file: No such file or directory").inOrder()
        assertThat(commands.map { it.exitCode }).containsExactly(0, 2).inOrder()
    }

    /** A block of [count] numbered lines, so which end a line came from is visible in the fixture. */
    private fun block(count: Int): String = (1..count).joinToString("\n") { "line $it" }

    @Test
    fun `output a review can draw whole is handed back byte for byte`() {
        // The common case is not rewritten by the rule that exists for the uncommon one, and a command
        // that printed nothing is that rule's zero rather than a special case.
        assertThat(reviewOutputExcerpt("")).isEmpty()
        assertThat(reviewOutputExcerpt("one")).isEqualTo("one")
        assertThat(reviewOutputExcerpt(block(16))).isEqualTo(block(16))
    }

    @Test
    fun `output one line over the limit loses its middle and says one line went`() {
        val excerpt = reviewOutputExcerpt(block(17)).lines()

        assertThat(excerpt).hasSize(17)
        assertThat(excerpt[8]).isEqualTo("… 1 more line")
        // The two ends are the block's own ends, not the first lines of the head and tail rules.
        assertThat(excerpt.first()).isEqualTo("line 1")
        assertThat(excerpt.last()).isEqualTo("line 17")
    }

    @Test
    fun `a longer block keeps both ends and counts what it dropped in the plural`() {
        val excerpt = reviewOutputExcerpt(block(100)).lines()

        assertThat(excerpt).hasSize(17)
        assertThat(excerpt[8]).isEqualTo("… 84 more lines")
        // The verdict a reader is looking for is on the last line, so the tail is the last lines and not
        // a second helping of the head.
        assertThat(excerpt.last()).isEqualTo("line 100")
    }
}
