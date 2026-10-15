package dev.eclipse.ssh.terminal

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.ui.terminal.TerminalCellMetrics
import dev.eclipse.ssh.ui.terminal.TerminalView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the drawing layer actually puts ink, at the one level no other test in this repository reads.
 *
 * Every other terminal test stops at a frame. A frame says which cells hold which character in which
 * style, and that is the emulator's answer - pure arithmetic, checked on the JVM in a second. Between
 * a frame and the screen sit the cell metrics, the centring origin, the wrapping layout, the run split
 * and the cursor, and a mistake anywhere along that chain leaves every one of those tests green while
 * the screen shows the wrong picture. This suite is the gate on that chain.
 *
 * The reference is arithmetic rather than a stored image, deliberately. A committed PNG would pin the
 * device's font rasteriser along with this app's layout, so it would break on the first emulator whose
 * system font differs - which is every API level bump - and be re-recorded rather than read. What is
 * checked here is what the font cannot change: that a cell's own background covers exactly that cell,
 * that a glyph lands in the cell the layout names and in no other, that a line wider than the window
 * continues on the row below, and that the cursor is drawn on the cell the frame names. Each of those
 * is true or false on every device, and false is a bug rather than a font difference.
 *
 * This lives in `androidTest` because reading pixels back is the whole point of it, and
 * `captureToImage` does not render under this project's Robolectric setup - see the note in
 * `TextEditorCanvasRobolectricTest`, which tried it. So these cases run on a device or emulator and
 * not in the JVM unit-test job.
 */
@RunWith(AndroidJUnit4::class)
class TerminalRenderGoldenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun the_view_reports_the_grid_its_cell_size_fits() {
        var reported: TerminalViewport? = null
        show(blankFrame(ROWS), onViewportChange = { reported = it })
        rule.waitForIdle()

        val viewport = requireNotNull(reported) { "the view reported no viewport" }
        // 492 px across a 24 px cell is 20 columns with 12 px left over, and 504 px down a 48 px cell
        // is 10 rows with 24 left over: the leftovers are what make the count stable, see [show].
        assertThat(viewport.visibleColumns).isEqualTo(COLUMNS)
        assertThat(viewport.visibleRows).isEqualTo(ROWS)
        assertThat(viewport.columns).isEqualTo(COLUMNS)
        assertThat(viewport.ptyRows).isEqualTo(ROWS)
        assertThat(viewport.bufferRows).isEqualTo(ROWS)
    }

    @Test
    fun text_is_painted_in_the_cells_the_layout_names_and_in_no_other() {
        show(frame(listOf(textLine("AB")) + List(ROWS - 1) { blankLine() }))
        val shot = capture()

        assertThat(inkAt(shot, row = 0, column = 0)).isTrue()
        assertThat(inkAt(shot, row = 0, column = 1)).isTrue()
        assertThat(inkAt(shot, row = 0, column = 8)).isFalse()
        assertThat(inkAt(shot, row = 1, column = 0)).isFalse()
    }

    @Test
    fun a_cell_that_sets_its_own_background_paints_exactly_that_cell() {
        val red = TerminalStyle(background = TerminalColor(0x00FF0000))
        val line = List(COLUMNS) { TerminalCell(style = if (it == 4) red else TerminalStyle()) }
        show(frame(listOf(line) + List(ROWS - 1) { blankLine() }))
        val shot = capture()

        assertThat(cellIs(shot, row = 0, column = 4, colour = RED)).isTrue()
        assertThat(cellIs(shot, row = 0, column = 3, colour = BACKGROUND_ARGB)).isTrue()
        assertThat(cellIs(shot, row = 0, column = 5, colour = BACKGROUND_ARGB)).isTrue()
    }

    @Test
    fun a_line_wider_than_the_window_continues_on_the_row_below() {
        val wide = List(COLUMNS * 2) { TerminalCell(value = if (it < COLUMNS) 'x' else 'y') }
        show(frame(listOf(wide, blankLine())))
        val shot = capture()

        // The second half of the line is drawn from the left margin of the row below it, which is what
        // makes a wrapped continuation read as a continuation rather than as a row of its own.
        assertThat(inkAt(shot, row = 0, column = 0)).isTrue()
        assertThat(inkAt(shot, row = 1, column = 0)).isTrue()
        assertThat(inkAt(shot, row = 2, column = 0)).isFalse()
    }

    @Test
    fun the_cursor_block_is_drawn_on_the_cell_the_frame_names() {
        show(
            frame(
                lines = List(ROWS) { blankLine() },
                cursorRow = 0,
                cursorColumn = 6,
                cursorVisible = true,
            ),
        )
        val shot = capture()

        assertThat(inkAt(shot, row = 0, column = 6)).isTrue()
        assertThat(inkAt(shot, row = 0, column = 12)).isFalse()
        assertThat(inkAt(shot, row = 3, column = 6)).isFalse()
    }

    /**
     * Puts [frame] on screen at a size whose cell count cannot wobble.
     *
     * The size is set in pixels rather than in `Dp`, so it is the same number on every device and no
     * density conversion stands between the intent and the measurement. The node is also deliberately
     * not a whole number of cells: a width of exactly 480 px is 20 cells of 24, and the pixel-to-`Dp`
     * round trip that [TerminalView] measures through can land a hair under it - 479.99997 - which
     * floors to 19 columns and fails a test about the test rather than about the view. Half a cell of
     * slack puts the division at 20.5 and moves the answer out of reach of any rounding. The 12 px and
     * 24 px left over are then split either side of the grid, which is why the cells start at
     * [GRID_X] and [GRID_Y].
     */
    private fun show(frame: TerminalFrame, onViewportChange: (TerminalViewport) -> Unit = {}) {
        rule.setContent {
            TerminalView(
                frame = frame,
                style = STYLE,
                background = BACKGROUND,
                foreground = FOREGROUND,
                metrics = METRICS,
                modifier = Modifier
                    .testTag(TAG)
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints.fixed(NODE_WIDTH, NODE_HEIGHT))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                onViewportChange = onViewportChange,
            )
        }
    }

    private fun capture(): Shot = rule.onNodeWithTag(TAG).captureToImage().shot()

    private fun blankFrame(lines: Int) = frame(List(lines) { blankLine() })

    private fun frame(
        lines: List<List<TerminalCell>>,
        cursorRow: Int = -1,
        cursorColumn: Int = 0,
        cursorVisible: Boolean = false,
    ) = TerminalFrame(
        lines = lines,
        firstLine = 0,
        totalLines = lines.size,
        cursorRow = cursorRow,
        cursorColumn = cursorColumn,
        columns = COLUMNS,
        rows = ROWS,
        cursorVisible = cursorVisible,
        revision = 0L,
        // Solid, so a capture cannot land on the dark half of a blink and read a cursor that is not
        // there - or, worse, pass while the cursor is drawn in the wrong place half the time.
        cursorBlink = false,
    )
}

