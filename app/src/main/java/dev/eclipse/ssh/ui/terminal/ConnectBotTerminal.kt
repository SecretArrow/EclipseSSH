/*
 * The terminal surface: ConnectBot's termlib Compose Terminal(), adapted to this
 * app's screen.
 *
 * This is deliberately a thin file. The library owns rendering, keyboard handling,
 * IME, selection, scrollback, hyperlinks and the visual half of pinch-to-zoom;
 * what belongs here is only what the app decides: which emulator is on screen, the
 * theme's colours, the font the app ships, the on-screen key row's sticky
 * modifiers, and when resizes are suspended. Everything else is the library's
 * public Terminal() API, used as shipped — the same patterns the library's own
 * test-app and the ConnectBot app follow.
 *
 * Font size is the app's, not the library's: termlib 0.3.15's pinch gesture is a
 * transient graphics-layer scale that resets when the fingers lift (Terminal.kt:
 * zoomScale reset to 1f on gesture end), and the ConnectBot app persists its own
 * font size by feeding it back through `initialFontSize`. This app already has
 * that plumbing — the persisted terminalFontSize setting, the settings screen and
 * the live pinch overlay in TerminalScreen — so the app keeps owning it and passes
 * it in here.
 */
package dev.eclipse.ssh.ui.terminal

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.presentation.TerminalSessionController
import dev.eclipse.ssh.terminal.TerminalKey
import org.connectbot.terminal.DelKeyMode
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.RightAltMode
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey

/**
 * The app's key-bar key ids as the library's VTermKey codes — one exact match each,
 * so a cap press goes through the emulator's own key pipeline and comes out as the
 * same bytes the same key on a hardware keyboard would have produced.
 */
fun TerminalKey.toVTermKey(): Int =
    when (this) {
        TerminalKey.ENTER -> VTermKey.ENTER
        TerminalKey.BACKSPACE -> VTermKey.BACKSPACE
        TerminalKey.TAB -> VTermKey.TAB
        TerminalKey.ESCAPE -> VTermKey.ESCAPE
        TerminalKey.ARROW_UP -> VTermKey.UP
        TerminalKey.ARROW_DOWN -> VTermKey.DOWN
        TerminalKey.ARROW_RIGHT -> VTermKey.RIGHT
        TerminalKey.ARROW_LEFT -> VTermKey.LEFT
        TerminalKey.HOME -> VTermKey.HOME
        TerminalKey.END -> VTermKey.END
        TerminalKey.PAGE_UP -> VTermKey.PAGEUP
        TerminalKey.PAGE_DOWN -> VTermKey.PAGEDOWN
        TerminalKey.INSERT -> VTermKey.INS
        TerminalKey.DELETE -> VTermKey.DEL
        TerminalKey.F1 -> VTermKey.FUNCTION_1
        TerminalKey.F2 -> VTermKey.FUNCTION_2
        TerminalKey.F3 -> VTermKey.FUNCTION_3
        TerminalKey.F4 -> VTermKey.FUNCTION_4
        TerminalKey.F5 -> VTermKey.FUNCTION_5
        TerminalKey.F6 -> VTermKey.FUNCTION_6
        TerminalKey.F7 -> VTermKey.FUNCTION_7
        TerminalKey.F8 -> VTermKey.FUNCTION_8
        TerminalKey.F9 -> VTermKey.FUNCTION_9
        TerminalKey.F10 -> VTermKey.FUNCTION_10
        TerminalKey.F11 -> VTermKey.FUNCTION_11
        TerminalKey.F12 -> VTermKey.FUNCTION_12
    }

/**
 * The modifier mask the library's dispatchKey expects: bit 0 Shift, bit 1 Alt,
 * bit 2 Ctrl — the libvterm order, spelled out here so a caller building a mask
 * from three booleans does not have to know it.
 */
fun vTermModifiers(
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean = false,
): Int = (if (ctrl) 4 else 0) or (if (alt) 2 else 0) or (if (shift) 1 else 0)

/**
 * The sticky Ctrl/Alt/Shift the on-screen key row latches, as the library's
 * ModifierManager: the Terminal() composable reads it on every key it dispatches,
 * so a latched Ctrl turns the next typed 'c' into Ctrl+C — the same
 * sticky-until-used contract the app's key row has always had.
 *
 * Wraps the app's existing [TerminalLatches] rather than replacing them, so the
 * key row's own toggle UI keeps working unchanged and the armed state still
 * survives rotation through the app's saver.
 */
