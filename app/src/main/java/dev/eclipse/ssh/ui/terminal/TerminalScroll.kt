package dev.eclipse.ssh.ui.terminal

import dev.eclipse.ssh.terminal.MouseMode
import dev.eclipse.ssh.terminal.TerminalVisualRow
import kotlin.math.abs
import kotlin.math.floor

/**
 * Turns finger travel into whole-line scroll steps.
 *
 * A step is priced at the *rendered* height of the line it moves past, not at one cell. The buffer
 * hard-wraps at the pty width; an 80-column pty on a phone showing ~44 columns makes most lines two
 * rows, so a step priced at one cell moves the screen twice as far as the finger on a wrapped line
 * and half as far on a short one - which is what makes a drag feel like it is catching and jumping.
 *
 * The remainder is carried rather than discarded, so a drag of less than one line is not lost, and
 * a drag of several lines is reported as one delta the caller can publish once instead of once per
 * crossing. It is carried across gestures too, deliberately: a finger that stopped nine tenths of a
 * line short has travelled that far, and honouring it on the next touch is where the screen keeps up
 * with the finger rather than starting over.
 *
 * Not thread-safe and not meant to be: it is driven from the pointer and animation callbacks, which
 * run on the main thread.
 */
class ScrollAccumulator {

    private var pending = 0f

    /** Finger travel not yet spent on a step, in pixels. Same sign as [dragBy]'s input. */
    val pendingPx: Float get() = pending

    /**
     * Drops the travel held for a step. For when the offset moves for a reason that is not this
     * accumulator's own - a page key, a badge jump, a re-clamp on resize: the pixels were measured
     * against a picture that is no longer on screen.
     */
    fun reset() {
        pending = 0f
    }

    /**
     * Adds [deltaPx] of travel and returns the whole steps to take, positive meaning further back
     * into the history. Zero when the travel does not yet add up to a step.
     *
     * Two prices, not one, because the window's top edge does not move the same distance in both
     * directions: at the live end a step back hands the prompt's row to the background while the top
     * stays pinned, and a step forward from there moves it again. Pricing both with one number is what
     * makes the screen translate and then snap back on the first drag of a session.
     *
     * A price that cannot price a step (zero, negative, or not a number) yields 0 for that direction and
     * **keeps the travel**: the step is free, so the finger's pixels belong to the step after it rather
     * than being discarded. The remainder survives the direction flip for the same reason.
     */
    fun dragBy(deltaPx: Float, backStepPx: Float, forwardStepPx: Float): Int {
        if (!deltaPx.isFinite()) return 0
        pending += deltaPx
        if (!pending.isFinite()) {
            pending = 0f
            return 0
        }
        val stepPx = if (pending >= 0f) backStepPx else forwardStepPx
        if (!stepPx.isFinite() || stepPx <= 0f) return 0
        // Truncation toward zero is the whole-step count in the direction of travel; the leftover is
        // exact because it is the same subtraction the division undid.
        val steps = (pending / stepPx).toInt()
        pending -= steps * stepPx
        return steps
    }
}

/**
 * The step a drag takes when the step in that direction costs nothing, or 0 when there is none.
 *
 * The fit's walk stalls wherever the line heights do not divide the budget evenly - a line taller than
 * the whole window, or a budget left half spent. There the window one offset further along begins on the
 * same buffer line as this one: the top of the screen does not move, so there is no distance to charge
 * and no strip to slide text into. But it is a different window all the same, and a drag that took no
 * step there would never take one at all, because the price in that direction stays zero for as long as
 * the offset does. So the step is free, and the travel stays for the step that does move something.
 *
 * [offset] is where the view *believes* the buffer is rather than what the frame on screen says, because
 * frames arrive one publication behind the gesture; [ceiling] is the end of the history. Either end is a
 * hard stop.
 */
