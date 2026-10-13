package dev.eclipse.ssh.ui.terminal

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.terminal.TerminalLayout
import org.junit.Test

/**
 * Where the keyboard's provisional word is drawn, and when it is not drawn at all.
 *
 * The word is withheld from the pty, so the grid does not carry it and the invisible field the keyboard
 * composes into is a pixel of transparent nothing: the view is the only place it can appear, and it has
 * to appear or the user is typing blind until each word commits. It goes in the cursor's cell, which is
 * where the character it is a guess at would land.
 *
 * The three cases where it must not be drawn are all one question - is the cursor the insertion point -
 * asked of three different situations, and the answer is the wrapping predicate the layout already
 * uses. [CC] is the case that made this matter: it addresses cells absolutely, so the display does not
 * wrap and the terminal's cursor is parked on the corner of its input box, well past the line being
 * typed. Drawing there would put the user's half-typed word in the middle of a panel.
 */
class TerminalComposingPreviewTest {

    private fun layout(cursorRow: Int, cursorColumn: Int) = TerminalLayout(
        rows = emptyList(),
        contentColumns = 0,
        cursorRow = cursorRow,
        cursorColumn = cursorColumn,
    )

    @Test
    fun `the word is drawn in the cursor's own cell`() {
        val preview = composingPreview("SEDANG", layout(cursorRow = 4, cursorColumn = 11), wraps = true, maxRows = 24)
        assertThat(preview).isNotNull()
        assertThat(preview!!.row).isEqualTo(4)
        assertThat(preview.column).isEqualTo(11)
        assertThat(preview.text).isEqualTo("SEDANG")
    }

    @Test
    fun `a screen painted positionally is not the insertion point`() {
        // A program that addresses cells absolutely leaves the cursor wherever it last put it. The word
        // belongs at the caret the program drew, which is not something this renderer can know, and a
        // guess in the wrong cell is worse than no preview: the user sees their typing land in a panel.
        assertThat(composingPreview("SEDANG", layout(4, 11), wraps = false, maxRows = 24)).isNull()
    }

    @Test
    fun `a cursor scrolled off the screen draws nothing`() {
        // The user is reading history, so the cursor's row is not in the window. There is no cell to
        // draw in, and inventing one would put the word over whatever the reader scrolled back to.
        assertThat(composingPreview("SEDANG", layout(cursorRow = -1, cursorColumn = 0), wraps = true, maxRows = 24)).isNull()
        assertThat(composingPreview("SEDANG", layout(cursorRow = 24, cursorColumn = 0), wraps = true, maxRows = 24)).isNull()
        // The top of the window is row 0, and an empty window has no rows at all.
        assertThat(composingPreview("SEDANG", layout(cursorRow = 0, cursorColumn = 3), wraps = true, maxRows = 24)).isNotNull()
        assertThat(composingPreview("SEDANG", layout(cursorRow = 0, cursorColumn = 3), wraps = true, maxRows = 0)).isNull()
    }

    @Test
    fun `nothing composing draws nothing`() {
        // Every keyboard that types directly, which is the whole of the difference: the text was sent
        // as it was typed, and the grid already shows it.
        assertThat(composingPreview("", layout(4, 11), wraps = true, maxRows = 24)).isNull()
    }
}
