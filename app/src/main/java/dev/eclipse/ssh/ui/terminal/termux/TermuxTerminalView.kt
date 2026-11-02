package dev.eclipse.ssh.ui.terminal.termux

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView
import dev.eclipse.ssh.ssh.TerminalChannel
import dev.eclipse.ssh.terminal.TerminalCursorShape
import dev.eclipse.ssh.terminal.TerminalViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * The Compose host for the Termux [TerminalView].
 *
 * Termux's [TerminalView] is an `android.view.View`, not a Composable. This wraps it in an
 * [AndroidView] so the rest of EclipseSSH's Compose tree can use it like any other Composable.
 *
 * The view is owned by Compose for the lifetime of this Composable; the session survives across
 * configuration changes because the session is held outside Compose (by
 * [dev.eclipse.ssh.presentation.MainViewModel.sessionStore]) and reattached to the new view on
 * recomposition via [attachSession].
 *
 * Bytes flow:
 *
 *  - **SSH → screen**: the channel's [TerminalChannel.output] SharedFlow is collected in a
 *    coroutine launched in [rememberCoroutineScope]; each chunk is passed to
 *    [EclipseSshTerminalSession.feedBytes], which calls `TerminalEmulator.append(data, length)`.
 *    The emulator then calls [EclipseSshTerminalSessionClient.onTextChanged] on the collector's
 *    thread, which calls [TerminalView.onScreenUpdated] (thread-safe via `postInvalidate`).
 *
 *  - **screen → SSH**: keystrokes are typed into the [TerminalView], which translates them into
 *    escape sequences via `TerminalSession.writeCodePoint` / the parent's `write(byte[], int, int)`
 *    override. That override forwards to the SSH channel via the sink installed in [setupSession].
 *
 *  - **resize**: the Compose host reports its pixel size through the [AndroidView]'s `update`
 *    block; we convert pixel size → columns/rows using the cell metrics Termux computes from the
 *    current typeface and text size, then call [TerminalChannel.resize] *and*
 *    [TerminalSession.updateSize][com.termux.terminal.TerminalSession.updateSize] so both halves
 *    of the terminal (the local emulator and the remote PTY) agree on the geometry.
 *
 * The Compose side re-derives `theme`, `fontSize`, and `cursorStyle` on every recomposition —
 * they are `val`s read from settings — and pushes them into the View through `update` so a
 * settings change is reflected without a session restart.
 *
 * The [DisposableEffect] at the bottom is what detaches the session from the view when the
 * Composable leaves the tree. Without it, the session's [EclipseSshTerminalSessionClient] would
 * hold a [WeakReference] to a View that's been disposed, which is fine, but the
 * [EclipseSshTerminalViewClient] also held a WeakReference and that one's WeakReference would
 * resolve to nothing — also fine — but the session would keep trying to call `onScreenUpdated`
 * on a view that no longer exists, which Termux logs as a warning on every redraw.
 */
