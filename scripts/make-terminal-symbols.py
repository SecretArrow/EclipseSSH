#!/usr/bin/env python3
"""Rebuilds `app/src/main/res/font/terminal_symbols.ttf` from JuliaMono.

This is the provenance of a binary the app ships. The file is 212 KB of font that no reviewer can read,
so what makes it trustworthy is that it is cut by a script from a named upstream release and that
`scripts/terminal-symbols.py` re-derives the table it stands behind from the file itself. Neither of
those is true of a `.ttf` somebody dropped in.

What it does, in order: takes the code points the two bundled JetBrains Mono faces have no glyph for
inside the blocks a terminal draws symbols from - the same list, from the same module, that the checker
uses, so the two cannot disagree about what the face is for - cuts JuliaMono down to exactly those,
renames the result (the SIL Open Font License reserves the upstream name), and rewrites the vertical
metrics to JetBrains Mono's so a symbol sits on the same baseline as the text beside it.

It needs `fontTools`, which is not a dependency of anything else here, and the upstream font, which is
not in the repository - a 22 MB tarball of every JuliaMono cut. With `--source` it uses a copy already
on disk; without it, it downloads the pinned release and checks the extracted member against the hash
below. It never edits the Kotlin source: it prints the table for `GlyphFaces.kt`, and
`scripts/terminal-symbols.py` is what fails when the two have drifted apart.

Usage:
  scripts/make-terminal-symbols.py                       # fetch, cut, write the bundled font
  scripts/make-terminal-symbols.py --source JuliaMono-Regular.ttf
  scripts/make-terminal-symbols.py --output /tmp/probe.ttf --source ...   # cut, but do not install
"""
import argparse
import hashlib
import importlib.util
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
FONT_DIR = ROOT / "app" / "src" / "main" / "res" / "font"
CHECKER = HERE / "terminal-symbols.py"

UPSTREAM_RELEASE = "v0.63.2"
UPSTREAM_URL = f"https://github.com/cormullion/juliamono/releases/download/{UPSTREAM_RELEASE}/JuliaMono-ttf.tar.gz"
UPSTREAM_MEMBER = "JuliaMono-Regular.ttf"
# The bytes that were actually cut, not the tarball's: a wrong hash on the member is the failure that
# matters, and it is also the one that survives a re-packaged release asset.
UPSTREAM_SHA256 = "40a07da0d1601215eb6b89312eb44128a3e2f36675d3e1f518264bd391fc7023"

# JetBrains Mono is cut to a 1000-unit em and this face to 2000, so every vertical figure below is
# twice the JetBrains Mono one: hhea and typo 1020/-300, win 1165/400, cap 730, x-height 550. A symbol
# that does not share the text's baseline is the same misalignment as a symbol that does not share its
# advance, which is the reason the face exists at all.
VERTICAL = {
    "hhea.ascent": 2040, "hhea.descent": -600, "hhea.lineGap": 0,
    "os2.sTypoAscender": 2040, "os2.sTypoDescender": -600, "os2.sTypoLineGap": 0,
    "os2.usWinAscent": 2330, "os2.usWinDescent": 800,
    "os2.sCapHeight": 1460, "os2.sxHeight": 1100,
}
FAMILY = "EclipseSSH Terminal Symbols"
SUBFAMILY = "Regular"
POSTSCRIPT = "EclipseSSHTerminalSymbols-Regular"


