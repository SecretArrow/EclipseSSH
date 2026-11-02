package dev.eclipse.ssh.ui.terminal.termux

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient
import dev.eclipse.ssh.terminal.TerminalKey
import dev.eclipse.ssh.terminal.TerminalKeys
import dev.eclipse.ssh.terminal.TerminalModifiers
import java.lang.ref.WeakReference

/**
 * The view-side counterpart to [EclipseSshTerminalSessionClient]: routes touch, key, IME, and
 * gesture events from Termux's [com.termux.view.TerminalView] into EclipseSSH.
 *
 * Termux's design assumes there is *one* terminal session per Activity and the view is the
 * top-level content view. EclipseSSH has neither: the view lives inside a Compose `AndroidView`,
 * multiple tabs each have their own session, and the IME is shown/hidden in response to taps
 * rather than permanently. This implementation adapts Termux's callbacks to those constraints.
 *
 * All of the "should*" methods return EclipseSSH's defaults, which match what Termux itself ships
 * (escape-on-back, char-based input, no ctrl-space workaround) so existing muscle memory and tmux
 * config files carry over unchanged.
 *
 * Modifier keys (ctrl, alt, shift, fn) are read through suppliers because they are bound to the
 * physical keys the user is currently holding — supplied as the on-screen key bar's state, not as
 * anything Termux can read directly. The key bar is the row of modifier buttons below the
 * terminal in the legacy UI; it is wired in the same place the session is.
 *
 * Logging matches [EclipseSshTerminalSessionClient]: forwarded to [android.util.Log] rather than
 * EclipseSSH's logger, because these are Termux-engine diagnostics and the developer greps for
 * the Termux tags.
 */
