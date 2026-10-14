package dev.eclipse.ssh.ui.terminal

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.terminal.TERMINAL_COLUMN_RANGE
import dev.eclipse.ssh.terminal.TERMINAL_ROW_RANGE
import dev.eclipse.ssh.terminal.TerminalCell
import dev.eclipse.ssh.terminal.TerminalFrame
import dev.eclipse.ssh.terminal.TerminalLayout
import dev.eclipse.ssh.terminal.TerminalSelection
import dev.eclipse.ssh.terminal.layoutKey
import dev.eclipse.ssh.terminal.terminalLayout
import dev.eclipse.ssh.terminal.wrapsForDisplay
import dev.eclipse.ssh.terminal.TerminalColor
import dev.eclipse.ssh.terminal.TerminalCursorShape
import dev.eclipse.ssh.terminal.TerminalStyle
import dev.eclipse.ssh.terminal.TerminalViewport
import dev.eclipse.ssh.terminal.TerminalVisualRow
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.floor

/**
 * The size of one character cell, and the only honest source for how many of them fit.
 *
 * Measured rather than assumed. A monospace advance depends on the font the device ships and on the
 * user's font-scale setting, so a hard-coded width reports a column count the remote pty does not
 * have - which arrives as a full-screen program formatting itself for a window three times the real
 * one. [width] is deliberately a float: an advance is rarely a whole number of pixels, and rounding
 * each glyph before multiplying compounds into several lost columns across a line.
 */
data class TerminalCellMetrics(val width: Float, val height: Float, val baseline: Float) {
    fun columnsIn(pixels: Float): Int = if (width <= 0f) 0 else floor(pixels / width).toInt()
    fun rowsIn(pixels: Float): Int = if (height <= 0f) 0 else floor(pixels / height).toInt()

    /**
     * The grid that fits in [widthPx] x [heightPx], and the corner to start drawing it from.
     *
     * A cell is rarely a whole number of pixels and a window is never an exact multiple of one, so a
     * remainder is unavoidable: at 13sp on a 1080-pixel phone it is up to a column across and a whole
     * row down. Drawn from the origin, every pixel of that remainder collects along the right edge and
     * under the last line, which is what made the text look pushed into the corner of its own window -
     * a gutter three times the intended margin on one side and the intended margin on the other, with
     * nothing on screen to say it was rounding rather than layout. Splitting it puts half on each side,
     * so the grid sits centred and the margin the caller asked for is the margin that appears.
     *
     * Bounded below at zero because a window narrower than one cell still has to report one column -
     * the pty cannot be told it has none - and that makes the remainder negative.
     */
    fun gridIn(widthPx: Float, heightPx: Float): TerminalGrid {
        val columns = columnsIn(widthPx).coerceAtLeast(1)
        val rows = rowsIn(heightPx).coerceAtLeast(1)
        return TerminalGrid(
            columns = columns,
            rows = rows,
            originX = ((widthPx - columns * width) / 2f).coerceAtLeast(0f),
            originY = ((heightPx - rows * height) / 2f).coerceAtLeast(0f),
        )
    }
}

/** How many cells fit in a window, and the offset that centres them in it. */
data class TerminalGrid(val columns: Int, val rows: Int, val originX: Float, val originY: Float)

/**
 * The same grid, widened to at least [minColumns] columns.
 *
 * This is the whole of the wide-terminal setting. A phone fits somewhere around forty-five columns of
 * legible monospace, and a terminal that tells the remote side it has forty-five is a terminal where
 * the remote side does the cutting: `ls -l` drops its last column, `git log --oneline` breaks a
 * subject line mid-word, a URL or a sha256 or a `/very/long/path` folds at whatever character happens
 * to sit at the margin, and a table's alignment is gone. None of that is recoverable afterwards,
 * because the newline the server sent is indistinguishable from one the user asked for.
 *
 * Asking for eighty instead - the width every command-line program on earth is written for - moves the
 * decision back to where it can be undone: the lines arrive whole, and the part that does not fit on
 * screen is wrapped at a word boundary by [dev.eclipse.ssh.terminal.terminalLayout], or panned to on the
 * alternate screen where wrapping would move a full-screen program's rows. Height is the other axis and
 * has its own two functions, [atLeastRows] and [atMostRows], because a floor is not the only thing a
 * height can be: the app-wide setting raises it, a host's own choice lowers it, and the screen decides
 * nothing at all.
 *
 * Bounded by the same range the pty and the buffer accept, so a stored setting can never ask for a
 * grid that one of them would silently refuse.
 */
fun TerminalGrid.atLeastColumns(minColumns: Int): TerminalGrid {
    val wanted = minColumns.coerceAtMost(TERMINAL_COLUMN_RANGE.last)
    return if (wanted <= columns) this else copy(columns = wanted)
}

/**
 * The same grid, made at least [minRows] rows tall.
 *
 * This is what makes the taller choices mean anything, and it is the width rule applied to the other
 * axis. A height used to be a ceiling - the screen cut it down to what fits, on the reasoning that a
 * row past the bottom edge is nowhere while a column past the right edge can be panned to. The second
 * half of that is still true, and is why a *host's* height stays a ceiling; what it got wrong was
 * concluding that a taller terminal is therefore pointless. A pty told it has 1000 rows is one whose
 * `ls` prints a thousand entries before it pages, whose `apt-get` output stays in the app's scrollback
 * instead of being paged away by the program, and whose `less` pages a thousand lines at a time - and
 * what the user reads is the window around the cursor, because `AnsiTerminalBuffer.frame` anchors
 * there and the scroll gesture walks back through the rest. Nothing is drawn past the bottom edge:
 * the view draws the height it has, and the pty is simply told a bigger number than that.
 *
 * Bounded by the same range the pty and the buffer accept, so a stored setting can never ask for a
 * grid that one of them would silently refuse.
 */
fun TerminalGrid.atLeastRows(minRows: Int): TerminalGrid {
    val wanted = minRows.coerceAtMost(TERMINAL_ROW_RANGE.last)
    return if (wanted <= rows) this else copy(rows = wanted)
}

/**
 * The narrowest grid to give a host's pty: the wider of the app-wide floor and this host's own choice.
 *
 * A per-host width has to be a floor *here*, at the view, and not only an argument to the pty when the
 * shell opens. The viewport is reported on every measurement pass and each report resizes the pty, so a
 * width applied at open time and nowhere else survives exactly until the first frame is measured - which
 * is a few milliseconds later, before any output has arrived. The setting looked as if it did nothing.
 *
 * The wider of the two rather than the more specific of the two, because both are floors and neither is
 * a ceiling: the app-wide setting says how narrow a terminal the user is willing to read anywhere, and
 * the host's says this particular server needs at least that much to format itself.
 */
internal fun minTerminalColumns(settingColumns: Int, hostColumns: Int): Int =
    maxOf(settingColumns.coerceAtLeast(0), hostColumns.coerceAtLeast(0))

/**
 * The same grid, shortened to a host's chosen height when that is shorter than the app-wide one.
 *
 * A host's `Rows` field is the one place a per-host value is a ceiling rather than a floor, and it is
 * the only way to tell a particular server that the window is short - which some programs want to know,
 * and which no app-wide setting can express without shortening every host. So it only ever lowers: a
 * host asking for fewer rows than the floor gets exactly what it asked for, and a host asking for more
 * is left at the floor, because a value that raised the height here would be a per-host copy of the
 * app-wide setting and would silently override it.
 *
 * Only the grid is affected. What the *pty* is told is a separate decision made at the view, where an
 * alternate screen asks for the on-screen height instead - see [TerminalViewport].
 *
 * Zero means "no opinion", the same sentinel the stored setting uses.
 */
