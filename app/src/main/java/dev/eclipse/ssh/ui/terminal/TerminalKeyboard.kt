package dev.eclipse.ssh.ui.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.terminal.TerminalKey
import dev.eclipse.ssh.terminal.TerminalModifiers
import dev.eclipse.ssh.terminal.rowOverflow

/**
 * The Ctrl / Alt / Shift latches on the on-screen key row.
 *
 * They latch rather than requiring a chord because a touchscreen cannot hold one key while pressing
 * another: tapping CTRL arms it, the next key press consumes it, and [consume] clears all three. Two
 * taps on the same latch turn it off again, so an armed modifier is never a trap. Sticky-until-used
 * is what every terminal keyboard on the platform does, and it is the only scheme that makes Ctrl-C
 * reachable with one thumb.
 */
@Stable
class TerminalLatches(ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) {
    var ctrl by mutableStateOf(ctrl)
        private set
    var alt by mutableStateOf(alt)
        private set
    var shift by mutableStateOf(shift)
        private set

    fun toggleCtrl() { ctrl = !ctrl }
    fun toggleAlt() { alt = !alt }
    fun toggleShift() { shift = !shift }

    /**
     * Toggle by latch id, for callers holding a [KeyBarCap] rather than a direct reference - the
     * key bar renders its latches from configuration and knows them as `"CTRL"`/`"ALT"`/`"SHIFT"`.
     * An unknown id is a no-op rather than an error: the caller is rendering untrusted config.
     */
    fun toggle(id: String) {
        when (id) {
            "CTRL" -> toggleCtrl()
            "ALT" -> toggleAlt()
            "SHIFT" -> toggleShift()
        }
    }

    /** Whether [id] is armed, by the same ids [toggle] accepts. */
    fun armed(id: String): Boolean = when (id) {
        "CTRL" -> ctrl
        "ALT" -> alt
        "SHIFT" -> shift
        else -> false
    }

    /** Reads the armed modifiers and disarms them, which is what pressing any other key does. */
    fun consume(): Triple<Boolean, Boolean, Boolean> {
        val armed = Triple(ctrl, alt, shift)
        ctrl = false
        alt = false
        shift = false
        return armed
    }

    companion object {
        /**
         * Survives rotation. An armed Ctrl is invisible state the user is relying on - losing it
         * because the device turned would send a bare `c` to a program they were trying to interrupt.
         */
        val Saver: Saver<TerminalLatches, List<Boolean>> = Saver(
            save = { listOf(it.ctrl, it.alt, it.shift) },
            restore = { TerminalLatches(it[0], it[1], it[2]) },
        )
    }
}

@Composable
fun rememberTerminalLatches(): TerminalLatches =
    rememberSaveable(saver = TerminalLatches.Saver) { TerminalLatches() }

