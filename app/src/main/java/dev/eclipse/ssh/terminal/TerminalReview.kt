package dev.eclipse.ssh.terminal

/**
 * A session cut into the commands a reader actually reads.
 *
 * [PromptReview] answers the three questions a reader asks about one line. This answers the one a reader
 * asks about a whole session - "what did it do?" - by cutting it into one entry per command the shell
 * announced: what was typed, what it printed, and how it ended. The cut is by *prompt*, because a prompt
 * is the one boundary every announcing shell sends, and the marks that say where output begins are
 * optional.
 *
 * The prose is here rather than in the composable that draws it for the same reason the arithmetic in
 * [PromptReview] is here: [ReviewedCommand.end] is what a screen reader is told, and a sentence that
 * reports a command which never finished as one that did is a defect a test can pin and a look at a
 * screen cannot.
 */

/**
 * One line of a session: the text on it, and whatever the shell said about it.
 *
 * The two travel together because they are indexed by the same thing, the absolute buffer line, and a
 * reader that took them as two lists could take them from either side of a scrollback trim and attribute
 * every command after the cut to the wrong prompt. [AnsiTerminalBuffer.sessionLines] builds the pairs
 * under one lock for exactly that reason.
 */
data class SessionLine(val text: String, val mark: PromptMark?)

/**
 * One command of a session: the line its prompt is on, what the user typed, what it printed, and how it
 * ended.
 *
 * [outputFrom] is the first line the command could have printed - the line the shell accepted the command
 * line on, which is the first line of output for a simple command and the line after the last
 * continuation for a multi-line one. A shell that never announces the acceptance leaves the prompt line
 * as the whole of the command, which is why [command] can still carry the prompt itself: [inputColumn]
 * is what says where the prompt ends, and a shell that sends `A` without `B` has not said.
 *
 * [endLine] and [exitCode] are the two halves of "how it ended", kept apart rather than folded into one
 * nullable status, because the three states a reader acts on differently are "no end reported",
 * "finished, status not reported" and "exited N" - see [end].
 */
data class ReviewedCommand(
    val promptLine: Int,
    val inputColumn: Int?,
    val outputFrom: Int,
    val endLine: Int?,
    val exitCode: Int?,
    val command: String,
    val output: String,
) {
    /**
     * How the command ended, in the words a reader can be told.
     *
     * The first two states are kept apart on purpose. A command with no end reported is one whose shell
     * may still be running it, or was killed before it could say; a command that finished without a
     * status ran to completion and its shell did not say how. Reporting both as "no status" would tell a
     * reader that a command which never finished had finished, and the whole point of a review is to be
     * able to see which command failed.
     */
    val end: String
        get() = when {
            endLine == null -> "no end reported"
            exitCode == null -> "finished, status not reported"
            else -> "exited $exitCode"
        }
}

/**
 * The session's commands, in the order the shell ran them - one entry per prompt it announced.
 *
 * A command is delimited by the *prompts* rather than by the marks that say where its output begins,
 * because the prompt is the one boundary every announcing shell sends: the acceptance mark is optional,
 * and a shell that omits it would otherwise run two commands together into one entry. So a command's
 * block is its prompt line up to the line before the next prompt, and the acceptance mark only says
 * where within that block the output starts.
 *
 * The last entry is the prompt the user is typing at, when there is one: it has no output and no end,
 * which is exactly what it is, and dropping it would hide the line the session is currently on.
 *
 * A session whose shell has announced nothing gives an empty list, which is the honest answer and not a
 * failure - it means no prompt has been marked, not that no command has run.
 */
fun reviewCommands(lines: List<SessionLine>): List<ReviewedCommand> {
    val marks = lines.map { it.mark }
    val commands = ArrayList<ReviewedCommand>()
    // From above the top, which is the first prompt - the walk [nextPrompt] exists for.
    var prompt = nextPrompt(marks, -1)
    while (prompt != null) {
        commands += reviewCommand(lines, marks, prompt, nextPrompt(marks, prompt) ?: lines.size)
        prompt = nextPrompt(marks, prompt)
    }
    return commands
}