fun TerminalGrid.atMostRows(hostRows: Int): TerminalGrid =
    if (hostRows in 1 until rows) copy(rows = hostRows) else this

/** Remembers the cell metrics for [style], measuring a run of glyphs rather than a single one. */
@Composable
fun rememberTerminalCellMetrics(style: TextStyle, measurer: TextMeasurer = rememberTextMeasurer()): TerminalCellMetrics =
    remember(style, measurer) {
        val measured = measurer.measure(AnnotatedString(MEASURE_RUN), style)
        TerminalCellMetrics(
            width = measured.size.width.toFloat() / MEASURE_RUN.length,
            height = measured.size.height.toFloat(),
            baseline = measured.firstBaseline,
        )
    }

private const val MEASURE_RUN = "MMMMMMMMMMMMMMMMMMMM"

/**
 * The gap between the terminal text and the edges of the window it is drawn in: one percent of the
 * screen, on all four sides.
 *
 * Proportional to the screen, because the pair of fixed values it replaces could not be. 8dp either
 * side was 4.4% of a 360dp phone and 1.5% of a tablet, so the same constant was a wide gutter on the
 * device with the fewest columns to spare and a hairline on the one with the most - on a phone it spent
 * nearly two columns of a 46-column window on blank space. The vertical 4dp was the opposite mistake,
 * thin enough that the last line's descenders ran into the key row below it.
 *
 * One percent of the *shorter* edge, and the same value on every side, so the frame is even. Measured
 * per axis instead, a phone would get 3.6dp across and 8dp down - a gap that looks like a mistake
 * rather than a margin, and one that spends the dimension the terminal has least of: the tab strip and
 * the key row already take a fixed bite out of the height, and 1% of the long edge is where the
 * percentage costs a whole row of output.
 *
 * [screenWidthDp] and [screenHeightDp] come from the window configuration, which reports 0 for a window
 * that has not been measured yet; the floor keeps that from becoming a negative padding, which Compose
 * rejects at runtime.
 */
fun terminalTextInset(screenWidthDp: Int, screenHeightDp: Int): Dp =
    (minOf(screenWidthDp, screenHeightDp) * TEXT_INSET_FRACTION).dp.coerceAtLeast(0.dp)

private const val TEXT_INSET_FRACTION = 0.01f

/**
 * Draws a [TerminalFrame] and turns touches into terminal input.
 *
 * A `Canvas` rather than a `LazyColumn` of `Text`, and that is the whole point of this file. The
 * previous renderer built one `AnnotatedString` per line and handed each to a `Text` composable, so
 * every frame - thirty a second while output is arriving - re-ran Compose layout for the whole
 * viewport and rebuilt a span tree per row. On a low-end phone that alone kept a core busy. Drawing
 * measures one run of identically-styled cells at a time and paints it in a single call, which for
 * ordinary output is a handful of draws per line and no composition at all.
 *
 * The frame is a plain value, so recomposition is driven by [TerminalFrame.revision] rather than by a
 * deep comparison of a quarter of a million cells.
 *
 * Rows are drawn through [dev.eclipse.ssh.terminal.terminalLayout] rather than one per buffer line, so a
 * line wider than the window occupies as many rows as it needs, broken at word boundaries. Every
 * coordinate the view deals in - the touch that starts a selection, the tint that shows it, the cursor,
 * the pan extent - goes through that same mapping, which is why it is one pure function and not a
 * sprinkling of arithmetic.
 *
 * Gestures, because a terminal has to spend them carefully. A drag scrolls - vertically through the
 * scrollback and horizontally across whatever is still wider than the screen, which after wrapping is
 * the alternate screen and the occasional unbreakable token. Selection is therefore behind a long press: the press alone selects the word under
 * the finger, and holding and then dragging extends the selection by cell. A plain drag used to start
 * a selection, which meant a terminal that could not be scrolled with a finger at all - every attempt
 * painted a selection and put a fragment of a line on the clipboard instead.
 *
 * @param minColumns the narrowest grid to give the pty regardless of how many columns fit on screen;
 *   0 fits the screen exactly. See [atLeastColumns] and [minTerminalColumns].
 * @param minRows the shortest grid to give the terminal regardless of how many rows fit on screen; 0
 *   fits the screen exactly. See [atLeastRows]. The view draws the rows it has either way - a taller
 *   grid is one the pty was told about, not one that is painted - so the extra height buys scrollback
 *   the shell will not page away, not pixels.
 * @param hostRows the height this host asked for, which can only shorten the grid; 0 has no opinion.
 *   See [atMostRows].
 * @param onScroll called with a line delta; positive scrolls back into the history.
 * @param onSelectionChange the live drag selection, in absolute buffer lines, or null when cleared.
 * @param composing the IME's provisional text - typed, not committed, and therefore not on the far end
 *   of the pty either. Drawn at the cursor by [composingPreview], which is the one place it can be
 *   drawn from: the grid does not carry it, and nothing else has the cell it belongs in.
 */