class EclipseSshTerminalViewClient(
    private val viewRef: WeakReference<com.termux.view.TerminalView>,
    private val inputMethodManager: () -> InputMethodManager?,
    private val isCtrlDown: () -> Boolean,
    private val isAltDown: () -> Boolean,
    private val isShiftDown: () -> Boolean,
    private val isFnDown: () -> Boolean,
    private val onSingleTapUp: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onScale: (Float) -> Unit,
    private val onCopyModeChanged: (Boolean) -> Unit,
    private val sessionProvider: () -> EclipseSshTerminalSession?,
) : TerminalViewClient {

    /**
     * Pinch-to-zoom on the terminal text. Termux drives the [com.termux.view.TerminalView.setTextSize]
     * directly from this; here we hand the factor back to the Compose side so the same setting the
     * legacy view persists (DataStore-backed `terminal_font_size`) gets updated.
     *
     * Returning `scale` unchanged tells the View to apply the factor itself; we return `1f` so the
     * View does *not* re-apply it (we have already consumed the gesture on the Compose side).
     */
    override fun onScale(scale: Float): Float {
        onScale(scale)
        return 1f
    }

    /**
     * A single tap with no mouse reporting active. Termux uses this to show the IME; EclipseSSH
     * also uses it to dismiss an active selection (mirrors the legacy view's tap-to-deselect).
     */
    override fun onSingleTapUp(e: MotionEvent) {
        onSingleTapUp()
    }

    /**
     * Map the system back button to Escape when a full-screen program is running (the same
     * "alternate screen + application cursor keys" condition Termux itself checks). With no
     * alternate screen, returning false lets the Activity's back handler run and leave the session.
     *
     * Reads the cursor state from our own [EclipseSshTerminalSession] rather than
     * `view.mTermSession.getEmulator()` because we never attach a real [TerminalSession] to the
     * View (it is `final` and would fork a local shell — see [EclipseSshTerminalSession]'s kdoc),
     * so the View's `mTermSession` is the no-op [EclipseSshTerminalSession.dummySession], whose
     * own `mEmulator` is `null`.
     */
    override fun shouldBackButtonBeMappedToEscape(): Boolean = sessionProvider()?.let { session ->
        session.emulator?.isAlternateBufferActive ?: false
    } ?: false

    /**
     * Char-based input rather than per-key. Termux itself returns false on phones and true on
     * hardware-keyboard devices; EclipseSSH does not distinguish, so phones get the IME-driven
     * path (which is the only path that gives correct key-composition for non-ASCII scripts).
     */
    override fun shouldEnforceCharBasedInput(): Boolean = false

    /**
     * Some IMEs send Ctrl+Space as a 0-byte event that should toggle IME. EclipseSSH does not
     * implement this toggle; returning false means the View passes the keystroke through to the
     * emulator, where it produces ^@ (NUL) — the same as a real terminal.
     */
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    /**
     * Whether this View is the active input target. Always true here because in Compose, the
     * AndroidView's TerminalView is the only thing in the tree that takes input; the activity-level
     * `hasFocus` check Termux's own implementation makes would return true the moment any sibling
     * Compose node has focus, which is not what the View expects.
     */
    override fun isTerminalViewSelected(): Boolean = true

    /**
     * Selection / scrollback pan mode toggled. The legacy view shows a "copy mode" hint; we
     * forward so the Compose side can show the same hint.
     */
    override fun copyModeChanged(copyMode: Boolean) {
        onCopyModeChanged(copyMode)
    }

    /**
     * Hardware-key down.
     *
     * We translate the key to its terminal byte sequence ourselves via [TerminalKeys.encode] — the
     * same table the legacy renderer uses — and write it through [EclipseSshTerminalSession.write],
     * which forwards to the SSH channel. We must NOT defer to Termux's own `mTermSession.write`,
     * for two reasons:
     *
     *  1. `TerminalSession.write` is guarded by `if (mShellPid > 0)` and drops bytes when no local
     *     shell has been forked — and our [dummySession] never forks one (see
     *     [EclipseSshTerminalSession]'s kdoc), so the write would be a silent no-op.
     *  2. If we returned `false` for any key Termux's [TerminalView.handleKeyCode] also handles,
     *     that path would dereference `mTermSession` (which is the dummy session whose own
     *     `mEmulator` is `null`) and NPE on `mTermSession.getEmulator().isCursorKeysApplicationMode()`.
     *     Returning `true` for every key we recognise keeps Termux out of that path.
     *
     * For keys with no terminal meaning (volume, back when not on the alternate screen) we return
     * `false` so the View passes them up to the Activity — those keys have no path through
     * `handleKeyCode`, so the NPE risk does not apply to them.
     */
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        val ssh = sessionProvider() ?: return false
        val terminalKey = keyCodeToTerminalKey(keyCode) ?: return false
        val bytes = TerminalKeys.encode(
            key = terminalKey,
            modifiers = TerminalModifiers(
                ctrl = e.isCtrlPressed || isCtrlDown(),
                alt = e.isAltPressed || isAltDown(),
                shift = e.isShiftPressed,
            ),
            applicationCursorKeys = ssh.isCursorKeysApplicationMode(),
        )
        ssh.write(bytes)
        return true
    }

    /**
     * Hardware-key up. Same as [onKeyDown]: defer to Termux's default, which is a no-op for keys
     * it does not track and a clean release for keys it does.
     */
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    /**
     * Long press. Termux shows its own selection toolbar on long press; we suppress that here
     * (returning true consumes the gesture) and let the Compose side show its own, which already
     * handles copy/paste against the SSH-layer clipboard rather than Termux's per-session one.
     */
    override fun onLongPress(event: MotionEvent): Boolean {
        onLongPress()
        return true
    }

    override fun readControlKey(): Boolean = isCtrlDown()

    override fun readAltKey(): Boolean = isAltDown()

    override fun readShiftKey(): Boolean = isShiftDown()

    override fun readFnKey(): Boolean = isFnDown()

    /**
     * A code point typed by the IME (a printable character, or one the soft keyboard produces
     * directly — the arrow keys and other non-printables arrive via [onKeyDown] instead).
     *
     * We encode and write the byte ourselves rather than deferring to Termux's default path, which
     * would call `session.writeCodePoint` — a no-op against our [dummySession] (see
     * [EclipseSshTerminalSession]'s kdoc).
     *
     * The modifier translation mirrors what real terminals do: Ctrl with an alphabetic code point
     * produces the corresponding C0 control (Ctrl+A → 0x01, …, Ctrl+Z → 0x1A); Alt prefixes the
     * encoded bytes with ESC, the long-standing xterm convention that lets shells bind Alt-key
     * chords as `M-` sequences. Shift is intentionally not applied to printable code points — by
     * the time a character reaches here the keyboard has already produced the shifted one, and
     * re-applying it would send `A` as `Ctrl-Shift` plus `A` rather than as itself.
     */
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        val ssh = sessionProvider() ?: return false
        val alt = isAltDown()
        // Ctrl+A..Z → 0x01..0x1A. Ctrl with anything else is left to the encoded form; the IME has
        // already produced the code point it means, and Termux's own default does the same.
        val effective = if (ctrlDown && codePoint in 'A'.code..'Z'.code) codePoint - 'A'.code + 1 else codePoint
        val bytes = if (effective < 0x80) {
            // Single-byte: avoid the UTF-8 encoder for ASCII so the Alt-ESC prefix can sit in front
            // of the byte rather than being swallowed into a multibyte sequence.
            byteArrayOf(effective.toByte())
        } else {
            // Printable non-ASCII (an accented letter, a CJK character): UTF-8 encode, the only
            // encoding a pty in UTF-8 mode understands.
            String(Character.toChars(effective)).toByteArray(Charsets.UTF_8)
        }
        val withAlt = if (alt) byteArrayOf(0x1B.toByte()) + bytes else bytes
        ssh.write(withAlt)
        return true
    }

    /**
     * The View finished attaching to its emulator. EclipseSSH does not need to do anything here;
     * the cursor blinker is started by the View itself, and color/font are set in [TermuxTerminalView].
     */
    override fun onEmulatorSet() {
        // No-op — the View's own setup is sufficient.
    }

    override fun logError(tag: String, message: String) {
        android.util.Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        android.util.Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        android.util.Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        android.util.Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        android.util.Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        android.util.Log.e(tag, e.message, e)
    }
}

