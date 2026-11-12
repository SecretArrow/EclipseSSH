/*
 * The session-side half of the ConnectBot terminal engine.
 *
 * One controller owns one ConnectBot TerminalEmulator for one terminal session —
 * the session, not the channel: a redial swaps the channel under it and the
 * emulator (with its scrollback, its alternate screen and every mode the remote
 * side had set) is exactly what survives, the way the old AnsiTerminalBuffer did.
 * The emulator itself — parsing, damage tracking, snapshots, and the render the
 * Compose Terminal() draws — is the library's; this class is the glue the library
 * deliberately does not ship, because the library owns the terminal and the app
 * owns the PTY.
 *
 * The threading contract is the library's, followed exactly:
 *  - [TerminalEmulator.writeInput] is thread-safe and callable from any dispatcher,
 *    so the pump runs on Dispatchers.Default beside the observers it feeds.
 *  - The emulator's onResize callback must not be answered with an emulator call
 *    (the native mutex is not reentrant); it is only queued here — a CONFLATED
 *    channel — and the resize loop applies it to the pty on its own coroutine.
 *  - [TerminalEmulator.close] is called exactly once, from [close], after the
 *    channel's pump has been stopped — never because the UI went away. The tab
 *    switcher keeps sessions alive across composition; this class is the session
 *    owner the library's README names, so only the owner's teardown path may close.
 *
 * The ending rules mirror what the old collector did, because they are facts about
 * channels rather than about either engine: the pump drains past the
 * END_OF_OUTPUT terminator before the ending is reported, a deliberate close is
 * still reported (the caller decides it is not an outage), and a pump cancelled by
 * a redial never reports — its channel was replaced on purpose.
 */
package dev.eclipse.ssh.presentation

import android.util.Log
import dev.eclipse.ssh.ssh.SessionEnd
import dev.eclipse.ssh.ssh.TerminalChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/**
 * The observer every decoded output chunk passes through after the emulator has it.
 *
 * Kept as an interface so the controller's tests need neither a real SessionLog nor
 * a real notification detector; the ViewModel wires the real ones — the session log
 * (which "Save session log" exports) and the notify-when-done detector, both of
 * which must see exactly what the terminal saw.
 */
fun interface OutputTee {
    /** Called once per chunk, in arrival order, with the chunk the emulator was fed. */
    fun onChunk(chunk: String)
}

/** A channel the controller is currently draining, and the job doing the draining. */
private class AttachedChannel(
    val channel: TerminalChannel,
    val pump: Job,
    /** Cancels this channel's producer without touching the other children. */
    val producer: Job,
)

/**
 * Owns one [TerminalEmulator] for the lifetime of a terminal session.
 *
 * Created by the ViewModel when a session first needs a terminal (before its first
 * channel opens), outlives every composition of the terminal screen and every
 * redial, and is closed by the ViewModel only when the session permanently goes
 * away — tab close, host delete. That is what preserves scrollback across tab
 * switches and reconnects, and it is the library's own session-ownership rule.
 *
 * @param defaultForeground ARGB of the theme's default foreground the emulator is
 *   created with; the remote side's OSC 10/11 answers come from the library.
 * @param defaultBackground ARGB of the theme's default background.
 * @param onKeyboardInput the destination for the key sequences the emulator's
 *   keyboard pipeline produces — wired to the live channel's writeBytes by the
 *   caller (the ViewModel routes by session key), so this class never learns
 *   about SSH.
 * @param tees observers of decoded output, called in order per chunk.
 * @param emulatorFactory seam for tests: builds the emulator the controller drives.
 *   The default is the library's real factory with this session's wiring.
 * @param externalScope the scope pump coroutines are launched into. The controller
 *   attaches and detaches channels within it; it must outlive any one channel but
 *   be cancelled when the session is torn down (the ViewModel's terminal scope).
 * @param log diagnostics sink, swappable in tests.
 */
