package dev.eclipse.ssh.ui.terminal

/**
 * Which of the terminal's faces draws a cluster.
 *
 * Two, and the split is not a preference. [TerminalMonoFontFamily] is the face the grid was measured
 * from and draws everything it carries; [TerminalSymbolFontFamily] is a second bundled face that
 * carries the symbols the first one does not. A cell whose character is in neither is left to the
 * platform, which is where CJK and colour emoji come from.
 */
internal enum class TerminalFace { TEXT, SYMBOL }

/**
 * The face that draws this cluster.
 *
 * The terminal needs this because a face is not a fallback chain: Compose resolves one family and one
 * weight per run, and hands the string to the platform, which reaches for another face only when the
 * glyph is missing - at *that* face's advance, which is not the cell's. So the face a cluster will be
 * drawn from has to be decided here, before the run is delimited, and the answer has to be a face the
 * app ships: leaving it to the platform is what renders `\u23F5` as a tofu box on one phone and as a
 * symbol on the next.
 *
 * [SYMBOL_RANGES] is exactly the set of code points the two bundled JetBrains Mono faces have no glyph
 * for, inside the blocks a terminal draws symbols from - so a cluster the text face can draw is never
 * taken away from it, and a cluster it cannot is never left to the platform. The two exceptions are
 * deliberate and are the two things a terminal should *not* draw itself: a cluster carrying an emoji
 * presentation selector or a zero-width joiner belongs to the platform's emoji chain, which is the only
 * thing that can render it in colour and join a sequence, and so does a code point outside those blocks
 * - CJK, whose glyphs no bundled face carries and whose font is guaranteed on the device.
 *
 * The ranges were read out of the three faces rather than chosen, and `scripts/terminal-symbols.py`
 * re-derives them from the fonts and fails when they and this table disagree. Both bundled text faces
 * are consulted, because a code point either of them carries has to stay with it: the two are the same
 * weight of the same design, and one of them having a glyph the other lacks is a fact about the design
 * rather than a reason to move a cell to another face.
 */
internal fun terminalFaceFor(value: Char, combining: String): TerminalFace = when {
    // Emoji presentation, and the joiner that builds a sequence: the platform's emoji face is the only
    // one that can draw either, and it has to be given the whole cluster to join it.
    combining.any { it == EMOJI_PRESENTATION || it == ZERO_WIDTH_JOINER } -> TerminalFace.TEXT
    inSymbolRanges(value.code) -> TerminalFace.SYMBOL
    else -> TerminalFace.TEXT
}

/** Whether a code point is one the bundled text faces do not carry. */
private fun inSymbolRanges(code: Int): Boolean {
    var low = 0
    var high = SYMBOL_RANGES.size / 2 - 1
    while (low <= high) {
        val middle = (low + high) / 2
        val start = SYMBOL_RANGES[middle * 2]
        val end = SYMBOL_RANGES[middle * 2 + 1]
        when {
            code < start -> high = middle - 1
            code > end -> low = middle + 1
            else -> return true
        }
    }
    return false
}

private const val EMOJI_PRESENTATION = '\uFE0F'
private const val ZERO_WIDTH_JOINER = '\u200D'

/**
 * The code points the bundled text faces have no glyph for, as inclusive `start, end` pairs, in order.
 *
 * `internal` rather than private so that the test can walk every range's two edges: the table is a
 * hundred ranges and a hand-written case for each is a case for the ones somebody thought of.
 *
 * Generated, never edited by hand: the list is the answer to "what is in the symbol blocks that
 * JetBrains Mono does not have and the bundled symbol face does", and the only honest source for it is
 * the three font files. `scripts/terminal-symbols.py` prints this block and checks it against them, and
 * a code point added to the symbol face without a matching range here is a cell that stays tofu.
 */
// BEGIN GENERATED terminal-symbol-ranges
internal val SYMBOL_RANGES = intArrayOf(
    0x2000, 0x2000,
    0x2002, 0x200A,
    0x200C, 0x200F,
    0x2011, 0x2012,
    0x2015, 0x2015,
    0x2017, 0x2017,
    0x2023, 0x2023,
    0x2025, 0x2025,
    0x2027, 0x202F,
    0x2031, 0x2031,
    0x2035, 0x2038,
    0x203B, 0x203B,
    0x2040, 0x2043,
    0x2047, 0x2064,
    0x2066, 0x206B,
    0x206E, 0x206F,
    0x219A, 0x219C,
    0x219F, 0x219F,
    0x21A1, 0x21A1,
    0x21A4, 0x21A4,
    0x21A8, 0x21A8,
    0x21AB, 0x21AC,
    0x21AE, 0x21BD,
    0x21BF, 0x21C8,
    0x21CA, 0x21CF,
    0x21D5, 0x21DA,
    0x21DC, 0x21DD,
    0x21E0, 0x21E4,
    0x21E6, 0x21E6,
    0x21E9, 0x21E9,
    0x21EB, 0x21FF,
    0x220D, 0x220D,
    0x2214, 0x2214,
    0x2216, 0x2217,
    0x221B, 0x221D,
    0x221F, 0x2222,
    0x2226, 0x2226,
    0x222C, 0x2233,
    0x223A, 0x223B,
    0x223D, 0x223D,
    0x223F, 0x2242,
    0x2244, 0x2244,
    0x2246, 0x2246,
    0x224A, 0x224A,
    0x224C, 0x224C,
    0x224E, 0x2253,
    0x2255, 0x2256,
    0x2258, 0x225E,
    0x2266, 0x2269,
    0x2274, 0x2279,
    0x227D, 0x2281,
    0x228A, 0x228D,
    0x229A, 0x229A,
    0x22A6, 0x22B3,
    0x22B5, 0x22B7,
    0x22B9, 0x22BA,
    0x22BE, 0x22C1,
    0x22C5, 0x22C5,
    0x22C7, 0x22C7,
    0x22CB, 0x22CD,
    0x22CF, 0x22CF,
    0x22D1, 0x22E1,
    0x22E3, 0x22ED,
    0x22F2, 0x2301,
    0x2306, 0x2307,
    0x230C, 0x2317,
    0x2319, 0x231B,
    0x2320, 0x2323,
    0x2327, 0x2327,
    0x2329, 0x232A,
    0x232C, 0x2335,
    0x237B, 0x2388,
    0x238C, 0x2394,
    0x2396, 0x239A,
    0x23AE, 0x23CD,
    0x23CF, 0x23FA,
    0x23FF, 0x23FF,
    0x25A2, 0x25A9,
    0x25AC, 0x25B1,
    0x25CD, 0x25CD,
    0x25D0, 0x25D3,
    0x25D6, 0x25E5,
    0x25EC, 0x25EE,
    0x25F0, 0x25F5,
    0x25F7, 0x266C,
    0x266E, 0x266E,
    0x2670, 0x2686,
    0x2688, 0x269F,
    0x26A2, 0x2712,
    0x2714, 0x2714,
    0x2716, 0x2716,
    0x2718, 0x2735,
    0x2737, 0x276D,
    0x2772, 0x2793,
    0x2795, 0x279B,
    0x279F, 0x27BF,
    0x2800, 0x28FF,
    0x2B00, 0x2B57,
    0x2B59, 0x2B73,
    0x2B76, 0x2BFF,
)
// END GENERATED terminal-symbol-ranges
