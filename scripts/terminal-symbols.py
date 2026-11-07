#!/usr/bin/env python3
"""Re-derives the terminal's symbol table from the three bundled fonts and checks the source against it.

`GlyphFaces.kt` decides which face draws a cluster, and its answer is a table of the code points the
two JetBrains Mono faces have no glyph for. A table like that is exactly the kind of thing that goes
stale in silence: swap a font, subset it again, and the terminal keeps routing a cell to a face that
no longer has the glyph, which is the tofu the table exists to remove - and nothing in the app can
notice, because a missing glyph is not an error at any layer the app can see.

So it is generated rather than written, and this is what re-derives it. It reads the code point
tables out of the three `.ttf` files in `app/src/main/res/font` directly - no fontTools, no
`fc-query`, nothing to install, because this has to run in CI next to `check-doc-figures.sh` - and
compares the result with the table in the Kotlin source. It is a check, never a fixer, for the same
reason that one is: a disagreement is as likely to mean a font was replaced by mistake as it is to
mean the table is behind, and a script that rewrote the source would hide which.

Usage:
  scripts/terminal-symbols.py            # check; exit 1 on any disagreement
  scripts/terminal-symbols.py --print    # print the table as the Kotlin source spells it
"""
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FONT_DIR = ROOT / "app" / "src" / "main" / "res" / "font"
TEXT_FACES = ["jetbrains_mono_regular.ttf", "jetbrains_mono_bold.ttf"]
SYMBOL_FACE = "terminal_symbols.ttf"
SOURCE = ROOT / "app" / "src" / "main" / "java" / "dev" / "eclipse" / "ssh" / "ui" / "terminal" / "GlyphFaces.kt"
LICENSES = ROOT / "app" / "src" / "main" / "java" / "dev" / "eclipse" / "ssh" / "feature" / "about" / "AboutLicenses.kt"
THIRD_PARTY = ROOT / "docs" / "THIRD-PARTY.md"
BEGIN = "// BEGIN GENERATED terminal-symbol-ranges"
END = "// END GENERATED terminal-symbol-ranges"

# The blocks a terminal draws symbols from. Everything outside them is left to the platform on
# purpose - CJK and the emoji are the two that matter - so this list is the whole of what the table
# is allowed to claim, and widening it means claiming a face the app does not ship.
BLOCKS = [
    (0x2000, 0x206F),  # General Punctuation
    (0x2190, 0x21FF),  # Arrows
    (0x2200, 0x22FF),  # Mathematical Operators
    (0x2300, 0x23FF),  # Miscellaneous Technical - U+23F5, the reported tofu
    (0x2500, 0x257F),  # Box Drawing
    (0x2580, 0x259F),  # Block Elements
    (0x25A0, 0x25FF),  # Geometric Shapes
    (0x2600, 0x26FF),  # Miscellaneous Symbols
    (0x2700, 0x27BF),  # Dingbats - the spinner alphabet
    (0x2800, 0x28FF),  # Braille Patterns
    (0x2B00, 0x2BFF),  # Miscellaneous Symbols and Arrows
]


def tables(data):
    """The font's table directory, as {tag: (offset, length)}."""
    out = {}
    for index in range(struct.unpack_from(">H", data, 4)[0]):
        offset = 12 + index * 16
        tag = data[offset : offset + 4].decode("latin-1")
        out[tag] = struct.unpack_from(">II", data, offset + 8)
    return out


def codepoints(path):
    """The code points a font's best `cmap` subtable maps, as a set."""
    data = path.read_bytes()
    found = tables(data)
    if "cmap" not in found:
        raise SystemExit(f"FAIL {path.name}: no cmap table")
    base = found["cmap"][0]

    # Format 12 when it is there - the full-repertoire subtable - and format 4 otherwise, which is
    # what every BMP-only face carries. Anything else is not a shape a real font puts in a `.ttf`.
    chosen = None
    for index in range(struct.unpack_from(">H", data, base + 2)[0]):
        platform, encoding, sub = struct.unpack_from(">HHI", data, base + 4 + index * 8)
        fmt = struct.unpack_from(">H", data, base + sub)[0]
        if fmt == 12 and (platform, encoding) == (3, 10):
            chosen = (12, base + sub)
        elif fmt == 4 and (platform, encoding) == (3, 1) and chosen is None:
            chosen = (4, base + sub)
    if chosen is None:
        raise SystemExit(f"FAIL {path.name}: no Unicode cmap subtable")

    fmt, offset = chosen
    if fmt == 12:
        count = struct.unpack_from(">I", data, offset + 12)[0]
        out = set()
        for group in range(count):
            start, end, _ = struct.unpack_from(">III", data, offset + 16 + group * 12)
            out.update(range(start, end + 1))
        return out

    segments = struct.unpack_from(">H", data, offset + 6)[0] // 2
    ends = struct.unpack_from(f">{segments}H", data, offset + 14)
    starts = struct.unpack_from(f">{segments}H", data, offset + 16 + segments * 2)
    deltas = struct.unpack_from(f">{segments}h", data, offset + 16 + segments * 4)
    range_offsets = struct.unpack_from(f">{segments}H", data, offset + 16 + segments * 6)
    out = set()
    for index in range(segments):
        for code in range(starts[index], min(ends[index], 0xFFFF) + 1):
            if range_offsets[index] == 0:
                glyph = (code + deltas[index]) & 0xFFFF
            else:
                # The range offset is measured from its own slot, and the glyph ids after it are
                # indexed by the distance from the segment's start.
                at = offset + 16 + segments * 6 + index * 2 + range_offsets[index] + (code - starts[index]) * 2
                glyph = struct.unpack_from(">H", data, at)[0]
                if glyph:
                    glyph = (glyph + deltas[index]) & 0xFFFF
            if glyph:
                out.add(code)
    return out