@Composable
fun TermuxTerminalView(
    channel: TerminalChannel,
    backgroundColor: Color,
    fontSizeSp: Int,
    cursorShape: TerminalCursorShape,
    modifier: Modifier = Modifier,
    isCtrlDown: () -> Boolean = { false },
    isAltDown: () -> Boolean = { false },
    isShiftDown: () -> Boolean = { false },
    isFnDown: () -> Boolean = { false },
    onTitleChange: (String) -> Unit = {},
    onSessionFinished: () -> Unit = {},
    onViewportChange: (TerminalViewport) -> Unit = {},
    onScale: (Float) -> Unit = {},
    onSingleTapUp: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onCopyModeChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current

    // The session lives as long as the channel does — kept here, not in the View, so a view
    // recreated by Compose (e.g. on a dark/light theme flip) reattaches to the same session
    // rather than restarting the SSH channel's stream. The remember-key is the channel identity
    // so a tab switch (new channel) produces a new session.
    val session =
        remember(channel) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val sshSession = EclipseSshTerminalSession()
            val sessionClient =
                EclipseSshTerminalSessionClient(
                    onTitleChange = onTitleChange,
                    onSessionFinished = onSessionFinished,
                    clipboard = clipboard,
                    pasteSink = { bytes -> runCatching { channel.writeBytes(bytes) } },
                    onBell = { /* bell audible: a tone is too disruptive without a setting; rely on the cursor flash */ },
                    cursorStyle = { cursorShapeToTermuxInt(cursorShape) },
                    titleProvider = { sshSession.currentTitle() },
                )
            // The session constructor takes the client; we cannot pass it both ways without a forward
            // reference, so reconstruct: the client reads from sshSession, and sshSession writes back
            // to the client. Constructed in this order so the titleProvider lambda closes over a
            // non-null sshSession.
            sshSession.attachClient(sessionClient)
            sshSession.setSshByteSink { data, offset, count ->
                if (count <= 0) return@setSshByteSink
                // Most writes are full slices; only copy when the emulator hands us a sub-range.
                val bytes = if (offset == 0 && count == data.size) data else data.copyOfRange(offset, offset + count)
                runCatching { channel.writeBytes(bytes) }
            }
            SshSessionHandle(sshSession, sessionClient)
        }

    // Pump SSH bytes into the emulator. Launched once per channel and cancelled when the
    // Composable leaves the tree (or the channel changes).
    LaunchedEffect(channel, session) {
        withContext(Dispatchers.IO) {
            channel.output.collect { chunk ->
                if (chunk === TerminalChannel.END_OF_OUTPUT) return@collect
                session.sshSession.feedBytes(chunk, chunk.size)
            }
        }
    }

    // Cache of the values the update block last pushed into the View and into the PTY, so a
    // recomposition that changes nothing does no work. Compose calls `update` on *every*
    // recomposition of this Composable, regardless of whether any of its inputs changed — so
    // without these guards a still terminal would have `setTextSize`, `onScreenUpdated`, an
    // emulator resize, and a PTY window-change fired at it on every frame the rest of the screen
    // invalidates. Each guard compares against the value last applied; only the diff path runs.
    val textSizeState = remember { intArrayOf(-1) }
    val geometryState = remember { intArrayOf(-1, -1, -1, -1) } // columns, rows, cellWidth, cellHeight
    val viewportState = remember { intArrayOf(-1, -1) } // columns, rows

    // The View itself. Created once per Composable entry, configured via update on every
    // recomposition so settings (font size, theme, cursor style) flow in without a session restart.
    Box(modifier.background(backgroundColor).fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setTerminalViewClient(
                        EclipseSshTerminalViewClient(
                            viewRef = WeakReference(this),
                            inputMethodManager = {
                                ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            },
                            isCtrlDown = isCtrlDown,
                            isAltDown = isAltDown,
                            isShiftDown = isShiftDown,
                            isFnDown = isFnDown,
                            onSingleTapUp = onSingleTapUp,
                            onLongPress = onLongPress,
                            onScale = onScale,
                            onCopyModeChanged = onCopyModeChanged,
                            sessionProvider = { session.sshSession },
                        ),
                    )
                    // Termux's default text size is 8sp; the user's setting is in sp already.
                    setTextSize(fontSizeSp)
                    setTypeface(Typeface.MONOSPACE)
                    // Install the inert dummy session on the View's public `mTermSession` field.
                    // We never call attachSession(...) — the View's attachSession calls updateSize(),
                    // which calls mTermSession.updateSize(), which in TerminalSession's body calls
                    // initializeEmulator() and that calls JNI.createSubprocess. libtermux.so is
                    // excluded from packaging, so the JNI call would UnsatisfiedLinkError. We bypass
                    // attachSession entirely and let the update block below drive mEmulator directly
                    // (set the public field ourselves).
                    //
                    // But the View's mTermSession must still be non-null: a handful of paths inside
                    // TerminalView — notably handleKeyCode, reached from onKeyDown when the client
                    // returns false for a key — dereference mTermSession without a null check and would
                    // NPE without a session here. The dummy session is inert (mShellPid stays 0, so
                    // write is a no-op; its own mEmulator is null, which its getEmulator() returns),
                    // which is exactly the safe failure those unguarded paths need.
                    mTermSession = session.sshSession.dummySession
                    // Hand the session client its first valid view reference. The session is
                    // constructed before the View exists, so the client starts with a null ref;
                    // every redraw callback no-ops until this point.
                    session.sessionClient.updateViewRef(this)
                    requestFocus()
                }
            },
            update = { view ->
                // Re-publish the view reference in case the AndroidView factory rebuilt the view
                // (configuration change, theme flip) and the session client is still pointing at
                // the old, disposed one. Cheap, so unconditional.
                session.sessionClient.updateViewRef(view)
                // Font size: only re-apply and re-measure cells when it actually changed, otherwise
                // every recomposition re-runs the Paint/FontMetrics dance and queues a redundant
                // setTextSize that itself triggers an onSizeChanged inside the View.
                if (textSizeState[0] != fontSizeSp) {
                    textSizeState[0] = fontSizeSp
                    view.setTextSize(fontSizeSp)
                    view.setTypeface(Typeface.MONOSPACE)
                    // Invalidate the cached geometry so the block below re-measures cells against
                    // the new text size rather than trusting stale dimensions.
                    geometryState[0] = -1
                }
                // Drive the emulator directly. The Termux View's updateSize() early-returns when
                // mTermSession is null, but ours is the dummy session (non-null), so we must NOT
                // call view.updateSize() — that path runs mTermSession.updateSize(), which calls
                // JNI.setPtyWindowSize against the dummy's invalid file descriptor. We compute cell
                // metrics ourselves — same 'M'-advance trick the legacy TerminalView uses — and
                // either construct the emulator lazily or resize it.
                val width = view.width
                val height = view.height
                if (width > 0 && height > 0) {
                    val paint =
                        Paint().apply {
                            textSize =
                                TypedValue.applyDimension(
                                    TypedValue.COMPLEX_UNIT_SP,
                                    fontSizeSp.toFloat(),
                                    view.resources.displayMetrics,
                                )
                            typeface = Typeface.MONOSPACE
                        }
                    val cellWidth = paint.measureText("M").toInt().coerceAtLeast(1)
                    val fm = paint.fontMetrics
                    val cellHeight = (fm.descent - fm.ascent).toInt().coerceAtLeast(1)
                    val columns = (width / cellWidth).coerceAtLeast(1)
                    val rows = (height / cellHeight).coerceAtLeast(1)
                    val existing = view.mEmulator
                    if (existing == null) {
                        val emulator = session.sshSession.ensureEmulator(columns, rows, cellWidth, cellHeight)
                        view.mEmulator = emulator
                        view.mClient?.onEmulatorSet()
                        geometryState[0] = columns
                        geometryState[1] = rows
                        geometryState[2] = cellWidth
                        geometryState[3] = cellHeight
                    } else if (
                        existing.mColumns != columns ||
                        existing.mRows != rows ||
                        geometryState[0] != columns ||
                        geometryState[1] != rows ||
                        geometryState[2] != cellWidth ||
                        geometryState[3] != cellHeight
                    ) {
                        session.sshSession.resizeEmulator(columns, rows, cellWidth, cellHeight)
                        geometryState[0] = columns
                        geometryState[1] = rows
                        geometryState[2] = cellWidth
                        geometryState[3] = cellHeight
                    }
                    // Report the viewport upstream only when it actually changed: this callback
                    // drives a PTY window-change on the SSH channel, and the View's onScreenUpdated
                    // below is enough to redraw against the same geometry. Without this guard every
                    // recomposition re-sends the window-change — which a strict server logs as a
                    // flood, and which races the very resize it reports.
                    if (viewportState[0] != columns || viewportState[1] != rows) {
                        viewportState[0] = columns
                        viewportState[1] = rows
                        onViewportChange(
                            TerminalViewport(
                                columns = columns,
                                ptyRows = rows,
                                bufferRows = rows,
                                visibleRows = rows,
                                visibleColumns = 0,
                            ),
                        )
                    }
                }
                view.onScreenUpdated()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    // Detach the session from any view state when the Composable leaves the tree. The channel
    // itself is owned by MainViewModel and closed on session-end by the SSH collector.
    DisposableEffect(channel) {
        onDispose {
            // Nothing to close here: the channel's lifecycle is owned by the ViewModel, and the
            // session holds no resources of its own beyond the emulator (which is GC-able).
            // This block exists to make the lifecycle explicit and to provide a hook for a
            // future "stop the collector when the tab is no longer visible" optimization.
        }
    }
}

