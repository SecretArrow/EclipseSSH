package dev.eclipse.ssh.ui.terminal.termux

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient
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
    private val onScale: (Float) -> Float,
    private val onCopyModeChanged: (Boolean) -> Unit,
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
     */
    override fun shouldBackButtonBeMappedToEscape(): Boolean {
        val emulator = viewRef.get()?.mTermSession?.emulator ?: return false
        return emulator.isAlternateBufferActive
    }

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
     * Hardware-key down. Returning false tells Termux "I didn't handle it, you continue with the
     * default translation" — which is what we want, since the default translation handles every
     * key EclipseSSH supports (arrows, function keys, ctrl+letter, etc.). The session parameter is
     * available for cases where a key's meaning depends on the active emulator mode (e.g. the
     * cursor-keys-application flag); the default translation already consults that, so we don't
     * need to here.
     */
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

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
     * A code point typed by the IME. Returning false hands it back to Termux's default handling,
     * which writes the code point to the session (with ctrl if [readControlKey] is true). We do
     * not need to intercept because the default path writes via [TerminalSession.writeCodePoint],
     * which our [EclipseSshTerminalSession.write] override forwards to the SSH channel.
     */
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

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
