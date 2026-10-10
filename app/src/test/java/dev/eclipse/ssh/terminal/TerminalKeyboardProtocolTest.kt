package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The kitty keyboard protocol's four forms and xterm's modifyOtherKeys, as the buffer tracks them.
 *
 * What the *keys* then send is [KeyboardProtocolTest]; this is about reading the program's request
 * correctly, answering it truthfully, and forgetting it when the program is gone or the screen it was
 * on has been handed back. The two failures worth guarding are a stack that outlives its program - a
 * full-screen editor that crashes leaves the shell's keyboard in a mode nobody asked for - and a query
 * answered with flags this terminal cannot honour, which makes the program stop using its fallback.
 */
class TerminalKeyboardProtocolTest {

    @Test
    fun `a session nobody has asked anything of reports no protocol`() {
        val buffer = session()

        assertThat(buffer.keyboardModes()).isEqualTo(KeyboardModes.NONE)
        assertThat(buffer.frame().keyboardModes).isEqualTo(KeyboardModes.NONE)
    }

    // region push, pop and the query

    @Test
    fun `a pushed flag is what the query answers with`() {
        val buffer = session()
        val replies = buffer.replies()

        buffer.feed("${ESC}[>5u${ESC}[?u")

        assertThat(replies).containsExactly("${ESC}[?1u")
    }