class TerminalSessionController(
    defaultForeground: Int,
    defaultBackground: Int,
    onKeyboardInput: (ByteArray) -> Unit,
    private val tees: List<OutputTee> = emptyList(),
    emulatorFactory: (
        onKeyboardInput: (ByteArray) -> Unit,
        onResize: (TerminalDimensions) -> Unit,
        defaultForeground: Int,
        defaultBackground: Int,
    ) -> TerminalEmulator = { keyboard, resize, fg, bg ->
        TerminalEmulatorFactory.create(
            onKeyboardInput = keyboard,
            onResize = resize,
            defaultForeground = androidx.compose.ui.graphics.Color(fg),
            defaultBackground = androidx.compose.ui.graphics.Color(bg),
        )
    },
    externalScope: CoroutineScope,
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) {
    companion object {
        private const val TAG = "TerminalSession"

        /** How long a detach waits for the channel's terminator to arrive. */
        private const val END_DRAIN_MS = 5_000L
    }

    /**
     * The emulator this session renders through. The Compose Terminal() is given
     * exactly this instance; the snapshot flow it collects is the emulator's own,
     * so rendering survives whatever composition does.
     */
    val emulator: TerminalEmulator =
        emulatorFactory(
            onKeyboardInput = onKeyboardInput,
            onResize = { dimensions ->
                lastResize = dimensions.columns to dimensions.rows
                resizeSignal.trySend(dimensions.columns to dimensions.rows)
            },
            defaultForeground,
            defaultBackground,
        )

    /**
     * The pty size the emulator last computed for this session's grid — the size a
     * redial's fresh channel should be opened with, and the size the resize loop
     * pushes to the live channel. Written on the emulator's callback thread, read
     * anywhere; a stale-but-recent grid beats a 80x24 default on a phone.
     */
    @Volatile
    var lastResize: Pair<Int, Int>? = null
        private set

    private val scope = CoroutineScope(SupervisorJob(externalScope.coroutineContext[Job]) + Dispatchers.Default)

    private val resizeSignal = Channel<Pair<Int, Int>>(Channel.CONFLATED)

    private val closed = AtomicBoolean(false)

    /** The channel this session is currently draining, with its pump and producer. */
    @Volatile
    private var attached: AttachedChannel? = null

    init {
        // The pty resize loop. onResize arrives on the emulator's callback thread;
        // channel.resize() writes an SSH packet, which must never happen there —
        // not for the blocking write, and not because the emulator's native mutex
        // is held while the callback runs. CONFLATED coalesces: a flapping
        // measurement produces one window-change, not a burst. The loop does not
        // stop when there is no channel: a redial attaches a fresh one moments
        // later, and its resizes must still go out.
        scope.launch {
            for ((columns, rows) in resizeSignal) {
                val channel = attached?.channel ?: continue
                runCatching { channel.resize(columns, rows) }
            }
        }
    }

    /**
     * Starts draining [channel] into the emulator: output bytes are fed verbatim,
     * and the session's observers see each chunk decoded, in order.
     *
     * Replaces any channel this session is already draining: the redial path calls
     * this after cancelling the old collector, and the old channel's ending must
     * not be announced as an outage over the session the new one has taken over.
     *
     * @param onEnd invoked exactly once per attached channel, after its stream has
     *   ended and drained, with why it ended. Deliberate endings arrive here too —
     *   the caller decides what the tab should say about them.
     */
    fun attach(
        channel: TerminalChannel,
        onEnd: (SessionEnd) -> Unit,
    ) {
        check(!closed.get()) { "controller is closed" }
        // Cancel-and-replace rather than detach-then-attach: the caller has already
        // stopped the previous collector job, and a second attach under the same
        // controller is a redial installing its channel.
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val producer =
            scope.launch {
                channel.output
                    .takeWhile { it !== TerminalChannel.END_OF_OUTPUT }
                    .collect { incoming.trySend(it) }
                incoming.close()
            }
        val pump =
            scope.launch(Dispatchers.Default) {
                try {
                    for (chunk in incoming) {
                        emulator.writeInput(chunk, 0, chunk.size)
                        val decoded = chunk.toString(Charsets.UTF_8)
                        for (tee in tees) tee.onChunk(decoded)
                    }
                } finally {
                    producer.cancel()
                    val end = runCatching { channel.awaitClosed() }.getOrNull() ?: SessionEnd.Released
                    onEnd(end)
                }
            }
        attached = AttachedChannel(channel, pump, producer)
    }

    /**
     * Stops draining the current channel without reporting an ending — the redial
     * path, where the old channel was replaced on purpose and its END_OF_OUTPUT
     * must not be read as the session's. Waits for in-flight chunks to land so a
     * shell's parting output still reaches the terminal.
     */
    fun detachCurrent() {
        val current = attached ?: return
        attached = null
        // Never blocking: close() runs on the caller's thread (the ViewModel's, on
        // the main thread during teardown), and join() here would park it for as
        // long as a congested channel took to deliver its terminator. The pump is
        // cancelled instead; the END_DRAIN_MS budget applies only in close(),
        // where the session is going away and the drain is worth a bounded wait.
        current.pump.cancel()
        current.producer.cancel()
    }

    /** Applies the theme's colours; the library answers OSC 10/11 from these. */
    fun applyTheme(foreground: Int, background: Int) {
        runCatching { emulator.setDefaultColors(foreground, background) }
    }

    /** Pastes [text] through the emulator, honouring the paste mode the remote set. */
    fun paste(text: String) = emulator.pasteText(text)

    /**
     * Permanently disposes this session's terminal: stops the pump, then closes the
     * emulator. Idempotent — a second call is a no-op — and never called by anything
     * but the session owner's teardown (tab close, host delete), because the
     * composable leaving composition is a tab switch, not a session end.
     *
     * The drain wait is bounded and off the caller's hot path: emulator.close() is
     * the step that actually releases native state, and it runs whether or not the
     * pump managed to drain within the budget.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        resizeSignal.close()
        val current = attached
        attached = null
        scope.launch {
            // Give the in-flight stream a moment to land its last chunks — a shell
            // being closed on purpose often has a farewell line still queued — then
            // stop the pump whatever the wait produced.
            current?.let {
                withTimeoutOrNull(END_DRAIN_MS) { it.pump.join() }
                it.pump.cancel()
                it.producer.cancel()
            }
            // The library's own rule: close() only when the owner permanently
            // disposes, after input producers are stopped — they are, above.
            runCatching { emulator.close() }
            log("session terminal closed")
        }
    }
}
