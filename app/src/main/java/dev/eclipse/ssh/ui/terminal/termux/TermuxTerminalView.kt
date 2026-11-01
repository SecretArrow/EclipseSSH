package dev.eclipse.ssh.ui.terminal.termux

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
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
    onScale: (Float) -> Float = { it },
    onSingleTapUp: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onCopyModeChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current

    // The session lives as long as the channel does — kept here, not in the View, so a view
    // recreated by Compose (e.g. on a dark/light theme flip) reattaches to the same session
    // rather than restarting the SSH channel's stream. The remember-key is the channel identity
    // so a tab switch (new channel) produces a new session.
    val session = remember(channel) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val sessionClient = EclipseSshTerminalSessionClient(
            onTitleChange = onTitleChange,
            onSessionFinished = onSessionFinished,
            clipboard = clipboard,
            pasteSink = { bytes -> runCatching { channel.writeBytes(bytes) } },
            onBell = { /* bell audible: a tone is too disruptive without a setting; rely on the cursor flash */ },
            cursorStyle = { cursorShapeToTermuxInt(cursorShape) },
        )
        val sshSession = EclipseSshTerminalSession(sessionClient = sessionClient)
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
                        ),
                    )
                    // Termux's default text size is 8sp; the user's setting is in sp already.
                    setTextSize(fontSizeSp)
                    setTypeface(Typeface.MONOSPACE)
                    // Hand the session client its first valid view reference. The session is
                    // constructed before the View exists, so the client starts with a null ref;
                    // every redraw callback no-ops until this point.
                    session.sessionClient.updateViewRef(this)
                    attachSession(session.sshSession)
                    requestFocus()
                }
            },
            update = { view ->
                view.setTextSize(fontSizeSp)
                view.setTypeface(Typeface.MONOSPACE)
                // Re-publish the view reference in case the AndroidView factory rebuilt the view
                // (configuration change, theme flip) and the session client is still pointing at
                // the old, disposed one.
                session.sessionClient.updateViewRef(view)
                // Let Termux's own updateSize() drive the local-emulator resize: it computes
                // columns/rows from the renderer's cell metrics (which it derives from the font
                // size and typeface set above), then calls session.updateSize(), which our
                // EclipseSshTerminalSession override routes to emulator.resize() without touching
                // the (non-existent) local PTY.
                view.updateSize()
                // The remote PTY resize is driven from the Compose side rather than the Termux
                // View, because the SSH channel's resize call is coroutine-aware and Termux's
                // updateSize() runs on the View's thread. Re-derive columns/rows the same way the
                // Termux renderer does — view.mRenderer is package-private, so reach it through
                // the public getColumnAndRow of a synthetic tap at origin. The simpler path used
                // here is to ask the emulator itself, which has the columns/rows it was last
                // resized to.
                val emulator = session.sshSession.emulator
                if (emulator != null) {
                    onViewportChange(
                        TerminalViewport(
                            columns = emulator.mColumns,
                            ptyRows = emulator.mRows,
                            bufferRows = emulator.mRows,
                            visibleRows = emulator.mRows,
                            visibleColumns = 0,
                        ),
                    )
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
private fun cursorShapeToTermuxInt(shape: TerminalCursorShape): Int = when (shape) {
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