@Composable
fun TerminalView(
    frame: TerminalFrame,
    style: TextStyle,
    background: Color,
    foreground: Color,
    metrics: TerminalCellMetrics,
    modifier: Modifier = Modifier,
    minColumns: Int = 0,
    minRows: Int = 0,
    hostRows: Int = 0,
    selection: TerminalSelection? = null,
    onSelectionChange: (TerminalSelection?) -> Unit = {},
    onSelectionFinished: (TerminalSelection) -> Unit = {},
    onScroll: (Int) -> Unit = {},
    /**
     * A tap, reported as the cell it landed on rather than as a bare event.
     *
     * The cell is what tells a hyperlink from ordinary text, and the view is the only thing that can
     * work it out: the position is in pixels and the mapping to a buffer line goes through the layout,
     * the pan and the drag shift. The caller does the rest - follow the link, drop a selection, raise
     * the keyboard - because what a tap means is the screen's decision and not the grid's.
     */
    onTap: (line: Int, column: Int) -> Unit = { _, _ -> },
    onViewportChange: (TerminalViewport) -> Unit = {},
    onLongPressCell: (line: Int, column: Int) -> Unit = { _, _ -> },
    /**
     * Reports a pinch as a font-size scale factor relative to the size in force when the gesture
     * started. The view does not own the font size - the setting does - so it reports the factor and
     * lets the caller decide what size that lands on and when to persist it.
     */
    onZoom: (scale: Float, ended: Boolean) -> Unit = { _, _ -> },
    /**
     * A vertical drag on a screen whose program is reading the mouse, as wheel reports.
     *
     * [count] reports rather than one, because a step of the drag crosses a line and a fast drag
     * crosses several. The cell is where the finger is: `tmux` scrolls the pane under the pointer and
     * `htop` the panel under it, so the coordinate is not decoration.
     */
    onMouseWheel: (up: Boolean, column: Int, row: Int, count: Int) -> Unit = { _, _, _, _ -> },
    /**
     * A vertical drag on the alternate screen of a program that is *not* reading the mouse, as arrow
     * key presses.
     *
     * This is xterm's alternate-scroll behaviour, and it is the only thing that can move a program
     * which has taken the screen and left no history behind it. It is deliberately not sent off the
     * alternate screen, where there is a scrollback to move instead and where an arrow key arriving
     * from a drag would be typed at the shell.
     */
    onArrowKeys: (up: Boolean, count: Int) -> Unit = { _, _ -> },
    composing: String = "",
) {
    val measurer = rememberTextMeasurer()
    // How wide each distinct cluster draws, kept across frames. Keyed on the style and the metrics
    // because both change which face draws a glyph and therefore what it measures.
    val clusters = remember(style, metrics) { ClusterWidthCache() }
    val density = LocalDensity.current
    // Whether the cursor is in its "on" half-period. A [mutableStateOf] read from inside the draw
    // lambda rather than from the composable body, so a blink invalidates the draw phase only - the
    // same trick the pan uses. Read as `.value` up here it would recompose the whole grid twice a
    // second, which on a phone is a measurable amount of work to make a rectangle flash.
    val cursorOn = remember { mutableStateOf(true) }
    // Focus, not lifecycle: a terminal behind another app, behind the lock screen, or in the recents
    // list has nothing to blink at, and the loop below is the kind of 2 Hz timer that costs nothing
    // per tick and a noticeable amount of battery per night. Window focus covers all three, and unlike
    // a lifecycle observer it needs no extra dependency here.
    val focused = LocalWindowInfo.current.isWindowFocused
    val cursorColour = remember(foreground) { foreground.copy(alpha = 0.85f) }
    val selectionColour = remember(foreground) { foreground.copy(alpha = 0.30f) }
    // The callbacks are read from inside long-lived pointer and scroll handlers, which are keyed on
    // the metrics rather than on the lambdas; without this a recomposition would leave them calling
    // the previous frame's closures and selection would be computed against stale coordinates.
    val currentFrame by rememberUpdatedState(frame)
    val scrollCallback by rememberUpdatedState(onScroll)
    val selectionCallback by rememberUpdatedState(onSelectionChange)
    val selectionFinished by rememberUpdatedState(onSelectionFinished)
    val longPress by rememberUpdatedState(onLongPressCell)
    val tap by rememberUpdatedState(onTap)
    val viewportChange by rememberUpdatedState(onViewportChange)
    val zoom by rememberUpdatedState(onZoom)
    val mouseWheel by rememberUpdatedState(onMouseWheel)
    val arrowKeys by rememberUpdatedState(onArrowKeys)

    BoxWithConstraints(modifier.background(background)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // Two grids, and the difference between them is the whole feature. `visible` is what fits on
        // screen; `grid` is what the pty is told it has, which is at least as wide and usually as tall.
        // They are the same object whenever both settings are "fit screen".
        val visible = remember(metrics, widthPx, heightPx) { metrics.gridIn(widthPx, heightPx) }
        val grid = remember(visible, minColumns, minRows, hostRows) {
            visible.atLeastColumns(minColumns).atLeastRows(minRows).atMostRows(hostRows)
        }
        // What the pty is told it has, which is the grid except while a program is painting the screen.
        //
        // `vim`, `htop`, `less` and `nano` address every cell positionally and draw their own borders
        // and status lines for the height they were told; a thousand-row `vim` on a forty-row screen is
        // a status line nobody can see and a body of text whose bottom half does not exist. The primary
        // screen has no such problem, because a shell's output flows and the window around the cursor is
        // what the reader looks at - so the two cases get different numbers, which is the same
        // distinction [dev.eclipse.ssh.terminal.terminalLayout] already draws for wrapping.
        //
        // Bounded by the grid as well as by the screen, so a host that asked for a short window keeps
        // it: capping only at the screen would let a full-screen program be told forty rows on a host
        // explicitly configured for thirty.
        val ptyRows = if (frame.alternateScreen) minOf(visible.rows, grid.rows) else grid.rows
        val origin = remember(visible) { Offset(visible.originX, visible.originY) }
        val viewport = TerminalViewport(
            columns = grid.columns,
            ptyRows = ptyRows,
            bufferRows = grid.rows,
            visibleRows = visible.rows,
            visibleColumns = visible.columns,
        )
        // Reported on every size change, including the one the software keyboard causes: the pty has
        // to know the window it is drawing into or a full-screen program wraps its own status line.
        // Keyed on the whole value, so entering and leaving an alternate screen reports the changed
        // height too - that flip is a resize as far as the far end is concerned.
        LaunchedEffect(viewport) { viewportChange(viewport) }

        // How far the grid is panned to the left, in pixels. A float rather than a column index so a
        // drag moves smoothly instead of snapping a character at a time, and a `MutableFloatState`
        // rather than a plain `var` so the draw below re-runs on a pan without recomposing anything.
        val pan = remember { mutableFloatStateOf(0f) }
        val visibleWidthPx = visible.columns * metrics.width
        // Where every row of this frame goes once lines too wide for the window have been wrapped at a
        // word boundary. Keyed on everything the answer is made of - see [layoutKey] - rather than on the
        // frame itself, whose equality is a comparison of several thousand cells.
        //
        // The height here is the *visible* one. A tall grid is a taller pty, not a taller screen, and the
        // frame the buffer hands over is already the window around the cursor - laying it out for a
        // thousand rows would wrap and bottom-anchor against space that is not on the phone.
        val layout = remember(layoutKey(frame, visible.columns, visible.rows)) {
            terminalLayout(frame, visible.columns, visible.rows)
        }
        // Read from the long-lived gesture handlers, which are keyed on the metrics and would otherwise
        // map a touch through the layout of whichever frame was on screen when they were installed.
        val currentLayout by rememberUpdatedState(layout)
        // From the layout, not the frame: after wrapping, the frame's widest line is no longer drawn as
        // one row, and panning to reach a column that is now on the row below would only lose the text.
        val maxPan = maxPanPx(layout.contentColumns, visible.columns, metrics.width)
        // Whether the display wraps, which is also the answer to "is the cursor the insertion point".
        // A shell writing flowed output puts the cursor exactly where the next character goes; a screen
        // some program painted positionally parks it wherever it likes, and drawing the user's
        // half-typed word there would put text in the middle of a panel. See [composingPreview].
        val wraps = wrapsForDisplay(frame.alternateScreen, frame.positionalScreen, visible.columns)

        // Follows the cursor, which is the one thing that must never be off-screen: with an 80-column
        // grid on a 45-column phone, typing a long command would otherwise run out of sight and the
        // user would be editing a line they cannot see. Keyed on the cursor rather than on the frame,
        // so this settles once per cursor move and a manual pan is left alone until the cursor moves
        // again - and re-clamped here too, because a narrower frame can strand an old pan past its end.
        //
        // The clock read and the distance are here to answer one question between them, and the answer
        // decides whether this follow can be tweened: see [followShouldAnimate]. A tween cannot be asked
        // for on every cursor move, because a program printing a long line moves the cursor every frame,
        // and a tween that is restarted before it finishes never arrives - it leaves the pan short of the
        // cursor, which is the newest characters and the cursor itself drawn off the right edge for as
        // long as the output lasts. Nor for a move of more than one cell, which is a jump rather than the
        // keystroke crossing the edge the tween was added for, and a jump spread over the tween's
        // duration is the cursor off the window for exactly that long. The one slot is a plain array
        // rather than snapshot state because no composition and no draw reads it.
        val lastFollowNanos = remember { longArrayOf(0L) }
        LaunchedEffect(layout.cursorColumn, layout.cursorRowInk, layout.cursorRow, maxPan, visible.columns, metrics.width) {
            val target = panForCursor(
                current = pan.floatValue,
                // The cursor's column *within its row*. On a wrapped line the two differ, and following
                // the line's column would pan a window whose text is already on screen.
                cursorColumn = layout.cursorColumn,
                // And this is what the cursor is read as once a program has parked it past the row's
                // text - see [panForCursor]. Keyed above, because deleting text moves the row's end
                // without moving the cursor.
                cursorRowInk = layout.cursorRowInk,
                cursorOnScreen = layout.cursorRow >= 0,
                cellWidth = metrics.width,
                visibleWidthPx = visibleWidthPx,
                maxPan = maxPan,
            )
            val now = System.nanoTime()
            val sinceLastFollow = now - lastFollowNanos[0]
            lastFollowNanos[0] = now
            if (target == pan.floatValue) return@LaunchedEffect
            // How far the window has to travel, in cells, because that is what decides whether this is
            // the one-cell courtesy the tween is for or a jump it must not spread out: see
            // [followShouldAnimate]. The distance is asked for rather than worked out here, so its two
            // edges - an unmeasured cell and the float arithmetic of a one-cell move - are the pure
            // function's business and are tested with it.
            val cellsMoved = cellsBetween(pan.floatValue, target, metrics.width)
            if (!followShouldAnimate(sinceLastFollow, cellsMoved)) {
                pan.floatValue = target
                return@LaunchedEffect
            }
            // Animated rather than assigned. The assignment moved the whole screen sideways in a single
            // frame every time the cursor crossed the right edge, which is the other half of what a
            // terminal that "jumps while typing" is. Linear and short on purpose: the follow restarts on
            // every cursor move, and an eased tween would spend its slow beginning again each keystroke.
            animate(
                initialValue = pan.floatValue,
                targetValue = target,
                animationSpec = tween(durationMillis = PAN_FOLLOW_MS, easing = LinearEasing),
            ) { value, _ -> pan.floatValue = value }
        }

        // Keyed on the cursor's position as well as on whether it exists, so every move restarts the
        // loop at the start of its "on" half-period: a cursor that happened to be in its dark phase when
        // the user pressed a key would otherwise be invisible at the moment they looked for it. While
        // output is streaming the restart happens per frame and the cursor simply stays solid, which is
        // what every other terminal does too.
        LaunchedEffect(frame.cursorVisible, frame.cursorBlink, focused, layout.cursorRow, layout.cursorColumn) {
            if (!cursorBlinks(frame.cursorVisible, frame.cursorBlink, focused)) {
                // Solid, not dark. A cursor frozen mid-blink reads as a rendering bug, and a terminal
                // whose window is not focused should still show where the caret is. `ESC [ ? 12 l` asks
                // for the same thing deliberately.
                cursorOn.value = true
                return@LaunchedEffect
            }
            while (true) {
                cursorOn.value = true
                delay(CURSOR_BLINK_MS)
                cursorOn.value = false
                delay(CURSOR_BLINK_MS)
            }
        }

        // What one step costs, in pixels, one price per direction. The window's top edge does not move
        // the same distance both ways - at the live end a step back hands the prompt's row to the
        // background while the top stays pinned - so a single price would translate the screen and then
        // snap it back on the first drag of a session. Measured from the buffer rather than assumed from
        // the top line's height, because that height is not the distance the top moves at all.
        //
        // Read from a gesture that outlives the frame it was installed with, hence the state wrappers.
        val currentBackPx by rememberUpdatedState(currentFrame.stepRowsBack * metrics.height)
        val currentForwardPx by rememberUpdatedState(currentFrame.stepRowsForward * metrics.height)

        // Where the finger is, in view pixels, for the wheel reports below. Filled by the gesture
        // handler rather than by a pointer observer of its own, and a bare FloatArray rather than
        // snapshot state: it is read from a drag callback, never from a composition, so writing it per
        // move must not invalidate anything.
        val pointerAt = remember { FloatArray(2) }

        // Fractional travel is accumulated rather than discarded, so a slow drag is not dead, and a
        // fast one is reported as one delta the caller publishes once instead of once per crossing.
        val scrollAccumulator = remember { ScrollAccumulator() }

        // How far the window is drawn from where it rests, in pixels, positive meaning the content has
        // moved down. A float read inside the draw phase, like the pan, so a drag of less than a step
        // costs a redraw rather than a recomposition or a published frame.
        val scrollShift = remember { mutableFloatStateOf(0f) }
        // The steps this view has sent and not yet seen come back in a frame, and the offset the last
        // frame it looked at carried. Bare IntArrays, not snapshot state: written from the gesture and
        // read from the frame effect, never from a composition, so a drag must not invalidate anything
        // by moving them.
        val owedSteps = remember { IntArray(1) }
        val drawnOffset = remember { IntArray(1) }

        val scrollState = rememberScrollableState { delta ->
            // The drag's delta is positive toward the live end; the accumulator's is positive into the
            // history, which is the sign `onScroll` documents - and the sign this handler used before
            // the accumulator existed, when the remainder arithmetic cancelled out to the same thing.
            val backPx = currentBackPx
            val forwardPx = currentForwardPx
            // Where the drag goes is asked before the step is charged rather than when the gesture
            // starts: a program can turn its mouse reporting on mid-drag, and the answer is read from
            // the frame being drawn so the two can never disagree about who owns the screen.
            val target = dragTarget(currentFrame.mouseMode, currentFrame.mouseSgr, currentFrame.alternateScreen)
            var steps = scrollAccumulator.dragBy(-delta, backPx, forwardPx)
            if (steps == 0 && target == DragTarget.Scrollback) {
                // Guarded by the offset this view believes it is at, not by the one the frame carries:
                // frames arrive through a StateFlow, so the frame can still be one publication behind
                // and an ungated commit would take the same end twice.
                steps = freeStep(
                    scrollAccumulator.pendingPx,
                    backPx,
                    forwardPx,
                    currentFrame.scrollOffset + owedSteps[0],
                    currentFrame.scrollCeiling,
                )
            }
            // Only the scrollback draws a shift: a wheel report and an arrow key are answered by the
            // program scrolling its own screen, and translating this one would slide the picture out
            // from under the answer it is about to receive.
            scrollShift.floatValue =
                if (target == DragTarget.Scrollback) scrollShiftPx(scrollAccumulator.pendingPx, backPx, forwardPx)
                else 0f
            if (steps != 0) {
                owedSteps[0] += steps
                val up = steps > 0
                when (target) {
                    DragTarget.Scrollback -> scrollCallback(steps)
                    // Reported where the finger is, from the last event the gesture handler saw - a
                    // wheel belongs to the pane or panel under the pointer, not to the one the cursor
                    // happens to be on.
                    DragTarget.Wheel -> {
                        val (column, row) = Offset(pointerAt[0], pointerAt[1])
                            .toScreenCell(metrics, origin, pan.floatValue, visible.columns, visible.rows)
                        mouseWheel(up, column, row, abs(steps))
                    }
                    DragTarget.Arrows -> arrowKeys(up, abs(steps))
                }
            }
            delta
        }

        // A zoom re-prices every pixel the accumulator is holding - the travel was measured against rows
        // of a different height - so it goes, along with the shift it was drawing.
        LaunchedEffect(metrics.height) {
            owedSteps[0] = 0
            scrollAccumulator.reset()
            scrollShift.floatValue = 0f
        }

        // A frame that is not the one this view's own steps add up to came from somewhere else: a page
        // key, the scrollback badge, a re-clamp on resize. The travel kept for a step of the old picture
        // is not the new picture's to spend, so it goes, along with the shift it was drawing.
        //
        // A frame that moved the window the same way this view's own steps still owed it is those steps
        // coming back, however many of them a single publication carried - and a frame where a session
        // printing output has pinned the offset alongside them is one of those too, which is why the
        // test is on the direction rather than on the distance. A frame that did not move the window at
        // all says nothing about who moved it, so it is left alone rather than taken as an answer.
        LaunchedEffect(frame.scrollOffset) {
            val moved = frame.scrollOffset - drawnOffset[0]
            if (moved == 0) return@LaunchedEffect
            drawnOffset[0] = frame.scrollOffset
            val owed = owedSteps[0]
            if (owed != 0 && (moved > 0) == (owed > 0)) {
                owedSteps[0] = if (abs(moved) >= abs(owed)) 0 else owed - moved
                // Re-priced against the frame that just landed: a free step taken against the live end
                // changes what the next one costs, and the finger may be still - so the picture has to
                // move to meet it now rather than at the next pointer event.
                scrollShift.floatValue =
                    scrollShiftPx(scrollAccumulator.pendingPx, currentBackPx, currentForwardPx)
            } else {
                owedSteps[0] = 0
                scrollAccumulator.reset()
                scrollShift.floatValue = 0f
            }
        }

        // Fling over pixels through that same accumulator. The room left is the buffer's own answer -
        // how far it has been scrolled and how far it can go - turned into pixels by the price of the
        // line the fling is about to cross, so the decay ends where the scrollback does.
        //
        // Only the scrollback flings. A wheel report is one discrete event that the program answers
        // with its own scrolling, so a decaying burst of them after the finger lifts would overshoot
        // whatever the program was going to do; and on the alternate screen there is nothing behind the
        // screen to fling through, which is why the room is zero there anyway.
        //
        // `exponentialDecay` and not `rememberSplineBasedDecay`: Compose 1.12 no longer publishes the
        // spline spec, or its density-aware ancestor, at all - the platform's own fling keeps it private
        // to foundation. What matters here is the unit, not the curve - the decay runs in the same
        // pixels the drag does, so a flick carries on at the rate the finger left it whatever the line
        // heights are - and `exponentialDecay` is the one public spec that answers in those units.
        val fling = remember {
            TerminalFlingBehavior(exponentialDecay<Float>()) { velocity ->
                val flings = dragTarget(
                    currentFrame.mouseMode,
                    currentFrame.mouseSgr,
                    currentFrame.alternateScreen,
                ) == DragTarget.Scrollback
                when {
                    !flings -> 0f
                    // The velocity arrives in the drag's convention, not the accumulator's: negative is
                    // towards the history, where the room left is what the ceiling still has above the
                    // offset - priced at the step the fling is about to cross, which is the one that
                    // direction is charged for, and at a cell where that step is free.
                    velocity < 0f -> flingRoomPx(
                        currentFrame.scrollCeiling - currentFrame.scrollOffset,
                        currentBackPx,
                        metrics.height,
                    )
                    else -> flingRoomPx(currentFrame.scrollOffset, currentForwardPx, metrics.height)
                }
            }
        }

        // Pixels, not columns, and clamped rather than accumulated: a pan has no rounding to carry
        // because the offset it produces is the offset that is drawn. Reporting only what was actually
        // consumed is what lets a drag past either end hand the gesture back instead of swallowing it.
        val panState = rememberScrollableState { delta ->
            val before = pan.floatValue
            val after = (before - delta).coerceIn(0f, maxPan)
            pan.floatValue = after
            before - after
        }

        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .scrollable(scrollState, Orientation.Vertical, reverseDirection = true, flingBehavior = fling)
                .scrollable(panState, Orientation.Horizontal)
                // First in the chain and therefore outermost, but it only ever consumes once a second
                // finger is down, so the single-finger handlers below - tap, long press, scroll, pan -
                // are untouched. A two-finger gesture is not something any of them can serve: vertical
                // `scrollable` would read the pinch as a scroll and throw the scrollback around while
                // the user was trying to make the text bigger.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var scale = 1f
                        var pinching = false
                        do {
                            val event = awaitPointerEvent()
                            // Recorded on the way past, whatever the gesture turns out to be: a wheel
                            // report needs the cell the finger is over, and this loop is the only place
                            // a pointer position is read for the whole of a drag. Consumption does not
                            // remove the position, so the pinch below can still have the event.
                            event.changes.lastOrNull()?.let {
                                pointerAt[0] = it.position.x
                                pointerAt[1] = it.position.y
                            }
                            val down = event.changes.count { it.pressed }
                            if (down >= 2) {
                                pinching = true
                                val step = event.calculateZoom()
                                if (step != 0f && step != 1f) {
                                    scale *= step
                                    zoom(scale, false)
                                }
                                // Consumed only while two fingers are down, which is what stops the
                                // scroll handlers from also acting on the same pointers.
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) zoom(scale, true)
                    }
                }
                .pointerInput(metrics, origin) {
                    detectTapGestures(
                        onTap = { position ->
                            val (line, column) =
                                position.toCell(currentFrame, currentLayout, metrics, origin, pan.floatValue, scrollShift.floatValue)
                            tap(line, column)
                        },
                        onLongPress = { position ->
                            val (line, column) =
                                position.toCell(currentFrame, currentLayout, metrics, origin, pan.floatValue, scrollShift.floatValue)
                            longPress(line, column)
                        },
                    )
                }
                .pointerInput(metrics, origin) {
                    var anchor: TerminalSelection? = null
                    var moved = false
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            // Anchored but deliberately not published. The long press above has already
                            // selected the word under the finger, and replacing that with a one-cell
                            // selection before the user has moved would undo it in front of them.
                            moved = false
                            val (line, column) =
                                position.toCell(currentFrame, currentLayout, metrics, origin, pan.floatValue, scrollShift.floatValue)
                            anchor = TerminalSelection.at(line, column)
                        },
                        onDrag = { change, _ ->
                            moved = true
                            val (line, column) =
                                change.position.toCell(currentFrame, currentLayout, metrics, origin, pan.floatValue, scrollShift.floatValue)
                            anchor = anchor?.movedTo(line, column)?.also(selectionCallback)
                        },
                        onDragEnd = { if (moved) anchor?.let(selectionFinished) },
                        onDragCancel = { if (moved) selectionCallback(null) },
                    )
                }
                .drawBehind {
                    // Erased first, and erased here rather than by anything above: this is the node that
                    // paints the frame, so it is the node that has to clear it.
                    //
                    // What it clears is the scope - the whole area the grid can reach - and it is drawn
                    // *outside* the translate below on purpose, so a panned grid cannot leave the strip
                    // it moved off uncovered. [drawFrame] paints a rectangle only where a cell's own style
                    // sets a background, and glyphs only where there is text, so without this line every
                    // other column and every row the fitted window leaves over at the bottom is left to
                    // whatever the parent happened to paint - and a terminal that draws its rows shorter
                    // than the last frame drew them (which is the common case: [terminalLayout] drops
                    // trailing blanks) then keeps the tail of the longer line it replaced. "Something
                    // above me is opaque" is not a contract this view can hold, and the one time it is
                    // not honoured the artifact is invisible to every test in this repository.
                    drawRect(background)
                    // Translated rather than drawn from the corner, so the half-cell that does not
                    // divide evenly is shared between the two edges - see [TerminalCellMetrics.gridIn].
                    // The pan is part of the same translate, which is why panning costs a redraw and
                    // not a recomposition.
                    translate(visible.originX - pan.floatValue, visible.originY) {
                        // Clipped to the rows the grid was measured for, top and bottom. The grid is
                        // centred in the view, so there is up to half a cell of background above and
                        // below it, and a sub-row drag slides rows across those edges: without the clip
                        // the row leaving the top would paint into the band above the grid and the row
                        // entering at the bottom would paint past the last row the pty knows about.
                        //
                        // Horizontal bounds are the scope's own, restated in the coordinates the
                        // translate has moved to, so nothing that was on screen before is cut off - the
                        // clip is about the vertical band and only about that.
                        val gridX = visible.originX - pan.floatValue
                        clipRect(
                            left = -gridX,
                            top = 0f,
                            right = size.width - gridX,
                            bottom = visible.rows * metrics.height,
                        ) {
                            drawFrame(
                                frame = currentFrame,
                                layout = currentLayout,
                                measurer = measurer,
                                style = style,
                                foreground = foreground,
                                background = background,
                                metrics = metrics,
                                clusters = clusters,
                                // The rows there is room for, not the rows the pty was told about: a tall
                                // grid is a taller pty, and a row below the bottom edge is a row of cells
                                // painted where nobody can see them.
                                maxRows = visible.rows,
                                selection = selection,
                                selectionColour = selectionColour,
                                cursorColour = cursorColour,
                                cursorOn = cursorOn.value,
                                composing = composing,
                                wraps = wraps,
                                // Read here, in the draw phase, so a drag's fraction of a row costs a
                                // redraw and nothing else.
                                shiftPx = scrollShift.floatValue,
                            )
                        }
                    }
                },
        )
    }
}

