package dev.eclipse.ssh.ui.terminal

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the terminal asks the keyboard for.
 *
 * This is the one part of the IME fix that can be held still off a device: the strips that were
 * appearing over the grid are the keyboard's answer to the field's `EditorInfo`, and what this app
 * controls is the `KeyboardOptions` that becomes it. The mapping from a [KeyboardType] to those flags
 * is Compose's and is asserted nowhere here - what is asserted is that the field asks for the type
 * whose flags are the ones that turn suggestions off, and that nothing quietly goes back to
 * `autoCorrectEnabled` alone, which does not.
 */
class TerminalImeOptionsTest {

    @Test
    fun `the terminal asks for the type whose flags suppress suggestions`() {
        assertThat(TERMINAL_KEYBOARD_OPTIONS.keyboardType).isEqualTo(KeyboardType.PasswordVisible)
    }

    @Test
    fun `commands are not capitalised or corrected`() {
        assertThat(TERMINAL_KEYBOARD_OPTIONS.autoCorrectEnabled).isFalse()
        assertThat(TERMINAL_KEYBOARD_OPTIONS.capitalization).isEqualTo(KeyboardCapitalization.None)
    }

    @Test
    fun `the enter key sends a key rather than an IME action`() {
        assertThat(TERMINAL_KEYBOARD_OPTIONS.imeAction).isEqualTo(ImeAction.None)
    }
}
