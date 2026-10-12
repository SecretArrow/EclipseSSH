package dev.eclipse.ssh.ui.terminal

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the courier field does with a keyboard that composes, without a keyboard.
 *
 * A soft keyboard does not hand over finished words one at a time. It reports the whole field on every
 * keystroke and marks the word under its suggestion strip as *composing* - a guess it will replace
 * wholesale when the user taps a suggestion or the next key narrows it. The field used to read that
 * whole report as committed text and diff it, so each keystroke of a half-typed word went to the shell
 * the moment it appeared: the reported `SED□NG`, where the far end had already been given `SEDANG` and
 * was then told to delete into the middle of it when the keyboard corrected itself, and the cursor
 * left sitting inside a word the user had finished typing.
 *
 * The invariants under test:
 *  - only the text outside the composition is the shell's business, and it is sent once;
 *  - a guess that is replaced by another guess sends nothing at all - not the correction, and not the
 *    Backspaces that would un-say it;
 *  - the composing region is reported outwards, because the grid has to draw it at the cursor: the
 *    shell has not been told about it, so nothing else can;
 *  - the field is never reset while a composition is live, because the composition is a range *into*
 *    the text a reset replaces.
 */
class TerminalComposingTest {

    // The sentinel is private to the file under test; this mirrors it by its definition - eight
    // U+200B - and every test below depends on that being what the field actually pads with.
    private val sentinel = Char(0x200B).toString().repeat(8)

    private fun field(text: String, composition: TextRange? = null) =
        TextFieldValue(text = text, selection = TextRange(text.length), composition = composition)

    /** The field mid-word: `SEDANG` typed, the keyboard still holding `ANG`. */
    private fun typing(committed: String, held: String) =
        field(sentinel + committed + held, TextRange(sentinel.length + committed.length, sentinel.length + committed.length + held.length))

    @Test
    fun `a composing word is not the shell's business`() {
        // The bug. `ANG` is a guess the keyboard may throw away; the shell is told about `SED` only.
        val step = fieldStep(committedHeld = sentinel, resetPendingFrom = null, change = typing("SED", "ANG"))
        assertThat(step.backspaces).isEqualTo(0)
        assertThat(step.text).isEqualTo("SED")
    }

    @Test
    fun `the composing word is reported for the grid to draw`() {
        // Withheld from the pty and drawn by the view instead, which is the only place it can appear:
        // the grid does not carry it, so without this the user types blind until each word commits.
        val step = fieldStep(sentinel, null, typing("SED", "ANG"))
        assertThat(step.composing).isEqualTo("ANG")
    }

    @Test
    fun `committing the word afterwards sends it exactly once`() {
        // The keyboard commits: same text, no composition. The base is what the shell was last told,
        // so the insertion is the word and nothing else - not the word again on top of itself.
        val step = fieldStep(committedHeld = sentinel + "SED", resetPendingFrom = null, change = field(sentinel + "SEDANG"))
        assertThat(step.backspaces).isEqualTo(0)
        assertThat(step.text).isEqualTo("ANG")
        assertThat(step.composing).isEmpty()
    }

    @Test
    fun `a keyboard that replaces its guess sends nothing for the correction`() {
        // The `SED□NG` artefact: the second guess replaces the first. Both have the same committed
        // prefix, so the shell hears nothing - where diffing the whole field would have deleted into
        // text the shell had already been given and re-sent the new guess after it.
        val replaced = fieldStep(sentinel + "SED", null, typing("SED", "ANGI"))
        assertThat(replaced.backspaces).isEqualTo(0)
        assertThat(replaced.text).isEmpty()
        assertThat(replaced.composing).isEqualTo("ANGI")
    }

    @Test
    fun `a composition that grows keystroke by keystroke sends nothing until it commits`() {
        // Every keystroke inside the composing region is a replacement of it, and none of them is the
        // shell's business. A word typed slowly is one insertion at the end, not one per letter.
        // `SED` has already been typed and committed, which is why the shell has heard it; the word
        // under the strip is the one that keeps being replaced.
        var base = sentinel + "SED"
        for (held in listOf("A", "AN", "ANG")) {
            val step = fieldStep(base, null, typing("SED", held))
            assertThat(step.backspaces).isEqualTo(0)
            assertThat(step.text).isEmpty()
            base = step.committed
        }
        val commit = fieldStep(base, null, field(sentinel + "SEDANG"))
        assertThat(commit.text).isEqualTo("ANG")
    }