/**
 * The invisible field that owns the IME session, translating everything it produces into terminal
 * input.
 *
 * A terminal has no text field, which is the difficulty: Android's software keyboards will only open
 * for a view holding an `InputConnection`, and they expect that view to contain editable text they
 * can inspect, autocorrect and delete backwards through. A terminal can offer none of that - the line
 * being edited lives in the remote shell's `readline`, and the app never sees it.
 *
 * The resolution is a one-line field, invisible, holding a [SENTINEL] the user cannot type and every
 * keystroke the IME has committed since the last reset, that is watched for *edits* rather than read
 * for content: an incoming edit is diffed against what the platform held, the insertion is sent, and
 * the IME's own text is adopted as the new value - so the app never performs a programmatic edit on
 * the field the IME is composing into, which is the bug this used to have (see
 * [TerminalInputBridge.resetPendingFrom] and [nextFieldValue]).
 *
 * The sentinel exists for Backspace. With a genuinely empty field, Gboard has nothing to delete and
 * reports the Backspace through `deleteSurroundingText` on the input connection instead of as a key
 * event, so the keypress vanished entirely - the most common complaint about every naive Compose
 * terminal. Padding the field with characters the user cannot type gives the IME something to consume,
 * and the deletion is read out of the diff. Zero-width spaces are used because autocorrect and
 * suggestion strips leave them alone, and because filtering them out cannot swallow a real keystroke -
 * an ordinary space would be indistinguishable from one the user pressed.
 *
 * Only what the IME has *committed* is the shell's business. A keyboard that predicts composes the
 * word under its suggestion strip - that is what makes the strip offer a correction at all - and the
 * region it is still composing is excised from the diff and never sent: see [committedFieldText]. It
 * is a guess the keyboard may replace wholesale, and a program at the far end reads what it is given
 * as final - `Ink` drawing [CC]'s input box, `readline` redrawing a line, `htop` reading a key. Sent
 * mid-word and then corrected, the guess is a stale cell and a cursor in the middle of a word the user
 * had already finished, which is the reported `SED□NG`. The caller is told the provisional text through
 * [onComposing] so it can be drawn at the cursor instead, where it is what the user is looking at.
 *
 * Hardware keys are taken before the field sees them, so an arrow key moves the remote cursor instead
 * of a caret that is not there, and so nothing arrives twice: consuming the event means no text
 * change follows it.
 *
 * Focus is the whole thing. Nothing here can receive a keystroke - not the IME's text, not a hardware
 * key through [onPreviewKeyEvent] - unless this field holds focus, which is why [onFocusChanged] is
 * reported outwards: the screen has to be able to tell whether the keyboard is connected to the shell
 * and ask again if it is not. Guessing "focus was requested, so it must be focused" is not the same
 * fact, and the difference is a terminal that silently swallows everything the user types.
 */
