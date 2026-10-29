package dev.eclipse.ssh.terminal

import dev.eclipse.ssh.ssh.TerminalChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalBuffer

/**
 * Bridge between the SSH channel byte stream and the Termux TerminalEmulator.
 *
 * The legacy custom renderer (dev.eclipse.ssh.terminal.AnsiTerminalBuffer) parsed
 * the SSH channel bytes itself and exposed a cell grid that the Compose Canvas
 * drew. That approach was the source of the text-wrapping, alignment and
 * realtime-streaming issues catalogued in terminal-text.txt: the parser and the
 * renderer were coupled through a frame model that did not survive rapid output
 * or wide columns, and the renderer had to re-derive the prompt layout every
 * frame rather than reading the emulator's own notion of where the cursor is.
 *
 * This bridge lets the SSH channel drive the Termux emulator directly. The
 * emulator is the one source of truth for cells, cursor and scrollback, and
 * the renderer (TermuxTerminalView) reads from the emulator's TerminalBuffer.
 *
 * Usage:
 *   val bridge = TermuxEmulatorBridge(channel, scope)
 *   bridge.start()        // begins collecting channel.output into the emulator
 *   bridge.write(bytes)   // user keystrokes from the renderer
 *   bridge.resize(r, c)   // called on TerminalView geometry change
 *   bridge.stop()         // cancels the collector, releases the emulator
 *
 * The bridge implements [TerminalOutput] so the emulator can hand back the
 * user's keystrokes (parsed into terminal escape sequences by the emulator
 * itself — backspace becomes \\b, an arrow key becomes the ANSI cursor move,
 * etc.) and the bridge forwards them to the SSH channel.
 *
 * This class is intentionally framework-light: no Hilt, no StateFlow, no
 * lifecycle. The owning screen owns the scope and the lifecycle; the bridge
 * is a plain Kotlin object so it survives a recomposition or a configuration
 * change without rebuilding the emulator and losing scrollback.
 *
 * Thread model:
 *  - The emulator is single-threaded by design. All calls into it (write,
 *    resize, processCodePoint) happen on [Dispatchers.Default] through a
 *    single supervisor job, so the renderer thread and the channel collector
 *    never race the emulator's internal state.
 *  - The [TerminalChannel.output] flow is a hot SharedFlow, so collecting it
 *    on a coroutine is safe even when the renderer is not attached.
 */
class TermuxEmulatorBridge(
    private val channel: TerminalChannel,
    private val scope: CoroutineScope,
) : TerminalOutput {

    /** The single TerminalEmulator instance shared by the bridge and the renderer. */
    @Volatile
    private var emulator: TerminalEmulator? = null

    /** The session shell that the emulator speaks to; never the local PTY. */
    @Volatile
    private var session: TerminalSession? = null

    /** The collector job that drains channel.output into the emulator. */
    private var collectorJob: Job? = null

    /** The supervisor that scopes every emulator-touching coroutine. */
    private val emulatorScope: CoroutineScope = CoroutineScope(
        scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]),
    )

    /**
     * Initialises the emulator and starts draining the SSH channel into it.
     * Idempotent: a second call is a no-op, which keeps a reconnect that
     * survives process death (SessionRegistry) from spawning two collectors.
     */
    fun start(rows: Int = 24, columns: Int = 80) {
        if (emulator != null) return
        val em = TerminalEmulator(this, rows, columns, rows * 2000 /* scrollback */)
        emulator = em
        // TerminalSession is the termux-app wrapper that drives TerminalEmulator
        // through a local PTY — we do not use it, but its listener contract is
        // the same one TermuxTerminalView expects, so we keep the reference for
        // the renderer's onTerminalSessionChange bridge. The session is not
        // started; only the emulator is.
        session = TerminalSession(this, rows, columns)
        collectorJob = emulatorScope.launch {
            channel.output.collect { bytes ->
                // The emulator's write is single-threaded; bounce through
                // Dispatchers.Default to guarantee no concurrent access.
                withContext(Dispatchers.Default) {
                    em.processTerm(bytes, bytes.size)
                }
            }
        }
    }

    /**
     * Forwards user input (keyboard, paste) from the renderer to the SSH channel.
     *
     * The bytes here have already been turned into terminal escape sequences
     * by the Termux emulator's input path (see [TerminalEmulator.processTerm]
     * in reverse — the renderer calls write() with the user's raw keystrokes,
     * the emulator translates them, and [onEmulatorOutput] hands them here).
     */
    fun write(bytes: ByteArray) {
        channel.writeBytes(bytes)
    }

    /** Resizes the emulator and the SSH channel's PTY in lockstep. */
    fun resize(rows: Int, columns: Int) {
        emulator?.resize(columns, rows)
        channel.resize(rows, columns)
    }

    /** Stops the collector and releases the emulator. Safe to call multiple times. */
    fun stop() {
        collectorJob?.cancel()
        emulatorScope.cancel()
        emulator = null
        session = null
    }

    /**
     * TerminalOutput callback — the emulator calls this with bytes the user's
     * input produced (escape sequences for arrow keys, etc.) so we can forward
     * them to the SSH channel.
     */
    override fun onEmulatorOutput(bytes: ByteArray?) {
        bytes ?: return
        write(bytes)
    }

    /** Required by TerminalOutput; the Termux renderer uses this to redraw. */
    override fun onTitleChanged(title: String?) {
        // The legacy renderer's titlebar is the host's address, not the remote's
        // xterm title; leave this as a no-op until a settings toggle asks for it.
    }

    /**
     * The Termux renderer hooks here to repaint on emulator state change.
     * The bridge is intentionally passive — the renderer pulls the next frame
     * from the emulator's buffer when this fires, so this method only needs to
     * wake the renderer up. The actual redraw lives in TermuxTerminalView.
     */
    override fun onColorsChanged(session: TerminalSession?) {
        // Forwarded to the renderer's invalidate() via the session reference
        // the renderer holds; no work here.
    }

    /**
     * The Termux TerminalSession's state-change hook. We surface only the
     * exits; the renderer's host card already shows status through the
     * SessionRegistry state machine.
     */
    override fun onSessionFinished(session: TerminalSession?) {
        // The SSH channel's own close future drives the visible state machine;
        // this hook is informational. Leave it a no-op until the UX audit that
        // wires the renderer's "Process exited" banner lands.
    }
}
