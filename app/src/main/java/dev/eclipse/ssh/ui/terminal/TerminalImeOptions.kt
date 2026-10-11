package dev.eclipse.ssh.ui.terminal

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * What the terminal's invisible field tells the keyboard about the text being typed into it.
 *
 * [KeyboardType.PasswordVisible] is the load-bearing part, and it is the *type* that does the work
 * rather than `autoCorrectEnabled`: Compose 1.12.1's `EditorInfo` mapper only ever *adds*
 * `TYPE_TEXT_FLAG_AUTO_CORRECT` when auto-correct is asked for, has no branch that sets
 * `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, and its own KDoc says most IMEs ignore the flag for
 * [KeyboardType.Text] and [KeyboardType.Ascii] - which is the suggestion strip this app was still
 * showing over the grid. `PasswordVisible` maps to
 * `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`, and Android's input-method attributes
 * count both password variations as a password field: that is what switches dictionary candidates,
 * corrections and personalised learning off for real commands, on every IME that reaches the flags
 * at all.
 *
 * What it costs: `KeyboardType.Ascii`'s one real effect, `IME_FLAG_FORCE_ASCII`, goes with it, so a
 * keyboard may offer non-ASCII candidates. The terminal takes UTF-8 on every other path, and a
 * candidate the user does not pick is never sent - the field is a diffed sink, not the line.
 */
internal val TERMINAL_KEYBOARD_OPTIONS = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.PasswordVisible,
    imeAction = ImeAction.None,
)