/** One capture's pixels, ARGB and row-major, with the width needed to index them. */
private class Shot(val pixels: IntArray, val width: Int)

private fun ImageBitmap.shot(): Shot = Shot(IntArray(width * height).also { readPixels(it) }, width)

private fun blankLine() = List(COLUMNS) { TerminalCell() }

private fun textLine(text: String) =
    List(COLUMNS) { index -> TerminalCell(value = text.getOrElse(index) { ' ' }) }

private fun cellX(column: Int) = GRID_X + column * CELL_PX

private fun cellY(row: Int) = GRID_Y + row * CELL_PY

/** Whether any pixel of the cell at [row], [column] differs from the background. */
private fun inkAt(shot: Shot, row: Int, column: Int): Boolean {
    for (y in cellY(row) until cellY(row) + CELL_PY) {
        for (x in cellX(column) until cellX(column) + CELL_PX) {
            if (shot.pixels[y * shot.width + x] != BACKGROUND_ARGB) return true
        }
    }
    return false
}

/**
 * Whether every pixel of the cell at [row], [column] is [colour].
 *
 * The outermost [EDGE] pixels are skipped: a rectangle drawn at a cell's boundary is antialiased
 * along it, and those pixels are a blend of the cell's colour and its neighbour's rather than a
 * statement about either.
 */
private fun cellIs(shot: Shot, row: Int, column: Int, colour: Int): Boolean {
    for (y in cellY(row) + EDGE until cellY(row) + CELL_PY - EDGE) {
        for (x in cellX(column) + EDGE until cellX(column) + CELL_PX - EDGE) {
            if (shot.pixels[y * shot.width + x] != colour) return false
        }
    }
    return true
}

private const val CELL_PX = 24
private const val CELL_PY = 48
private const val COLUMNS = 20
private const val ROWS = 10

/** Half a cell wider and taller than the grid, so the count is 20.5 cells and not 20. */
private const val NODE_WIDTH = COLUMNS * CELL_PX + CELL_PX / 2
private const val NODE_HEIGHT = ROWS * CELL_PY + CELL_PY / 2

/** Where the grid starts inside the captured node: half of the 12 px and 24 px the size leaves over. */
private const val GRID_X = 6
private const val GRID_Y = 12
private const val EDGE = 2
private const val TAG = "terminal-render-golden"

private val BACKGROUND = Color(0xFF101010)
private val FOREGROUND = Color(0xFFE0E0E0)
private val BACKGROUND_ARGB = 0xFF101010.toInt()
private val RED = 0xFFFF0000.toInt()

/** The cell size the size in [show] is a whole number and a half of. */
private val METRICS = TerminalCellMetrics(CELL_PX.toFloat(), CELL_PY.toFloat(), CELL_PY * 0.75f)

/**
 * Small next to a cell, so a glyph cannot reach the edge of the one it is drawn in and read as ink in
 * the next. Nothing here depends on the face or the size - only on the ink staying inside its cell.
 */
private val STYLE = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
