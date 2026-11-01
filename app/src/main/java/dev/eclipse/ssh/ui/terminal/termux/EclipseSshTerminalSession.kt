package dev.eclipse.ssh.ui.terminal.termux

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient

/**
 * A [TerminalOutput] that drives a [TerminalEmulator] from SSH channel bytes — the half of the
 * Termux integration the parent class does not provide.
 *
 * Why this is not a [com.termux.terminal.TerminalSession] subclass: `TerminalSession` is
 * declared `final` in the termux-app source (`public final class TerminalSession extends
 * TerminalOutput`), and its job is to fork a local shell under a PTY, then pump that PTY's
 * stdin/stdout through a pair of background threads into the emulator. EclipseSSH already has a
 * remote PTY — the SSH channel — and there is no local shell to fork. So we extend
 * [TerminalOutput] directly (which *is* abstract, with `write(byte[], int, int)` and four other
 * abstract methods), hold the emulator ourselves, and let the [TermuxTerminalView] attach this
 * instance to a [com.termux.view.TerminalView] via reflection on its public `mTermSession` and
 * `mEmulator` fields (the View's `attachSession` accepts only a `TerminalSession`, which we cannot
 * subclass, so the public-field route is the only path).
 *
 * Bytes flow:
 *
 *  - **SSH → screen**: [feedBytes] calls [TerminalEmulator.append] directly, on whatever thread
 *    the SSH collector runs on. The emulator then calls [TerminalSessionClient.onTextChanged] on
 *    the same thread, which our client invalidates the View from.
 *
 *  - **screen → SSH**: the View translates keystrokes into escape sequences via the emulator's
 *    `processCodePoint` etc., which call back into [write] (the abstract method we implement here).
 *    The override forwards to the SSH channel via the sink installed by [setSshByteSink].
 *
 * The emulator is created lazily — the Termux View's `updateSize()` runs after the first layout,
 * computes columns/rows from its renderer's cell metrics, and the View's body then calls back
 * into [TermuxTerminalView]'s update block, which calls [ensureEmulator] here. Mirrors the lazy
 * init Termux's own `TerminalSession` does.
 *
 * The [dummySession] field is a [TerminalSession] constructed purely so the
 * [TerminalSessionClient] callbacks (which all take a non-null `TerminalSession` parameter) have
 * something to receive. `TerminalSession`'s constructor only stores its arguments — it does not
 * fork the subprocess until `initializeEmulator` is called, and we never call that, so the
 * dummy session is inert. The client implementations in this package ignore the session parameter
 * anyway (they read state from the emulator or from their own fields).
 */