/**
 * Maps a touch to an absolute buffer coordinate, clamped to the frame it landed on.
 *
 * [origin] is where the grid actually starts, which is not the corner of the view: without subtracting
 * it, a tap near the left edge selects the character to its left and a long press picks the wrong word,
 * because the pixels the grid was shifted by are counted as part of the first cell.
 *
 * [pan] is added back for the same reason in the other axis - on a grid panned halfway across, the
 * character under the finger is not the one the untranslated coordinate names. It is passed as a value
 * read at the moment of the touch rather than keyed into the gesture handler, because keying a
 * long-lived pointer handler on something that changes at frame rate restarts it mid-gesture.
 *
 * [shift] is taken off for the same reason again, vertically: while a drag is in flight the whole strip
 * is drawn displaced, and a mapping that ignored it would select the row above the one under the finger
 * for the whole of the gesture. See [touchedRow], which also takes off the rows above the window - those
 * belong to the margins and not to the window, so they are not rows a selection can name.
 */
private fun Offset.toCell(
    frame: TerminalFrame,
    layout: TerminalLayout,
    metrics: TerminalCellMetrics,
    origin: Offset,
    pan: Float = 0f,
    shift: Float = 0f,
): Pair<Int, Int> {
    val row = touchedRow(y, origin.y, metrics.height, shift, layout.marginAbove.size, layout.rows.size)
    val column = if (metrics.width <= 0f) 0 else floor((x + pan - origin.x) / metrics.width).toInt()
    // Through the layout, because the row under the finger is a *visual* row: on a wrapped line the
    // third row of the screen can be the second half of the second line of output, and a selection
    // addressed by screen row would copy text from somewhere else entirely.
    val visual = layout.rows.getOrNull(row)
        ?: return frame.firstLine to column.coerceAtLeast(0)
    return (frame.firstLine + visual.line) to (visual.from + column).coerceAtLeast(0)
}

