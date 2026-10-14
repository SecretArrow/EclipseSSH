package dev.eclipse.ssh.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ESC, spelled out so the sequences below stay readable and copy-pasteable. */
private const val E = "\u001B"

/**
 * The control strings that carry no display: DCS, SOS, PM and APC.
 *
 * These are swallowed as a unit, and the rule for what ends one is the whole subject of this file. A
 * control string ends at ST - `ESC \` - and at nothing else. An ESC inside the payload that is not
 * followed by a backslash is *data*, and a parser that treats it as the end prints the rest of the
 * payload as text and moves this buffer's cursor past where the far end believes it is.
 *
 * That is not a theoretical failure. tmux frames a passthrough as `ESC P tmux; <payload>` in which
 * **every ESC is doubled**, so a client that ends the string at the first ESC prints the payload - and
 * once this buffer's cursor disagrees with the far end's, the far end never sends those cells again,
 * which is what makes the damage permanent for the session rather than momentary.
 *
 * The expected values here are not derived: they were measured against tmux 3.5a as the reference
 * emulator, by running `cat <bytes>` in a pane and reading `capture-pane -p`. See
 * `testing/tmux-replay/README.md`.
 */
class TerminalControlStringTest {

    // region the payload is swallowed, and only ST ends it

    @Test
    fun `a tmux passthrough is swallowed whole rather than printed`() {
        val buffer = session()

        buffer.feed("$E" + "Ptmux;$E${E}[?25l$E${E}[>4;2m$E\\ready")

        assertThat(buffer.plainText()).isEqualTo("ready")
    }

    @Test
    fun `the payload of a passthrough is discarded rather than executed`() {
        // The doubling is the point: those ESCs are data. A parser that unwraps them instead runs the
        // program's private business - hiding this terminal's cursor, turning on modifyOtherKeys -
        // against a terminal that was only ever asked to ignore a DCS.
        val buffer = session()

        buffer.feed("$E" + "Ptmux;$E${E}[?25l$E${E}[>4;2m$E\\")

        assertThat(buffer.frame().cursorVisible).isTrue()
        assertThat(buffer.keyboardModes().modifyOtherKeys).isEqualTo(ModifyOtherKeys.OFF)
    }

    @Test
    fun `an esc inside a dcs does not end it`() {
        val buffer = session()

        buffer.feed("$E" + "Pada${E}b$E\\ready")

        assertThat(buffer.plainText()).isEqualTo("ready")
    }

    @Test
    fun `an apc payload is swallowed whole`() {
        val buffer = session()

        buffer.feed("$E" + "_hidden${E}x$E\\ready")

        assertThat(buffer.plainText()).isEqualTo("ready")
    }

    @Test
    fun `a control string still ends at st, and the bytes before it are gone`() {
        val buffer = session()

        buffer.feed("$E" + "Psomething-else$E\\ready")

        assertThat(buffer.plainText()).isEqualTo("ready")
        assertThat(buffer.frame().cursorColumn).isEqualTo(5)
    }

    @Test
    fun `a bel still ends a control string`() {
        // BEL is not ST, and it ends the string here as it does everywhere in practice: a program that
        // terminated a DCS with a bell is malformed, and swallowing the rest of the session is a worse
        // answer than accepting it.
        val buffer = session()

        buffer.feed("$E" + "Pnoise\u0007ready")

        assertThat(buffer.plainText()).isEqualTo("ready")
    }

    // endregion

    // region the bounds

    @Test(timeout = 10_000)
    fun `a dcs that never terminates is abandoned and printing resumes`() {
        // The one exit that keeps a corrupt stream from swallowing the session: past the cap the run is
        // given up and whatever follows is treated as the text it turns out to be.
        val buffer = session()

        buffer.feed("$E" + "P" + "x".repeat(5_000) + "tail")

        assertThat(buffer.plainText()).contains("tail")
    }

    @Test(timeout = 10_000)
    fun `an st split across two reads still ends the control string`() {
        // A chunk boundary lands between the ESC and the backslash all the time on a real connection,
        // and the ESC-pending decision has to survive it - the state that says "an ST may be starting"
        // is one character wide, and it is held across reads like any other.
        val buffer = session()

        buffer.feed("$E" + "Ppayload$E")
        buffer.feed("\\ready")

        assertThat(buffer.plainText()).isEqualTo("ready")
    }

    @Test(timeout = 10_000)
    fun `an unterminated dcs past the cap gives up and prints what follows`() {
        val buffer = session()

        buffer.feed("$E" + "P" + "z".repeat(5_000))
        buffer.feed("tail")

        assertThat(buffer.plainText()).contains("tail")
    }

    // endregion

    // region osc, which is not the same rule

    @Test
    fun `an esc ends an osc, which is what tmux does with one`() {
        // Measured, not assumed: tmux prints `leready` for `<ESC>]0;ti<ESC>tle<ESC>\ready` - the payload
        // up to the ESC is taken as the title, the `t` is eaten as the final byte of an unknown escape,
        // and the rest prints. This terminal does the same, so the two agree on a corrupted stream.
        val buffer = session()

        buffer.feed("$E]0;ti${E}tle$E\\ready")

        assertThat(buffer.plainText()).isEqualTo("leready")
    }

    // endregion

    private fun session(columns: Int = 20, rows: Int = 3): AnsiTerminalBuffer =
        AnsiTerminalBuffer(columns = columns, rows = rows, scrollbackLimit = 200)
}
