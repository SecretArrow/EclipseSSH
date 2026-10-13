package dev.eclipse.ssh.ui.terminal

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.eclipse.ssh.R

/**
 * The terminal's typeface: JetBrains Mono, bundled rather than borrowed from the platform.
 *
 * `FontFamily.Monospace` is whatever monospace the device happens to ship - Droid Sans Mono on one
 * phone, Roboto Mono on another, a vendor's own on a third - so the grid the user reads changed shape
 * from device to device, and none of those faces was designed for the thing a terminal asks of a font:
 * a fixed advance that stays honest at small sizes, glyphs that do not blur into each other at 12sp, and
 * the near-ambiguous pairs a shell is full of - `l1I`, `O0`, `{}` `()` `[]`, `.,;:` - drawn so they
 * cannot be confused. JetBrains Mono is cut for exactly that, which is why it is worth carrying the two
 * files instead of trusting the platform.
 *
 * Both weights are declared, and the bold one matters more than it looks. SGR bold (`ESC[1m`) is how a
 * prompt, a `grep` match and half of `ls --color` announce themselves, and the renderer asks for
 * [FontWeight.Bold] on those cells (see `drawFrame`). With only the regular file registered, Compose
 * would *synthesise* bold by smearing the regular outline, which at terminal sizes thickens the stem
 * unevenly and drifts the advance - the one thing a monospace grid cannot tolerate, because the cell
 * width was measured from the regular cut. Registering the real bold face means a bold cell is the same
 * width as a plain one and the column alignment holds.
 */
internal val TerminalMonoFontFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/**
 * The face that draws the symbols JetBrains Mono has no glyph for: `⏵`, `✻`, the braille spinner
 * alphabet, and the rest of the blocks in [terminalFaceFor]'s table.
 *
 * It exists because a fallback *chain* is not something Compose can be given. `FontFamily(...)` is one
 * face per weight, not a list to walk: when the resolved face has no glyph for a character the string
 * goes to the platform, which draws it from whatever the device happens to ship at *that* face's own
 * advance. Two things go wrong with that, and the second is the one that cannot be argued with. The
 * glyph may simply not be there - `U+23F5` BLACK RIGHT-POINTING DOUBLE TRIANGLE is in no Android
 * system font on every phone, and a device that lacks it draws a tofu box, which is the reported
 * "kotak merah berisi angka hex" in the middle of `⏵⏵ bypass permissions on`. And the face that does
 * stand in advances its glyph by something other than the cell: `⏵` in a platform face measures about
 * a whole em against a cell of six tenths, so the glyph is drawn a third of a cell past its own column
 * and the symbol beside it is drawn on top of it.
 *
 * Bundling the face is what makes both answers ours. The file is a subset of JuliaMono, cut to
 * exactly the code points in [SYMBOL_RANGES] and nothing else - 212 KB, the size of one of the
 * JetBrains Mono cuts, and smaller than every alternative that covers `U+23F5` and braille. JuliaMono
 * is chosen over the alternatives for one measurable reason: its advance is **1200 units of a 2000-unit
 * em**, which is 0.6 em, which is exactly the advance JetBrains Mono is cut to and exactly the cell
 * `rememberTerminalCellMetrics` measures. A symbol drawn from it lands on its own column with nothing
 * left over, which is the whole property the grid needs. Its vertical metrics are normalised to
 * JetBrains Mono's for the same reason - a symbol has to sit on the same baseline as the text beside
 * it - and it is renamed, as the SIL Open Font License requires of a modified copy.
 *
 * One weight only, deliberately. A second cut would double the file for a cell that is a symbol, and
 * Compose *synthesises* the weight it is not given by smearing the outline - which widens the glyph
 * and drifts its advance, the one thing this face exists to keep. So [terminalFaceFor]'s callers ask
 * for no weight and no slant on a symbol cell, and a bold `⏵` is drawn at the regular cut rather than
 * being faked.
 *
 * See `scripts/make-terminal-symbols.py` for the command that produced the file and
 * `docs/THIRD-PARTY.md` for the licence.
 */
internal val TerminalSymbolFontFamily: FontFamily = FontFamily(
    Font(R.font.terminal_symbols, FontWeight.Normal),
)

/** The family that draws [this] face. See [terminalFaceFor] for which cluster goes to which. */
internal val TerminalFace.family: FontFamily
    get() = when (this) {
        TerminalFace.TEXT -> TerminalMonoFontFamily
        TerminalFace.SYMBOL -> TerminalSymbolFontFamily
    }