    @Test
    fun `a push of no flags is a push of zero, not of one`() {
        // `first` would read the empty parameter list as 1, and 1 is the disambiguate flag: a program
        // pushing the empty set would turn on the one flag this terminal does implement.
        val buffer = session()
        buffer.feed("${ESC}[>1u")

        buffer.feed("${ESC}[>u")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    @Test
    fun `a pop puts back the flags the push saved`() {
        val buffer = session()

        buffer.feed("${ESC}[>1u")
        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(KittyKeyboard.DISAMBIGUATE)

        buffer.feed("${ESC}[<u")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    @Test
    fun `a pop that empties the stack resets every flag`() {
        // The flags are set outside the stack, then a push saves them. Popping that push empties the
        // stack, and an emptied stack is the default - not the value its bottom entry happens to hold.
        val buffer = session()
        buffer.feed("${ESC}[=1u${ESC}[>0u")

        buffer.feed("${ESC}[<u")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    @Test
    fun `a pop of more entries than the stack holds is a reset, not a crash`() {
        val buffer = session()
        buffer.feed("${ESC}[>1u")

        buffer.feed("${ESC}[<99u")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    @Test
    fun `popping an empty stack resets the flags that were set without pushing`() {
        val buffer = session()
        buffer.feed("${ESC}[=1u")

        buffer.feed("${ESC}[<u")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    @Test
    fun `the stack evicts its oldest entry rather than growing without bound`() {
        // Every push saves the flag that is already set, so a bounded stack is emptied by sixteen pops
        // and an unbounded one still holds an entry - and restores a flag - after them.
        val buffer = session()
        repeat(17) { buffer.feed("${ESC}[>1u") }

        repeat(16) { buffer.feed("${ESC}[<u") }

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    @Test
    fun `a pop of one entry is what an omitted count means`() {
        // Two pushes, so a default of two would reach past the flag the second one set aside.
        val buffer = session()
        buffer.feed("${ESC}[>1u${ESC}[>0u")

        buffer.feed("${ESC}[<u")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(KittyKeyboard.DISAMBIGUATE)
    }

    @Test
    fun `a pop of several entries stops at the bottom of the stack`() {
        val buffer = session()
        buffer.feed("${ESC}[>0u${ESC}[>1u${ESC}[>0u")

        buffer.feed("${ESC}[<3u")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    // endregion

    // region the set form

    @Test
    fun `the set form assigns by default`() {
        val buffer = session()

        buffer.feed("${ESC}[=1u")
        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(KittyKeyboard.DISAMBIGUATE)

        buffer.feed("${ESC}[=0u")
        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    @Test
    fun `mode two sets the named bits and leaves the rest alone`() {
        val buffer = session()
        buffer.feed("${ESC}[=1u")

        buffer.feed("${ESC}[=2;2u")

        // Bit 2 is refused, so the set is a no-op on a flag this terminal keeps rather than a silent
        // upgrade to a capability it does not have.
        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(KittyKeyboard.DISAMBIGUATE)
    }

    @Test
    fun `mode three clears the named bits and leaves the rest alone`() {
        val buffer = session()
        buffer.feed("${ESC}[=1u")

        buffer.feed("${ESC}[=1;3u")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    @Test
    fun `an unknown mode is read as the default rather than ignored`() {
        val buffer = session()
        buffer.feed("${ESC}[=1u")

        buffer.feed("${ESC}[=0;9u")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    // endregion

    // region the alternate screen

    @Test
    fun `the alternate screen has a keyboard mode of its own`() {
        val buffer = session()
        buffer.feed("${ESC}[>1u")

        buffer.feed("${ESC}[?1049h")

        // A full-screen program never inherits the flags of the shell it was started from - it would
        // otherwise get escape-coded keys it never asked for.
        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    @Test
    fun `a flag pushed on the alternate screen does not survive into the next one`() {
        val buffer = session()
        // Pushed twice, for a reason: a single stale entry is invisible, because popping it empties the
        // stack and an emptied stack resets every flag anyway. The flags are set outside the stack so
        // each push saves a flag that is not the default.
        buffer.feed("${ESC}[?1049h${ESC}[=1u${ESC}[>0u${ESC}[=1u${ESC}[>0u")

        buffer.feed("${ESC}[?1049l")
        buffer.feed("${ESC}[?1049h")

        // The program that pushed them is gone. This is the crash case: `vim` killed mid-run must not
        // leave the next full-screen program in a keyboard mode of its making, in its flags or in the
        // stack a pop would restore them from.
        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)

        buffer.feed("${ESC}[<u")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    @Test
    fun `the primary screen gets its own flags back when the program exits`() {
        val buffer = session()
        buffer.feed("${ESC}[>1u")

        buffer.feed("${ESC}[?1049h${ESC}[>0u${ESC}[?1049l")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(KittyKeyboard.DISAMBIGUATE)
    }

    @Test
    fun `a program that leaves without popping cannot strand the shell in its mode`() {
        val buffer = session()
        buffer.feed("${ESC}[?1049h${ESC}[>1u")

        buffer.feed("${ESC}[?1049l")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    // endregion

    // region modifyOtherKeys

    @Test
    fun `modifyOtherKeys is set and reported by its own pair of sequences`() {
        val buffer = session()
        val replies = buffer.replies()

        buffer.feed("${ESC}[>4;2m${ESC}[?4m")

        assertThat(buffer.keyboardModes().modifyOtherKeys).isEqualTo(ModifyOtherKeys.ALL)
        assertThat(replies).containsExactly("${ESC}[>4;2m")
    }

    @Test
    fun `the query answers with the level in force and not with the highest one`() {
        // A program that asked for level 1 and hears `2` back would stop sending plain bytes for the
        // keys level 1 leaves alone - Ctrl-C among them - and the interrupt would become a literal `c`.
        val buffer = session()
        val replies = buffer.replies()

        buffer.feed("${ESC}[?4m")
        buffer.feed("${ESC}[>4;1m${ESC}[?4m")

        assertThat(replies).containsExactly("${ESC}[>4;0m", "${ESC}[>4;1m").inOrder()
    }

    @Test
    fun `an omitted level turns modifyOtherKeys off`() {
        // Which is xterm's "reset the resource to its initial value", and the form Claude Code sends
        // on the way out of its own teardown.
        val buffer = session()
        buffer.feed("${ESC}[>4;2m")

        buffer.feed("${ESC}[>4m")

        assertThat(buffer.keyboardModes().modifyOtherKeys).isEqualTo(ModifyOtherKeys.OFF)
    }

    @Test
    fun `a level above two is clamped rather than stored`() {
        val buffer = session()

        buffer.feed("${ESC}[>4;9m")

        assertThat(buffer.keyboardModes().modifyOtherKeys).isEqualTo(ModifyOtherKeys.ALL)
    }

    @Test
    fun `the disabling form turns modifyOtherKeys off`() {
        val buffer = session()
        buffer.feed("${ESC}[>4;2m")

        buffer.feed("${ESC}[>4n")

        assertThat(buffer.keyboardModes().modifyOtherKeys).isEqualTo(ModifyOtherKeys.OFF)
    }

    @Test
    fun `the other modify resources are not mistaken for this one`() {
        // `>1;2m` is modifyCursorKeys and `>2;2m` modifyFunctionKeys: the same final byte, the same
        // shape, a different resource. Neither is this terminal's to act on.
        val buffer = session()

        buffer.feed("${ESC}[>1;2m${ESC}[>2;2m")

        assertThat(buffer.keyboardModes().modifyOtherKeys).isEqualTo(ModifyOtherKeys.OFF)
    }

    @Test
    fun `a prefixed m is not SGR`() {
        val buffer = session()
        val replies = buffer.replies()

        buffer.feed("${ESC}[1m${ESC}[>4;2m")

        assertThat(replies).isEmpty()
        assertThat(buffer.keyboardModes().modifyOtherKeys).isEqualTo(ModifyOtherKeys.ALL)
    }

    @Test
    fun `the alternate screen does not disturb modifyOtherKeys`() {
        // Kitty's flags are per screen because the protocol says so; xterm's is a terminal-wide
        // resource, and a full-screen program expects it to be exactly where the shell left it.
        val buffer = session()
        buffer.feed("${ESC}[>4;2m")

        buffer.feed("${ESC}[?1049h${ESC}[?1049l")

        assertThat(buffer.keyboardModes().modifyOtherKeys).isEqualTo(ModifyOtherKeys.ALL)
    }

    // endregion

    // region reset

    @Test
    fun `a soft reset clears the keyboard flags`() {
        val buffer = session()
        buffer.feed("${ESC}[=1u")

        buffer.feed("${ESC}[!p")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    @Test
    fun `a soft reset clears modifyOtherKeys`() {
        val buffer = session()
        buffer.feed("${ESC}[>4;2m")

        buffer.feed("${ESC}[!p")

        assertThat(buffer.keyboardModes().modifyOtherKeys).isEqualTo(ModifyOtherKeys.OFF)
    }

    @Test
    fun `a soft reset empties the stack as well as the flags`() {
        // Two pushes, for a reason: one stale entry is invisible, because popping it empties the stack
        // and an emptied stack resets every flag anyway. The flags are set outside the stack so each
        // push saves a flag the reset would otherwise leave for the next pop to restore.
        val buffer = session()
        buffer.feed("${ESC}[=1u${ESC}[>0u${ESC}[=1u${ESC}[>0u")

        buffer.feed("${ESC}[!p${ESC}[<u")

        assertThat(buffer.keyboardModes().kittyFlags).isEqualTo(0)
    }

    // endregion

    @Test
    fun `the frame carries the modes the keys are encoded with`() {
        val buffer = session()

        buffer.feed("${ESC}[>5u${ESC}[>4;2m")

        val modes = buffer.frame().keyboardModes
        assertThat(modes.kittyFlags).isEqualTo(KittyKeyboard.DISAMBIGUATE)
        assertThat(modes.modifyOtherKeys).isEqualTo(ModifyOtherKeys.ALL)
    }

    @Test
    fun `Claude Code's own startup and its control keys`() {
        // Measured from the bundle: `<u` (pop), `>5u` (push), `>4;2m` (modifyOtherKeys 2), then `?u`
        // on the first input. The reply has to be the one flag this terminal keeps, and the keys sent
        // afterwards have to be the two forms the program's own parsers accept.
        val buffer = session()
        val replies = buffer.replies()

        buffer.feed("${ESC}[<u${ESC}[>5u${ESC}[>4;2m${ESC}[?u")

        assertThat(replies).containsExactly("${ESC}[?1u")
        val modes = buffer.keyboardModes()
        assertThat(TerminalKeys.encode('c', TerminalModifiers(ctrl = true), modes = modes).text())
            .isEqualTo("${ESC}[99;5u")
        assertThat(TerminalKeys.encode(TerminalKey.TAB, TerminalModifiers(shift = true), modes = modes).text())
            .isEqualTo("${ESC}[27;2;9~")
        assertThat(TerminalKeys.encode(TerminalKey.ENTER, modes = modes).text()).isEqualTo("\r")
    }

    private fun session(columns: Int = 80, rows: Int = 24): AnsiTerminalBuffer =
        AnsiTerminalBuffer(columns = columns, rows = rows, scrollbackLimit = 200)

    private fun AnsiTerminalBuffer.replies(): MutableList<String> =
        mutableListOf<String>().also { list -> responder = { list += it } }

    private fun ByteArray.text(): String = String(this, Charsets.US_ASCII)

    private companion object {
        const val ESC = "\u001B"
    }
}
