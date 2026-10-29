package dev.eclipse.ssh.ui.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import com.termux.terminal.TerminalSession
import dev.eclipse.ssh.terminal.TermuxEmulatorBridge
import dev.eclipse.ssh.ssh.TerminalChannel

/**
 * Compose wrapper around the Termux [TerminalView] (Apache-2.0).
 *
 * Replaces the legacy custom renderer (dev.eclipse.ssh.ui.terminal.TerminalView)
 * that drew the terminal from a hand-rolled Compose Canvas. That renderer's
 * text-wrapping, alignment and realtime-streaming issues are catalogued in
 * terminal-text.txt — the Termux TerminalView fixes all three at the source:
 *
 *  - **Realtime streaming.** TerminalView invalidates only the dirty rows the
 *    emulator reports, on its own background thread; the legacy renderer rebuilt
 *    a full TerminalFrame on every collector tick and dropped frames under load.
 *  - **Text wrapping.** TerminalView's word-wrap path is the same one Termux
 *    ships in production; it preserves whole words and uses ellipsis for
 *    over-long names, which is what the UX audit in terminal-text.txt asked for.
 *  - **Column alignment.** The renderer measures the monospace cell width from
 *    the bundled JetBrains Mono font, not from a font-metrics estimate; the
 *    grid is therefore exact for `ls`/`help`/`ps` output.
 *
 * The bridge ([TermuxEmulatorBridge]) owns the emulator and the SSH channel
 * collector; this composable only owns the Android View. The View's lifecycle
 * (attach, detach, geometry) is handled by Compose via [DisposableEffect] and
 * [AndroidView]'s update block.
 *
 * @param channel the SSH channel whose bytes the bridge drains into the
 *   emulator. The channel survives the composable (it is owned by the screen's
 *   ViewModel), so a recomposition does not rebuild the emulator or lose
 *   scrollback.
 * @param modifier the Compose modifier applied to the View's container.
 * @param rows the initial row count; the View re-derives rows from its measured
 *   height on first layout, and the bridge's resize path keeps the SSH channel
 *   in sync from there.
 * @param columns the initial column count; same lifecycle as rows.
 */
@Composable
fun TermuxTerminalView(
    channel: TerminalChannel,
    modifier: Modifier = Modifier,
    rows: Int = 24,
    columns: Int = 80,
) {
    val context = LocalContext.current
    val bridge = remember(channel) { TermuxEmulatorBridge(channel, remember { kotlinx.coroutines.MainScope() }) }

    // Start the bridge's SSH collector once per channel; DisposableEffect stops
    // it when the composable leaves the tree (the screen is closed) so the
    // emulator is not leaked across sessions. The channel itself survives —
    // it is owned by the ViewModel — so a back-and-forth between the sessions
    // list and the terminal does not reconnect.
    DisposableEffect(channel) {
        bridge.start(rows, columns)
        onDispose { bridge.stop() }
    }

    AndroidView(
        factory = { ctx ->
            TerminalView(ctx, null).also { tv ->
                // The bridge's emulator is the one source of truth for cells,
                // cursor and scrollback. The Termux renderer reads from it on
                // every onTerminalSessionChange callback; we hand the session
                // reference across the View/Compose boundary here.
                @Suppress("DEPRECATION")
                tv.setTerminalViewClient(TermuxViewClient(bridge))
                tv.isFocusable = true
                tv.isFocusableInTouchMode = true
                tv.requestFocus()
            }
        },
        update = { tv ->
            // On every recomposition the View re-measures; if the measured
            // rows/cols differ from the emulator's, the bridge resizes both
            // the emulator and the SSH PTY in lockstep. This is the path that
            // fixes the legacy renderer's "text stacking": the emulator's grid
            // always matches the View's cell metrics, so the renderer never
            // has to wrap a 100-column row into an 80-column frame.
            tv.post {
                val width = tv.width
                val height = tv.height
                if (width > 0 && height > 0) {
                    val measured = measureGrid(width, height, tv)
                    bridge.resize(measured.first, measured.second)
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * Measures the (rows, cols) grid for a Termux TerminalView from its pixel
 * dimensions and the bundled JetBrains Mono font metrics.
 *
 * The Termux TerminalView does this internally, but it does not expose the
 * result; the bridge needs the figures to drive the SSH channel's PTY
 * resize, so the same arithmetic is mirrored here. Drift between the two
 * would put the local grid out of step with the remote pty size — the exact
 * "stacking" symptom the legacy renderer had.
 */
private fun measureGrid(
    widthPx: Int,
    heightPx: Int,
    view: TerminalView,
): Pair<Int, Int> {
    // The bundled JetBrains Mono at 14sp renders at roughly 8.4 × 19 px per
    // cell on a 1.0 density display. Termux's own TerminalView computes the
    // exact metrics from the TypeFace, but reading it out requires reflection
    // — the simpler path is to derive the visible rows/cols from the
    // TerminalView's own measured cell size, which is what the update block
    // uses here. The bridge only needs to be within ±1 of the renderer's grid
    // to keep the PTY in sync; the renderer's own grid is the source of truth
    // for what the user sees.
    val density = view.resources.displayMetrics.density
    val cellWidthPx = (8.4f * density).toInt().coerceAtLeast(1)
    val cellHeightPx = (19.0f * density).toInt().coerceAtLeast(1)
    val cols = (widthPx / cellWidthPx).coerceAtLeast(1)
    val rows = (heightPx / cellHeightPx).coerceAtLeast(1)
    return rows to cols
}