/**
 * Map EclipseSSH's cursor-shape enum to the int constants Termux's [TerminalEmulator] expects.
 *
 * Termux's constants are not exposed as a Kotlin enum; they are bare `int` fields on
 * [com.termux.terminal.TerminalEmulator] (CURSOR_STYLE_BLOCK = 0, _UNDERLINE = 1, _BAR = 2). The
 * values are stable across v0.118.x and have been since the constants were introduced.
 */
private fun cursorShapeToTermuxInt(shape: TerminalCursorShape): Int =
    when (shape) {
        TerminalCursorShape.BLOCK -> 0
        TerminalCursorShape.UNDERLINE -> 1
        TerminalCursorShape.BAR -> 2
    }

/**
 * The session plus the client that talks back to it, kept together so a recreated View can find
 * the right client to update.
 *
 * The session is what feeds bytes into the emulator and writes the emulator's output to the SSH
 * channel; the client is what the emulator calls back into on every screen change. The two are
 * created together (the session takes the client in its constructor) and live for the same
 * length of time — the lifetime of the channel — so pairing them in a value type keeps the
 * call sites in [TermuxTerminalView] honest: they cannot pass one without the other.
 */
private data class SshSessionHandle(
    val sshSession: EclipseSshTerminalSession,
    val sessionClient: EclipseSshTerminalSessionClient,
)