class LatchModifierManager(
    private val latches: TerminalLatches,
) : ModifierManager {
    override fun isCtrlActive(): Boolean = latches.ctrl

    override fun isAltActive(): Boolean = latches.alt

    override fun isShiftActive(): Boolean = latches.shift

    /** Called by the library after a key consumed the armed modifiers. */
    override fun clearTransients() {
        latches.consume()
    }
}

/**
 * The android.graphics.Typeface the termlib renderer draws with: the app's bundled
 * JetBrains Mono, with the platform's fallback chain behind it for the scripts the
 * bundle does not cover (CJK, Arabic, and the rest a terminal can print).
 *
 * termlib takes a platform Typeface rather than a Compose FontFamily, and the
 * platform's own family building is what gives the fallback: `CustomFallbackBuilder`
 * (API 29+) chains the bundled face ahead of the system default. On API 28 the
 * builder does not exist, so the plain bundled face is used and the platform's
 * per-glyph system fallback still answers for anything the bundle lacks.
 *
 * Built once per screen and remembered by the caller; a Typeface is a shared
 * native object with no per-composition cost.
 */
internal fun terminalTypeface(context: Context): Typeface =
    // The bundled JetBrains Mono, with the platform's per-glyph system fallback answering for
    // any glyph the bundle lacks (CJK, Arabic, and the rest a terminal can print). resources.getFont
    // returns a Typeface the renderer paints with directly, and the platform's own fallback chain
    // covers what the bundle does not — the same path every Android app's text rendering takes.
    context.resources.getFont(dev.eclipse.ssh.R.font.jetbrains_mono_regular)

/**
 * Draws one session's terminal through the ConnectBot termlib renderer.
 *
 * @param controller the session's terminal owner — the emulator inside it is what
 *   the library renders, and the session outlives this composition (tab switches
 *   keep it), so nothing here closes it.
 * @param background the theme's terminal background colour.
 * @param foreground the theme's terminal foreground colour.
 * @param fontSizeSp the app's persisted font size in sp, from the settings and the
 *   pinch overlay the TerminalScreen owns.
 * @param latches the key row's sticky modifiers, bridged to the library.
 * @param onPasteRequest called when the library wants the clipboard — the app's
 *   audited SecureClipboard boundary, wired by the caller.
 * @param onHyperlinkClick called with a URL the remote side drew (OSC 8 or
 *   auto-detected); the app confirms before anything leaves it.
 * @param keyboardEnabled whether keystrokes go to the shell.
 * @param showSoftKeyboard whether the IME should be up; false while a modal owns
 *   the keyboard.
 * @param resizeSuspended true while a layout change is temporary (menu open, IME
 *   animating): the library holds its grid rather than resizing under it.
 * @param onImeVisibilityChanged the IME's answer, for the screen's focus logic.
 */
@Composable
fun ConnectBotTerminal(
    controller: TerminalSessionController,
    background: Color,
    foreground: Color,
    fontSizeSp: Int,
    latches: TerminalLatches,
    onPasteRequest: () -> Unit,
    onHyperlinkClick: (String) -> Unit,
    keyboardEnabled: Boolean = true,
    showSoftKeyboard: Boolean = true,
    modifier: Modifier = Modifier,
    resizeSuspended: Boolean = false,
    onImeVisibilityChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    // The app's own monospace font (JetBrains Mono bundle) with the system's
    // fallbacks behind it for scripts the bundle does not cover — the same family
    // the legacy renderer measured, so a session looks the same before and after
    // the migration.
    val typeface = remember { terminalTypeface(context) }
    val modifierManager = remember(latches) { LatchModifierManager(latches) }
    val fontSize =
        fontSizeSp.coerceIn(
            SettingsRepository.TERMINAL_FONT_SIZE_RANGE.first,
            SettingsRepository.TERMINAL_FONT_SIZE_RANGE.last,
        )

    Terminal(
        terminalEmulator = controller.emulator,
        modifier = modifier.fillMaxSize(),
        typeface = typeface,
        initialFontSize = fontSize.sp,
        backgroundColor = background,
        foregroundColor = foreground,
        keyboardEnabled = keyboardEnabled,
        showSoftKeyboard = showSoftKeyboard,
        modifierManager = modifierManager,
        rightAltMode = RightAltMode.CharacterModifier,
        delKeyMode = DelKeyMode.Delete,
        onHyperlinkClick = onHyperlinkClick,
        onPasteRequest = onPasteRequest,
        onImeVisibilityChanged = onImeVisibilityChanged,
        resizeSuspended = resizeSuspended,
    )
}
