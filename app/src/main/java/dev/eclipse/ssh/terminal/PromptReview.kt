package dev.eclipse.ssh.terminal

/**
 * The questions a reader asks of a session's prompt marks, answered without a screen.
 *
 * The marks say where the shell's own boundaries are, and everything a reader does with them is one of
 * three things: move to the next or previous command, or find out how the command behind the text it is
 * looking at ended. Each of those is a pure function over a mark list whose *index is the absolute
 * buffer line*, which is what the buffer keeps and what a frame's [TerminalFrame.promptMarks] are a
 * window onto - so the same three functions answer for a screen and for a whole session, and the second
 * is what navigation needs, since the prompt the reader is walking towards is usually off-screen.
 *
 * They are here rather than in the view model because the interesting part is the arithmetic and the
 * arithmetic is what goes wrong: which prompt is "the one before this line" when the line *is* a
 * prompt, what a command's status is when the shell marked its end and the next prompt's start on the
 * same row, and what to answer when the shell has said nothing at all. None of that needs a device.
 */

/**
 * The nearest prompt strictly before [line], or null when the session has none above it.
 *
 * Strictly, so a reader standing on a prompt is not sent to the one it is already on: the two moves a
 * reader has are "the command before this one" and "the command after it", and the line under the
 * cursor is neither. [line] may be anywhere, including past the end of [marks], which is what makes
 * "is there anything above the top of this window" answerable without special-casing it.
 */
fun previousPrompt(marks: List<PromptMark?>, line: Int): Int? {
    for (index in minOf(line, marks.size) - 1 downTo 0) {
        if (marks[index].isPrompt()) return index
    }
    return null
}

/** The nearest prompt strictly after [line], or null when the session has none below it. */
fun nextPrompt(marks: List<PromptMark?>, line: Int): Int? {
    for (index in (line + 1).coerceAtLeast(0) until marks.size) {
        if (marks[index].isPrompt()) return index
    }
    return null
}

/**
 * The mark that says how the command behind [line] ended, or null when the shell has not said.
 *
 * The command behind a line is the one whose prompt is the nearest at or above it - and the line that
 * *carries* that prompt is the shell's own boundary, so it belongs to the command it ends as well as to
 * the one it starts. That is not a technicality: a shell emits `D` from its prompt hook and `A` from the
 * prompt itself, so the two routinely land on one row, and a rule that handed that row only to the new
 * command would lose the status of the command just finished - which is exactly the status a reader is
 * looking at the row above it for. So the search runs to the next prompt's own line and includes it, and
 * stops there: the `D` of the command behind [line] cannot be below the prompt that follows it, so a
 * command that never reported an end does not borrow the status of the one after it.
 *
 * [line] past the end of [marks] is answered as the last line is, because the question a reader asks is
 * always about the text it is looking at, and a window can sit below the last mark the shell has made.
 *
 * The mark is returned rather than its status because a bare `D` is a real answer: the command finished,
 * and the shell did not say how, which a reader has to be able to tell apart from a shell that said
 * nothing at all.
 */
fun commandEndAt(marks: List<PromptMark?>, line: Int): PromptMark? {
    if (marks.isEmpty()) return null
    val start = line.coerceIn(0, marks.lastIndex)
    val limit = (nextPrompt(marks, start) ?: marks.lastIndex) + 1
    for (index in start until limit) {
        val mark = marks[index] ?: continue
        if (PromptMarkKind.FINISHED in mark.kinds) return mark
    }
    return null
}

private fun PromptMark?.isPrompt(): Boolean = this != null && PromptMarkKind.PROMPT in kinds
