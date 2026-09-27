package dev.eclipse.ssh.ui.terminal

import kotlin.math.abs

/**
 * Whether a cluster is one cell without measuring it at all.
 *
 * A printable ASCII character is in every face this app can be pointed at, and no monospace face
 * advances one by anything but a cell, so the answer is not in question - and asking it costs no
 * allocation, which is what makes this worth having. The renderer asks about every cell of every row
 * of every frame, and a terminal's output is overwhelmingly printable ASCII.
 *
 * A character carrying a combining mark is excluded rather than reasoned about: the mark is the one
 * thing that can change which face draws the cluster, and it is rare enough that measuring it costs
 * nothing worth avoiding.
 */
internal fun clusterIsCertainlyOneCell(value: Char, combining: String): Boolean =
    combining.isEmpty() && value.code in 0x20..0x7E

/**
 * Whether a cluster of glyphs the renderer measured occupies the one cell the terminal allotted it.
 *
 * The renderer draws a whole style run as a single string from the run's own left edge and lets the
 * font place each glyph after it by its advance. That is exact only while every glyph in the run has
 * the cell's advance: the bundled face is a monospace one, so an ordinary letter is exactly one cell
 * and a run of them stays on the grid for as long as the run is long.
 *
 * A glyph the bundled face does not carry is drawn from a fallback face instead, at that face's own
 * advance - which is not the cell's. Every glyph after it in the same run is then displaced by the
 * difference, so the *text* slides out from under the cursor while the cursor itself stays where the
 * grid puts it. That is the whole of the reported symptom: a cursor that does not sit where it looks
 * like it sits.
 *
 * So a cluster that does not measure one cell has to end the run it is in, and start the next one.
 * This predicate is that decision, and it is deliberately made at **one cluster** at a time rather
 * than on the run's total width: over a long run the rounded cell width accumulates a drift of its
 * own, so a whole-run comparison cannot tell a rounding error from a fallback glyph. One glyph's
 * measurement carries no accumulation at all.
 *
 * The tolerance is a fraction of the cell rather than a pixel count, because the difference being
 * looked for - a fallback face's advance against the cell's - is a fraction of the cell too, and a
 * fixed number of pixels would mean something different at every font size the user picks. A quarter
 * of a cell sits well above the sub-pixel rounding of a single measurement and well below the
 * difference a fallback or a wide glyph makes, which is at least two fifths of a cell.
 */
internal fun clusterFitsOneCell(measuredWidth: Float, cellWidth: Float): Boolean {
    if (cellWidth <= 0f) return true
    return abs(measuredWidth - cellWidth) <= cellWidth * CELL_WIDTH_TOLERANCE_FRACTION
}

/**
 * How far a measured cluster may differ from the cell before it is treated as off-grid.
 *
 * Named after what it is for: a glyph drawn from another face differs by two fifths of a cell or
 * more, and a glyph from the bundled face differs only by the rounding of its own measurement.
 */
internal const val CELL_WIDTH_TOLERANCE_FRACTION = 0.25f

/**
 * How many times a cluster is repeated before it is measured, so that the rounding of a single
 * measurement is divided away rather than being compared against the cell.
 *
 * The same trick, and the same number, as the one that measures the cell itself - a repeat long
 * enough that one pixel of rounding no longer decides the answer.
 */
internal const val CLUSTER_MEASURE_REPEAT = 20

/**
 * The exclusive end of the run of cells that starts at [from] and is drawn as one string.
 *
 * A run is a stretch of cells sharing a style, drawn from one left edge through the font's own
 * advances - which only stays on the grid while every cell in it carries a glyph the bundled face
 * advances by exactly one cell. So the run ends at the first cell that either changes style or does
 * not, and the two are tested in that order: a cell of another style is not this run's to measure,
 * and asking about it would measure it in the wrong weight.
 *
 * The cell the run *starts* at is tested first and on its own. Leaving it out is the trap: it is not
 * reached by the loop below, so an off-grid cluster in the first cell would take the whole following
 * run of ordinary text with it - which is the drift this exists to stop, in its most common shape,
 * since a line usually begins where the program put the glyph.
 *
 * [sameStyle] and [fitsOneCell] are indexed by cell, and neither is called outside `[from, lastColumn)`.
 */
internal fun glyphRunEnd(
    from: Int,
    lastColumn: Int,
    sameStyle: (Int) -> Boolean,
    fitsOneCell: (Int) -> Boolean,
): Int {
    if (from >= lastColumn) return from
    if (!fitsOneCell(from)) return from + 1
    var end = from + 1
    while (end < lastColumn && sameStyle(end) && fitsOneCell(end)) end++
    return end
}
