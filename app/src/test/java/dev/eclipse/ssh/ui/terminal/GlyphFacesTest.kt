package dev.eclipse.ssh.ui.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which face draws a cluster.
 *
 * This is the decision that makes the symbol repertoire the app's own rather than the device's, so it
 * is pinned here rather than at the draw call. Every code point below is a real one from the report
 * this answers - `⏵⏵` in [CC]'s status line, the `✻` spinner, the braille alphabet a progress
 * indicator uses - and each is asserted by name so that a regenerated table cannot quietly drop one.
 */
class GlyphFacesTest {

    @Test
    fun `the symbols no bundled text face carries go to the symbol face`() {
        // U+23F5 BLACK RIGHT-POINTING DOUBLE TRIANGLE, the reported tofu: [CC] writes `⏵⏵ bypass
        // permissions on`. The three beside it are the same block's triangles and the same complaint.
        assertThat(terminalFaceFor('⏵', "")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('⏴', "")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('⏶', "")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('⏷', "")).isEqualTo(TerminalFace.SYMBOL)
        // U+23BF, the tool-result corner a terminal draws under a command, and the media controls.
        assertThat(terminalFaceFor('⎿', "")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('⏸', "")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('⏹', "")).isEqualTo(TerminalFace.SYMBOL)
    }

    @Test
    fun `the spinner alphabet goes to the symbol face`() {
        // The set a progress indicator cycles through. U+2736 and U+00B7 are *not* here: JetBrains Mono
        // carries both, and a glyph the text face can draw is not taken away from it.
        assertThat(terminalFaceFor('✻', "")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('✢', "")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('✳', "")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('✽', "")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('✶', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('·', "")).isEqualTo(TerminalFace.TEXT)
    }

    @Test
    fun `the whole braille block goes to the symbol face`() {
        // 256 code points, the largest single block in the table, and the one a progress indicator
        // sweeps from end to end. Both edges are asserted because both are range boundaries.
        assertThat(terminalFaceFor('⠀', "")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('⣿', "")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('⠁', "")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('⟿', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('⤀', "")).isEqualTo(TerminalFace.TEXT)
    }

    @Test
    fun `what the text face already draws is left alone`() {
        // The rule that keeps the change from being a repaint of the whole terminal: box drawing and
        // block elements have *no* gaps in either JetBrains Mono face, so nothing in them moves, and
        // the arrows, bullets and geometric shapes the text face carries stay where they were.
        assertThat(terminalFaceFor('─', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('│', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('┌', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('╭', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('╰', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('█', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('░', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('●', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('■', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('→', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('←', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('…', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('a', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor(' ', "")).isEqualTo(TerminalFace.TEXT)
    }

    @Test
    fun `what the app does not bundle a face for is left to the platform`() {
        // A code point outside the symbol blocks is not the symbol face's: CJK is the case that
        // matters, because the symbol face does not carry it and the device always does. Emoji are the
        // other, and they are the reason a cluster carrying a presentation selector is excluded below.
        assertThat(terminalFaceFor('中', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('Ａ', "")).isEqualTo(TerminalFace.TEXT)
        // An astral character arrives as one surrogate per cell - a cell holds a single `Char` - and
        // neither half is in the table, so both stay with the platform's emoji face.
        assertThat(terminalFaceFor('\uD83D', "")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('\uDE00', "")).isEqualTo(TerminalFace.TEXT)
    }

    @Test
    fun `an emoji presentation selector or a joiner hands the cluster to the platform`() {
        // The two things a symbol face must not be given: only the platform's emoji face can draw a
        // variation sequence in colour or join a zero-width-joiner sequence into one glyph. The base
        // is a symbol the table routes, so this is the exception and not a coincidence.
        assertThat(terminalFaceFor('✳', "️")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('✳', "︎")).isEqualTo(TerminalFace.SYMBOL)
        assertThat(terminalFaceFor('⚡', "‍")).isEqualTo(TerminalFace.TEXT)
        assertThat(terminalFaceFor('✳', "́")).isEqualTo(TerminalFace.SYMBOL)
    }

    @Test
    fun `the table is a sorted list of pairs`() {
        assertThat(SYMBOL_RANGES.size % 2).isEqualTo(0)
        var index = 0
        while (index < SYMBOL_RANGES.size) {
            // Inclusive at both ends, so a range of one code point is written with the same number
            // twice rather than being dropped - `U+2000` EN QUAD is exactly that case.
            assertThat(SYMBOL_RANGES[index]).isAtMost(SYMBOL_RANGES[index + 1])
            if (index > 0) assertThat(SYMBOL_RANGES[index]).isGreaterThan(SYMBOL_RANGES[index - 1] + 1)
            index += 2
        }
    }

    @Test
    fun `every range is inclusive at both ends and nothing outside one is a symbol`() {
        // The whole table, both edges of every range, rather than the few code points above: a
        // regenerated table that shifted a boundary by one would still pass a hand-picked set. A gap
        // that is one wide is skipped on the outside test - the cell between two ranges is not outside
        // a range - and the ends of the table have nothing to test against.
        var index = 0
        while (index < SYMBOL_RANGES.size) {
            val start = SYMBOL_RANGES[index]
            val end = SYMBOL_RANGES[index + 1]
            assertThat(terminalFaceFor(start.toChar(), "")).isEqualTo(TerminalFace.SYMBOL)
            assertThat(terminalFaceFor(end.toChar(), "")).isEqualTo(TerminalFace.SYMBOL)
            if (index == 0 || start > SYMBOL_RANGES[index - 1] + 1) {
                assertThat(terminalFaceFor((start - 1).toChar(), "")).isEqualTo(TerminalFace.TEXT)
            }
            if (index + 2 >= SYMBOL_RANGES.size || end < SYMBOL_RANGES[index + 2] - 1) {
                assertThat(terminalFaceFor((end + 1).toChar(), "")).isEqualTo(TerminalFace.TEXT)
            }
            index += 2
        }
    }

    @Test
    fun `the search over the table is exact`() {
        // The binary search is the one piece of arithmetic here, so it is checked against the linear
        // answer for every code point the table can hold. A search that returned true for a code point
        // just past a range would move a text glyph to the symbol face silently.
        val routed = HashSet<Int>()
        var index = 0
        while (index < SYMBOL_RANGES.size) {
            for (code in SYMBOL_RANGES[index]..SYMBOL_RANGES[index + 1]) routed += code
            index += 2
        }
        for (code in 0x2000..0x2C00) {
            val expected = if (code in routed) TerminalFace.SYMBOL else TerminalFace.TEXT
            assertThat(terminalFaceFor(code.toChar(), "")).isEqualTo(expected)
        }
    }
}
