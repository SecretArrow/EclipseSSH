#!/usr/bin/env python3
"""Re-derives the terminal's width tables from the Unicode data and checks the source against them.

`TerminalWidth.kt` decides how many columns a character spends on the grid, and it does that with
three tables: which code points are wide, which marks take no column, and which non-mark characters
take none either. A table like that is exactly the kind of thing that goes stale in silence. Unicode
adds characters every year, and every one of them is a cell that lands in the wrong column - or a
column of text that walks one cell further left on every line - in a way no layer of the app can
report, because a wrong column is a perfectly legal column.

Two of the three are therefore derived rather than written, and this is what re-derives them:

    marks = (gc is Mn or Me) - the reference's own non-mark list - NARROW_MARKS
    wide  = (East_Asian_Width is W or F, and the code point is assigned)
            - (marks + the reference's own non-mark list)
            + the code points the reference counts wide that the standard does not

The rule is not invented here: it is what the tables already are, and the derivation reproduces the
committed ranges exactly, boundary for boundary. What it buys is that the next Unicode release is a
diff instead of a discovery.

The one input that cannot be derived is the reference's non-mark list - the joiners, the directional
marks, the Hangul fillers, the tag characters - because no Unicode data file contains it. That list,
`ZERO_WIDTH_RANGES`, and `NARROW_MARKS` are read out of the Kotlin source and used as inputs, so the
blocks this script checks are the two that can be.

Usage:
  scripts/terminal-width.py                # check; exit 1 on any disagreement
  scripts/terminal-width.py --check        # the same, spelled out
  scripts/terminal-width.py --print        # print both blocks as the Kotlin source spells them
  scripts/terminal-width.py --source DIR   # read the Unicode data from DIR instead of fetching it

It needs no fontTools, no JDK and nothing to install: it reads two text files and does arithmetic on
them, because it has to run in CI next to `check-doc-figures.sh`. It is a check, never a fixer, for
the same reason `scripts/terminal-symbols.py` is: a disagreement is as likely to mean the Unicode
data was updated as it is to mean the table is wrong, and a script that rewrote the source would hide
which. `--print` is what the answer looks like when a person has decided.
"""
import argparse
import hashlib
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app" / "src" / "main" / "java" / "dev" / "eclipse" / "ssh" / "terminal" / "TerminalWidth.kt"

# Pinned to one Unicode release, by hash as well as by URL, so that a check is a statement about a
# version and not about whatever the network served today. A release is picked up by editing these
# three lines and re-reading the diff, which is the whole point of pinning them.
VERSION = "16.0.0"
BASE = f"https://www.unicode.org/Public/{VERSION}/ucd"
FILES = {
    "EastAsianWidth.txt": "43adc76c0686a42cb370764eb8cfe2b2a45b10b855e5572a2db4a0eecce15d5b",
    "extracted/DerivedGeneralCategory.txt": "7676ab755a41ef82108460238569e60ad65c191ddafe61b36c6765ec1353f293",
}

# The code points the reference counts as two columns and the standard does not. Measured, not
# reasoned about: glibc's `wcwidth` under `C.UTF-8` answers 2 for these eight, and the standard calls
# them ambiguous, which is one column everywhere else. The emulator follows the reference here for the
# same reason it follows it at all - the far side of the pty is what it has to agree with - and the
# Kotlin says so where the table is. A second entry belongs here only with a measurement behind it.
REFERENCE_WIDE = [(0x3248, 0x324F)]

BEGIN_MARKS = "// BEGIN GENERATED zero-width-marks"
END_MARKS = "// END GENERATED zero-width-marks"
BEGIN_WIDE = "// BEGIN GENERATED wide-ranges"
END_WIDE = "// END GENERATED wide-ranges"


def fetch(name, directory):
    """The pinned data file, from `directory` if it is there and from the network otherwise."""
    local = directory / name if directory else None
    if local is not None and local.is_file():
        data = local.read_bytes()
    else:
        url = f"{BASE}/{name}"
        print(f"fetching {url}")
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != FILES[name]:
        sys.exit(
            f"{name}: sha256 is {digest},\n  expected {FILES[name]}\n"
            f"  Either the Unicode release moved under a pinned URL, or this is not the file it says."
        )
    return data.decode("utf-8")


def properties(text):
    """A data file as {property value: set of code points}, for the fields it names in field 1."""
    out = {}
    for line in text.splitlines():
        line = line.split("#")[0].strip()
        if not line:
            continue
        field, value = [part.strip() for part in line.split(";")[:2]]
        if ".." in field:
            first, last = field.split("..")
            points = range(int(first, 16), int(last, 16) + 1)
        else:
            points = (int(field, 16),)
        out.setdefault(value, set()).update(points)
    return out