/**
 * Where a pointer is, as the 1-based cell of the screen a program drew - which is what a mouse report
 * carries.
 *
 * The screen cell and not the buffer coordinate [toCell] answers with. A program reading the mouse has
 * never seen the scrollback: it is being told which of *its own* cells the wheel is over, and a row
 * counted from the first line the session ever wrote would name a cell it does not have. Every screen
 * that reports the mouse is positional - [dev.eclipse.ssh.terminal.terminalLayout] does not wrap one -
 * so the visual row the finger is on and the row the program drew are the same row.
 *
 * Clamped to the grid, so a finger just past the last row names the last row rather than a cell that
 * does not exist.
 */
private fun Offset.toScreenCell(
    metrics: TerminalCellMetrics,
    origin: Offset,
    pan: Float,
    columns: Int,
    rows: Int,
): Pair<Int, Int> {
    val row = if (metrics.height <= 0f) 0 else floor((y - origin.y) / metrics.height).toInt()
    val column = if (metrics.width <= 0f) 0 else floor((x + pan - origin.x) / metrics.width).toInt()
    return (column.coerceIn(0, (columns - 1).coerceAtLeast(0)) + 1) to
        (row.coerceIn(0, (rows - 1).coerceAtLeast(0)) + 1)
}

/**
 * How wide each distinct cluster draws, remembered across the frames of a session.
 *
 * Keyed on the cluster **and** the two attributes that decide which face draws it, because a mark the
 * regular weight carries at zero advance can be missing from the bold one - the same characters then
 * genuinely measure differently by weight, and one entry serving both would answer for the wrong face
 * half the time.
 *
 * The cache is what makes asking the question affordable. A screen of ordinary output asks about the
 * same few dozen clusters on every frame and measures each of them once, and since a cluster of the
 * bundled face measures exactly one cell, the overwhelmingly common answer is the one that leaves the
 * run whole - so the measurement costs a hash lookup and buys nothing but the certainty that the run
 * may be drawn as one string.
 */
