package dev.eclipse.ssh.terminal

/**
 * The keyboard enhancements a remote program can turn on, as [AnsiTerminalBuffer] tracks them.
 *
 * Both change the bytes a key press sends, both are asked for at startup and dropped on the way out,
 * and both belong to the session rather than to a key - carried in as an argument only so
 * [TerminalKeys] stays pure and testable without a session.
 */
data class KeyboardModes(
    /** Kitty's flags, already reduced to the ones this terminal implements. See [KittyKeyboard]. */
    val kittyFlags: Int = 0,
    /**
     * xterm's modifyOtherKeys level: 0 off, [ModifyOtherKeys.AMBIGUOUS] reports only the modifiers the
     * legacy bytes would otherwise lose, [ModifyOtherKeys.ALL] reports every key with a modifier.
     */
    val modifyOtherKeys: Int = 0,
) {
    val none: Boolean get() = kittyFlags == 0 && modifyOtherKeys == 0

    companion object {
        val NONE = KeyboardModes()
    }
}

/**
 * Kitty's keyboard protocol flags, `CSI > flags u` and the four other forms around it.
 *
 * Only [DISAMBIGUATE] is implemented, and [SUPPORTED] is what the query answers with, because
 * reporting a flag the terminal cannot honour is a claim the program then relies on. Each of the
 * other four is refused for its own reason, and none of them is "not yet":
 *
 *  * [REPORT_EVENT_TYPES] wants repeat and release events. This app has no source for them - Android
 *    reports a held key as more key-downs and the key bar is a plain touch target - so a program
 *    asking would get press events and would never learn why it saw no others.
 *  * [REPORT_ALTERNATE_KEYS] wants a shift state and a physical-key layout. A character reaches this
 *    app already shifted, with the shift folded into the character, and nothing maps an Android key
 *    code to a PC-101 position.
 *  * [REPORT_ALL_KEYS] means "send no text, only key events". Text typed through the IME arrives as
 *    committed characters with no key identity at all, so this terminal cannot stop sending it.
 *  * [ASSOCIATED_TEXT] is defined only as an enhancement to [REPORT_ALL_KEYS].
 */
object KittyKeyboard {
    const val DISAMBIGUATE = 0b1
    const val REPORT_EVENT_TYPES = 0b10
    const val REPORT_ALTERNATE_KEYS = 0b100
    const val REPORT_ALL_KEYS = 0b1000
    const val ASSOCIATED_TEXT = 0b10000

    /** The flags this terminal honours; every other bit is refused rather than stored. */
    const val SUPPORTED = DISAMBIGUATE

    /**
     * How many pushes are kept. The protocol asks for a bound so a program cannot grow the stack
     * without limit; past it the oldest entry goes, which is the protocol's own rule.
     */
    const val MAX_DEPTH = 16
}

/** xterm's modifyOtherKeys levels, as `CSI > 4 ; level m` sets them. */
object ModifyOtherKeys {
    /**
     * The resource number the sequences are addressed to. `CSI > 4 ; n m` is modifyOtherKeys; the
     * other numbers xterm defines under the same final byte are other resources entirely -
     * modifyCursorKeys and modifyFunctionKeys - which this terminal does not implement and must not
     * mistake for this one.
     */
    const val RESOURCE = 4

    const val OFF = 0

    /**
     * Only the keys whose modifier the legacy bytes would lose: `Ctrl-;`, `Ctrl-2`. Not `Shift-Tab`
     * or `Ctrl-Enter` - the keys with a traditional encoding, which level 1 leaves alone and level 2
     * takes.
     */
    const val AMBIGUOUS = 1

    /** Every key with a modifier, including the ones a control byte already covers: `Ctrl-C`. */
    const val ALL = 2
}

