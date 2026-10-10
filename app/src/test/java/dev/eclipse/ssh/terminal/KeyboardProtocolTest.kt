package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The bytes a key press sends once a program has turned on one of the two keyboard protocols.
 *
 * Every row of the design's scenario table is one assertion here, and the last group is the one that
 * matters most: it checks our bytes against the parsers of the program this app's users actually run
 * (Claude Code, whose `>5u` / `>4;2m` / `?u` startup sequence these encodings exist for). A terminal
 * that emits a sequence the consumer's own regexes reject has turned every Ctrl-C into a literal `c`
 * and no test of ours would have noticed.
 */
class KeyboardProtocolTest {

    // region kitty flag 1, the disambiguate flag

    @Test
    fun `with the flag on an unmodified character is still its own byte`() {
        assertThat(TerminalKeys.encode('a', modes = KITTY).codes()).isEqualTo(listOf(0x61))
    }

    @Test
    fun `with the flag on ctrl sends the character's own code point and not the control byte`() {
        // The whole point of the protocol: 0x03 cannot say whether the user pressed Ctrl-C or Ctrl-Shift-C
        // on a keyboard where shift is folded in, and 99 can.
        assertThat(TerminalKeys.encode('c', CTRL, modes = KITTY).codes()).isEqualTo("${ESC}[99;5u".codes())
        assertThat(TerminalKeys.encode('z', CTRL, modes = KITTY).codes()).isEqualTo("${ESC}[122;5u".codes())
    }

    @Test
    fun `with the flag on alt is reported too`() {
        assertThat(TerminalKeys.encode('a', ALT, modes = KITTY).codes()).isEqualTo("${ESC}[97;3u".codes())
        assertThat(TerminalKeys.encode('a', CTRL_ALT, modes = KITTY).codes())
            .isEqualTo("${ESC}[97;7u".codes())
    }

    @Test
    fun `with the flag on a shift-only character is left alone, because it carries its own shift`() {
        assertThat(TerminalKeys.encode('A', SHIFT, modes = KITTY).codes()).isEqualTo(listOf(0x41))
    }

    @Test
    fun `with the flag on escape gets a code of its own`() {
        // Flag 1's other half: `ESC` alone is otherwise indistinguishable from the start of a sequence.
        assertThat(TerminalKeys.encode(TerminalKey.ESCAPE, modes = KITTY).codes())
            .isEqualTo("${ESC}[27u".codes())
        assertThat(TerminalKeys.encode(TerminalKey.ESCAPE, ALT, modes = KITTY).codes())
            .isEqualTo("${ESC}[27;3u".codes())
    }

    @Test
    fun `with the flag on enter, tab and backspace keep their legacy bytes`() {
        // The spec exempts them so a user can still type `reset` at a shell that is not listening.
        assertThat(TerminalKeys.encode(TerminalKey.ENTER, CTRL, modes = KITTY).codes()).isEqualTo(listOf(0x0D))
        assertThat(TerminalKeys.encode(TerminalKey.TAB, modes = KITTY).codes()).isEqualTo(listOf(0x09))
        assertThat(TerminalKeys.encode(TerminalKey.BACKSPACE, modes = KITTY).codes())
            .isEqualTo(listOf(0x7F))
    }

    @Test
    fun `with the flag on the cursor and function keys are untouched`() {
        // They have no code point to report, and their own sequences already carry the modifiers.
        assertThat(TerminalKeys.encode(TerminalKey.ARROW_UP, CTRL, modes = KITTY).codes())
            .isEqualTo("${ESC}[1;5A".codes())
        assertThat(TerminalKeys.encode(TerminalKey.F5, modes = KITTY).codes())
            .isEqualTo("${ESC}[15~".codes())
    }

    @Test
    fun `a flag this terminal refuses is not honoured`() {
        // Flags 2, 4, 8 and 16 ask for event types, a physical key map, "no text" and associated text,
        // none of which this app can produce. Reporting them would be a promise it cannot keep.
        val refused = KeyboardModes(kittyFlags = 0b11110)

        assertThat(TerminalKeys.encode('c', CTRL, modes = refused).codes()).isEqualTo(listOf(0x03))
        assertThat(TerminalKeys.encode(TerminalKey.ESCAPE, modes = refused).codes()).isEqualTo(listOf(0x1B))
    }

    // endregion

    // region modifyOtherKeys