    @Test
    fun `a commit right after a composing word is diffed against the committed text`() {
        // Nothing is left behind by the withheld guess: the field's committed projection is what the
        // shell was told, so the next character arrives as itself rather than after a correction.
        val held = fieldStep(sentinel, null, typing("SED", "ANG"))
        val next = fieldStep(held.committed, held.resetPendingFrom, field(sentinel + "SEDANG!"))
        assertThat(next.backspaces).isEqualTo(0)
        assertThat(next.text).isEqualTo("ANG!")
    }

    @Test
    fun `the committed text keeps the sentinel`() {
        // The padding is not text, but the deletion accounting is about the whole field and a held
        // Backspace has to keep counting into it - so excising the composition must not excise it.
        val step = fieldStep(sentinel, null, typing("SED", "ANG"))
        assertThat(step.committed).isEqualTo(sentinel + "SED")
    }

    @Test
    fun `a reset never lands under a live composition`() {
        // A newline in the committed text is normally the moment to clear the tail, and here it would
        // tear the field out from under a keyboard holding a word: the composition is a range into the
        // text being replaced. The line is finished on the commit that follows instead.
        val live = field(sentinel + "hi\n", TextRange(sentinel.length, sentinel.length + 2))
        val kept = nextFieldValue(live)
        assertThat(kept.text).isEqualTo(live.text)
        assertThat(kept.composition).isEqualTo(live.composition)

        // The same change with nothing composing does reset, which is what makes the guard above the
        // thing that decided it rather than a coincidence of the text.
        val plain = nextFieldValue(field(sentinel + "hi\n"))
        assertThat(plain.text).isEqualTo(sentinel)
        assertThat(plain.composition).isNull()
    }

    @Test
    fun `a reset never lands under a live composition at the hard cap either`() {
        // The other reset trigger, and the same reason. A word is nowhere near the cap, so the only
        // reset this can defer is one that would have happened anyway - on the next keystroke.
        // Also private to the file under test, mirrored the same way: the field resets once its text
        // reaches the sentinel plus this many characters.
        val long = "x".repeat(64)
        val live = field(sentinel + long, TextRange(sentinel.length, sentinel.length + long.length))
        assertThat(nextFieldValue(live).text).isEqualTo(live.text)

        // At the cap and not composing, it does reset - which is what makes the line above a guard
        // rather than the cap simply not having been reached.
        val capped = nextFieldValue(field(sentinel + long))
        assertThat(capped.text).isNotEqualTo(sentinel + long)
        assertThat(capped.text.startsWith(sentinel)).isTrue()
    }

    @Test
    fun `an excised region in the middle is the concatenation around it`() {
        // The composition is a suffix in practice - the caret sits at the end of a one-line field - and
        // nothing here depends on that: what the shell should have is what came before it and what came
        // after, joined.
        val text = "ab" + "MID" + "cd"
        assertThat(committedFieldText(text, TextRange(2, 5))).isEqualTo("abcd")
    }

    @Test
    fun `a collapsed composition is no composition`() {
        // Some keyboards report a zero-width range at the caret while typing directly. Reading that as
        // a live composition would withhold every character the user typed.
        val text = sentinel + "uptime"
        assertThat(committedFieldText(text, TextRange(9, 9))).isEqualTo(text)
        assertThat(composingFieldText(text, TextRange(9, 9))).isEmpty()
        assertThat(committedFieldText(text, null)).isEqualTo(text)
        assertThat(composingFieldText(text, null)).isEmpty()
    }

    @Test
    fun `a reversed range is excised the same way`() {
        // The two ends of a range are not ordered by anything the API promises, and reading them the
        // wrong way round would delete the text between them instead of the range itself.
        val text = "ab" + "MID" + "cd"
        assertThat(committedFieldText(text, TextRange(5, 2))).isEqualTo("abcd")
        assertThat(composingFieldText(text, TextRange(5, 2))).isEqualTo("MID")
    }

    @Test
    fun `the composing text never carries sentinel padding`() {
        // A composition over the padding is not something a keyboard should do, and if one does the
        // grid must not be handed eight invisible characters to draw.
        val text = "ab" + sentinel + "C"
        assertThat(composingFieldText(text, TextRange(2, text.length))).isEqualTo("C")
    }
}