@Composable
fun TerminalInputBridge(
    focusRequester: FocusRequester,
    latches: TerminalLatches,
    onText: (String) -> Unit,
    onKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit,
    onChar: (Char, Boolean, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
    /**
     * The IME's provisional text, as ordinary characters, or "" when it holds none.
     *
     * It is the part of what the user has typed that the shell has not been told about - so the grid
     * cannot show it, and the caller draws it at the cursor. Empty on every keyboard that does not
     * compose, which is the whole of the difference: the text was sent as it was typed.
     */
    onComposing: (String) -> Unit = {},
) {
    var field by remember { mutableStateOf(sentinelValue()) }
    /**
     * The field content a reset has not reached yet. Setting [field] back to the sentinel is a
     * programmatic edit, and Compose pushes those to the platform on the next recomposition - so
     * until that frame lands, an edit can still arrive that was computed against the *old* content.
     * This holds that old content, and [pendingResetBase] decides which of the two an incoming
     * change was computed against. Null in the common case, where nothing is in flight and the
     * adopted value is exactly what the platform holds.
     */
    var resetPendingFrom by remember { mutableStateOf<String?>(null) }
    /**
     * The field's text as of the last edit, with the IME's provisional region cut out: the diff base
     * for the next one.
     *
     * Held rather than read back off [field], because [field] is what the *platform* holds and that
     * is a different thing in two ways - it carries the composition, and it may still be a frame
     * behind a reset this app made. What the shell has been told is the only correct base for
     * deciding what to tell it next, and it is what keeps a composing region out of the pty.
     */
    var committedHeld by remember { mutableStateOf(SENTINEL) }
    /** What the IME last reported as provisional, which is what a reset must not tear. */
    var composingHeld by remember { mutableStateOf("") }
    BasicTextField(
        value = field,
        onValueChange = { change ->
            // Everything about this edit is decided by [fieldStep], which is pure: what the shell is
            // told, what the field becomes, and what is still provisional. What is left here is the
            // carrying out - and the one thing that cannot be pure, which is whether a latched Ctrl or
            // Alt sends the text as characters rather than as a string.
            val step = fieldStep(committedHeld, resetPendingFrom, change)
            // Deletions first: an edit that both replaces and inserts emulates the replacement as
            // Backspaces followed by the new text, which is the order a shell would need to see.
            repeat(step.backspaces) { onKey(TerminalKey.BACKSPACE, false, false, false) }
            if (step.text.isNotEmpty()) {
                val (ctrl, alt, _) = latches.consume()
                if (ctrl || alt) {
                    // A latched modifier applies to one character, which is what Ctrl-C is.
                    step.text.forEach { char -> onChar(char, ctrl, alt) }
                } else {
                    sendCommittedText(step.text, onText, onKey)
                }
            }
            // Adopt what the IME produced rather than clearing it: `change` *is* the platform
            // field's content, so adopting it keeps the two in lockstep with no programmatic edit
            // for the IME to trip over. Clearing back to the sentinel here instead - the previous
            // design - tore the IME's state twice over: a commit that arrived before the reset was
            // pushed was diffed against stale content and re-sent the previous keystroke with the
            // new one (symbols doubled), and tearing a pending composition made some keyboards
            // drop its last character entirely, so Enter ran "hel" for "help". See
            // [nextFieldValue] for when a reset does happen, and the sentinel's own docs for why
            // the field is never left empty.
            field = step.field
            committedHeld = step.committed
            resetPendingFrom = step.resetPendingFrom
            composingHeld = step.composing
            onComposing(step.composing)
        },
        modifier = modifier
            .size(1.dp)
            .focusRequester(focusRequester)
            // Before the requester in the chain would report the focus of whatever is above it; after
            // it, this observes the field itself. Losing focus ends the IME's session, so whatever it
            // was holding goes with it: nothing will report the composition again, and a word left
            // drawn at the cursor would be an artefact of a keyboard the user has already put away.
            .onFocusChanged { state ->
                if (!state.isFocused) {
                    composingHeld = ""
                    onComposing("")
                }
                onFocusChanged(state.isFocused)
            }
            .semantics { contentDescription = "Terminal input" }
            .onPreviewKeyEvent { event ->
                val handled = handleKeyEvent(event, latches, onKey, onChar)
                // Enter that arrived as a key event is the moment the line is finished and the
                // buffer's tail becomes history, which is the safe point to clear it: the IME has
                // finished any composition before sending the key, and the next keystroke is a new
                // line rather than the same frame. KeyDown only - the up event of the same press
                // must not clear twice.
                if (handled && event.type == KeyEventType.KeyDown &&
                    TERMINAL_NAMED_KEYS[event.key] == TerminalKey.ENTER
                ) {
                    // Never while a composition is live: the reset is a programmatic edit, and the
                    // IME's composition is a range *into* the text it replaces - tearing the field
                    // out from under one is what makes a keyboard drop the word it was holding, so
                    // the line is finished on the commit that follows instead.
                    if (resetPendingFrom == null && composingHeld.isEmpty() &&
                        field.text.length > SENTINEL.length
                    ) {
                        resetPendingFrom = committedHeld
                        field = sentinelValue()
                        committedHeld = SENTINEL
                    }
                }
                handled
            },
        // A terminal is not prose: autocorrect would rewrite commands, capitalisation would rewrite
        // paths, and an IME action would insert a newline the shell did not ask for. The type is what
        // carries that, and why - see [TERMINAL_KEYBOARD_OPTIONS].
        keyboardOptions = TERMINAL_KEYBOARD_OPTIONS,
        singleLine = true,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
    )
}

/**
 * Sends text the IME committed, turning any newline inside it into a real Enter.
 *
 * Most keyboards report Return as a key event, which [handleKeyEvent] maps to [TerminalKey.ENTER];
 * some commit it as text instead, and a multi-line clipboard suggestion or a voice-typed "new line"
 * arrives the same way. Passing those through verbatim would put a bare LF on the wire where every
 * other route in the app sends CR - `TerminalKeys.normalizeNewlines` already holds that line for a
 * paste - so the same Return would encode differently depending on which keyboard the user installed,
 * and a shell reading a raw pty would see the wrong end-of-line. CRLF is one Return, not two.
 *
 * This is not the paste path: a paste goes through `MainViewModel.pasteIntoTerminal`, which brackets
 * it when the remote asked for bracketed paste.
 */
internal fun sendCommittedText(
    typed: String,
    onText: (String) -> Unit,
    onKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit,
) {
    // Nothing to send is not a write of nothing: the call site already guards this, and a helper that
    // cannot be trusted on its own is the kind that grows a second caller without one.
    if (typed.isEmpty()) return
    if (typed.none { it == LINE_FEED || it == CARRIAGE_RETURN }) {
        onText(typed)
        return
    }
    val run = StringBuilder(typed.length)
    var index = 0
    while (index < typed.length) {
        val char = typed[index]
        if (char != LINE_FEED && char != CARRIAGE_RETURN) {
            run.append(char)
            index++
            continue
        }
        if (run.isNotEmpty()) {
            onText(run.toString())
            run.setLength(0)
        }
        onKey(TerminalKey.ENTER, false, false, false)
        index += if (char == CARRIAGE_RETURN && typed.getOrNull(index + 1) == LINE_FEED) 2 else 1
    }
    if (run.isNotEmpty()) onText(run.toString())
}

private const val LINE_FEED = '\n'
private const val CARRIAGE_RETURN = '\r'

/** Whether the IME is holding text it has not committed. A collapsed range is no composition. */
private fun isComposing(composition: TextRange?): Boolean = composition != null && !composition.collapsed

/**
 * Hands a hardware or IME key event to [mapTerminalKeyEvent] and carries out what comes back.
 *
 * Returning true consumes the event, which is what stops the invisible field from also processing it
 * and reporting the same keystroke a second time as a text change. Nothing is decided here: the rules
 * live in the pure mapper, and this reads the event and disarms the latches, which is all a composable
 * is in a position to do.
 */
private fun handleKeyEvent(
    event: KeyEvent,
    latches: TerminalLatches,
    onKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit,
    onChar: (Char, Boolean, Boolean) -> Unit,
): Boolean {
    val action = mapTerminalKeyEvent(
        keyDown = event.type == KeyEventType.KeyDown,
        key = event.key,
        codePoint = event.utf16CodePoint,
        held = TerminalModifiers(
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
        ),
        armed = TerminalModifiers(ctrl = latches.ctrl, alt = latches.alt, shift = latches.shift),
    )
    return when (action) {
        // Declined, so the latches are left alone: they are armed for the key the user is about to
        // type, and this was not it.
        TerminalKeyAction.Decline -> false
        is TerminalKeyAction.Named -> {
            latches.consume()
            onKey(action.key, action.modifiers.ctrl, action.modifiers.alt, action.modifiers.shift)
            true
        }
        is TerminalKeyAction.Chord -> {
            latches.consume()
            onChar(action.char, action.modifiers.ctrl, action.modifiers.alt)
            true
        }
    }
}

/** U+200B, written by code point because an invisible character in source is a trap. */
private val SENTINEL_CHAR: Char = Char(0x200B)
private val SENTINEL = SENTINEL_CHAR.toString().repeat(SENTINEL_LENGTH)

/** Long enough that a fast repeated Backspace cannot empty the field between resets. */
private const val SENTINEL_LENGTH = 8

/**
 * How much committed text the courier keeps before it resets to the sentinel. Generous on purpose:
 * a reset is the only programmatic edit this field ever makes, so each one is a moment the IME can
 * trip over, and the fewer of them there are the better. It only has to be short enough that a
 * fumbled reset cannot lose a whole command line.
 */
private const val FIELD_HARD_CAP = 64

private fun sentinelValue() = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))