/**
 * As much of one command's output as a review draws, with a count of what it left out.
 *
 * A review entry is one line of a list, and a command can print a hundred thousand of them: drawing a
 * `find /` in full would lay out a whole scrollback inside a single list item, which is the wrong thing
 * to draw and the wrong thing to ask a screen reader to walk through. So an entry shows the head and the
 * tail and says how many lines lie between.
 *
 * Both ends rather than one, because which end matters is the command's business and not the pane's. A
 * failing command's verdict is on its last line - the compiler's error count, the `No such file` - while
 * a listing's first lines are where the reader looks; a pane that cut to one end would hide the answer
 * for half the commands it drew.
 *
 * [output] is the block the cut already produced, not the buffer, so a command that printed nothing
 * gives back nothing. A block that fits is handed back unchanged, byte for byte: the common case must
 * not be rewritten by the rule that exists for the uncommon one.
 */
fun reviewOutputExcerpt(output: String): String {
    val lines = output.split('\n')
    if (lines.size <= REVIEW_OUTPUT_HEAD + REVIEW_OUTPUT_TAIL) return output
    val omitted = lines.size - REVIEW_OUTPUT_HEAD - REVIEW_OUTPUT_TAIL
    return (lines.take(REVIEW_OUTPUT_HEAD) +
        "… $omitted more ${if (omitted == 1) "line" else "lines"}" +
        lines.takeLast(REVIEW_OUTPUT_TAIL))
        .joinToString("\n")
}

private const val REVIEW_OUTPUT_HEAD = 8
private const val REVIEW_OUTPUT_TAIL = 8

private fun reviewCommand(
    lines: List<SessionLine>,
    marks: List<PromptMark?>,
    promptLine: Int,
    next: Int,
): ReviewedCommand {
    val mark = marks[promptLine]
    // The line the shell accepted the command line on, which is the first line its output can be on.
    val accepted = (promptLine + 1 until next)
        .firstOrNull { PromptMarkKind.OUTPUT in (marks[it]?.kinds ?: emptySet()) }
    val outputFrom = maxOf(accepted ?: (promptLine + 1), promptLine + 1)
    val endLine = ownEndLine(marks, promptLine, next)
    return ReviewedCommand(
        promptLine = promptLine,
        inputColumn = mark?.inputColumn,
        outputFrom = outputFrom,
        endLine = endLine,
        exitCode = endLine?.let { marks[it]?.exitCode },
        command = typed(lines.subList(promptLine, outputFrom).joinToString("\n") { it.text }, mark?.inputColumn),
        output = lines.subList(outputFrom, next).joinToString("\n") { it.text }.trimEnd(),
    )
}

/**
 * The line the shell reported the end of the command at [promptLine] on, or null when it has not said.
 *
 * Deliberately not the question [commandEndAt] answers. That one is asked about a *line* a reader is
 * looking at and answers with the command that line belongs to, which for the row carrying one command's
 * `D` and the next prompt's `A` is the command that just finished - the status a reader looks at that row
 * for. This is asked about a *command*, so it has to be that command's own end, and the row that carries
 * both is exactly where the two answers differ: asked of a command's prompt line, the line-scoped
 * question would hand back the *previous* command's status, and a review that showed a successful command
 * as failed because the one before it failed is the defect this scan exists to avoid.
 *
 * The scan therefore starts on the line after the prompt and stops on the next prompt's line, where a `D`
 * is still this command's: a shell reports a command's end from its prompt hook, before printing the
 * prompt, so a finished command's `D` is never below the prompt that follows it, and the previous
 * command's is never below this one's prompt. A command whose end has not arrived by the time the next
 * prompt appears is one that never reported one - which is a different thing from a status of zero, and
 * is why the answer is a line or nothing rather than a number.
 */
private fun ownEndLine(marks: List<PromptMark?>, promptLine: Int, next: Int): Int? {
    for (index in promptLine + 1..minOf(next, marks.lastIndex)) {
        if (PromptMarkKind.FINISHED in (marks[index]?.kinds ?: emptySet())) return index
    }
    return null
}

/**
 * A command block with the prompt cut off its first line, so a reader is handed `grep nope file` rather
 * than `user@host:~$ grep nope file`.
 *
 * The prompt is only on the block's first line - the continuation lines of a multi-line command carry
 * the shell's secondary prompt, which is part of what the user typed - so the cut is bounded by that
 * line's own length and cannot run into the next one. Without a column there is nothing to cut by: a
 * shell that announced its prompt but not where the typing starts leaves the prompt in the text, which
 * is honest and better than guessing a boundary out of the prompt's own characters.
 */
private fun typed(block: String, inputColumn: Int?): String {
    if (inputColumn == null) return block
    val breakAt = block.indexOf('\n').let { if (it < 0) block.length else it }
    return block.substring(inputColumn.coerceIn(0, breakAt))
}