fun freeStep(pendingPx: Float, backStepPx: Float, forwardStepPx: Float, offset: Int, ceiling: Int): Int {
    if (!pendingPx.isFinite() || pendingPx == 0f) return 0
    val limit = ceiling.coerceAtLeast(0)
    val at = offset.coerceIn(0, limit)
    val backFree = backStepPx.isFinite() && backStepPx <= 0f
    val forwardFree = forwardStepPx.isFinite() && forwardStepPx <= 0f
    return when {
        pendingPx > 0f && backFree && at < limit -> 1
        pendingPx < 0f && forwardFree && at > 0 -> -1
        else -> 0
    }
}

/**
 * How far a fling can still travel, in pixels: the steps left in that direction, priced at what one
 * costs there.
 *
 * A step that costs nothing - the same stalled walk [freeStep] covers - is priced at one cell rather
 * than at zero. Zero would be the fling's "nowhere to go" and the flick would die on the spot, while the
 * step it stands on is free and the ones after it are not. One cell is the cheapest a priced step can
 * be, so a stall prices the fling short rather than long: the decay ends a little early instead of
 * running on over a screen that has stopped moving.
 */
fun flingRoomPx(stepsLeft: Int, stepPx: Float, cellPx: Float): Float {
    if (stepsLeft <= 0) return 0f
    val price = if (stepPx.isFinite() && stepPx > 0f) stepPx else cellPx
    if (!price.isFinite() || price <= 0f) return 0f
    return stepsLeft * price
}

/**
 * How far the window is drawn from where it rests, in pixels, positive meaning the content has moved
 * down and the reader has gone back into the history.
 *
 * The travel the accumulator has not spent yet, clamped to what the frame's margins can actually
 * reveal: past that there is nothing to draw in the strip the shift opens, and a shift wider than the
 * margin would show the background at the top or bottom edge for the whole of a step.
 */
fun scrollShiftPx(pendingPx: Float, backStepPx: Float, forwardStepPx: Float): Float {
    if (!pendingPx.isFinite()) return 0f
    val back = if (backStepPx.isFinite() && backStepPx > 0f) backStepPx else 0f
    val forward = if (forwardStepPx.isFinite() && forwardStepPx > 0f) forwardStepPx else 0f
    return pendingPx.coerceIn(-forward, back)
}

/**
 * The row of [TerminalLayout.rows] a touch at [y] landed on, or -1 when there is nothing to land on.
 *
 * [shiftPx] is subtracted because the row under the finger is the row as *drawn*: while a drag is in
 * flight the whole strip is displaced, and a mapping that ignored it would select the row above the one
 * the finger is on for the whole of the gesture. [marginAbove] is then taken off, because the rows above
 * the window are not rows of it.
 *
 * Clamped to the window rather than answered with the margin row's own coordinates: a touch that lands
 * on a margin row - which it can only do mid-drag, when one is partly on screen - belongs to the text
 * nearest the finger, and the alternative is a selection on a buffer line the layout was never given.
 */
fun touchedRow(
    y: Float,
    originY: Float,
    cellHeight: Float,
    shiftPx: Float,
    marginAbove: Int,
    rows: Int,
): Int {
    if (rows <= 0) return -1
    if (!cellHeight.isFinite() || cellHeight <= 0f) return 0
    val drawn = floor(((y - originY - shiftPx) / cellHeight).toDouble()).toInt()
    return (drawn - marginAbove).coerceIn(0, rows - 1)
}

/**
 * The lines to move for a page of [pageRows] rows, positive meaning further back into the history.
 *
 * A page is a screenful of *rows*, so it is not a fixed number of lines: walking back past a wrapped
 * line spends two of the page's rows, and past a short one spends a single row. Paging by a line
 * count instead is what makes Page Up land mid-line and drift.
 *
 * [anchor] is the buffer line the window's bottom edge sits at when the offset is zero; [fromOffset]
 * is the offset in lines to page from, and the result is a delta to add to it. The walk stops at
 * [ceiling] and at 0, so a page near either end moves part of the way rather than not at all; an
 * offset already outside that range is read as its nearest end, and the delta is measured from there.
 *
 * [rowsOfLine] reports how many rows the line at a buffer line index occupies; it is expected to
 * answer for out-of-range indices rather than throw, and a line is always worth at least one row.
 */