/**
 * One incoming edit, expressed as what the shell needs to be told: how many characters were removed
 * from the end, and what was inserted after that removal.
 *
 * Deletions in the middle cannot happen here - the caret sits at the end, the field is one line, and
 * the IME has no selection UI over an invisible field - so the diff is a suffix-count, not a general
 * tree diff. A replace-em-all edit (autocorrect rewriting the tail, or the IME delivering a batched
 * composition as one change) arrives as deleted > 0 *and* inserted text, and the deletions are sent
 * first so the shell applies them in the order a user's hands would.
 *
 * Deletions that reach into the sentinel count like any other: a held Backspace keeps sending them,
 * which is the deliberate choice - the alternative, clamping at the padding, is a terminal that
 * swallows the last few presses of a long hold instead of passing them on.
 */
internal data class TerminalEdit(val deleted: Int, val inserted: String)

/**
 * One incoming edit, decided: what the shell is told, what the field becomes, and what is provisional.
 *
 * Pure, and separate from the composable that carries it out, because this is where the field reports
 * came from and every one of them is a pair of strings rather than a matter of which keyboard the user
 * installed and how fast their thumbs were. `TerminalComposingTest` drives it directly.
 *
 * [backspaces] and [text] are the shell's share of the edit - the committed text, diffed against the
 * committed text the field held, so a composing region is never sent and never counted as deleted.
 * [field] is what the platform should hold next, [committed] is the projection of it that the next
 * edit is diffed against, [composing] is what the caller draws at the cursor, and [resetPendingFrom]
 * carries a reset that the platform may not have been pushed yet.
 */