def kotlin_ranges(source, name=None, marker=None):
    """The ranges of one table, as a set of code points.

    With a marker, the block between the two marker lines. Without one, the named list: the two inputs
    this script reads out of the source are hand-kept, so they have no generated block to read.
    """
    if marker is not None:
        begin, end = (BEGIN_MARKS, END_MARKS) if marker == "marks" else (BEGIN_WIDE, END_WIDE)
        body = source.split(begin, 1)[1].split(end, 1)[0]
    else:
        body = source.split(f"private val {name} = ", 1)[1].split("\n)", 1)[0]
    points = set()
    for match in re.finditer(r"0x([0-9A-Fa-f]+)(?:\.\.0x([0-9A-Fa-f]+))?", body):
        first = int(match.group(1), 16)
        last = int(match.group(2), 16) if match.group(2) else first
        points.update(range(first, last + 1))
    return points


def spans(points):
    """The points as the maximal `(first, last)` runs a table is written in."""
    runs = []
    for point in sorted(points):
        if runs and point == runs[-1][1] + 1:
            runs[-1][1] = point
        else:
            runs.append([point, point])
    return [tuple(run) for run in runs]


def derive(source, directory):
    """The two generated tables, as sets of code points, from the pinned data and the hand-kept lists."""
    eaw = properties(fetch("EastAsianWidth.txt", directory))
    category = properties(fetch("extracted/DerivedGeneralCategory.txt", directory))

    wide = set()
    for value in ("W", "F"):
        wide |= eaw.get(value, set())
    assigned = set()
    for value, points in category.items():
        if value != "Cn":
            assigned |= points
    marks = category.get("Mn", set()) | category.get("Me", set())

    # The reference's own non-mark list is an input rather than a derivation, and it is subtracted from
    # the marks as well as added to the zero-width set, so that a code point the standard calls a mark
    # and the reference counts as one column is not in both.
    uncolumned = kotlin_ranges(source, "ZERO_WIDTH_RANGES") | kotlin_ranges(source, "NARROW_MARKS")
    marks -= uncolumned
    zero = marks | uncolumned

    for first, last in REFERENCE_WIDE:
        wide |= set(range(first, last + 1))
    wide &= assigned
    wide -= zero
    return marks, wide


def kotlin_block(name, runs):
    """One table as the Kotlin source spells it: `0x` on both ends, five hex digits at least.

    Five rather than four because it is what the tables were already written with, so printing one
    reproduces the block in the source byte for byte - the check is then about the code points and
    nothing else, and a diff after a Unicode release is the ranges that moved.
    """
    lines = [f"private val {name} = listOf("]
    lines += [f"    0x{first:05X}..0x{last:05X}," for first, last in runs]
    lines.append(")")
    return "\n".join(lines)


def report(what, expected, actual):
    """What differs, named as the ranges a person has to look at."""
    missing, extra = spans(expected - actual), spans(actual - expected)
    print(f"{what}: {len(expected)} code points in {len(spans(expected))} ranges, source has {len(actual)}")
    for label, runs in (("missing from the source", missing), ("not in the derivation", extra)):
        if runs:
            print(f"  {label}:")
            for first, last in runs:
                print(f"    U+{first:04X}..U+{last:04X}")
    return not missing and not extra


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="check the source (the default)")
    parser.add_argument("--print", dest="show", action="store_true", help="print both blocks")
    parser.add_argument(
        "--source",
        type=Path,
        help="a directory holding the two data files, at the paths their URLs end in",
    )
    arguments = parser.parse_args()

    source = SOURCE.read_text()
    marks, wide = derive(source, arguments.source)

    if arguments.show:
        print(BEGIN_MARKS)
        print(kotlin_block("ZERO_WIDTH_MARKS", spans(marks)))
        print(END_MARKS)
        print()
        print(BEGIN_WIDE)
        print(kotlin_block("WIDE_RANGES", spans(wide)))
        print(END_WIDE)
        return 0

    print(f"Unicode {VERSION}: {len(wide)} wide code points, {len(marks)} zero-width marks")
    marks_match = report("zero-width marks", marks, kotlin_ranges(source, marker="marks"))
    wide_match = report("wide ranges", wide, kotlin_ranges(source, marker="wide"))
    if marks_match and wide_match:
        print("Both tables are the ones the Unicode data gives.")
        return 0
    print("\nRe-run with --print to see the blocks, and read the diff before committing it.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