def checker():
    """The checker module, so the block list and the cmap reader are shared rather than copied."""
    spec = importlib.util.spec_from_file_location("terminal_symbols", CHECKER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def upstream(source):
    """The upstream regular cut, from `source` if given, else downloaded and verified."""
    if source is not None:
        data = Path(source).read_bytes()
    else:
        print(f"downloading {UPSTREAM_URL}")
        with urllib.request.urlopen(UPSTREAM_URL) as response:
            archive = response.read()
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / "JuliaMono-ttf.tar.gz"
            path.write_bytes(archive)
            with tarfile.open(path) as tar:
                member = next((m for m in tar.getmembers() if Path(m.name).name == UPSTREAM_MEMBER), None)
                if member is None:
                    raise SystemExit(f"FAIL the release carries no {UPSTREAM_MEMBER}")
                data = tar.extractfile(member).read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != UPSTREAM_SHA256:
        raise SystemExit(f"FAIL upstream {UPSTREAM_MEMBER} is {digest}, not {UPSTREAM_SHA256}")
    print(f"upstream {UPSTREAM_MEMBER}: {len(data)} bytes, sha256 verified")
    return data


def cut(source, wanted, output):
    """Runs the subsetter: no layout features, no hinting, every name record kept for the rename.

    `--no-recalc-timestamp` so the output is a function of the input and nothing else: without it the
    subsetter stamps `head.modified` with the clock, and two runs of this script on the same upstream
    font produce two files that differ in four bytes and in nothing else.
    """
    subprocess.run(
        [sys.executable, "-m", "fontTools.subset", str(source),
         "--unicodes=" + ",".join(f"U+{code:04X}" for code in wanted),
         "--layout-features=", "--no-hinting", "--drop-tables+=DSIG",
         "--no-recalc-timestamp", "--name-IDs=*", f"--output-file={output}"],
        check=True,
    )


def rewrite(path):
    """Renames the face and moves it onto JetBrains Mono's vertical metrics."""
    from fontTools.ttLib import TTFont

    font = TTFont(path)
    # Same reason as the subsetter's flag: a fresh `TTFont` restamps `head.modified` on save by
    # default, which would put the clock back into the one field this script goes out of its way to
    # keep out of the output.
    font.recalcTimestamp = False
    font["hhea"].ascent = VERTICAL["hhea.ascent"]
    font["hhea"].descent = VERTICAL["hhea.descent"]
    font["hhea"].lineGap = VERTICAL["hhea.lineGap"]
    os2 = font["OS/2"]
    os2.sTypoAscender = VERTICAL["os2.sTypoAscender"]
    os2.sTypoDescender = VERTICAL["os2.sTypoDescender"]
    os2.sTypoLineGap = VERTICAL["os2.sTypoLineGap"]
    os2.usWinAscent = VERTICAL["os2.usWinAscent"]
    os2.usWinDescent = VERTICAL["os2.usWinDescent"]
    os2.sCapHeight = VERTICAL["os2.sCapHeight"]
    os2.sxHeight = VERTICAL["os2.sxHeight"]
    # Name ids 0, 5 and 13 - the copyright, the version and the licence - are left as upstream wrote
    # them, which is what the licence asks for. Only the names that would carry the reserved one move.
    for record in font["name"].names:
        if record.nameID in (1, 16):
            record.string = FAMILY
        elif record.nameID in (2, 17):
            record.string = SUBFAMILY
        elif record.nameID == 4:
            record.string = f"{FAMILY} {SUBFAMILY}"
        elif record.nameID == 6:
            record.string = POSTSCRIPT
        elif record.nameID == 3:
            record.string = f"{POSTSCRIPT};1.0"
    font.save(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--source", type=Path, help="an upstream JuliaMono-Regular.ttf already on disk")
    parser.add_argument("--output", type=Path, default=FONT_DIR / "terminal_symbols.ttf")
    args = parser.parse_args()

    module = checker()
    mono = [module.codepoints(FONT_DIR / name) for name in module.TEXT_FACES]
    upstream_path = None
    with tempfile.TemporaryDirectory() as scratch:
        if args.source is None:
            upstream_path = Path(scratch) / UPSTREAM_MEMBER
            upstream_path.write_bytes(upstream(None))
        else:
            upstream_path = args.source
            digest = hashlib.sha256(upstream_path.read_bytes()).hexdigest()
            if digest != UPSTREAM_SHA256:
                raise SystemExit(f"FAIL {upstream_path} is {digest}, not {UPSTREAM_SHA256}")
        julia = module.codepoints(upstream_path)

        wanted = [code for start, end in module.BLOCKS for code in range(start, end + 1)
                  if code in julia and all(code not in face for face in mono)]
        print(f"{len(wanted)} code points to cut")

        staged = Path(scratch) / "terminal_symbols.ttf"
        cut(upstream_path, wanted, staged)
        rewrite(staged)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(staged, args.output)
        size = args.output.stat().st_size

    # The table as `GlyphFaces.kt` spells it, derived from the file just written rather than from the
    # list that produced it - so the two agreeing is a measurement, not a tautology.
    cut_points = module.codepoints(args.output)
    table = module.ranges(module.routed(list(mono), cut_points))
    print(f"wrote {args.output} ({size} bytes)")
    print(f"{sum(b - a + 1 for a, b in table)} code points in {len(table)} ranges; "
          f"paste into GlyphFaces.kt, then run scripts/terminal-symbols.py:")
    for start, end in table:
        print(f"    0x{start:04X}, 0x{end:04X},")
    return 0


if __name__ == "__main__":
    sys.exit(main())