internal data class FieldStep(
    val backspaces: Int,
    val text: String,
    val field: TextFieldValue,
    val committed: String,
    val composing: String,
    val resetPendingFrom: String?,
)

/**
 * Decides one incoming edit. See [FieldStep] for what comes back and why the decision is a value.
 *
 * [committedHeld] is the field's committed text as of the last edit, which is what the shell was told
 * and therefore the only correct base to diff against; [change] is the whole report, composition and
 * all, of which only the text outside the composition is the shell's business.
 */
internal fun fieldStep(
    committedHeld: String,
    resetPendingFrom: String?,
    change: TextFieldValue,
): FieldStep {
    // Only the IME's committed text is the shell's business: see [committedFieldText]. This is what
    // keeps a half-typed word off the far end of the pty.
    val incoming = committedFieldText(change.text, change.composition)
    // The diff base is what the platform held when the IME computed this edit: normally the committed
    // text the field already carries (the last adopted edit, which is exactly what the platform holds
    // - adoption pushes nothing), but while a reset is in flight the platform may still hold the
    // pre-reset content for a frame, and diffing against the wrong one of the two is how a keystroke
    // gets sent twice or not at all.
    val base = pendingResetBase(resetPendingFrom, committedHeld, incoming)
    val edit = diffTerminalEdit(base, incoming)
    val next = nextFieldValue(change)
    return FieldStep(
        backspaces = edit.deleted,
        text = edit.inserted.filter { it != SENTINEL_CHAR },
        field = next,
        committed = committedFieldText(next.text, next.composition),
        composing = composingFieldText(change.text, change.composition),
        // If that was a reset, the platform still holds what `change` says until the next frame.
        resetPendingFrom = if (next.text != change.text) incoming else null,
    )
}

/**
 * The field's text with the IME's provisional region cut out: everything the shell may be told about.
 *
 * A soft keyboard does not hand over finished words one at a time. It reports the whole field on every
 * keystroke and marks the part it is still *composing* with a range - the word under the suggestion
 * strip, which a tap on a suggestion replaces wholesale. Only the text outside that range has been
 * committed, so only that text is the shell's business, and a word the user has not finished must not
 * reach a program that reads what it is given as final.
 *
 * The sentinel padding is deliberately kept: it is not text, but the deletion accounting in
 * [diffTerminalEdit] is about the whole field, and a held Backspace has to keep counting into it. Only
 * the range is excised, and [composingFieldText] is the other half of the split.
 *
 * The composition is a suffix in practice - the caret sits at the end of a one-line field, so the
 * keyboard has nowhere else to compose - and the code does not depend on that. Excising a range in the
 * middle is still the text the shell should have: it is the concatenation of what came before the
 * composition and what came after it, and a keyboard that composed around existing text (none does
 * here) would get a diff against that, not against the guess.
 */
internal fun committedFieldText(text: String, composition: TextRange?): String {
    val range = composition ?: return text
    if (range.collapsed) return text
    val from = minOf(range.start, range.end).coerceIn(0, text.length)
    val to = maxOf(range.start, range.end).coerceIn(from, text.length)
    return text.substring(0, from) + text.substring(to)
}