    @Test
    fun `level two reports a control byte key as its own code point`() {
        assertThat(TerminalKeys.encode('c', CTRL, modes = LEVEL_2).codes())
            .isEqualTo("${ESC}[27;5;99~".codes())
        assertThat(TerminalKeys.encode('a', CTRL, modes = LEVEL_2).codes())
            .isEqualTo("${ESC}[27;5;97~".codes())
    }

    @Test
    fun `level two reports the keys the kitty flag exempts`() {
        assertThat(TerminalKeys.encode(TerminalKey.TAB, SHIFT, modes = LEVEL_2).codes())
            .isEqualTo("${ESC}[27;2;9~".codes())
        assertThat(TerminalKeys.encode(TerminalKey.ENTER, SHIFT, modes = LEVEL_2).codes())
            .isEqualTo("${ESC}[27;2;13~".codes())
        assertThat(TerminalKeys.encode(TerminalKey.ENTER, ALT, modes = LEVEL_2).codes())
            .isEqualTo("${ESC}[27;3;13~".codes())
        assertThat(TerminalKeys.encode(TerminalKey.BACKSPACE, CTRL, modes = LEVEL_2).codes())
            .isEqualTo("${ESC}[27;5;127~".codes())
    }

    @Test
    fun `level two leaves an unmodified key alone`() {
        // A sequence carrying "no modifiers" says exactly what the byte already says, and a program
        // would parse it for nothing.
        assertThat(TerminalKeys.encode('a', modes = LEVEL_2).codes()).isEqualTo(listOf(0x61))
        assertThat(TerminalKeys.encode(TerminalKey.TAB, modes = LEVEL_2).codes()).isEqualTo(listOf(0x09))
        assertThat(TerminalKeys.encode(TerminalKey.ENTER, modes = LEVEL_2).codes()).isEqualTo(listOf(0x0D))
    }

    @Test
    fun `level one reports only what the legacy bytes would lose`() {
        // Ctrl-; has no control byte, so it would arrive as a bare `;` and the modifier would vanish.
        assertThat(TerminalKeys.encode(';', CTRL, modes = LEVEL_1).codes())
            .isEqualTo("${ESC}[27;5;59~".codes())
        assertThat(TerminalKeys.encode('2', CTRL, modes = LEVEL_1).codes())
            .isEqualTo("${ESC}[27;5;50~".codes())

        // Ctrl-C does have one, so level 1 leaves it as the interrupt byte it has always been.
        assertThat(TerminalKeys.encode('c', CTRL, modes = LEVEL_1).codes()).isEqualTo(listOf(0x03))
        // Alt is a prefix rather than a lost modifier, and level 1 does not touch it.
        assertThat(TerminalKeys.encode('a', ALT, modes = LEVEL_1).codes()).isEqualTo(listOf(0x1B, 0x61))
        // Nor a key with a traditional encoding: level 1 is the "no traditional encoding" level.
        assertThat(TerminalKeys.encode(TerminalKey.TAB, SHIFT, modes = LEVEL_1).codes())
            .isEqualTo("${ESC}[Z".codes())
        assertThat(TerminalKeys.encode(TerminalKey.ENTER, CTRL, modes = LEVEL_1).codes())
            .isEqualTo(listOf(0x0D))
        assertThat(TerminalKeys.encode(TerminalKey.BACKSPACE, ALT, modes = LEVEL_1).codes())
            .isEqualTo(listOf(0x1B, 0x7F))
    }

    @Test
    fun `level one with nothing held changes nothing`() {
        assertThat(TerminalKeys.encode(TerminalKey.ESCAPE, modes = LEVEL_1).codes()).isEqualTo(listOf(0x1B))
        assertThat(TerminalKeys.encode(';', modes = LEVEL_1).codes()).isEqualTo(listOf(0x3B))
    }

    @Test
    fun `kitty is asked before modifyOtherKeys, because a program may set both`() {
        // Claude Code does exactly this, and the kitty form is the narrower request of the two.
        val both = KeyboardModes(kittyFlags = KittyKeyboard.DISAMBIGUATE, modifyOtherKeys = ModifyOtherKeys.ALL)

        assertThat(TerminalKeys.encode('c', CTRL, modes = both).codes()).isEqualTo("${ESC}[99;5u".codes())
        assertThat(TerminalKeys.encode('a', CTRL, modes = both).codes()).isEqualTo("${ESC}[97;5u".codes())
        // Tab is exempt from the kitty flag, so modifyOtherKeys is what covers it - which is how a
        // Shift-Tab still arrives as a Shift-Tab.
        assertThat(TerminalKeys.encode(TerminalKey.TAB, SHIFT, modes = both).codes())
            .isEqualTo("${ESC}[27;2;9~".codes())
    }