private class ClusterWidthCache {
    private val widths = HashMap<String, Float>()

    /** Whether this cell's cluster draws from a face that advances it by exactly one cell. */
    fun spendsOneCell(cell: TerminalCell, textStyle: TextStyle, measurer: TextMeasurer, cellWidth: Float): Boolean {
        if (clusterIsCertainlyOneCell(cell.value, cell.combining)) return true
        val cluster = cell.value + cell.combining
        // A blank cell has no ink to misplace, and a space is the one glyph every face carries.
        if (cluster.isBlank()) return true
        val key = buildString(cluster.length + 2) {
            append(if (cell.style.bold) 'b' else '-')
            append(if (cell.style.italic) 'i' else '-')
            append(cluster)
        }
        val width = widths.getOrPut(key) {
            // Bounded, because a screen of text nobody types twice would otherwise grow this for as
            // long as the session lives. Emptying it costs one measurement per cluster to refill.
            if (widths.size >= MAX_CACHED_CLUSTERS) widths.clear()
            // Measured as a repeat for the same reason the cell itself is: one measurement rounds to a
            // whole pixel, which at a phone's font size is a fifth of the difference being looked for.
            val measured = measurer.measure(AnnotatedString(cluster.repeat(CLUSTER_MEASURE_REPEAT)), textStyle)
            measured.size.width.toFloat() / CLUSTER_MEASURE_REPEAT
        }
        return clusterFitsOneCell(width, cellWidth)
    }
}

/** Distinct clusters remembered before the cache is emptied and refilled. */
private const val MAX_CACHED_CLUSTERS = 512

/**
 * Paints one frame: backgrounds, then glyph runs, then the selection tint, then the cursor.
 *
 * Runs, not cells. Consecutive cells that share a style are measured and drawn as one string, so a
 * line of ordinary output is a single draw call and a colourful prompt is a few - against one call
 * per character, which is what made the old renderer's cost scale with the text rather than with the
 * styling. Trailing blanks are skipped entirely: most of a terminal grid is empty space.
 *
 * One row of [layout] per row of the screen, so which columns of which line are drawn where is decided
 * in one pure, tested function instead of here; this loop only paints what it is handed.
 *
 * [shiftPx] is how far the whole picture is drawn below where it rests, which is how a drag that has not
 * yet travelled a whole step moves the text under the finger. [TerminalFrame.marginAbove] and
 * [TerminalFrame.marginBelow] are the rows that shift uncovers at the two edges, and they are drawn at
 * their own off-screen row indices so that at a shift of zero they sit outside the grid and this paints
 * exactly the picture it painted before the margins existed.
 */