/**
 * The IME's provisional region, as the characters the user has typed but not committed.
 *
 * What the view draws at the cursor, because nothing else can: the shell has not been told about it, so
 * the grid does not carry it, and the invisible field the IME is composing into is one pixel of
 * transparent nothing. Empty whenever the keyboard composes nothing, which is every keyboard that
 * types directly.
 */
internal fun composingFieldText(text: String, composition: TextRange?): String {
    val range = composition ?: return ""
    if (range.collapsed) return ""
    val from = minOf(range.start, range.end).coerceIn(0, text.length)
    val to = maxOf(range.start, range.end).coerceIn(from, text.length)
    return text.substring(from, to).filter { it != SENTINEL_CHAR }
}

/**
 * What changed between the text the platform held and the text it now reports.
 *
 * Pure, so the exact worst cases - a commit racing the previous one, a composition landing after a
 * reset, autocorrect rewriting a tail - are assertions in `TerminalCourierFieldTest` rather than
 * field reports of doubled symbols and eaten characters.
 */
internal fun diffTerminalEdit(base: String, incoming: String): TerminalEdit {
    // The shared prefix: everything up to where the two texts stop agreeing.
    var common = 0
    while (common < base.length && common < incoming.length &&
        base[common] == incoming[common] && common < SENTINEL_LENGTH + FIELD_HARD_CAP
    ) common++
    val deleted = base.length - common
    val inserted = incoming.substring(common)
    return TerminalEdit(deleted, inserted)
}

/**
 * The content an incoming edit was diffed against.
 *
 * Compose pushes a programmatic value change to the platform field at the next recomposition, so
 * between `field = sentinelValue()` and that frame landing there are two candidates for what the
 * platform held when the IME computed its edit: the pre-reset text the platform may still hold, or
 * the reset value the recomposition is installing ([adopted], which is what the field carries).
 * The discriminator is structural: an edit computed against the pre-reset content worked from its
 * keystroke tail, so one of the two tails extends the other - an insertion appends past it, a
 * deletion truncates it - while an edit computed after the reset landed starts a tail of its own
 * that merely happens to overlap. Tail relation means the old base; anything else means the reset
 * landed and [adopted] is the truth.
 *
 * Both tails must be non-empty for the relation to mean anything: an empty pre-reset tail carries
 * no evidence (the reset was from a bare sentinel), and an empty incoming tail is the
 * reset-landed-then-backspace-into-the-padding case, where guessing "raced" would send a handful
 * of spurious Backspaces for what was one press.
 */
internal fun pendingResetBase(resetPendingFrom: String?, adopted: String, incoming: String): String {
    if (resetPendingFrom == null) return adopted
    val preResetTail = resetPendingFrom.drop(SENTINEL_LENGTH)
    val incomingTail = incoming.drop(SENTINEL_LENGTH)
    val raced = preResetTail.isNotEmpty() && incomingTail.isNotEmpty() &&
        (incomingTail.startsWith(preResetTail) || preResetTail.startsWith(incomingTail))
    return if (raced) resetPendingFrom else adopted
}

/**
 * The next value for the courier field: adopt the IME's edit, unless it is time to reset.
 *
 * Adoption is the rule and the reset is the exception, each for its own reason:
 *  - **Adopt.** The value `onValueChange` reports *is* the platform field's content, so echoing it
 *    back as state keeps the two in lockstep with no programmatic edit at all. The previous design
 *    reset on every keystroke, which meant tearing the field's text twice per character: a commit
 *    that arrived before the reset's recomposition was diffed against stale content and re-sent the
 *    previous symbol along with the new one (the doubled `/`), and a keyboard mid-composition could
 *    lose its pending character to the tear, so Enter ran `hel` for `help`.
 *  - **Reset, but only when the shell cannot lose anything.** The field cannot grow forever, and it
 *    must never run empty of sentinel. Both are handled here, and the two moments are chosen for
 *    safety rather than frequency: right after a newline in the committed text (the line is
 *    finished), or when the hard cap is reached. At those moments the IME is between words, which
 *    is when a reset is least able to eat a character.
 *  - **Never while a composition is live**, whatever else says otherwise. A reset is a programmatic
 *    edit and the composition is a range into the text it replaces, so tearing the field is what
 *    loses the character a keyboard was holding and re-anchors its composing region against text it
 *    no longer belongs to. A word is nowhere near the hard cap, so the only thing this can defer is
 *    the reset that would have happened anyway.
 */
