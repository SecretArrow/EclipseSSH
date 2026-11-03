package dev.eclipse.ssh.ui.terminal.termux

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import java.io.ByteArrayOutputStream

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
     *
     * Exposed (rather than private) so [TermuxTerminalView] can install it on the View's public
     * `mTermSession` field. Termux's [TerminalView] dereferences `mTermSession` in a couple of
     * paths it does not null-guard (notably `handleKeyCode`, reached from `onKeyDown` when the
     * client returns `false` for a key it does not own); without a session on the view, those paths
     * NPE. The dummy session is inert — `mShellPid` stays `0`, so `write` is a no-op and `getEmulator`
     * returns the field we never set — which is exactly what those unguarded paths need: a non-null
     * reference that fails safe.
     */
    internal val dummySession: com.termux.terminal.TerminalSession by lazy {
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
     * Whether the remote program has enabled DECCKM (application cursor keys), read from the
     * emulator. [TerminalKeys.encode] needs this so the arrow keys and Home/End arrive as `SS3`
     * sequences — which `bash` with `vi` bindings, `less`, and most curses programs expect — rather
     * than the `CSI` form, which those programs ignore.
     *
     * Returns `false` before the emulator exists, matching Termux's own pre-emulator behaviour.
     */
    fun isCursorKeysApplicationMode(): Boolean = emulatorField?.isCursorKeysApplicationMode() ?: false

    /**
     * Whether the remote program has enabled DECPAM (application keypad). Same role as
     * [isCursorKeysApplicationMode] but for the keypad keys; rarely enabled by shells but common in
     * `tmux` and full-screen TUIs.
     */
    fun isKeypadApplicationMode(): Boolean = emulatorField?.isKeypadApplicationMode() ?: false

    /**
     * Bytes that arrived from the SSH channel before the emulator was constructed.
     *
     * The emulator is created lazily on the first View layout; the SSH channel can begin streaming
     * the moment it opens, which is before the first frame. Without this buffer the login banner and
     * the first prompt are dropped — [feedBytes] no-ops while [emulatorField] is null — and the user
     * sees a blank screen for the first second of every session.
     *
     * Drained into the emulator by [ensureEmulator] the moment one exists. Bounded by
     * [MAX_PENDING_BYTES] so a torn-down view cannot pin unbounded memory.
     */
    private val pendingBytes = ByteArrayOutputStream()

    /**
     * Construct the emulator if it does not exist, then return it. Called from
     * [TermuxTerminalView]'s update block on the main thread, the first time the View has non-zero
     * dimensions; subsequent calls return the existing instance.
     */
    fun ensureEmulator(
        columns: Int,
        rows: Int,
        cellWidthPixels: Int,
        cellHeightPixels: Int,
    ): TerminalEmulator {
        emulatorField?.let { return it }
        val client =
            sessionClient
                ?: error("attachClient must be called before ensureEmulator (called from TermuxTerminalView's update block)")
        val emulator =
            TerminalEmulator(
                this,
                columns,
                rows,
                cellWidthPixels,
                cellHeightPixels,
                transcriptRows,
                client,
            )
        emulatorField = emulator
        drainPending(emulator)
        return emulator
    }

    /**
     * Resize the emulator. Called from [TermuxTerminalView]'s update block when the View's
     * dimensions change. The remote PTY's resize is driven separately by the Compose side via
     * [dev.eclipse.ssh.ssh.TerminalChannel.resize] — Termux's own session does both in one call
     * because it owns the local PTY; we split the responsibilities for the same reason the
     * legacy code did.
     */
    fun resizeEmulator(
        columns: Int,
        rows: Int,
        cellWidthPixels: Int,
        cellHeightPixels: Int,
    ) {
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
     * Before the emulator exists, bytes are buffered into [pendingBytes] rather than dropped. The
     * login banner and the first prompt arrive within milliseconds of the SSH channel opening, and
     * the emulator is not constructed until the View's first layout (a frame or two later) — without
     * buffering, those bytes were lost and the user saw a blank screen for the first second of the
     * session. [ensureEmulator] drains the buffer into the emulator as soon as one exists.
     *
     * The redraw is not driven from here — the emulator calls back into the [TerminalSessionClient]
     * (onTextChanged) on every screen update, and the client invalidates the view.
     */
    fun feedBytes(
        data: ByteArray,
        length: Int,
    ) {
        val emulator = emulatorField
        if (emulator == null) {
            // Re-check inside the lock: the SSH thread's volatile read of [emulatorField] can be
            // overtaken by the main thread's [ensureEmulator] running between that read and the
            // `synchronized(pendingBytes)` entry below. If [ensureEmulator] completes first it
            // calls [drainPending], which sees an empty buffer (these bytes are not in it yet) and
            // returns — leaving these bytes stranded in the buffer with no second drain to collect
            // them. Re-reading [emulatorField] under the same lock [drainPending] takes closes that
            // window: either the emulator is still null (buffer, as before) or it has appeared
            // (append directly, never touching the buffer).
            synchronized(pendingBytes) {
                val live = emulatorField
                if (live != null) {
                    live.append(data, length)
                    return
                }
                // Copy the slice: the SharedFlow's replay buffer may reuse this array, and we hold it
                // across the gap until the emulator arrives.
                pendingBytes.write(data, 0, length)
                // Cap so a session whose emulator never materialises (a view torn down before its
                // first layout) does not pin unbounded memory. The head is dropped because the
                // most recent bytes — the prompt — are what the user actually wants to see.
                if (pendingBytes.size() > MAX_PENDING_BYTES) {
                    val all = pendingBytes.toByteArray()
                    pendingBytes.reset()
                    pendingBytes.write(all, all.size - MAX_PENDING_BYTES, MAX_PENDING_BYTES)
                }
            }
            return
        }
        emulator.append(data, length)
    }

    /**
     * Drain the bytes that arrived before the emulator existed. Called once from [ensureEmulator]
     * on the main thread, the moment the emulator is constructed; the buffer is then retired.
     */
    private fun drainPending(emulator: TerminalEmulator) {
        val pending: ByteArray
        synchronized(pendingBytes) {
            if (pendingBytes.size() == 0) return
            pending = pendingBytes.toByteArray()
            pendingBytes.reset()
        }
        emulator.append(pending, pending.size)
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
    override fun write(
        data: ByteArray,
        offset: Int,
        count: Int,
    ) {
        sshByteSink?.invoke(data, offset, count)
    }

    override fun titleChanged(
        oldTitle: String,
        newTitle: String,
    ) {
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

        // Enough for a long login banner plus the first prompt; the emulator arrives within a
        // frame or two of the channel opening, so in practice only a few KB ever collect here.
        private const val MAX_PENDING_BYTES = 64 * 1024
    }
}
