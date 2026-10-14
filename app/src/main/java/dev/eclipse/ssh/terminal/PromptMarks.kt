package dev.eclipse.ssh.terminal

/**
 * The four things a shell tells its terminal about the line it is writing, from `OSC 133`.
 *
 * A shell taught to announce itself sends one of these before and after each thing it does, and the
 * terminal records which line each one landed on. That record is what makes a screen of a thousand
 * lines navigable: [PROMPT] is where a prompt was printed, [INPUT] is the line the user typed on,
 * [OUTPUT] is where the command's output begins, and [FINISHED] is where it ended - carrying the
 * status, so a command that failed can be found without reading the output it printed.
 *
 * These are what the shell *said*, kept as said. A shell that never announces itself leaves none, and
 * nothing downstream may assume a line carries one.
 */
enum class PromptMarkKind {
    /** `A`: a prompt is about to be printed on this line. */
    PROMPT,

    /** `B`: the prompt has been printed and this line is the user's to type on. */
    INPUT,

    /** `C`: the command was accepted and its output starts here. */
    OUTPUT,

    /** `D`: the command finished here, with the status in [PromptMark.exitCode]. */
    FINISHED,
}

/**
 * What the shell said about one line, and where on it the shell said it.
 *
 * The kinds are a set because a line usually carries more than one: `A` and `B` are sent around a
 * prompt printed on a single line, so the line the user types on carries both. A shell is also free to
 * send them in whatever order its own prompt handling produces, and two marks that landed on the same
 * line are two facts about that line rather than a correction of each other.
 *
 * [inputColumn] is where the user's typing begins - the column the cursor stood at when `B` arrived,
 * which is one past the prompt the shell printed. It is what lets a reader be handed the command
 * instead of the prompt and the command run together. `C`'s column is deliberately not kept: a shell
 * may or may not print a newline between the command it accepted and the output it is about to print,
 * so the column the mark arrives at says nothing about where the output begins.
 *
 * [exitCode] is the status `D` carried, or null when it carried none. A shell sending a bare `D` is
 * saying the command finished without saying how, which is not the same as a status of zero.
 */
data class PromptMark(
    val kinds: Set<PromptMarkKind>,
    val inputColumn: Int? = null,
    val exitCode: Int? = null,
) {
    /**
     * This mark and [later] as one, for the second mark to land on a line the first already marked.
     *
     * Kinds accumulate and the newest column and status win, which is the only reading that keeps both
     * facts: `A` then `B` on one line is a prompt *and* an input line, and a shell that re-sends `B`
     * after redrawing its prompt has moved the input start rather than described a second one.
     */
    fun and(later: PromptMark): PromptMark = PromptMark(
        kinds = kinds + later.kinds,
        inputColumn = later.inputColumn ?: inputColumn,
        exitCode = later.exitCode ?: exitCode,
    )
}

/**
 * What the shell said about one line of a frame, addressed by *absolute buffer line*, or null.
 *
 * The same coordinate [hyperlinkAt] takes and for the same reason: the marks on a frame are window-
 * aligned, and every caller outside this package - a selection, a scroll, a reader - already addresses
 * lines absolutely, so the window's offset comes off here rather than at every call site. A line the
 * frame does not hold is null and not a throw.
 */
fun promptMarkAt(frame: TerminalFrame, line: Int): PromptMark? =
    frame.promptMarks.getOrNull(line - frame.firstLine)