internal fun nextFieldValue(change: TextFieldValue): TextFieldValue {
    if (isComposing(change.composition)) return change
    val resetsOnNewline = change.text.any { it == LINE_FEED || it == CARRIAGE_RETURN }
    if (!resetsOnNewline && change.text.startsWith(SENTINEL) &&
        change.text.length < SENTINEL_LENGTH + FIELD_HARD_CAP
    ) {
        // Plain adoption: what the IME wrote is what the field now holds.
        return change
    }
    // A reset, at one of the two safe moments (a finished line, the hard cap) or because the
    // sentinel was somehow consumed. The new tail is the keystrokes that survive the reset - none,
    // when the line is finished - so the shell's next Backspace still has something to eat.
    val tail = if (resetsOnNewline) {
        ""
    } else {
        change.text.filter { it != SENTINEL_CHAR }.takeLast(FIELD_HARD_CAP - SENTINEL_LENGTH)
    }
    return TextFieldValue(SENTINEL + tail, TextRange(SENTINEL.length + tail.length))
}

/**
 * The rows of keys a phone keyboard does not have, drawn from the configuration the settings screen
 * edits.
 *
 * Everything here is reachable no other way on a touch device - there is no Ctrl on Gboard, no Esc,
 * and no Page Up - so this bar is not a convenience but the difference between the terminal being
 * usable and being a text box. Each row scrolls horizontally rather than wrapping, so the leftmost
 * keys stay where the thumb expects them at every screen width. [KeyBarPrefs.rowsForLayout] decides
 * how many rows there are and what is on each; this composable only draws what it is handed.
 */