private fun DrawScope.drawFrame(
    frame: TerminalFrame,
    layout: TerminalLayout,
    measurer: TextMeasurer,
    style: TextStyle,
    foreground: Color,
    background: Color,
    metrics: TerminalCellMetrics,
    clusters: ClusterWidthCache,
    maxRows: Int,
    selection: TerminalSelection?,
    selectionColour: Color,
    cursorColour: Color,
    cursorOn: Boolean = true,
    composing: String = "",
    wraps: Boolean = false,
    shiftPx: Float = 0f,
) {
    val cellWidth = metrics.width
    val cellHeight = metrics.height
    if (cellWidth <= 0f || cellHeight <= 0f) return
    // Where the window sits among the layout's rows, in rows, and how many buffer lines are above it -
    // two different numbers as soon as a margin line wraps. The first places the margin, the second
    // names the buffer line a margin row came from; the window's own rows need neither because the
    // layout's `firstLine` already anchors them.
    val windowTop = layout.marginAbove.size
    val marginAboveLines = frame.marginAbove.size
    val shift = if (shiftPx.isFinite()) shiftPx else 0f

    // The rows a drag uncovers above and below the window are drawn by the window's own body, so the
    // wrap, the run-breaking and the combining marks are handled in one place instead of three.
    fun paintRow(line: List<TerminalCell>, visual: TerminalVisualRow, top: Float, absoluteLine: Int) {
        // The row's own columns, and only those: the layout has already dropped the trailing blanks and
        // decided where a line too wide for the window breaks. Bounded by the line again because the
        // layout and the frame are read at slightly different moments while output is arriving.
        val lastColumn = visual.to.coerceAtMost(line.size)
        // Damage erase in the spirit of libvterm's `damagerect`: a row the buffer reports as changed is
        // repainted end-to-end with the default background before its runs go down, so a shorter line
        // cannot keep the tail of the longer one it replaced even if the frame's global clear were ever
        // skipped or clipped. Rows outside the damage keep the old path untouched.
        val rowDirty = frame.damageFull || (frame.damageFrom != -1 && absoluteLine in frame.damageFrom..frame.damageTo)
        if (rowDirty) {
            drawRect(
                background,
                topLeft = Offset(0f, top),
                size = Size((visual.to - visual.from) * cellWidth, cellHeight),
            )
        } else if (lastColumn < line.size) {
            // Even without damage, the skipped trailing blanks must not show the previous frame's tail
            // where this row is narrower than the grid it replaced.
            drawRect(
                background,
                topLeft = Offset((lastColumn - visual.from) * cellWidth, top),
                size = Size((line.size - lastColumn) * cellWidth, cellHeight),
            )
        }
        var column = visual.from
        while (column < lastColumn) {
            val cell = line[column]
            val resolved = cell.style.resolve(foreground, background)
            // The face this cell is drawn from, decided before the run is delimited rather than by the
            // platform when the glyph turns out to be missing. See [terminalFaceFor].
            val face = terminalFaceFor(cell.value, cell.combining)
            val glyphStyle = style.merge(
                TextStyle(
                    color = resolved.foreground,
                    // No weight and no slant on a symbol cell: one symbol cut is bundled, so asking for
                    // bold would have Compose synthesise it by smearing the outline, and a synthesised
                    // glyph is the one thing that can move off the cell this renderer keeps it on.
                    fontWeight = if (face == TerminalFace.TEXT && cell.style.bold) FontWeight.Bold else null,
                    fontStyle = if (face == TerminalFace.TEXT && cell.style.italic) FontStyle.Italic else null,
                    fontFamily = face.family,
                    textDecoration = decorationFor(cell),
                ),
            )
            // A run is a stretch of cells drawn as one string, so it is a stretch of cells of one style
            // *and* one face: the two faces are different typefaces at different advances, and a string
            // drawn in one of them cannot carry a glyph that belongs to the other. The face is part of
            // the question `joins` answers, which is why the predicate is asked about it here.
            //
            // The link is part of it for the same reason and one more: a run's style is built once from
            // its first cell, and a linked cell is underlined where the program did not ask for it - so
            // a run spanning a link's edge would either underline the text after the link or leave the
            // link itself with no mark on it, depending on which side the run began.
            //
            // The other half is why the run has to end at all. A cluster the run's face does not carry
            // is drawn from a face further down the chain at that face's own advance, which is not the
            // cell's: inside a run it displaces every glyph after it, so the text slides along the row
            // while the cursor stays where the grid puts it, and the cursor is then not where it looks
            // like it is. Ending the run at such a cluster puts it, and everything after it, back on its
            // own cell. A cluster that measures one cell - which is every character of the face that
            // draws it, now that a symbol cell names a face that has the glyph - leaves the run whole,
            // so a line of ordinary output is still the single draw call it was.
            val end = glyphRunEnd(
                from = column,
                lastColumn = lastColumn,
                joins = {
                    line[it].style == cell.style &&
                        line[it].hyperlink == cell.hyperlink &&
                        terminalFaceFor(line[it].value, line[it].combining) == face
                },
                fitsOneCell = { clusters.spendsOneCell(line[it], glyphStyle, measurer, cellWidth) },
            )
            // A wide character's right half is a blank cell and belongs in that character's run. The
            // run's own background is painted before its text, so a run that began on the right half
            // would paint its background across the second column of the glyph - half a character, in
            // any program that colours the text after one.
            val runEnd = if (line[column].wide && end == column + 1 && end < lastColumn) end + 1 else end
            // A cell's ink is its base plus whatever zero-width characters were put on it, so a run is
            // built from the clusters and not from the bases. Appending the marks here is what lets the
            // font do the joining: the bundled face carries the combining marks at zero advance, so a
            // mark rides on the base's cell and the run still measures one cell per cell. Skipping them
            // would silently drop every accent and every zero-width joiner from what is drawn - and,
            // since a run is measured as a whole, it would also shift the ink of every glyph after the
            // one that lost its mark.
            val text = buildString(runEnd - column) {
                for (index in column until runEnd) {
                    append(line[index].value)
                    append(line[index].combining)
                }
            }
            // Relative to the row, not to the line: a wrapped continuation is drawn from the left margin.
            val left = (column - visual.from) * cellWidth
            val runWidth = (runEnd - column) * cellWidth
            if (resolved.background != null) {
                drawRect(resolved.background, topLeft = Offset(left, top), size = Size(runWidth, cellHeight))
            }
            if (text.isNotBlank()) {
                val glyphs = measurer.measure(AnnotatedString(text), glyphStyle)
                drawText(glyphs, topLeft = Offset(left, top))
            }
            column = runEnd
        }
        if (selection != null && absoluteLine in selection.startLine..selection.endLine) {
            // Clipped to this row's columns, so a line selected across a wrap is tinted on each of the
            // rows it occupies and on no part of the grid that holds none of it.
            val from = maxOf(selection.firstColumnOn(absoluteLine), visual.from)
            val to = minOf(selection.lastColumnOn(absoluteLine, frame.columns), visual.to - 1)
            if (to >= from) {
                drawRect(
                    selectionColour,
                    topLeft = Offset((from - visual.from) * cellWidth, top),
                    size = Size((to - from + 1) * cellWidth, cellHeight),
                )
            }
        }
    }

    // At a negative row index, so the strip it covers is exactly the one a shift towards the history
    // opens at the top edge; at rest it is off the top of the screen by its own height.
    layout.marginAbove.forEachIndexed { index, visual ->
        val line = frame.marginAbove.getOrNull(visual.line) ?: return@forEachIndexed
        paintRow(line, visual, (index - windowTop) * cellHeight + shift, frame.firstLine - marginAboveLines + visual.line)
    }
    layout.rows.forEachIndexed { row, visual ->
        // Bounded by the grid rather than by `size.height`: a draw modifier does not clip, and the
        // translate that centres the grid moves the bottom row past the height this scope reports, so
        // a height test would let a partial row paint into the margin below it.
        if (row >= maxRows) return@forEachIndexed
        val line = frame.lines.getOrNull(visual.line) ?: return@forEachIndexed
        paintRow(line, visual, row * cellHeight + shift, frame.firstLine + visual.line)
    }
    // From the bottom edge of the screen and not from the last row the fit left, because the rows a step
    // towards the live end uncovers come up through that edge. What the fit left over below the window -
    // `maxRows - windowRows` rows of background, which the resting picture already shows - stays where it
    // is rather than being carried by the margin. See the residual in the plan: the two agree whenever
    // the budget divides evenly into row heights, which is the case this was built for.
    val belowTop = maxRows * cellHeight
    layout.marginBelow.forEachIndexed { index, visual ->
        val line = frame.marginBelow.getOrNull(visual.line) ?: return@forEachIndexed
        paintRow(
            line,
            visual,
            belowTop + index * cellHeight + shift,
            frame.firstLine + frame.lines.size + visual.line,
        )
    }

    // The IME's provisional text, drawn where the cursor is because nowhere else can: the shell has not
    // been told about it, so the grid does not carry it, and the field the keyboard is composing into
    // is a pixel of transparent nothing. It is drawn under the cursor, so the hollow block sits on the
    // first character of the word - which is what a terminal that echoed as you typed would show.
    //
    // Clipped to the row it starts on. It is one word and the window is many columns wide, so it fits
    // almost always; a word longer than the remainder of its row would otherwise paint into the row
    // below, and a provisional word has no more claim to the next row than an unbreakable token has.
    composingPreview(composing, layout, wraps, maxRows)?.let { preview ->
        val left = preview.column * cellWidth
        val top = preview.row * cellHeight + shift
        val glyphs = measurer.measure(AnnotatedString(preview.text), style.merge(TextStyle(color = foreground)))
        clipRect(left, top, left + glyphs.size.width, top + cellHeight) {
            drawText(glyphs, topLeft = Offset(left, top))
        }
    }
    if (cursorOn && frame.cursorVisible && layout.cursorRow in 0 until maxRows) {
        // The block is drawn hollow, so the character underneath stays legible - a filled one hides
        // whatever the cursor is on, which on a phone is exactly the character the user is trying to
        // check. A bar and an underline are the shapes a program picks to say which mode it is in, and
        // neither covers a character, so both are drawn solid.
        val left = layout.cursorColumn * cellWidth
        val top = layout.cursorRow * cellHeight + shift
        when (frame.cursorShape) {
            TerminalCursorShape.BLOCK -> drawRect(
                cursorColour,
                topLeft = Offset(left, top),
                size = Size(cellWidth, cellHeight),
                style = Stroke(width = CURSOR_STROKE),
            )
            // At the bottom edge of the cell, inside it rather than under it, so it cannot bleed into
            // the row below.
            TerminalCursorShape.UNDERLINE -> drawRect(
                cursorColour,
                topLeft = Offset(left, top + cellHeight - CURSOR_STROKE),
                size = Size(cellWidth, CURSOR_STROKE),
            )
            // The left edge, which is where an insertion point belongs: the caret sits *before* the
            // character it would replace rather than over it.
            TerminalCursorShape.BAR -> drawRect(
                cursorColour,
                topLeft = Offset(left, top),
                size = Size(CURSOR_STROKE, cellHeight),
            )
        }
    }
}

