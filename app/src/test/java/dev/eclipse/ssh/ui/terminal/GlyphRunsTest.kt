package dev.eclipse.ssh.ui.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The predicate that decides whether a cluster may be drawn as part of a run.
 *
 * It is the whole of the fix's decision, and it is pure, so it is pinned here rather than at the
 * draw call: a run that steps over a cluster drawn from a face that advances it by something other
 * than a cell has every glyph after it displaced, which is what slides the text out from under the
 * cursor. The numbers below are the measured ones - the bundled face advances every glyph it carries
 * by six tenths of an em, and the faces that stand in for the ones it does not carry advance theirs
 * by a whole em.
 */
class GlyphRunsTest {

    @Test
    fun `printable ascii is settled without a measurement`() {
        // The fast path, and the one that matters for cost: this is asked once per cell per row per
        // frame, so it must not allocate. Its answer is not in question for any face.
        assertThat(clusterIsCertainlyOneCell('a', "")).isTrue()
        assertThat(clusterIsCertainlyOneCell('M', "")).isTrue()
        assertThat(clusterIsCertainlyOneCell(' ', "")).isTrue()
        assertThat(clusterIsCertainlyOneCell('~', "")).isTrue()
        assertThat(clusterIsCertainlyOneCell('!', "")).isTrue()
    }

    @Test
    fun `anything the fast path cannot answer for goes to the measurement`() {
        // One past either end of the printable range: a control character is not drawn at all and a
        // non-ASCII character may come from any face, so neither is answered here.
        assertThat(clusterIsCertainlyOneCell(0x1F.toChar(), "")).isFalse()
        assertThat(clusterIsCertainlyOneCell(0x7F.toChar(), "")).isFalse()
        assertThat(clusterIsCertainlyOneCell('é', "")).isFalse()
        assertThat(clusterIsCertainlyOneCell('中', "")).isFalse()
        assertThat(clusterIsCertainlyOneCell('⣋', "")).isFalse()
        // A mark is the one thing that can change the face a base is drawn from, so a cluster carrying
        // one is always measured even when its base is an ordinary letter.
        assertThat(clusterIsCertainlyOneCell('e', "́")).isFalse()
        assertThat(clusterIsCertainlyOneCell('a', "️")).isFalse()
    }

    @Test
    fun `a run of ordinary cells is taken whole`() {
        // The case that must not regress: a line of ordinary output is one draw call, not forty. The
        // predicates are the cheap ones for every cell, and the run covers all of them.
        val end = glyphRunEnd(0, 40, sameStyle = { true }, fitsOneCell = { true })

        assertThat(end).isEqualTo(40)
    }

    @Test
    fun `a run ends at the cell that does not fit, and the next one starts there`() {
        // Cell 5 is a braille spinner. The run is [0, 5) and the next call starts at 5, which is then
        // a run of its own - so the spinner and the ordinary text after it are both placed on their
        // own cells, and neither inherits the other's advance.
        val end = glyphRunEnd(0, 40, sameStyle = { true }, fitsOneCell = { it != 5 })

        assertThat(end).isEqualTo(5)
        assertThat(glyphRunEnd(5, 40, sameStyle = { true }, fitsOneCell = { it != 5 })).isEqualTo(6)
        assertThat(glyphRunEnd(6, 40, sameStyle = { true }, fitsOneCell = { it != 5 })).isEqualTo(40)
    }

    @Test
    fun `an off-grid cluster in the first cell does not take the text after it with it`() {
        // The trap this function exists in the shape of: the loop below never looks at the cell the
        // run starts at, so a guard on the loop alone would let a spinner at the start of a line drag
        // the whole line out of alignment. A line usually begins where the program put the glyph, so
        // this is the common shape of the bug rather than a corner of it.
        val end = glyphRunEnd(9, 40, sameStyle = { true }, fitsOneCell = { it != 9 })

        assertThat(end).isEqualTo(10)
    }