@Composable
fun TerminalKeyRow(
    latches: TerminalLatches,
    prefs: KeyBarPrefs,
    onKey: (TerminalKey, Boolean, Boolean, Boolean) -> Unit,
    onText: (String) -> Unit,
    /** A single custom character held with Ctrl or Alt, the same path a typed character takes. */
    onChar: (Char, Boolean, Boolean) -> Unit,
    /** The colour behind the row, which its edge fades blend into: the terminal's own. */
    background: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        prefs.rowsForLayout().forEach { row ->
            KeyRowScroller(background) {
                row.forEach { cap ->
                    when (cap.kind) {
                        KeyBarCapKind.LATCH -> LatchKey(cap.id, latches.armed(cap.id), { latches.toggle(cap.id) }, prefs.size)
                        KeyBarCapKind.KEY -> {
                            val key = TerminalKey.entries.firstOrNull { it.name == cap.id } ?: return@forEach
                            TerminalKeyCap(cap.label ?: KeyBarCatalog.defaultLabel(key), size = prefs.size) {
                                val (ctrl, alt, shift) = latches.consume()
                                onKey(key, ctrl, alt, shift)
                            }
                        }
                        KeyBarCapKind.TEXT -> {
                            val text = cap.text.orEmpty()
                            TerminalKeyCap(cap.label ?: text, size = prefs.size) {
                                // The latches apply to a custom cap the way they apply to a typed
                                // character: Ctrl-/ is a real binding (it sends US), and treating a
                                // custom cap as "just text" would silently ignore an armed latch.
                                val (ctrl, alt, _) = latches.consume()
                                if (ctrl || alt) {
                                    text.forEach { char -> onChar(char, ctrl, alt) }
                                } else {
                                    onText(text)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One row of caps, with the edges it is scrolled past faded instead of cut.
 *
 * The row scrolls rather than wrapping, so the cap at the edge is clipped mid-glyph: "ENTER" arrives
 * as "EN", which was reported as a control drawn wrong rather than as a row with more in it. The fade
 * is drawn only where there is something scrolled out of sight, so it never suggests a hidden key
 * that does not exist, and it changes nothing about the row itself - every cap is where it was, and
 * in the same place when the row is scrolled back.
 */
@Composable
private fun KeyRowScroller(
    background: Color,
    content: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        val scroll = rememberScrollState()
        Row(
            Modifier.horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
        val overflow = rowOverflow(scroll.value, scroll.maxValue)
        if (overflow.atStart || overflow.atEnd) {
            Box(
                Modifier.matchParentSize().drawWithContent {
                    drawContent()
                    val width = size.width
                    val height = size.height
                    // At most half the row: on a narrow screen a wider fade would leave no cap at
                    // full strength.
                    val fade = KEY_ROW_FADE_WIDTH.toPx().coerceAtMost(width / 2f)
                    if (overflow.atStart) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(background, Color.Transparent),
                                startX = 0f,
                                endX = fade,
                            ),
                            size = Size(fade, height),
                        )
                    }
                    if (overflow.atEnd) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color.Transparent, background),
                                startX = width - fade,
                                endX = width,
                            ),
                            topLeft = Offset(width - fade, 0f),
                            size = Size(fade, height),
                        )
                    }
                },
            )
        }
    }
}

/**
 * The caps, in thumb order.
 *
 * `internal` so a test can hold the bar to the keys a terminal is unusable without: on a touch device
 * there is no other way to reach Esc, Tab, the arrows or Page Up, so a cap quietly dropped from this
 * list is a key that no longer exists on the device.
 */
internal val NAMED_ROW: List<Pair<String, TerminalKey>> = listOf(
    "ESC" to TerminalKey.ESCAPE,
    "TAB" to TerminalKey.TAB,
    "←" to TerminalKey.ARROW_LEFT,
    "↓" to TerminalKey.ARROW_DOWN,
    "↑" to TerminalKey.ARROW_UP,
    "→" to TerminalKey.ARROW_RIGHT,
    "HOME" to TerminalKey.HOME,
    "END" to TerminalKey.END,
    "PGUP" to TerminalKey.PAGE_UP,
    "PGDN" to TerminalKey.PAGE_DOWN,
    "DEL" to TerminalKey.DELETE,
    "BKSP" to TerminalKey.BACKSPACE,
    "ENTER" to TerminalKey.ENTER,
)

@Composable
private fun LatchKey(label: String, armed: Boolean, onToggle: () -> Unit, size: KeyBarSize) {
    TerminalKeyCap(
        label = label,
        armed = armed,
        size = size,
        // Announced rather than only coloured: an armed latch changes what the next key does, which a
        // screen reader user has no other way to discover.
        description = if (armed) "$label on" else "$label off",
        onClick = onToggle,
    )
}

@Composable
private fun TerminalKeyCap(
    label: String,
    armed: Boolean = false,
    description: String? = null,
    size: KeyBarSize = KeyBarSize.NORMAL,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = description ?: label },
        shape = RoundedCornerShape(8.dp),
        color = if (armed) MaterialTheme.colorScheme.primary else KEY_BACKGROUND,
    ) {
        Text(
            label,
            color = if (armed) MaterialTheme.colorScheme.onPrimary else KEY_FOREGROUND,
            style = MaterialTheme.typography.labelLarge,
            // Long custom labels must not stretch the row or the cap: they soften-wrap and the cap
            // keeps its height, so a `sudo` button and a `/` button read as the same control.
            softWrap = false,
            maxLines = 1,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = size.verticalPaddingDp.dp)
                // A thumb misses; the size's minimum height is the floor that keeps even a compact
                // cap easy to hit, and every cap in a row the same height is what makes the row read
                // as one firm, even strip.
                .heightIn(min = size.minHeightDp.dp),
        )
    }
}

private val KEY_BACKGROUND = Color(0xFF1D2638)
private val KEY_FOREGROUND = Color(0xFFB3C1D9)

/** How far a scrolled row's edge fade reaches in from the side of the screen. */
private val KEY_ROW_FADE_WIDTH = 24.dp