def names(path):
    """The font's `name` table, as {name id: text} for the Windows Unicode records."""
    data = path.read_bytes()
    start = tables(data)["name"][0]
    count, string_offset = struct.unpack_from(">HH", data, start + 2)
    out = {}
    for index in range(count):
        platform, encoding, _, name_id, length, offset = struct.unpack_from(">HHHHHH", data, start + 6 + index * 12)
        if (platform, encoding) != (3, 1):
            continue
        out[name_id] = data[start + string_offset + offset : start + string_offset + offset + length].decode("utf-16-be")
    return out


def routed(text, symbol):
    """The code points the symbol face is given: in a block, no text face has it, the symbol face does."""
    out = []
    for start, end in BLOCKS:
        for code in range(start, end + 1):
            if code in symbol and all(code not in face for face in text):
                out.append(code)
    return out


def ranges(codes):
    out = []
    for code in codes:
        if out and code == out[-1][1] + 1:
            out[-1][1] = code
        else:
            out.append([code, code])
    return out


def source_table():
    text = SOURCE.read_text()
    if BEGIN not in text or END not in text:
        raise SystemExit(f"FAIL {SOURCE}: the generated markers are gone")
    body = text.split(BEGIN, 1)[1].split(END, 1)[0]
    numbers = [int(token, 16) for token in re.findall(r"0x([0-9A-Fa-f]+)", body)]
    return [numbers[i : i + 2] for i in range(0, len(numbers), 2)]


# The family names a font may call itself. The Reserved Font Name clause of the SIL Open Font License
# is the one obligation a subset can break invisibly: it says a modified version may not carry the
# original's reserved name, so a regeneration that forgot to rename would ship a licence violation no
# build step and no test could see. What it may *not* drop is the copyright and the licence notice.
FAMILY_NAME_IDS = [1, 4, 6, 16]
RESERVED_NAME = "JuliaMono"


def attribution(symbol):
    """Checks the subset's name table, and the two documents that name it, against each other."""
    failures = 0
    found = names(symbol)

    for name_id in FAMILY_NAME_IDS:
        text = found.get(name_id, "")
        if RESERVED_NAME in text:
            print(f"FAIL {symbol.name}: name id {name_id} is {text!r}, which carries the reserved name", file=sys.stderr)
            failures += 1
    for name_id, wanted in ((0, RESERVED_NAME), (13, "SIL Open Font License")):
        if wanted not in found.get(name_id, ""):
            print(f"FAIL {symbol.name}: name id {name_id} does not name {wanted}", file=sys.stderr)
            failures += 1

    # The version the font calls itself, which is the upstream one it was subset from - so a
    # regeneration from a newer JuliaMono moves it, and both documents have to move with it.
    match = re.search(r"(\d+\.\d+\.\d+)", found.get(5, ""))
    if match is None:
        print(f"FAIL {symbol.name}: name id 5 names no version", file=sys.stderr)
        return failures + 1
    version = match.group(1)
    for document in (THIRD_PARTY, LICENSES):
        if version not in document.read_text():
            print(f"FAIL {document.relative_to(ROOT)} does not name {symbol.name} at {version}", file=sys.stderr)
            failures += 1
    return failures


def main():
    faces = {name: codepoints(FONT_DIR / name) for name in TEXT_FACES}
    symbol = codepoints(FONT_DIR / SYMBOL_FACE)
    expected = ranges(routed(list(faces.values()), symbol))
    print(f"text faces: {[len(f) for f in faces.values()]} code points, symbol face: {len(symbol)}")
    print(f"routed: {sum(b - a + 1 for a, b in expected)} code points in {len(expected)} ranges")

    if "--print" in sys.argv:
        for start, end in expected:
            print(f"    0x{start:04X}, 0x{end:04X},")
        return 0

    actual = source_table()
    if actual != expected:
        # The whole diff, not the first disagreement: a font swap moves hundreds of ranges at once
        # and a reader needs to see whether that is what happened or whether one boundary slipped.
        only_source = [pair for pair in actual if pair not in expected]
        only_fonts = [pair for pair in expected if pair not in actual]
        for pair in only_source:
            print(f"FAIL in the source only: 0x{pair[0]:04X}..0x{pair[1]:04X}", file=sys.stderr)
        for pair in only_fonts:
            print(f"FAIL in the fonts only:  0x{pair[0]:04X}..0x{pair[1]:04X}", file=sys.stderr)
        print(f"FAIL {len(only_source)} range(s) only in {SOURCE.name}, {len(only_fonts)} only in the fonts", file=sys.stderr)
        return 1

    # A code point that no face carries is left to the platform, which is right for CJK and wrong for
    # anything else - so the ones inside the blocks are named rather than silently missing. They are
    # unassigned code points in practice, and this is what says so.
    orphans = [code for start, end in BLOCKS for code in range(start, end + 1)
               if code not in symbol and all(code not in face for face in faces.values())]
    if orphans:
        print(f"left to the platform: {len(orphans)} code point(s) in the blocks, e.g. "
              + ", ".join(f"U+{code:04X}" for code in orphans[:6]))
    print(f"OK {len(expected)} ranges match {SOURCE.relative_to(ROOT)}")

    if attribution(FONT_DIR / SYMBOL_FACE):
        return 1
    print(f"OK {SYMBOL_FACE} keeps its copyright and licence, drops the reserved name, "
          "and is named at its version in both documents")
    return 0


if __name__ == "__main__":
    sys.exit(main())