    @Test
    fun `a style change ends the run before the cell is measured`() {
        // The order of the two tests, pinned: a cell of another style belongs to the next run, and
        // measuring it here would measure it in this run's weight - the one thing the cache is keyed
        // on. So `fitsOneCell` must not be reached for a cell that already failed the style test.
        val measured = mutableListOf<Int>()
        val end = glyphRunEnd(
            from = 0,
            lastColumn = 40,
            sameStyle = { it < 4 },
            fitsOneCell = { measured += it; true },
        )

        assertThat(end).isEqualTo(4)
        assertThat(measured).containsExactly(0, 1, 2, 3)
    }

    @Test
    fun `an empty range yields an empty run`() {
        // Total, so that the caller's own guard and this one cannot disagree about an empty row.
        assertThat(glyphRunEnd(7, 7, sameStyle = { true }, fitsOneCell = { true })).isEqualTo(7)
        assertThat(glyphRunEnd(9, 7, sameStyle = { true }, fitsOneCell = { true })).isEqualTo(9)
    }

    /** A cell at a phone's font size, which is the scale the tolerance is a fraction of. */
    private val cell = 10f

    @Test
    fun `a glyph of the bundled face fits the cell it was measured in`() {
        // Exactly one advance, which is what the cell width was measured from in the first place: the
        // cell is not an independent quantity that a glyph has to match, it *is* this face's advance.
        assertThat(clusterFitsOneCell(cell, cell)).isTrue()
    }

    @Test
    fun `the rounding of a single measurement is not mistaken for another face`() {
        // A single measurement rounds to a whole pixel, which at this size is a tenth of the cell -
        // and the cell width itself is rounded to a twentieth. Neither may read as a fallback glyph.
        assertThat(clusterFitsOneCell(cell - 0.5f, cell)).isTrue()
        assertThat(clusterFitsOneCell(cell + 0.5f, cell)).isTrue()
        assertThat(clusterFitsOneCell(cell - 1f, cell)).isTrue()
        // And the fraction is a fraction of the cell, so the same relative error is tolerated at any
        // font size the user picks - a fixed pixel count would have meant something else at each one.
        assertThat(clusterFitsOneCell(24f - 1.2f, 24f)).isTrue()
        assertThat(clusterFitsOneCell(24f + 1.2f, 24f)).isTrue()
    }

    @Test
    fun `a glyph from another face never fits`() {
        // The cell width is the bundled face's six tenths of an em, so a face that advances a glyph by
        // a whole em measures five thirds of a cell - two thirds of a cell out, far past the tolerance.
        // That is the CJK glyph, the fullwidth form, and most platform faces' stand-in for an emoji.
        // Drawn as part of a run it shifts every glyph after it by that much, several pixels each.
        val em = cell / 0.6f
        assertThat(clusterFitsOneCell(em, cell)).isFalse()
        // Narrower than the cell displaces the run the other way, and is isolated for the same reason.
        assertThat(clusterFitsOneCell(0.5f * cell, cell)).isFalse()
        // A braille spinner and an emoji presentation selector land here too, which is the common case
        // on a phone: both are absent from the bundled face and drawn from a platform one.
        assertThat(clusterFitsOneCell(1.35f * cell, cell)).isFalse()
    }

    @Test
    fun `the tolerance is a boundary, not a band with a gap in it`() {
        // Inclusive at the edge: a cluster exactly a quarter of a cell out is still the bundled face
        // at the far end of its rounding, and treating it as a fallback would break every long run.
        assertThat(clusterFitsOneCell(cell + 0.25f * cell, cell)).isTrue()
        assertThat(clusterFitsOneCell(cell - 0.25f * cell, cell)).isTrue()
        // And just past it is not.
        assertThat(clusterFitsOneCell(cell + 0.25f * cell + 0.01f, cell)).isFalse()
        assertThat(clusterFitsOneCell(cell - 0.25f * cell - 0.01f, cell)).isFalse()
    }

    @Test
    fun `a cell with no width to compare against cannot be off it`() {
        // The caller returns before drawing anything at all when the metrics are not measurable yet,
        // so this arm exists only so that the predicate is total: an unmeasured cell must not turn
        // every glyph on the screen into a run of its own.
        assertThat(clusterFitsOneCell(0f, 0f)).isTrue()
        assertThat(clusterFitsOneCell(12f, 0f)).isTrue()
        assertThat(clusterFitsOneCell(0f, -1f)).isTrue()
    }
}
