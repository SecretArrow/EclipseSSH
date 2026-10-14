package dev.eclipse.ssh.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.eclipse.ssh.terminal.ReviewedCommand
import dev.eclipse.ssh.terminal.reviewOutputExcerpt

/**
 * A session's commands as a list a reader - or a screen reader - can walk.
 *
 * This is what makes a session *readable* rather than only visible. The live terminal is a grid of
 * painted cells: it has no text nodes, so a screen reader has nothing to walk and a reader cannot scroll
 * by command. This pane is built out of ordinary [Text], one entry per command the shell announced, and
 * every fact in it is a word rather than a colour - a command that failed says "exited 2", it does not
 * only turn red. That is the whole design: the model behind it ([ReviewedCommand.end]) is written in
 * sentences precisely so that the pane has nothing to interpret.
 *
 * The colour is a redundant cue, and deliberately the weaker one. A status line is drawn at full
 * foreground when the command failed and at reduced alpha when it did not, which reads as "prominent"
 * against every one of the terminal themes - each of which pairs its own foreground with its own
 * background - without inventing a red or a green that a light theme would wash out.
 *
 * Tapping an entry is the pane's one action: it hands the command back so the caller can take the reader
 * to the text it describes. [Modifier.clickable] merges the entry's text into one node for a screen
 * reader, so the tap target and the spoken sentence are the same object.
 */
@Composable
fun TerminalReviewPane(
    commands: List<ReviewedCommand>,
    foreground: Color,
    fontSize: Float,
    onJump: (ReviewedCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (commands.isEmpty()) {
        EmptyReview(foreground, fontSize, modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        items(commands, key = { it.promptLine }) { command ->
            ReviewEntry(command, foreground, fontSize, onJump)
        }
    }
}

/**
 * One command: what was typed, how it ended, and as much of what it printed as a list entry can carry.
 *
 * The three are separate text nodes in one column rather than one paragraph, so a screen reader stops on
 * each: the command is the heading of the entry, the status is the answer a reader came for, and the
 * output is the evidence.
 */
@Composable
private fun ReviewEntry(
    command: ReviewedCommand,
    foreground: Color,
    fontSize: Float,
    onJump: (ReviewedCommand) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Jump to this command") { onJump(command) }
            .padding(vertical = 8.dp),
    ) {
        // The command itself, which is what the reader is looking for. Monospace, because it is a
        // command line and its spacing is part of it - an alignment in a `for` loop, a column of a
        // pipeline.
        Text(
            text = command.command.ifEmpty { "(empty command line)" },
            color = foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
        )
        // How it ended. Full foreground only for a command that failed, which is the one the reader is
        // usually scanning the list for; everything else is a step down, so the failures are what stands
        // out. The words do the work - see the class doc - so this changes nothing for a reader who
        // cannot see it.
        Text(
            text = command.end,
            color = foreground.copy(alpha = if (command.exitCode != null && command.exitCode != 0) 1f else 0.65f),
            fontSize = (fontSize - 1f).coerceAtLeast(MIN_REVIEW_FONT_SIZE).sp,
            modifier = Modifier.padding(top = 1.dp),
        )
        val output = reviewOutputExcerpt(command.output)
        if (output.isNotEmpty()) {
            Text(
                text = output,
                color = foreground.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // The rule that ends the entry, so a reader can see where one command stops and the next begins
        // even when the output ran to the excerpt's limit.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(1.dp)
                .background(foreground.copy(alpha = 0.12f)),
        )
    }
}

/**
 * What the pane says when there is nothing to list.
 *
 * A session whose shell announces nothing is the ordinary case, not a failure - most shells emit no
 * prompt marks until they are configured to - so this has to explain the emptiness rather than look
 * broken. It does not offer to configure the shell: that is the user's own shell on the far end, and the
 * app has no business writing to it.
 */
@Composable
private fun EmptyReview(foreground: Color, fontSize: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = EMPTY_REVIEW,
            color = foreground.copy(alpha = 0.7f),
            fontSize = (fontSize - 1f).coerceAtLeast(MIN_REVIEW_FONT_SIZE).sp,
            modifier = Modifier.padding(24.dp),
        )
    }
}

private const val EMPTY_REVIEW =
    "This shell has not announced any commands.\n\n" +
        "The review is built from the prompt marks a shell emits as it runs each command. " +
        "A shell that does not emit them has nothing to list here."

/** A floor for the two secondary lines, so a 6sp terminal setting does not make them unreadable. */
private const val MIN_REVIEW_FONT_SIZE = 9f
