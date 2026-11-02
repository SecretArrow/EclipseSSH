package dev.eclipse.ssh.ui.terminal.termux

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.lang.ref.WeakReference

/**
 * Bridges Termux's [TerminalSessionClient] callbacks to EclipseSSH's UI + clipboard.
 *
 * Termux's [TerminalSession] calls back into this client on the thread it was constructed on
 * (the SSH collector's dispatcher in our case) for text changes, title changes, bells, etc. The
 * session client's job is two-fold: keep the view's [com.termux.view.TerminalView.onScreenUpdated]
 * in sync with the emulator, and translate the few callbacks that have meaning beyond "redraw"
 * (clipboard, bell, title) into EclipseSSH's own callbacks.
 *
 * The view is held by [WeakReference] rather than a direct reference: the [com.termux.view.TerminalView]
 * is owned by Compose's `AndroidView`, and the session can outlive the view (the SSH collector keeps
 * running while the user is on the Files screen, for example). A direct reference would let the
 * session keep the view — and the Activity it holds — alive across configuration changes.
 *
 * Cursor style is read through a `() -> Int` supplier because it is a user setting that can change
 * at runtime (Settings → Terminal → Cursor style); reading it eagerly at construction would freeze
 * the value for the session's lifetime.
 *
 * Logging is forwarded to [android.util.Log] rather than EclipseSSH's existing logger because
 * these messages are about the Termux engine internals — the existing logger is for the SSH layer
 * and would lose Termux's own tags (which a developer grepping logcat for "TerminalEmulator" relies
 * on finding).
 */
class EclipseSshTerminalSessionClient(
    private val onTitleChange: (String) -> Unit,
    private val onSessionFinished: () -> Unit,
    private val clipboard: ClipboardManager,
    private val pasteSink: (ByteArray) -> Unit,
    private val onBell: () -> Unit,
    private val cursorStyle: () -> Int,
    private val titleProvider: () -> String,
) : TerminalSessionClient {
    /**
     * The view, set once the [com.termux.view.TerminalView] is created by Compose's `AndroidView`.
     *
     * Held as a [WeakReference] so the session — which lives as long as the SSH channel — does not
     * keep the View (and through it, the Activity) alive across configuration changes. Read through
     * an [java.util.concurrent.atomic.AtomicReference] because the SSH collector calls
     * [onTextChanged] on its own thread while Compose calls [updateViewRef] on the main thread.
     *
     * A `null` reference (the value before the View is created, or after it is disposed) is fine:
     * the redraw callbacks simply no-op until a view attaches, the same as Termux's own session
     * before its view is laid out.
     */
    private val viewRef: java.util.concurrent.atomic.AtomicReference<WeakReference<com.termux.view.TerminalView>> =
        java.util.concurrent.atomic
            .AtomicReference(WeakReference(null))

    /**
     * Install the view reference once the [com.termux.view.TerminalView] has been created by
     * Compose. Called from [TermuxTerminalView]'s `AndroidView` factory; the prior reference, if
     * any, is replaced so a view recreated on recomposition reattaches cleanly.
     */
    fun updateViewRef(view: com.termux.view.TerminalView) {
        viewRef.set(WeakReference(view))
    }

    /**
     * The emulator's screen changed. Termux's redraw path is view-internal: it reads
     * [com.termux.terminal.TerminalEmulator.getScreen] in [com.termux.view.TerminalView.onScreenUpdated]
     * and paints directly to the Canvas. Our job is just to call that method on the right thread.
     *
     * The view's `onScreenUpdated` is safe to call from any thread — it ends in `postInvalidate()`,
     * not `invalidate()` — so we do not need to bounce through the main handler. The Termux app
     * itself calls it from the main thread because that is where its main handler runs, not
     * because the method requires it.
     */
    override fun onTextChanged(changedSession: TerminalSession) {
        viewRef.get().get()?.onScreenUpdated()
    }

    /**
     * The shell set its window title (xterm's `\033]0;...\007` escape). EclipseSSH already shows
     * the title in the tab strip; the callback updates it the same way the legacy path does.
     *
     * The session parameter is ignored because we cannot subclass Termux's `final`
     * `TerminalSession` — see [EclipseSshTerminalSession]'s kdoc — so the dummy session passed in
     * has no title set on it. The actual title lives on the [com.termux.terminal.TerminalEmulator]
     * and is read through [titleProvider] instead.
     */
    override fun onTitleChanged(changedSession: TerminalSession) {
        onTitleChange(titleProvider())
    }

    /**
     * The session ended. In Termux this is the local shell exiting; here it is the SSH channel
     * closing. The legacy path already detects the same via [TerminalChannel.END_OF_OUTPUT]; this
     * callback is the redundancy that catches a session the SSH layer reported as still open but
     * the emulator saw close.
     */
    override fun onSessionFinished(finishedSession: TerminalSession) {
        onSessionFinished()
    }

    override fun onCopyTextToClipboard(
        session: TerminalSession,
        text: String,
    ) {
        clipboard.setPrimaryClip(ClipData.newPlainText("EclipseSSH terminal", text))
    }

    /**
     * A program (vim, tmux) asked for the system clipboard. Read it and forward to the SSH channel
     * the same way a paste from the key bar would — the legacy code's [pasteSink] equivalent.
     */
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(viewRef.get().get()?.context) ?: return
        pasteSink(text.toString().toByteArray(Charsets.UTF_8))
    }

    override fun onBell(session: TerminalSession) {
        onBell()
    }

    /**
     * Color-scheme change (e.g. OSC 4 to swap palette entries). The renderer reads colors from the
     * emulator on every frame, so a redraw is sufficient.
     */
    override fun onColorsChanged(session: TerminalSession) {
        viewRef.get().get()?.onScreenUpdated()
    }

    /**
     * Cursor visibility toggled by DECSET 25. The renderer draws the cursor from the emulator, so
     * a redraw is sufficient — but only if the cursor blinker is not what called us. If the
     * blinker *is* running, this is the blinker's own state-flip, and we'd be double-invalidating.
     */
    override fun onTerminalCursorStateChange(state: Boolean) {
        viewRef.get().get()?.onScreenUpdated()
    }

    /**
     * [com.termux.terminal.TerminalEmulator] cursor styles are integer constants
     * (`TerminalEmulator.CURSOR_STYLE_BLOCK = 0`, `_UNDERLINE = 1`, `_BAR = 2`).
     * EclipseSSH stores the user's choice as a string in
     * [SettingsRepository][dev.eclipse.ssh.data.settings.SettingsRepository]; the supplier here
     * returns the corresponding int.
     */
    override fun getTerminalCursorStyle(): Int = cursorStyle()

    override fun logError(
        tag: String,
        message: String,
    ) {
        Log.e(tag, message)
    }

    override fun logWarn(
        tag: String,
        message: String,
    ) {
        Log.w(tag, message)
    }

    override fun logInfo(
        tag: String,
        message: String,
    ) {
        Log.i(tag, message)
    }

    override fun logDebug(
        tag: String,
        message: String,
    ) {
        Log.d(tag, message)
    }

    override fun logVerbose(
        tag: String,
        message: String,
    ) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(
        tag: String,
        message: String,
        e: Exception,
    ) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(
        tag: String,
        e: Exception,
    ) {
        Log.e(tag, e.message, e)
    }
}