    @Test
    fun `with neither protocol on the bytes are exactly the legacy ones`() {
        for (key in TerminalKey.entries) {
            for (modifiers in MODIFIER_SETS) {
                assertThat(TerminalKeys.encode(key, modifiers, applicationCursorKeys = true, modes = KeyboardModes.NONE))
                    .isEqualTo(TerminalKeys.encode(key, modifiers, applicationCursorKeys = true))
                assertThat(TerminalKeys.encode(key, modifiers, modes = KeyboardModes.NONE))
                    .isEqualTo(TerminalKeys.encode(key, modifiers))
            }
        }
        for (char in listOf('a', 'C', ';', '2', 'é')) {
            for (modifiers in MODIFIER_SETS) {
                assertThat(TerminalKeys.encode(char, modifiers, modes = KeyboardModes.NONE))
                    .isEqualTo(TerminalKeys.encode(char, modifiers))
            }
        }
    }

    // endregion

    // region what the program on the other end parses

    @Test
    fun `every sequence sent under Claude Code's own flags matches its own parsers`() {
        // The two regexes are Claude Code's, read out of the installed bundle rather than guessed:
        // `^\x1b\[(\d+)(?::(\d*)(?::(\d+))?)?(?:;(\d+))?u` for the kitty form and
        // `^\x1b\[27;(\d+);(\d+)~` for the xterm one. Note what they do not accept: no event-type
        // sub-field in the kitty form, and both parameters required in the xterm one.
        val kitty = Regex("^\\x1b\\[(\\d+)(?::(\\d*)(?::(\\d+))?)?(?:;(\\d+))?u$")
        val other = Regex("^\\x1b\\[27;(\\d+);(\\d+)~$")
        val modes = KeyboardModes(kittyFlags = KittyKeyboard.DISAMBIGUATE, modifyOtherKeys = ModifyOtherKeys.ALL)
        val cases = listOf(
            TerminalKeys.encode('c', CTRL, modes = modes) to 99,
            TerminalKeys.encode('z', CTRL, modes = modes) to 122,
            TerminalKeys.encode('a', ALT, modes = modes) to 97,
            TerminalKeys.encode(TerminalKey.ESCAPE, modes = modes) to 27,
            TerminalKeys.encode(TerminalKey.TAB, SHIFT, modes = modes) to 9,
            TerminalKeys.encode(TerminalKey.ENTER, ALT, modes = modes) to 13,
            TerminalKeys.encode(TerminalKey.ENTER, SHIFT, modes = modes) to 13,
        )

        for ((bytes, codepoint) in cases) {
            val text = String(bytes, Charsets.US_ASCII)
            val asKitty = kitty.matchEntire(text)
            val asOther = other.matchEntire(text)

            assertThat(asKitty != null || asOther != null).isTrue()
            // The code point the consumer reads back must be the key the user pressed, or the program
            // presses a different key than the one on screen.
            val read = asKitty?.groupValues?.get(1)?.toIntOrNull() ?: asOther!!.groupValues[2].toInt()
            assertThat(read).isEqualTo(codepoint)
        }
    }

    // endregion

    private fun ByteArray.codes(): List<Int> = map { it.toInt() and 0xFF }

    private fun String.codes(): List<Int> = toByteArray(Charsets.US_ASCII).map { it.toInt() and 0xFF }

    private companion object {
        const val ESC = "\u001B"
        val KITTY = KeyboardModes(kittyFlags = KittyKeyboard.DISAMBIGUATE)
        val LEVEL_1 = KeyboardModes(modifyOtherKeys = ModifyOtherKeys.AMBIGUOUS)
        val LEVEL_2 = KeyboardModes(modifyOtherKeys = ModifyOtherKeys.ALL)
        val CTRL = TerminalModifiers(ctrl = true)
        val ALT = TerminalModifiers(alt = true)
        val SHIFT = TerminalModifiers(shift = true)
        val CTRL_ALT = TerminalModifiers(ctrl = true, alt = true)
        val MODIFIER_SETS = listOf(
            TerminalModifiers.NONE,
            CTRL,
            ALT,
            SHIFT,
            CTRL_ALT,
            TerminalModifiers(ctrl = true, shift = true),
            TerminalModifiers(ctrl = true, alt = true, shift = true),
        )
    }
}