private const val CURSOR_STROKE = 2f

/**
 * The IME's provisional text and the cell it is drawn in, or null when there is nothing to draw.
 *
 * Three different situations with one answer. The keyboard may be composing nothing, which is every
 * keyboard that types directly - the text was sent as it was typed. The display may be a screen some
 * program painted positionally: `top`, `vim`, `nano` and [CC] address cells absolutely and leave the
 * terminal's cursor wherever they last put it - [CC] parks it on the corner of its input box - so the
 * cursor is not the insertion point, and drawing the user's half-typed word at it would put text in the
 * middle of a panel. Or the cursor's row may be off screen, because the user has scrolled back into the
 * history to read something.
 *
 * "The cursor is the insertion point" is exactly [wrapsForDisplay]'s question - a shell writing flowed
 * output, where the cursor sits on the cell the next character will go into - so this shares that one
 * answer instead of keeping a second predicate that could drift away from it.
 */
internal fun composingPreview(
    composing: String,
    layout: TerminalLayout,
    wraps: Boolean,
    maxRows: Int,
): ComposingPreview? {
    if (composing.isEmpty() || !wraps) return null
    if (layout.cursorRow !in 0 until maxRows) return null
    return ComposingPreview(row = layout.cursorRow, column = layout.cursorColumn, text = composing)
}

/** A cell of the layout and the provisional text drawn in it. */
internal data class ComposingPreview(val row: Int, val column: Int, val text: String)

/**
 * Half the blink period. 500 ms on, 500 ms off - close enough to xterm's 530 that it reads as a
 * terminal cursor rather than as something flashing for attention.
 */
private const val CURSOR_BLINK_MS = 500L

private fun decorationFor(cell: TerminalCell): TextDecoration? {
    // A linked cell is underlined even where the program did not ask for it: a link the reader cannot
    // see is a link the reader cannot choose to follow.
    val underline = cell.style.underline || cell.hyperlink != null
    return when {
        underline && cell.style.strikethrough ->
            TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        underline -> TextDecoration.Underline
        cell.style.strikethrough -> TextDecoration.LineThrough
        else -> null
    }
}

/**
 * The colours a cell is actually painted in, after the attributes that swap or fade them.
 *
 * Inverse has to be resolved here rather than at parse time: it swaps foreground with background,
 * and a cell that never set either has to swap the *theme's* pair, which the buffer does not know.
 * `hidden` is folded in the same way - it means "draw the text in the background colour", which is
 * how a password prompt hides what it echoes, and reproducing it exactly is the point.
 */
private fun TerminalStyle.resolve(
    defaultForeground: Color,
    defaultBackground: Color,
): ResolvedColours {
    val rawForeground = if (foreground.isDefault) defaultForeground else foreground.toComposeColor()
    val rawBackground = if (background.isDefault) null else background.toComposeColor()
    var fg = if (dim) rawForeground.copy(alpha = DIM_ALPHA) else rawForeground
    var bg = rawBackground
    if (inverse) {
        val swapped = bg ?: defaultBackground
        bg = fg
        fg = swapped
    }
    if (hidden) fg = bg ?: defaultBackground
    return ResolvedColours(fg, bg)
}

/** The palette index or 24-bit value the parser recorded, as an opaque colour. */
private fun TerminalColor.toComposeColor(): Color = Color(OPAQUE or rgb)

private const val OPAQUE = 0xFF000000.toInt()

private const val DIM_ALPHA = 0.55f

private data class ResolvedColours(val foreground: Color, val background: Color?)