class EclipseSshTerminalSession(
    private val transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
) : TerminalOutput() {

    /**
     * The session client, set once via [attachClient] before the first byte flows. Held as a `var`
     * rather than a constructor parameter because of a forward-reference cycle: the client's
     * `titleProvider` lambda closes over `this` [EclipseSshTerminalSession] (to read
     * [currentTitle]), so the client cannot be constructed before this object exists. The pattern
     * is construct-then-attach, both happening inside the same `remember(channel)` block in
     * [TermuxTerminalView].
     *
     * `@Volatile` because the SSH collector reads it (via [feedBytes] → emulator → callback →
     * this client) on its thread while the main thread writes it (via [attachClient]).
     */
    @Volatile
    private var sessionClient: TerminalSessionClient? = null

    /**
     * Install the session client. Must be called exactly once, before any byte flows through
     * [feedBytes] or any keystroke arrives through [write]. Constructed in
     * [TermuxTerminalView]'s `remember(channel)` block immediately after this session.
     */
    fun attachClient(client: TerminalSessionClient) {
        sessionClient = client
    }

    /**
     * A [TerminalSession] constructed purely so the [TerminalSessionClient] callbacks (which all
     * take a non-null `TerminalSession` parameter) have something to receive.
     *
     * `TerminalSession` is `final` and its constructor requires a `shellPath`, but the body only
     * stores the arguments — it does not fork the subprocess until `initializeEmulator` is
     * called, and we never call that. The path passed here is `/bin/true`, a real binary on every
     * Termux-supported device that exits immediately if anything ever did exec it; chosen so a
     * stack trace reads as intent-obvious rather than as a leftover from copy-paste.
     *
     * Constructed lazily because the client must be attached first (see [attachClient]) — the
     * `TerminalSession` constructor stores the client, and that client is the same one our
     * [attachClient] holds. Without lazy, we'd need to pass the client to two constructors.
     */
    private val dummySession: com.termux.terminal.TerminalSession by lazy {
        com.termux.terminal.TerminalSession(
            "/bin/true",
            "/",
            emptyArray(),
            emptyArray(),
            transcriptRows,
            sessionClient ?: error("attachClient must be called before any terminal callback fires"),
        )
    }

    /**
     * Where bytes the emulator *produces* (user keystrokes, escape-sequence replies) should go.
     *
     * Installed once when the SSH channel is attached. The lambda writes to
     * [dev.eclipse.ssh.ssh.TerminalChannel.writeBytes]; receiving a slice `(ByteArray, offset, count)`
     * rather than a copy keeps the allocation off the emulator thread.
     */
    @Volatile
    private var sshByteSink: ((ByteArray, Int, Int) -> Unit)? = null

    fun setSshByteSink(sink: (ByteArray, Int, Int) -> Unit) {
        sshByteSink = sink
    }

    /**
     * The emulator, lazily constructed. Held as a plain `var` because [TerminalEmulator] does
     * not survive a resize (its [TerminalEmulator.resize] mutates in place, so the same instance
     * is kept across resizes); only [ensureEmulator] writes the field, only [emulator] reads it.
     *
     * `@Volatile` because the SSH collector reads it on its thread (via [feedBytes]) while the
     * main thread writes it (via [ensureEmulator]); the worst case without volatile is the
     * collector seeing a half-published reference, which would NPE in `append` — recoverable but
     * not worth the risk.
     */
    @Volatile
    private var emulatorField: TerminalEmulator? = null

    /** The emulator, or null before the first [ensureEmulator] call. */
    val emulator: TerminalEmulator? get() = emulatorField

    /**
     * Construct the emulator if it does not exist, then return it. Called from
     * [TermuxTerminalView]'s update block on the main thread, the first time the View has non-zero
     * dimensions; subsequent calls return the existing instance.
     */
    fun ensureEmulator(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int): TerminalEmulator {
        emulatorField?.let { return it }
        val client = sessionClient
            ?: error("attachClient must be called before ensureEmulator (called from TermuxTerminalView's update block)")
        val emulator = TerminalEmulator(
            this,
            columns,
            rows,
            cellWidthPixels,
            cellHeightPixels,
            transcriptRows,
            client,
        )
        emulatorField = emulator
        return emulator
    }

    /**
     * Resize the emulator. Called from [TermuxTerminalView]'s update block when the View's
     * dimensions change. The remote PTY's resize is driven separately by the Compose side via
     * [dev.eclipse.ssh.ssh.TerminalChannel.resize] — Termux's own session does both in one call
     * because it owns the local PTY; we split the responsibilities for the same reason the
     * legacy code did.
     */
    fun resizeEmulator(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        emulatorField?.resize(columns, rows, cellWidthPixels, cellHeightPixels)
    }

    /**
     * Feed bytes received from the SSH channel into the emulator.
     *
     * Called on whatever dispatcher the SSH collector runs on. [TerminalEmulator.append] is
     * `synchronized` internally, so concurrent callers are safe; the only invariant it asks of
     * callers is that no two threads call it with overlapping buffers, which a single SSH
     * collector satisfies.
     *
     * The redraw is not driven from here — the emulator calls back into the [TerminalSessionClient]
     * (onTextChanged) on every screen update, and the client invalidates the view.
     */
    fun feedBytes(data: ByteArray, length: Int) {
        emulatorField?.append(data, length)
    }

    /**
     * The current window title, set by [titleChanged] when the emulator processes an xterm title
     * escape (`\033]0;...\007`). Read by the [EclipseSshTerminalSessionClient]'s titleProvider so
     * the Compose tab strip updates — the client cannot read it from [dummySession] because the
     * emulator calls back into *this* object's [titleChanged], not the dummy session's.
     *
     * `@Volatile` because the SSH collector thread writes it (via the emulator's call into
     * [titleChanged]) while the main thread reads it (via the client's titleProvider callback).
     */
    @Volatile
    private var currentTitle: String = ""

    /** The current window title, or empty string if the shell has not set one. */
    fun currentTitle(): String = currentTitle

    /**
     * The emulator's escape-sequence replies and the user's keystrokes, written back through the
     * SSH channel rather than to a local PTY that does not exist.
     *
     * [TerminalOutput.write] is the abstract method Termux's emulator calls when it produces
     * output (cursor-position replies, mode replies, the user's keystrokes translated to escape
     * sequences). The Termux `TerminalSession` implementation forwards these to the local PTY's
     * stdin; we forward to the SSH channel.
     */
    override fun write(data: ByteArray, offset: Int, count: Int) {
        sshByteSink?.invoke(data, offset, count)
    }

    override fun titleChanged(oldTitle: String, newTitle: String) {
        // Store the new title so the client's titleProvider (which reads via [currentTitle]) sees
        // it. The client's onTitleChanged ignores the dummy session parameter and calls the
        // titleProvider, which returns this field.
        currentTitle = newTitle
        sessionClient?.onTitleChanged(dummySession)
    }

    override fun onCopyTextToClipboard(text: String) {
        sessionClient?.onCopyTextToClipboard(dummySession, text)
    }

    override fun onPasteTextFromClipboard() {
        sessionClient?.onPasteTextFromClipboard(dummySession)
    }

    override fun onBell() {
        sessionClient?.onBell(dummySession)
    }

    override fun onColorsChanged() {
        sessionClient?.onColorsChanged(dummySession)
    }

    companion object {
        private const val TAG = "EclipseSshTerminalSession"
        private const val DEFAULT_TRANSCRIPT_ROWS = 2000
    }
}
