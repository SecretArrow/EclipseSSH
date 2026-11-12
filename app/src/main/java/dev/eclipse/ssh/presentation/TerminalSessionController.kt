package dev.eclipse.ssh.presentation

import android.util.Log
import dev.eclipse.ssh.ssh.SessionEnd
import dev.eclipse.ssh.ssh.TerminalChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

fun interface OutputTee {
    fun onChunk(chunk: String)
}

private class AttachedChannel(
    val channel: TerminalChannel,
    val pump: Job,
    val producer: Job,
)

/**
 * Owns one ConnectBot TerminalEmulator for the lifetime of a terminal session. The
 * emulator — scrollback, alternate screen, every mode — survives every redial and
 * every composition; only the channel is swapped under it. See the migration report.
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
        private const val END_DRAIN_MS = 5_000L
    }

    val emulator: TerminalEmulator =
        emulatorFactory(
            onKeyboardInput,
            { dimensions ->
                lastResize = Pair(dimensions.columns, dimensions.rows)
                resizeSignal.trySend(Pair(dimensions.columns, dimensions.rows))
            },
            defaultForeground,
            defaultBackground,
        )

    @Volatile
    var lastResize: Pair<Int, Int>? = null
        private set

    private val scope = CoroutineScope(SupervisorJob(externalScope.coroutineContext[Job]) + Dispatchers.Default)
    private val resizeSignal = Channel<Pair<Int, Int>>(Channel.CONFLATED)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var attached: AttachedChannel? = null

    init {
        scope.launch {
            for (requested in resizeSignal) {
                val channel = attached?.channel ?: continue
                runCatching { channel.resize(requested.first, requested.second) }
            }
        }
    }

    fun attach(
        channel: TerminalChannel,
        onEnd: (SessionEnd) -> Unit,
    ) {
        check(!closed.get()) { "controller is closed" }
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val producer =
            scope.launch {
                kotlinx.coroutines.flow.takeWhile(channel.output) { bytes -> bytes !== TerminalChannel.END_OF_OUTPUT }
                    .collect { bytes -> incoming.trySend(bytes) }
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

    fun detachCurrent() {
        val current = attached ?: return
        attached = null
        current.pump.cancel()
        current.producer.cancel()
    }

    fun applyTheme(foreground: Int, background: Int) {
        runCatching { emulator.setDefaultColors(foreground, background) }
    }

    fun paste(text: String) = emulator.pasteText(text)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        resizeSignal.close()
        val current = attached
        attached = null
        scope.launch {
            current?.let {
                withTimeoutOrNull(END_DRAIN_MS) { it.pump.join() }
                it.pump.cancel()
                it.producer.cancel()
            }
            runCatching { emulator.close() }
            log("session terminal closed")
        }
    }
}