/**
 * Map a hardware [KeyEvent.KEYCODE_*] to the [TerminalKey] the encoder expects.
 *
 * Returns `null` for keys with no terminal meaning — volume, back (handled separately via
 * [shouldBackButtonBeMappedToEscape]), menu, the soft keys — so [onKeyDown] returns `false` and
 * the View passes them up to the Activity. Every key that returns non-null is one the View's own
 * `handleKeyCode` would also handle, and since that path dereferences `mTermSession` (which for us
 * is the inert [dummySession]) returning `true` for it is what keeps Termux out of the NPE risk
 * described in [onKeyDown].
 *
 * Comprehensive on purpose: the legacy renderer handles this same set, and parity is what makes the
 * Termux renderer a drop-in rather than a regression for users who switch.
 */
private fun keyCodeToTerminalKey(keyCode: Int): TerminalKey? = when (keyCode) {
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKey.ENTER
    KeyEvent.KEYCODE_TAB -> TerminalKey.TAB
    KeyEvent.KEYCODE_DEL -> TerminalKey.BACKSPACE
    KeyEvent.KEYCODE_ESCAPE -> TerminalKey.ESCAPE
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_NUMPAD_8 -> TerminalKey.ARROW_UP
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_NUMPAD_2 -> TerminalKey.ARROW_DOWN
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_NUMPAD_4 -> TerminalKey.ARROW_LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_NUMPAD_6 -> TerminalKey.ARROW_RIGHT
    KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_NUMPAD_7 -> TerminalKey.HOME
    KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_NUMPAD_1 -> TerminalKey.END
    KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_NUMPAD_9 -> TerminalKey.PAGE_UP
    KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_NUMPAD_3 -> TerminalKey.PAGE_DOWN
    KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_NUMPAD_0 -> TerminalKey.INSERT
    KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKey.DELETE
    KeyEvent.KEYCODE_F1 -> TerminalKey.F1
    KeyEvent.KEYCODE_F2 -> TerminalKey.F2
    KeyEvent.KEYCODE_F3 -> TerminalKey.F3
    KeyEvent.KEYCODE_F4 -> TerminalKey.F4
    KeyEvent.KEYCODE_F5 -> TerminalKey.F5
    KeyEvent.KEYCODE_F6 -> TerminalKey.F6
    KeyEvent.KEYCODE_F7 -> TerminalKey.F7
    KeyEvent.KEYCODE_F8 -> TerminalKey.F8
    KeyEvent.KEYCODE_F9 -> TerminalKey.F9
    KeyEvent.KEYCODE_F10 -> TerminalKey.F10
    KeyEvent.KEYCODE_F11 -> TerminalKey.F11
    KeyEvent.KEYCODE_F12 -> TerminalKey.F12
    else -> null
}
