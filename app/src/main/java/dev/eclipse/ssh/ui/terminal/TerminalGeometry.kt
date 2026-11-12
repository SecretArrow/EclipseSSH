package dev.eclipse.ssh.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

internal fun minTerminalColumns(
    settingColumns: Int,
    hostColumns: Int,
): Int = maxOf(settingColumns.coerceAtLeast(0), hostColumns.coerceAtLeast(0))

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
fun TerminalGrid.atMostRows(hostRows: Int): TerminalGrid = if (hostRows in 1 until rows) copy(rows = hostRows) else this

/** Remembers the cell metrics for [style], measuring a run of glyphs rather than a single one. */
@Composable
fun rememberTerminalCellMetrics(
    style: TextStyle,
    measurer: TextMeasurer = rememberTextMeasurer(),
): TerminalCellMetrics =
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
fun terminalTextInset(
    screenWidthDp: Int,
    screenHeightDp: Int,
): Dp = (minOf(screenWidthDp, screenHeightDp) * TEXT_INSET_FRACTION).dp.coerceAtLeast(0.dp)

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
    /**
     * One gesture of the finger, for a program that asked the terminal to report the mouse.
     *
     * Reported at a 1-based *screen* cell, which is what a mouse report carries: a program reading the
     * mouse has never seen the scrollback, so the cell it is told about is one of its own.
     *
     * Whether the program is owed the gesture is decided here rather than by the caller, from the mode in
     * the frame being drawn. The local gesture a report stands in for is read off that same frame, so the
     * two can never come from different pictures of who owns the screen.
     */
    onMouseGesture: (gesture: MouseGesture, column: Int, row: Int) -> Unit = { _, _, _ -> },
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
    val mouseGesture by rememberUpdatedState(onMouseGesture)

    BoxWithConstraints(modifier.background(background)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // Two grids, and the difference between them is the whole feature. `visible` is what fits on
        // screen; `grid` is what the pty is told it has, which is at least as wide and usually as tall.
        // They are the same object whenever both settings are "fit screen".
        val visible = remember(metrics, widthPx, heightPx) { metrics.gridIn(widthPx, heightPx) }
        val grid =
            remember(visible, minColumns, minRows, hostRows) {
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
        val viewport =
            TerminalViewport(
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
        val layout =
            remember(layoutKey(frame, visible.columns, visible.rows)) {
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
            val target =
                panForCursor(
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

        // Whether the finger's own gesture has been taken from the program by a long press. A bare
        // BooleanArray for the same reason [pointerAt] is one, and shared between two handlers that
        // cannot call each other: the tap handler raises it when the long press fires, and the gesture
        // loop below reads it to stop reporting a drag the user has taken back for a selection.
        val localGesture = remember { BooleanArray(1) }

        // The geometry a mouse report's cell is worked out from. Read through state wrappers because the
        // loop below is keyed on nothing - it has to outlive a recomposition, or a pinch, which changes
        // the font size and therefore the metrics, would tear the gesture in half - and a captured
        // [metrics] would then be the size the gesture started at, so every report would name the wrong
        // cell.
        val reportMetrics by rememberUpdatedState(metrics)
        val reportOrigin by rememberUpdatedState(origin)
        val reportColumns by rememberUpdatedState(visible.columns)
        val reportRows by rememberUpdatedState(visible.rows)

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

        val scrollState =
            rememberScrollableState { delta ->
                // The drag's delta is positive toward the live end; the accumulator's is positive into the
                // history, which is the sign `onScroll` documents - and the sign this handler used before
                // the accumulator existed, when the remainder arithmetic cancelled out to the same thing.
                val backPx = currentBackPx
                val forwardPx = currentForwardPx
                // Where the drag goes is asked before the step is charged rather than when the gesture
                // starts: a program can turn its mouse reporting on mid-drag, and the answer is read from
                // the frame being drawn so the two can never disagree about who owns the screen.
                val target = dragTarget(currentFrame.mouseMode, currentFrame.mouseSgr, currentFrame.alternateScreen)
                // A drag the program owns is not a scroll, and it is not charged against the accumulator
                // either: those pixels are spent as the reports the finger produced, and a step taken out of
                // the same travel would be a second answer to one gesture.
                var steps = if (target == DragTarget.Motion) 0 else scrollAccumulator.dragBy(-delta, backPx, forwardPx)
                if (steps == 0 && target == DragTarget.Scrollback) {
                    // Guarded by the offset this view believes it is at, not by the one the frame carries:
                    // frames arrive through a StateFlow, so the frame can still be one publication behind
                    // and an ungated commit would take the same end twice.
                    steps =
                        freeStep(
                            scrollAccumulator.pendingPx,
                            backPx,
                            forwardPx,
                            currentFrame.scrollOffset + owedSteps[0],
                            currentFrame.scrollCeiling,
                        )
                }
                // Only the scrollback draws a shift: a wheel report, an arrow key and a drag the program
                // owns are all answered by the program moving something of its own, and translating this
                // screen would slide the picture out from under the answer it is about to receive.
                scrollShift.floatValue =
                    if (target == DragTarget.Scrollback) {
                        scrollShiftPx(scrollAccumulator.pendingPx, backPx, forwardPx)
                    } else {
                        0f
                    }
                if (steps != 0) {
                    owedSteps[0] += steps
                    val up = steps > 0
                    when (target) {
                        DragTarget.Scrollback -> scrollCallback(steps)
                        // Reported where the finger is, from the last event the gesture handler saw - a
                        // wheel belongs to the pane or panel under the pointer, not to the one the cursor
                        // happens to be on.
                        DragTarget.Wheel -> {
                            val (column, row) =
                                Offset(pointerAt[0], pointerAt[1])
                                    .toScreenCell(metrics, origin, pan.floatValue, visible.columns, visible.rows)
                            mouseWheel(up, column, row, abs(steps))
                        }
                        DragTarget.Arrows -> arrowKeys(up, abs(steps))
                        // Nothing here, and nothing above either: a motion report is one per cell the finger
                        // crosses rather than one per line of travel, and the gesture loop is what sees every
                        // cell, so the loop is what reports them.
                        DragTarget.Motion -> Unit
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
        val fling =
            remember {
                TerminalFlingBehavior(exponentialDecay<Float>()) { velocity ->
                    val flings =
                        dragTarget(
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
                        velocity < 0f ->
                            flingRoomPx(
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
        val panState =
            rememberScrollableState { delta ->
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
                //
                // It is also the only place that sees a whole gesture, which is why the mouse reports are
                // made here rather than in the handlers below. A tap is a press and a release, a drag is a
                // press and a movement and a release, and telling the two apart needs the down, the moves
                // and the up together - and the up is an event no `detect*` helper hands back: `onTap`
                // never fires for a drag, and a drag's release is exactly what a program tracking a
                // selection is waiting for.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pointerAt[0] = down.position.x
                        pointerAt[1] = down.position.y

                        // The 1-based screen cell a finger is over, written into [out] rather than
                        // returned: this runs on every move of every gesture, and a Pair per event is an
                        // allocation a drag does not need. The geometry comes through the state wrappers
                        // above, because this loop outlives a recomposition.
                        fun cellAt(
                            position: Offset,
                            out: IntArray,
                        ) {
                            val (column, row) =
                                position.toScreenCell(
                                    reportMetrics,
                                    reportOrigin,
                                    pan.floatValue,
                                    reportColumns,
                                    reportRows,
                                )
                            out[0] = column
                            out[1] = row
                        }

                        // Whether the program is owed a gesture. Asked per report rather than once at the
                        // down, because a program can turn its mouse reporting on or off in the middle of
                        // a gesture and the frame being drawn is the only thing that knows.
                        fun owed(
                            gesture: MouseGesture,
                            held: Boolean,
                        ) = MouseModes.reports(
                            currentFrame.mouseMode,
                            currentFrame.mouseSgr,
                            gesture,
                            buttonHeld = held,
                        )

                        // What the finger has done so far, all of it per gesture and therefore locals
                        // rather than state. The press is deliberately not reported at the down: the
                        // gesture could still turn out to be a pinch or a long press, and a report already
                        // sent cannot be taken back. It goes out with the first movement - which is what
                        // makes a drag - or with the release, and either way it carries the cell the
                        // finger went down on.
                        val downCell = IntArray(2).also { cellAt(down.position, it) }
                        // The cell the finger is on, and the last cell a report named - the down cell
                        // until a movement report replaces it.
                        val at =
                            IntArray(2).also {
                                it[0] = downCell[0]
                                it[1] = downCell[1]
                            }
                        var namedX = downCell[0]
                        var namedY = downCell[1]
                        var moved = false
                        var pressSent = false
                        var pinching = false
                        // Cleared at every down: it is the long press's answer about the gesture in
                        // flight, and a stale one would silence the next gesture instead.
                        localGesture[0] = false

                        var scale = 1f
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
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2) {
                                pinching = true
                                val step = event.calculateZoom()
                                if (step != 0f && step != 1f) {
                                    scale *= step
                                    zoom(scale, false)
                                }
                                // Consumed only while two fingers are down, which is what stops the
                                // scroll handlers from also acting on the same pointers.
                                event.changes.forEach { it.consume() }
                            } else if (!pinching && !localGesture[0]) {
                                // Taken on every event, the one that lifts the finger included: a release
                                // says where the button came up, which is not always where the last
                                // movement left it.
                                event.changes.lastOrNull()?.let { cellAt(it.position, at) }
                                if (at[0] != downCell[0] || at[1] != downCell[1]) moved = true
                                // One report per cell crossed, which is the granularity a mouse has: a
                                // finger that has not left its cell has not moved anywhere the program can
                                // see, and sub-cell jitter inside a cell is not a report either. A motion
                                // is a button held and moving, so the lift - which is the release below -
                                // is not one.
                                if (fingers == 1 &&
                                    (at[0] != namedX || at[1] != namedY) &&
                                    owed(MouseGesture.MOTION, held = true)
                                ) {
                                    if (!pressSent) {
                                        pressSent = owed(MouseGesture.PRESS, held = false)
                                        if (pressSent) mouseGesture(MouseGesture.PRESS, downCell[0], downCell[1])
                                    }
                                    mouseGesture(MouseGesture.MOTION, at[0], at[1])
                                    namedX = at[0]
                                    namedY = at[1]
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) zoom(scale, true)

                        // A drag has already reported its press and is waiting for the release that says
                        // where the button came up. A pinch and a long press are this terminal's own
                        // gestures - two fingers zoom, and a hold selects - so neither is reported; but a
                        // press that went out before the second finger or the hold arrived is closed
                        // rather than left open, because a program left holding a button down is worse
                        // than one that saw a click it did not ask for.
                        if (pressSent) {
                            if (owed(MouseGesture.RELEASE, held = false)) {
                                mouseGesture(MouseGesture.RELEASE, at[0], at[1])
                            }
                        } else if (!moved && !pinching && !localGesture[0]) {
                            // A tap: a press and a release at the one cell the finger never left. A drag
                            // the program did not ask for is not this - it was the terminal's own scroll,
                            // and [moved] is what tells the two apart.
                            if (owed(MouseGesture.PRESS, held = false)) {
                                mouseGesture(MouseGesture.PRESS, downCell[0], downCell[1])
                                if (owed(MouseGesture.RELEASE, held = false)) {
                                    mouseGesture(MouseGesture.RELEASE, downCell[0], downCell[1])
                                }
                            }
                        }
                    }
                }.pointerInput(metrics, origin) {
                    detectTapGestures(
                        onTap = { position ->
                            val (line, column) =
                                position.toCell(currentFrame, currentLayout, metrics, origin, pan.floatValue, scrollShift.floatValue)
                            tap(line, column)
                        },
                        onLongPress = { position ->
                            // The finger has taken the gesture back from the program. A long press is this
                            // terminal's own selection - the way a held Shift is in a terminal with a
                            // keyboard - so the drag that follows it is not the program's to hear about.
                            localGesture[0] = true
                            val (line, column) =
                                position.toCell(currentFrame, currentLayout, metrics, origin, pan.floatValue, scrollShift.floatValue)
                            longPress(line, column)
                        },
                    )
                }.pointerInput(metrics, origin) {
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
                }.drawBehind {
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

fun terminalTextInset(
    screenWidthDp: Int,
    screenHeightDp: Int,
): Dp = (minOf(screenWidthDp, screenHeightDp) * TEXT_INSET_FRACTION).dp.coerceAtLeast(0.dp)

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
    /**
     * One gesture of the finger, for a program that asked the terminal to report the mouse.
     *
     * Reported at a 1-based *screen* cell, which is what a mouse report carries: a program reading the
     * mouse has never seen the scrollback, so the cell it is told about is one of its own.
     *
     * Whether the program is owed the gesture is decided here rather than by the caller, from the mode in
     * the frame being drawn. The local gesture a report stands in for is read off that same frame, so the
     * two can never come from different pictures of who owns the screen.
     */
    onMouseGesture: (gesture: MouseGesture, column: Int, row: Int) -> Unit = { _, _, _ -> },
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
    val mouseGesture by rememberUpdatedState(onMouseGesture)

    BoxWithConstraints(modifier.background(background)) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // Two grids, and the difference between them is the whole feature. `visible` is what fits on
        // screen; `grid` is what the pty is told it has, which is at least as wide and usually as tall.
        // They are the same object whenever both settings are "fit screen".
        val visible = remember(metrics, widthPx, heightPx) { metrics.gridIn(widthPx, heightPx) }
        val grid =
            remember(visible, minColumns, minRows, hostRows) {
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
        val viewport =
            TerminalViewport(
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
        val layout =
            remember(layoutKey(frame, visible.columns, visible.rows)) {
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
            val target =
                panForCursor(
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

        // Whether the finger's own gesture has been taken from the program by a long press. A bare
        // BooleanArray for the same reason [pointerAt] is one, and shared between two handlers that
        // cannot call each other: the tap handler raises it when the long press fires, and the gesture
        // loop below reads it to stop reporting a drag the user has taken back for a selection.
        val localGesture = remember { BooleanArray(1) }

        // The geometry a mouse report's cell is worked out from. Read through state wrappers because the
        // loop below is keyed on nothing - it has to outlive a recomposition, or a pinch, which changes
        // the font size and therefore the metrics, would tear the gesture in half - and a captured
        // [metrics] would then be the size the gesture started at, so every report would name the wrong
        // cell.
        val reportMetrics by rememberUpdatedState(metrics)
        val reportOrigin by rememberUpdatedState(origin)
        val reportColumns by rememberUpdatedState(visible.columns)
        val reportRows by rememberUpdatedState(visible.rows)

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

        val scrollState =
            rememberScrollableState { delta ->
                // The drag's delta is positive toward the live end; the accumulator's is positive into the
                // history, which is the sign `onScroll` documents - and the sign this handler used before
                // the accumulator existed, when the remainder arithmetic cancelled out to the same thing.
                val backPx = currentBackPx
                val forwardPx = currentForwardPx
                // Where the drag goes is asked before the step is charged rather than when the gesture
                // starts: a program can turn its mouse reporting on mid-drag, and the answer is read from
                // the frame being drawn so the two can never disagree about who owns the screen.
                val target = dragTarget(currentFrame.mouseMode, currentFrame.mouseSgr, currentFrame.alternateScreen)
                // A drag the program owns is not a scroll, and it is not charged against the accumulator
                // either: those pixels are spent as the reports the finger produced, and a step taken out of
                // the same travel would be a second answer to one gesture.
                var steps = if (target == DragTarget.Motion) 0 else scrollAccumulator.dragBy(-delta, backPx, forwardPx)
                if (steps == 0 && target == DragTarget.Scrollback) {
                    // Guarded by the offset this view believes it is at, not by the one the frame carries:
                    // frames arrive through a StateFlow, so the frame can still be one publication behind
                    // and an ungated commit would take the same end twice.
                    steps =
                        freeStep(
                            scrollAccumulator.pendingPx,
                            backPx,
                            forwardPx,
                            currentFrame.scrollOffset + owedSteps[0],
                            currentFrame.scrollCeiling,
                        )
                }
                // Only the scrollback draws a shift: a wheel report, an arrow key and a drag the program
                // owns are all answered by the program moving something of its own, and translating this
                // screen would slide the picture out from under the answer it is about to receive.
                scrollShift.floatValue =
                    if (target == DragTarget.Scrollback) {
                        scrollShiftPx(scrollAccumulator.pendingPx, backPx, forwardPx)
                    } else {
                        0f
                    }
                if (steps != 0) {
                    owedSteps[0] += steps
                    val up = steps > 0
                    when (target) {
                        DragTarget.Scrollback -> scrollCallback(steps)
                        // Reported where the finger is, from the last event the gesture handler saw - a
                        // wheel belongs to the pane or panel under the pointer, not to the one the cursor
                        // happens to be on.
                        DragTarget.Wheel -> {
                            val (column, row) =
                                Offset(pointerAt[0], pointerAt[1])
                                    .toScreenCell(metrics, origin, pan.floatValue, visible.columns, visible.rows)
                            mouseWheel(up, column, row, abs(steps))
                        }
                        DragTarget.Arrows -> arrowKeys(up, abs(steps))
                        // Nothing here, and nothing above either: a motion report is one per cell the finger
                        // crosses rather than one per line of travel, and the gesture loop is what sees every
                        // cell, so the loop is what reports them.
                        DragTarget.Motion -> Unit
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
        val fling =
            remember {
                TerminalFlingBehavior(exponentialDecay<Float>()) { velocity ->
                    val flings =
                        dragTarget(
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
                        velocity < 0f ->
                            flingRoomPx(
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
        val panState =
            rememberScrollableState { delta ->
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
                //
                // It is also the only place that sees a whole gesture, which is why the mouse reports are
                // made here rather than in the handlers below. A tap is a press and a release, a drag is a
                // press and a movement and a release, and telling the two apart needs the down, the moves
                // and the up together - and the up is an event no `detect*` helper hands back: `onTap`
                // never fires for a drag, and a drag's release is exactly what a program tracking a
                // selection is waiting for.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pointerAt[0] = down.position.x
                        pointerAt[1] = down.position.y

                        // The 1-based screen cell a finger is over, written into [out] rather than
                        // returned: this runs on every move of every gesture, and a Pair per event is an
                        // allocation a drag does not need. The geometry comes through the state wrappers
                        // above, because this loop outlives a recomposition.
                        fun cellAt(
                            position: Offset,
                            out: IntArray,
                        ) {
                            val (column, row) =
                                position.toScreenCell(
                                    reportMetrics,
                                    reportOrigin,
                                    pan.floatValue,
                                    reportColumns,
                                    reportRows,
                                )
                            out[0] = column
                            out[1] = row
                        }

                        // Whether the program is owed a gesture. Asked per report rather than once at the
                        // down, because a program can turn its mouse reporting on or off in the middle of
                        // a gesture and the frame being drawn is the only thing that knows.
                        fun owed(
                            gesture: MouseGesture,
                            held: Boolean,
                        ) = MouseModes.reports(
                            currentFrame.mouseMode,
                            currentFrame.mouseSgr,
                            gesture,
                            buttonHeld = held,
                        )

                        // What the finger has done so far, all of it per gesture and therefore locals
                        // rather than state. The press is deliberately not reported at the down: the
                        // gesture could still turn out to be a pinch or a long press, and a report already
                        // sent cannot be taken back. It goes out with the first movement - which is what
                        // makes a drag - or with the release, and either way it carries the cell the
                        // finger went down on.
                        val downCell = IntArray(2).also { cellAt(down.position, it) }
                        // The cell the finger is on, and the last cell a report named - the down cell
                        // until a movement report replaces it.
                        val at =
                            IntArray(2).also {
                                it[0] = downCell[0]
                                it[1] = downCell[1]
                            }
                        var namedX = downCell[0]
                        var namedY = downCell[1]
                        var moved = false
                        var pressSent = false
                        var pinching = false
                        // Cleared at every down: it is the long press's answer about the gesture in
                        // flight, and a stale one would silence the next gesture instead.
                        localGesture[0] = false

                        var scale = 1f
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
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2) {
                                pinching = true
                                val step = event.calculateZoom()
                                if (step != 0f && step != 1f) {
                                    scale *= step
                                    zoom(scale, false)
                                }
                                // Consumed only while two fingers are down, which is what stops the
                                // scroll handlers from also acting on the same pointers.
                                event.changes.forEach { it.consume() }
                            } else if (!pinching && !localGesture[0]) {
                                // Taken on every event, the one that lifts the finger included: a release
                                // says where the button came up, which is not always where the last
                                // movement left it.
                                event.changes.lastOrNull()?.let { cellAt(it.position, at) }
                                if (at[0] != downCell[0] || at[1] != downCell[1]) moved = true
                                // One report per cell crossed, which is the granularity a mouse has: a
                                // finger that has not left its cell has not moved anywhere the program can
                                // see, and sub-cell jitter inside a cell is not a report either. A motion
                                // is a button held and moving, so the lift - which is the release below -
                                // is not one.
                                if (fingers == 1 &&
                                    (at[0] != namedX || at[1] != namedY) &&
                                    owed(MouseGesture.MOTION, held = true)
                                ) {
                                    if (!pressSent) {
                                        pressSent = owed(MouseGesture.PRESS, held = false)
                                        if (pressSent) mouseGesture(MouseGesture.PRESS, downCell[0], downCell[1])
                                    }
                                    mouseGesture(MouseGesture.MOTION, at[0], at[1])
                                    namedX = at[0]
                                    namedY = at[1]
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (pinching) zoom(scale, true)

                        // A drag has already reported its press and is waiting for the release that says
                        // where the button came up. A pinch and a long press are this terminal's own
                        // gestures - two fingers zoom, and a hold selects - so neither is reported; but a
                        // press that went out before the second finger or the hold arrived is closed
                        // rather than left open, because a program left holding a button down is worse
                        // than one that saw a click it did not ask for.
                        if (pressSent) {
                            if (owed(MouseGesture.RELEASE, held = false)) {
                                mouseGesture(MouseGesture.RELEASE, at[0], at[1])
                            }
                        } else if (!moved && !pinching && !localGesture[0]) {
                            // A tap: a press and a release at the one cell the finger never left. A drag
                            // the program did not ask for is not this - it was the terminal's own scroll,
                            // and [moved] is what tells the two apart.
                            if (owed(MouseGesture.PRESS, held = false)) {
                                mouseGesture(MouseGesture.PRESS, downCell[0], downCell[1])
                                if (owed(MouseGesture.RELEASE, held = false)) {
                                    mouseGesture(MouseGesture.RELEASE, downCell[0], downCell[1])
                                }
                            }
                        }
                    }
                }.pointerInput(metrics, origin) {
                    detectTapGestures(
                        onTap = { position ->
                            val (line, column) =
                                position.toCell(currentFrame, currentLayout, metrics, origin, pan.floatValue, scrollShift.floatValue)
                            tap(line, column)
                        },
                        onLongPress = { position ->
                            // The finger has taken the gesture back from the program. A long press is this
                            // terminal's own selection - the way a held Shift is in a terminal with a
                            // keyboard - so the drag that follows it is not the program's to hear about.
                            localGesture[0] = true
                            val (line, column) =
                                position.toCell(currentFrame, currentLayout, metrics, origin, pan.floatValue, scrollShift.floatValue)
                            longPress(line, column)
                        },
                    )
                }.pointerInput(metrics, origin) {
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
                }.drawBehind {
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
