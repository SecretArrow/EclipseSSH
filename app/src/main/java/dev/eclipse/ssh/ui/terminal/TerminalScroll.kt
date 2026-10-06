package dev.eclipse.ssh.ui.terminal

import dev.eclipse.ssh.terminal.MouseMode
import dev.eclipse.ssh.terminal.TerminalVisualRow
import kotlin.math.abs

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
     * Adds [deltaPx] of travel and returns the whole steps to take, positive meaning further back
     * into the history. Zero when the travel does not yet add up to a step.
     *
     * [stepPx] is the rendered height of the line the next step would move past. A value that cannot
     * price a step (zero, negative, or not a number) yields 0 and leaves the remainder alone rather
     * than dividing by it.
     */
    fun dragBy(deltaPx: Float, stepPx: Float): Int {
        if (!deltaPx.isFinite()) return 0
        if (!stepPx.isFinite() || stepPx <= 0f) return 0
        pending += deltaPx
        if (!pending.isFinite()) {
            pending = 0f
            return 0
        }
        // Truncation toward zero is the whole-step count in the direction of travel; the leftover is
        // exact because it is the same subtraction the division undid.
        val steps = (pending / stepPx).toInt()
        pending -= steps * stepPx
        return steps
    }
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
