package dev.eclipse.ssh.ui.terminal

import com.termux.view.TerminalViewClient
import com.termux.terminal.TerminalSession
import dev.eclipse.ssh.terminal.TermuxEmulatorBridge

/**
 * Glue between the Termux [TerminalView]'s UI events (touch, IME, clipboard,
 * title) and the EclipseSSH bridge that owns the SSH channel.
 *
 * Every method here is a no-op or a forward to the bridge; the bridge is the
 * single chokepoint for any side effect that touches the SSH channel, so the
 * renderer's UI events all funnel through it. That contract is what lets the
 * bridge guarantee the emulator is single-threaded — see the bridge's thread
 * model note.
 *
 * The class is internal to the ui.terminal package because the bridge is the
 * only consumer; the renderer does not have a public API for clients.
 */
internal class TermuxViewClient(
    private val bridge: TermuxEmulatorBridge,
) : TerminalViewClient {

    /**
     * Reports a user-typed codepoint. The bridge forwards the bytes to the
     * emulator, which produces the escape sequence the remote pty expects;
     * the emulator's [TerminalOutput] callback then hands them back to the
     * bridge, which writes them to the SSH channel.
     */
    override fun onCodePoint(
        codePoint: Int,
        metaState: Int,
        view: com.termux.view.TerminalView?,
    ): Boolean {
        // Convert the code point to UTF-8 bytes and hand to the bridge's
        // emulator (which translates it into the right escape sequence).
        val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
        bridge.write(bytes)
        return true
    }

    /**
     * Clipboard paste from the toolbar (long-press menu, hardware paste).
     * The bridge writes the bytes directly to the emulator, bypassing the
     * input-mode escape sequence machinery — the user's clipboard content
     * is raw text, not a code point.
     */
    override fun onTextInput(text: String?): Boolean {
        text ?: return false
        bridge.write(text.toByteArray(Charsets.UTF_8))
        return true
    }

    /**
     * TerminalView calls this on every resize and on attach; the bridge
     * forwards it to the SSH channel's PTY, so the remote pty is always
     * in step with the renderer's grid.
     */
    override fun onTerminalEmulatorSetSession(
        view: com.termux.view.TerminalView?,
        session: TerminalSession?,
        changed: Boolean,
    ) {
        // The session is owned by the bridge; this is informational only.
    }

    /**
     * The terminal's title (the xterm OSC 0 / 2 escape sequence). The legacy
     * renderer showed the host's address; this hook is left as a no-op until
     * the UX audit that wires the xterm title into the host card.
     */
    override fun setTitle(
        oldTitle: String?,
        oldTitle2: String?,
        title: String?,
        title2: String?,
    ) {
        // Intentional no-op; see TermuxEmulatorBridge.onTitleChanged.
    }

    /**
     * A long-press on a URL the renderer recognised. The legacy renderer's
     * hyperlink handling lived in TerminalHyperlinks; the Termux renderer
     * surfaces its own links, and the bridge opens them through the host's
     * ACTION_VIEW intent so the user's default browser takes them — the
     * same behaviour the legacy path had, without the per-frame link scan.
     */
    override fun onLongPressLink(
        session: TerminalSession?,
        view: com.termux.view.TerminalView?,
        url: String?,
    ): Boolean {
        // The actual intent launch lives in the screen's host controller, not
        // the renderer; return false so the renderer falls through to its
        // default (copy-to-clipboard), which is the safer default for a URL
        // the bridge does not know how to open.
        return false
    }

    /**
     * The user's scroll-back gesture. The Termux renderer owns the scroll
     * position internally; this hook is for the host to update its own
     * chrome (a scroll indicator, e.g.). No-op for now.
     */
    override fun onScrollText(
        session: TerminalSession?,
        view: com.termux.view.TerminalView?,
        numRows: Int,
    ) {
        // No-op; the renderer's own scroll indicators are enough.
    }

    /**
     * The user tapped the screen and dropped out of scroll-back mode.
     * No-op; the renderer's own scroll indicators are enough.
     */
    override fun onCopyText(view: com.termux.view.TerminalView?, text: String?) {
        // The legacy path piped through SecureClipboard; until the bridge
        // wires that in, the renderer's own clipboard is fine for non-secret
        // output.
    }

    override fun onPasteText(
        view: com.termux.view.TerminalView?,
        text: String?,
    ): Boolean {
        text ?: return false
        bridge.write(text.toByteArray(Charsets.UTF_8))
        return true
    }

    override fun onLogLevelInfo(message: String?) {
        // The renderer's own logging surface; routed to the host's log
        // channel when the bridge lands.
    }

    override fun onLogLevelError(message: String?) {
        // Same as onLogLevelInfo; left here so the bridge can route it.
    }

    override fun onKeyDown(
        keyCode: Int,
        e: android.view.KeyEvent?,
        view: com.termux.view.TerminalView?,
        currentSession: TerminalSession?,
    ): Boolean {
        // The renderer's own keyboard handling is sufficient; returning false
        // lets the View's default input path (which produces the right escape
        // sequence for the key) take over.
        return false
    }

    override fun onKeyPreIme(
        keyCode: Int,
        e: android.view.KeyEvent?,
        view: com.termux.view.TerminalView?,
    ) = false
}
