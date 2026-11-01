package dev.eclipse.ssh.ui.terminal.termux

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.lang.reflect.Field

/**
 * A [TerminalSession] that owns its emulator without ever forking a local PTY.
 *
 * Termux's [TerminalSession] is built for the case Termux itself is in: it exec's a local shell
 * (bash, sh, …) under a PTY, then pipes that PTY's stdin/stdout through a pair of background
 * threads into the [TerminalEmulator]. The constructor stores its arguments; the work happens in
 * [initializeEmulator], which in the parent does three things in one breath —
 *
 *  1. constructs the [TerminalEmulator],
 *  2. calls `JNI.createSubprocess(...)` to fork the local PTY, and
 *  3. starts three reader/writer/waiter threads against the resulting file descriptors.
 *
 * EclipseSSH already *has* a remote PTY — the SSH channel — whose bytes arrive through
 * [dev.eclipse.ssh.ssh.TerminalChannel.output] as a [kotlinx.coroutines.flow.SharedFlow]. Feeding
 * them through a second, local PTY would be both pointless and broken: there is no local shell to
 * read the queue, and the only process the JNI call could fork is the user's *own* app process.
 *
 * So this subclass overrides [initializeEmulator] to do step 1 only — construct the emulator and
 * stash it in the parent's private `mEmulator` field via reflection — and exposes [feedBytes] so
 * the SSH collector can drive the emulator directly. Output written *by* the emulator (cursor-
 * position queries, mode replies, the user's keystrokes translated to escape sequences) goes the
 * other way: the parent's [write] would push to the local PTY's stdin queue, but the override
 * forwards to the SSH channel via the sink installed by [setSshByteSink].
 *
 * The reflection is unavoidable: [mEmulator][`mEmulator`] is private, the parent's
 * [initializeEmulator] sets it inside the same method that calls `JNI.createSubprocess`, and the
 * field is read by [getEmulator] (public) and by every method on this class that needs the screen.
 * We can neither call `super` (which would exec) nor leave the field null.
 */
class EclipseSshTerminalSession(
    private val sessionClient: TerminalSessionClient,
    private val transcriptRowsOverride: Int = DEFAULT_TRANSCRIPT_ROWS,
) : TerminalSession(
    // shellPath / cwd / args / env are dead weight here — see the class kdoc for why
    // initializeEmulator is overridden to never call JNI.createSubprocess — but the parent
    // constructor stores them. Pass values that make that storage harmless: "/bin/true" is a
    // real binary on every Termux-supported device, exits immediately if anything ever did exec
    // it, and reads as intent-obvious in a stack trace ("this session is not a shell host").
    shellPath = "/bin/true",
    cwd = "/",
    args = emptyArray(),
    env = emptyArray(),
    transcriptRows = transcriptRowsOverride,
    client = sessionClient,
) {
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
     * Feed bytes received from the SSH channel into the emulator.
     *
     * Called on whatever dispatcher the SSH collector runs on. [TerminalEmulator.append] is
     * `synchronized` internally, so concurrent callers are safe; the only invariant it asks of
     * callers is that no two threads call it with overlapping buffers, which a single SSH
     * collector satisfies.
     *
     * The redraw is not driven from here — the emulator calls back into the [TerminalSessionClient]
     * (onTextChanged) on every screen update, and the client invalidates the view. Mirrors the
     * path Termux's own main-thread handler takes after a `MSG_NEW_INPUT`.
     */
    fun feedBytes(data: ByteArray, length: Int) {
        val emulator = emulator ?: return
        emulator.append(data, length)
    }

    /**
     * Construct the emulator without starting a local subprocess.
     *
     * Override-only: the parent's body is bypassed entirely. We build the [TerminalEmulator]
     * against `this` (TerminalOutput) and [sessionClient] (TerminalSessionClient), then write it
     * into the parent's private `mEmulator` field so every other method on this class —
     * [getEmulator], [updateSize], [isRunning] — sees a non-null emulator as they expect.
     *
     * Called the first time [TerminalView.updateSize] runs with non-zero view dimensions: the
     * parent's `updateSize` body checks `mEmulator == null` and, on the null branch, calls this
     * method to lazily construct it. Every later call goes to the resize branch in [updateSize]
     * below.
     */
    override fun initializeEmulator(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        val emulator = TerminalEmulator(
            /* session = */ this,
            /* columns = */ columns,
            /* rows = */ rows,
            /* cellWidthPixels = */ cellWidthPixels,
            /* cellHeightPixels = */ cellHeightPixels,
            /* transcriptRows = */ transcriptRowsOverride,
            /* client = */ sessionClient,
        )
        emulatorField.set(this, emulator)
    }

    /**
     * Resize without touching a local PTY.
     *
     * The parent's body, on the non-null branch, calls `JNI.setPtyWindowSize(mTerminalFileDescriptor, …)`
     * before resizing the emulator. We never set `mTerminalFileDescriptor` (no subprocess was
     * forked), so the JNI call would either crash on a 0 file descriptor or silently no-op
     * depending on the device — neither is acceptable. Override to skip the JNI call entirely
     * and resize the emulator only.
     *
     * The remote PTY *is* still told about the new size: the SSH-channel side of the resize is
     * driven from [TermuxTerminalView], which calls [dev.eclipse.ssh.ssh.TerminalChannel.resize]
     * alongside this method. The split mirrors the legacy code's two-step `onResizeTerminal` —
     * local buffer resize, then remote PTY resize.
     *
     * The `super.initializeEmulator` call on the null branch is what makes this a drop-in for the
     * parent: the same lazy-init contract holds.
     */
    override fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (emulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels)
        } else {
            emulator?.resize(columns, rows, cellWidthPixels, cellHeightPixels)
        }
    }

    /**
     * The emulator's escape-sequence replies and the user's keystrokes, written back through the
     * SSH channel rather than to a local PTY that does not exist.
     *
     * The parent's body checks `mShellPid > 0` before queueing; ours has no shell and no queue,
     * so the guard is replaced by a null check on the sink (which is installed before the first
     * keystroke can arrive — see [TermuxTerminalView]).
     */
    override fun write(data: ByteArray, offset: Int, count: Int) {
        sshByteSink?.invoke(data, offset, count)
    }

    companion object {
        private const val TAG = "EclipseSshTerminalSession"
        private const val DEFAULT_TRANSCRIPT_ROWS = 2000

        /**
         * Cached after first lookup; [Class.getDeclaredField] is slow enough to matter on a hot path
         * if it were re-read per session, and `by lazy` makes the lookup thread-safe without a lock.
         */
        private val emulatorField: Field by lazy {
            try {
                TerminalSession::class.java.getDeclaredField("mEmulator").apply { isAccessible = true }
            } catch (e: NoSuchFieldException) {
                // The field name is part of termux-app's stable ABI for v0.118.3 (verified against
                // source at that tag). A failure here means a Termux version bump renamed it; fail
                // loud at first use rather than silently dropping the emulator.
                Log.e(TAG, "TerminalSession.mEmulator field not found — Termux version mismatch", e)
                throw e
            }
        }
    }
}