fun pageSteps(
    anchor: Int,
    fromOffset: Int,
    pageRows: Int,
    ceiling: Int,
    rowsOfLine: (Int) -> Int,
): Int {
    if (pageRows == 0) return 0
    val back = pageRows > 0
    val budget = if (pageRows == Int.MIN_VALUE) Int.MAX_VALUE else abs(pageRows)
    val limit = ceiling.coerceAtLeast(0)
    var offset = fromOffset.coerceIn(0, limit)
    val start = offset
    var rows = 0
    while (rows < budget) {
        // Going back, the line entering at the top of the window; going forward, the one entering at
        // the bottom. Either way it is the line the content moves by, and its height is the cost.
        val line = if (back) anchor - offset - 1 else anchor - offset
        val next = if (back) offset + 1 else offset - 1
        if (next < 0 || next > limit) break
        rows += rowsOfLine(line).coerceAtLeast(1)
        offset = next
    }
    return offset - start
}

/**
 * The height to charge for a line no layout has measured: the tallest line on screen.
 *
 * A page is walked over lines the window has not reached yet - that is the whole point of paging - and
 * nothing has laid those out. The tallest is the estimate because it is the safe one: it prices every
 * unmeasured step at the worst case, so a page covers a screenful or less and never overshoots past
 * one. Where the output is regular - a log, a listing, anything wrapped at one width, which is when a
 * page is worth pressing at all - the tallest is also the usual height and the estimate is exact.
 *
 * A frame with no rows left to charge, or a screen too narrow for anything to wrap, is one row per
 * line.
 */
fun tallestRowsPerLine(rows: List<TerminalVisualRow>): Int {
    var tallest = 1
    val heights = HashMap<Int, Int>()
    for (row in rows) {
        val height = (heights[row.line] ?: 0) + 1
        heights[row.line] = height
        if (height > tallest) tallest = height
    }
    return tallest
}

/** Where a vertical drag goes: the local history, or bytes to the program that took the screen. */
sealed interface DragTarget {
    /** The local scrollback. The remote program is not told about the gesture. */
    data object Scrollback : DragTarget

    /** Wheel reports to the remote, one per step crossed. */
    data object Wheel : DragTarget

    /** Arrow key presses to the remote, one per step crossed. */
    data object Arrows : DragTarget
}

/**
 * Where a vertical drag goes, given who is listening.
 *
 * A program that asked for SGR mouse reports is waiting for the wheel and gets it. Everything else on
 * the alternate screen gets arrow keys, which is xterm's alternate-scroll behaviour and the only thing
 * that can move a program which has taken the screen and is not reading the mouse - there is no
 * scrollback behind it to move instead. Off the alternate screen there *is* a scrollback, so that is
 * where the drag goes: a program addressing the primary screen keeps its lines in the history, and
 * typing arrow keys into the shell on a drag is worse than doing nothing at all.
 *
 * Tracking without SGR falls through to those same two answers rather than inventing the legacy
 * encoding: this terminal sends SGR coordinates or none, so a program asking for the old format gets
 * the same keys it would get from a terminal without a mouse.
 */
fun dragTarget(mouseMode: MouseMode, mouseSgr: Boolean, alternateScreen: Boolean): DragTarget {
    val wheel = when (mouseMode) {
        // X10 reports presses and nothing else, and a wheel is not a press. A drag on a program that
        // asked only for that gets the arrow keys it would get from a terminal with no mouse at all.
        MouseMode.OFF, MouseMode.X10 -> false
        MouseMode.NORMAL, MouseMode.BUTTON, MouseMode.ANY -> mouseSgr
    }
    return when {
        wheel -> DragTarget.Wheel
        alternateScreen -> DragTarget.Arrows
        else -> DragTarget.Scrollback
    }
}