/**
 * The bytes for a key that one of the two keyboard protocols has turned into an escape code, or null
 * to send the legacy bytes.
 *
 * Kitty is asked first, and that ordering is the whole reason a program may set both. Its flag list
 * is the program's own statement of which keys it wants escaped, so a key kitty claims is already the
 * form the program asked for; modifyOtherKeys then covers what kitty left alone, which is how a
 * program that sets both still gets its modified Enter and Tab.
 *
 * [modifierIsLost] is whether the legacy bytes are the same as they would be with no modifier held,
 * which is what xterm's level 1 is defined over: the keys with no traditional encoding - Ctrl-; and
 * friends, which would otherwise arrive as a bare `;`. Level 2 is defined over every key, so it does
 * not consult it.
 */
internal fun escapeCoded(
    code: Int,
    modifiers: TerminalModifiers,
    modes: KeyboardModes,
    kittyEscapes: Boolean,
    modifierIsLost: Boolean,
): ByteArray? = when {
    kittyEscapes && modes.kittyFlags and KittyKeyboard.DISAMBIGUATE != 0 -> kittySequence(code, modifiers)
    // Both modifyOtherKeys forms carry the modifier, so with none held they would encode it as "no
    // modifiers" - a sequence that says exactly what the legacy bytes say and that a program parses
    // for nothing. Escape is the exception, and it is the branch above: flag 1 exists to give it a
    // code of its own.
    modifiers.none -> null
    modes.modifyOtherKeys >= ModifyOtherKeys.ALL -> modifyOtherKeysSequence(code, modifiers)
    modes.modifyOtherKeys == ModifyOtherKeys.AMBIGUOUS && modifierIsLost ->
        modifyOtherKeysSequence(code, modifiers)
    else -> null
}

/**
 * The code point a key sends, or null when the key has no character of its own.
 *
 * Null is the gate that keeps the cursor, function and keypad keys out of both protocols, which is
 * what both want: an arrow already carries its modifiers in `CSI 1;5A`, and the kitty functional
 * numbers (`57352` and up) are not code points this app could report without inventing them.
 */
internal fun keyCodeOf(key: TerminalKey): Int? = when (key) {
    TerminalKey.ENTER -> CR
    TerminalKey.TAB -> HT
    TerminalKey.BACKSPACE -> DEL
    TerminalKey.ESCAPE -> ESC
    else -> null
}

/**
 * `CSI code ; modifiers u`, or `CSI code u` with none.
 *
 * The 1 that precedes a modifier field is dropped rather than sent as `;1`: the protocol says an
 * omitted field means "no modifiers", and the consumer this app is measured against parses both.
 */
private fun kittySequence(code: Int, modifiers: TerminalModifiers): ByteArray =
    if (modifiers.none) csi("${code}u") else csi("$code;${modifierParameter(modifiers)}u")

/** xterm's `CSI 27 ; modifiers ; code ~`, which is the same bit field kitty uses. */
private fun modifyOtherKeysSequence(code: Int, modifiers: TerminalModifiers): ByteArray =
    csi("27;${modifierParameter(modifiers)};$code~")

/**
 * The xterm modifier parameter: a bit field offset by one, because 0 is not a legal CSI parameter
 * and would be read as "omitted". Both protocols spell shift 1, alt 2 and ctrl 4, and both subtract
 * the one back when reading it, so one function serves for the two.
 */
internal fun modifierParameter(modifiers: TerminalModifiers): Int =
    1 + (if (modifiers.shift) 1 else 0) + (if (modifiers.alt) 2 else 0) + (if (modifiers.ctrl) 4 else 0)

/** ESC, the prefix of every sequence in this file. */
private const val ESC = 0x1B

/** CR - the character Enter sends, and so the code point both protocols report for it. */
private const val CR = 0x0D

/** HT - the character Tab sends. */
private const val HT = 0x09

/** DEL - the character Backspace sends, rather than BS. See `TerminalKeys`' own note on that. */
private const val DEL = 0x7F

/** `ESC [` followed by [tail], which is always plain ASCII. */
internal fun csi(tail: String): ByteArray =
    byteArrayOf(ESC.toByte(), 0x5B) + tail.toByteArray(Charsets.US_ASCII)
