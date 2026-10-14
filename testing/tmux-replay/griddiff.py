#!/usr/bin/env python3
"""Compare the app's grid with tmux's, and - with --bisect - name the read that first diverges.

The oracle is tmux itself, so this compares an emulator against an emulator. A differing row is an
incident: the app would show the user something tmux does not believe is on screen. tmux sends only the
cells it believes changed, so once a row differs tmux never resends it, and the difference stays for the
rest of the session - which is what makes this a count worth gating on.

  griddiff.py --frames rec.frames [--cols 52] [--rows 60] [--limit N] [--attrs]
  griddiff.py --app dump.txt --oracle oracle.txt
  griddiff.py --bisect rec.frames [--cols 52] [--rows 60] [--step 5]

Exit 0 when the grids match, 1 when they differ, 2 on a harness failure.

Rows are compared as text. Colour, attributes and cell widths are not, so a green run says the ink is
in the right place, not that it is drawn right.

[--attrs] adds a second comparison of the same rows: one label per column for the cell's *style*, so the
two grids are compared as "which columns share a style, and which are default". Text alone cannot see a
cell that carries a colour and no glyph, which is exactly the cell an erase leaves behind; that is the
difference this mode is for, and it is the one a green text run cannot rule out.

Three limits are worth stating where the mode is used rather than in a comment nobody reads:

- Only the *pattern* is compared - labels are assigned per row in order of first use, so `a` means "the
  first style this row painted" and not a palette entry. Comparing the colours themselves would compare
  this app's theme against tmux's palette, which is a real difference and not a divergence. A grid that
  consistently painted the wrong palette entry would pass.
- A blank cell's foreground is not compared, and its background and attributes are. A blank's foreground
  is invisible, and the two sides legitimately disagree about what a blank carries - this emulator keeps
  the pen's foreground on a cell it erased and tmux resets it - so comparing it would report the
  disagreement rather than a defect, on every erase in the corpus.
- The attributes compared are the ones the emulator models. SGR 5 and 6 (blink), 53 and 55 (overline),
  58 and 59 (underline colour) and the underline styles of 4 are not modelled, so a fixture that used
  them would compare as if it had not. That is a blind spot by decision and not by omission; the emulator
  records blink as nothing on purpose, and the reader ignores the same codes on tmux's side.
"""
import argparse
import pathlib
import re
import struct
import subprocess
import sys
import unicodedata

HERE = pathlib.Path(__file__).resolve().parent


class HarnessError(RuntimeError):
    pass


def read_frames(path):
    frames = []
    with open(path, "rb") as f:
        while True:
            head = f.read(4)
            if len(head) < 4:
                return frames
            length = struct.unpack(">I", head)[0]
            body = f.read(length)
            if len(body) != length:
                raise HarnessError(f"{path}: truncated frame {len(frames) + 1}")
            frames.append(body)


def escape(data):
    return "".join(
        c if 32 <= ord(c) < 127 else f"\\x{ord(c):02x}" for c in data.decode("latin-1")
    )


CURSOR = re.compile(r"^#.*cursor (\d+),(\d+) visible (true|false)", re.M)
# The tools' own header lines, matched by shape rather than by a leading `#`: a screen row that happens to
# start with one - a markdown heading in the output being replayed, say - is content, not a header.
HEADER = re.compile(r"^# (frames|cursor|reply|attrsraw|attrs)\b")
# The app's per-column style labels, and tmux's raw `capture-pane -e` rows. `attrsraw` is matched
# first: `attrs\b` does not match it, but saying so in the alternation is cheaper than proving it.
APP_ATTRS = re.compile(r"^# attrs (\d+) (.*)$", re.M)
RAW_ATTRS = re.compile(r"^# attrsraw (\d+) (.*)$", re.M)


def rows_of(text, header=False):
    return [
        line.rstrip()
        for line in text.splitlines()
        if not (header and HEADER.match(line))
    ]


def cursor_of(text):
    """The cursor the dump reported, as `(row, column, visible)`, or None if it reported none."""
    found = CURSOR.search(text)
    return (found.group(1), found.group(2), found.group(3)) if found else None


def app_attrs(text):
    """The app's per-column style labels, one string per row, in row order."""
    found = {int(row): keys for row, keys in APP_ATTRS.findall(text)}
    return [found[row] for row in sorted(found)]


def oracle_attrs(text):
    """tmux's raw `capture-pane -e` rows, in row order."""
    found = {int(row): raw for row, raw in RAW_ATTRS.findall(text)}
    return [found[row] for row in sorted(found)]


# A CSI ending in `m` is the only sequence this reader acts on. tmux's capture also emits `SO`/`SI` when a
# program used its own charset shift, and `ESC ( X` for a charset selection; both are stepped over, since
# neither says anything about a cell's style.
#
# OSC is stepped over for a stronger reason: `capture -e` re-emits the hyperlinks a pane printed, as
# `OSC 8 ; params ; URI ST` around the text they cover, and a URI is routinely longer than the pane is
# wide. A reader that does not recognise the sequence takes its body for text and counts its characters
# as columns, so any fixture - or any real recording from `git`, `ls` or `gh` - that contains a link
# fails with a width disagreement that is the reader's, not the emulator's.
#
# The terminator is optional because a link still open at the end of a line arrives without one: the
# body runs to the end of the row, or to the ESC that starts the next sequence, and either way it is
# stepped over rather than counted.
SGR = re.compile("\x1b\\[([0-9;:]*)m")
CSI = re.compile("\x1b\\[[0-9;?]*[ -/]*[@-~]")
CHARSET = re.compile("\x1b[()*+][ -~]")
OSC = re.compile("\x1b\\][^\x07\x1b]*(?:\x07|\x1b\\\\)?")

# The attributes this comparison can see, as the single letters a style key spells them with. They are
# exactly the fields of the emulator's `TerminalStyle` that paint a glyph, and the codes below are exactly
# the ones it records. SGR 5 and 6 (blink) are recorded as nothing on purpose - the emulator's own comment
# gives the reason, the battery cost of invalidating the view twice a second - so a blinking cell and a
# still one are the same style on both sides, and this reader ignores the codes rather than inventing a
# difference the emulator does not have.
#
# 53 and 55 (overline), 58 and 59 (underline colour) and the `:n` sub-parameter of 4 (underline style)
# are not modelled either, and are consumed and dropped for the same reason. SGR 53 is worth naming
# because tmux does not ignore it: a program that sends 53 gets `5:3` back from `capture -e`, which this
# reader takes as a blink and an underline style - both unmodelled - so the two sides agree about the
# underline and about nothing else. No fixture uses it, and one that wanted to would have to be built
# knowing that.
BOLD, DIM, ITALIC, UNDERLINE, REVERSE, HIDDEN, STRIKE = "b", "d", "i", "u", "r", "h", "s"
ON_CODES = {"1": BOLD, "2": DIM, "3": ITALIC, "4": UNDERLINE, "7": REVERSE, "8": HIDDEN, "9": STRIKE}
OFF_CODES = {
    "21": BOLD + DIM, "22": BOLD + DIM, "23": ITALIC, "24": UNDERLINE,
    "27": REVERSE, "28": HIDDEN, "29": STRIKE,
}

# The style a pane starts in, and what a cell with no style of its own carries.
DEFAULT_STYLE = (None, None, frozenset())


def apply_sgr(style, body):
    """The style after one SGR sequence: `(foreground, background, attributes)`.

    A slot is `None` for the default, `p<n>` for a palette entry and `rRRGGBB` for a true colour - the
    spellings tmux's own capture uses, and never compared against the app's, which resolves a palette
    entry to the theme's bytes. See `labelled` for why only the pattern is compared.
    """
    fg, bg, attrs = style
    attrs = set(attrs)
    parts = body.split(";")
    index = 0
    while index < len(parts):
        head, _, tail = parts[index].partition(":")
        code = int(head) if head.isdigit() else 0
        if code == 0:
            fg, bg, attrs = None, None, set()
        elif code == 39:
            fg = None
        elif code == 49:
            bg = None
        elif 30 <= code <= 37:
            fg = f"p{code - 30}"
        elif 40 <= code <= 47:
            bg = f"p{code - 40}"
        elif 90 <= code <= 97:
            fg = f"p{code - 90 + 8}"
        elif 100 <= code <= 107:
            bg = f"p{code - 100 + 8}"
        elif code in (38, 48, 58):
            slot, used = extended_colour(parts, index, tail)
            if code == 38 and slot is not None:
                fg = slot
            elif code == 48 and slot is not None:
                bg = slot
            index += used
        elif str(code) in ON_CODES:
            attrs.update(ON_CODES[str(code)])
        elif str(code) in OFF_CODES:
            attrs.difference_update(OFF_CODES[str(code)])
        index += 1
    return fg, bg, frozenset(attrs)


def extended_colour(parts, index, tail):
    """The colour a `38`/`48`/`58` introduces, and how many following parts it consumed.

    Two spellings are in the wild and tmux's capture emits both: `38;5;n` and `38:5:n` for a palette
    entry, `38;2;r;g;b` and `38:2:r:g:b` for a true colour. The sub-parameters are either inside the same
    part, after a colon, or in the parts that follow it. A mode this reader does not know is consumed as
    one part and yields nothing, so a sequence it cannot read is still not read as text.
    """
    if tail:
        mode = [int(part) if part.isdigit() else -1 for part in tail.split(":")]
        used = 0
    else:
        mode = [int(parts[index + 1]) if index + 1 < len(parts) and parts[index + 1].isdigit() else -1]
        used = 1
        needed = {5: 2, 2: 4}.get(mode[0], 1)
        while len(mode) < needed and index + used < len(parts):
            mode.append(int(parts[index + used]) if parts[index + used].isdigit() else -1)
            used += 1
    if len(mode) >= 2 and mode[0] == 5:
        return f"p{mode[1]}", used
    if len(mode) >= 4 and mode[0] == 2:
        r, g, b = (max(0, min(255, part)) for part in mode[1:4])
        return f"r{r:02X}{g:02X}{b:02X}", used
    return None, used


def character_width(character):
    """The columns a character takes: two for the wide forms, none for a mark that rides on another."""
    if unicodedata.combining(character) or unicodedata.category(character) in ("Mn", "Me", "Cf"):
        return 0
    return 2 if unicodedata.east_asian_width(character) in ("W", "F") else 1


def style_slots(raw, width, style):
    """One style slot per column, from a `capture-pane -e` row, given and returning the style in force.

    tmux writes the cells it has something to say about and stops: the trailing run of default blanks
    every row ends with is not written at all, and a row that is blank from end to end is one escape
    sequence and no text. Padding to the pane's width puts those cells back, and it is what makes a
    trimmed row and an untrimmed one compare as the same screen - which they are, and which they have to,
    because a coloured blank is written and a default one is not.

    The style carried in and out is not a detail. tmux's capture is one SGR stream across the whole pane,
    not one per row, so a row that opens with no escape is painted in whatever the row above ended in -
    which is exactly what an erase that reaches past the end of its line produces. Padding each row with
    its own last style instead reads every such row as default, and reports the erase as a divergence
    that is not there.
    """
    slots = []
    index = 0
    while index < len(raw):
        character = raw[index]
        if character == "\x1b":
            sgr = SGR.match(raw, index)
            if sgr:
                style = apply_sgr(style, sgr.group(1))
                index = sgr.end()
                continue
            other = CSI.match(raw, index) or CHARSET.match(raw, index) or OSC.match(raw, index)
            if other:
                index = other.end()
                continue
            index += 2 if index + 1 < len(raw) and raw[index + 1] in "NO" else 1
            continue
        slot = style_slot(style, character)
        for _ in range(character_width(character)):
            slots.append(slot)
        index += 1
    if len(slots) > width:
        raise HarnessError(
            f"a row of {len(slots)} columns in a {width}-column pane: the two sides disagree about a "
            f"character's width, so no column after it can be compared"
        )
    return slots + [style_slot(style, " ")] * (width - len(slots)), style


def style_slot(style, character):
    """The slot one column carries: the style in force, minus the foreground where no glyph is drawn.

    The foreground is dropped on a blank because the two sides legitimately disagree about what a blank
    carries. This emulator keeps the pen's foreground on a cell it erased - invisible either way, and
    kept so the blank can join the run beside it - and tmux resets it, so comparing it would report that
    disagreement as a defect on every erase in the corpus. The background is not dropped, and that
    asymmetry is the point: a blank's background is what the reader sees, and what an erase is judged on.
    """
    fg, bg, attrs = style
    return (None if character == " " else fg), bg, attrs


def labelled(slots):
    """Slots as one character per column: `.` for the default style, then a letter per distinct style.

    Labels rather than colours, and assigned in order of first use, so the two sides are compared as
    patterns. See the module docstring for why the colours themselves cannot be compared.
    """
    labels = {}
    keys = []
    for slot in slots:
        if slot == DEFAULT_STYLE:
            keys.append(".")
            continue
        if slot not in labels:
            if len(labels) >= len(LABELS):
                raise HarnessError(f"a row with more than {len(LABELS)} styles")
            labels[slot] = LABELS[len(labels)]
        keys.append(labels[slot])
    return "".join(keys)


LABELS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"


def app_dump(frames_path, cols, rows, limit=None, attrs=False):
    cmd = [str(HERE / "run-griddump.sh"), str(cols), str(rows)]
    if limit:
        cmd += ["--frames", str(limit)]
    if attrs:
        cmd += ["--attrs"]
    with open(frames_path, "rb") as stdin:
        done = subprocess.run(cmd, stdin=stdin, capture_output=True, text=True)
    if done.returncode != 0:
        raise HarnessError(f"run-griddump.sh failed: {done.stderr.strip()}")
    return done.stdout


def oracle_dump(frames_path, cols, rows, limit=None, attrs=False):
    cmd = [str(HERE / "oracle.sh"), str(frames_path), str(cols), str(rows)]
    if limit:
        cmd += ["--frames", str(limit)]
    if attrs:
        cmd += ["--attrs"]
    done = subprocess.run(cmd, capture_output=True, text=True)
    if done.returncode != 0:
        raise HarnessError(f"oracle.sh failed: {done.stderr.strip()}")
    return done.stdout


def app_rows(frames_path, cols, rows, limit=None, attrs=False):
    return rows_of(app_dump(frames_path, cols, rows, limit, attrs), header=True)


def oracle_rows(frames_path, cols, rows, limit=None, attrs=False):
    return rows_of(oracle_dump(frames_path, cols, rows, limit, attrs), header=True)


def oracle_keys(raw_rows, width):
    """tmux's rows as style labels, with the capture's SGR state carried from row to row."""
    style = DEFAULT_STYLE
    keys = []
    for raw in raw_rows:
        slots, style = style_slots(raw, width, style)
        keys.append(labelled(slots))
    return keys


def attrs_of(frames_path, cols, rows, limit=None):
    """Both sides of the style comparison: the app's labels, and tmux's derived from its own dump."""
    app_keys = app_attrs(app_dump(frames_path, cols, rows, limit, attrs=True))
    oracle_raw = oracle_attrs(oracle_dump(frames_path, cols, rows, limit, attrs=True))
    return app_keys, oracle_keys(oracle_raw, cols)


def first_difference(app, oracle):
    for column in range(max(len(app), len(oracle))):
        a = app[column] if column < len(app) else None
        o = oracle[column] if column < len(oracle) else None
        if a != o:
            return column, a, o
    return None


def classify(app, oracle):
    if not app.strip() and oracle.strip():
        return "blank"
    if app.strip() and not oracle.strip():
        return "ghost"
    if len(app) > len(oracle):
        return "extra"
    if len(app) < len(oracle):
        return "missing"
    return "mismatch"


def incidents_of(app, oracle):
    return [
        (index, app[index] if index < len(app) else "", oracle[index] if index < len(oracle) else "")
        for index in range(max(len(app), len(oracle)))
        if (app[index] if index < len(app) else "") != (oracle[index] if index < len(oracle) else "")
    ]


def compare(frames_path, cols, rows, limit=None):
    """Both sides of one prefix: the rows each ends on, and the cursor each reports."""
    app_text = app_dump(frames_path, cols, rows, limit)
    oracle_text = oracle_dump(frames_path, cols, rows, limit)
    return (
        rows_of(app_text, header=True),
        cursor_of(app_text),
        rows_of(oracle_text, header=True),
        cursor_of(oracle_text),
    )


def report(app, oracle, app_cursor=None, oracle_cursor=None, limit=None, quiet=False, styles=None):
    incidents = incidents_of(app, oracle)
    kinds = {}
    for _, a, o in incidents:
        kind = classify(a, o)
        kinds[kind] = kinds.get(kind, 0) + 1
    # Only when both sides reported one. A dump given to `--app`/`--oracle` carries no cursor, and
    # "neither said anything" is not agreement.
    cursor_differs = app_cursor is not None and oracle_cursor is not None and app_cursor != oracle_cursor
    styles = styles or []

    if not quiet or incidents or cursor_differs or styles:
        scope = f" (first {limit} reads)" if limit else ""
        width = max((len(row) for row in app), default=0)
        print(f"grid {len(app)} rows, widest {width} columns{scope}")
        if not incidents and not cursor_differs and not styles:
            print("verdict: IDENTICAL - every row matches tmux, the styles included, and so does the cursor")
            return 0
        if incidents:
            print(
                f"verdict: DIVERGES - {len(incidents)} incident(s): "
                + " ".join(f"{k} {v}" for k, v in sorted(kinds.items()))
            )
        elif styles:
            print(f"verdict: DIVERGES - the text matches, {len(styles)} row(s) of style do not")
        else:
            print("verdict: DIVERGES - the grid matches, the cursor does not")
        for index, a, o in incidents[:12]:
            column, _, _ = first_difference(a, o) or (0, None, None)
            print(f"  row {index}: {classify(a, o)}")
            print(f"    app    |{a}|")
            print(f"    oracle |{o}|")
            print(f"    first difference at column {column}")
        if len(incidents) > 12:
            print(f"  ... and {len(incidents) - 12} more")
        for index, a, o in styles[:12]:
            column, _, _ = first_difference(a, o) or (0, None, None)
            print(f"  row {index}: style differs ('.' is the default style, a letter is a style the row paints)")
            print(f"    app    |{a}|")
            print(f"    tmux   |{o}|")
            print(f"    first difference at column {column}")
        if len(styles) > 12:
            print(f"  ... and {len(styles) - 12} more")
        if cursor_differs:
            print(f"  cursor: app {app_cursor[0]},{app_cursor[1]} visible {app_cursor[2]}")
            print(f"          tmux {oracle_cursor[0]},{oracle_cursor[1]} visible {oracle_cursor[2]}")
    return 1 if (incidents or cursor_differs or styles) else 0


def bisect(frames_path, cols, rows, step, attrs=False):
    frames = read_frames(frames_path)
    print(f"{len(frames)} reads recorded")

    def divergence(count):
        """What differs at this prefix, described, or None when the two agree."""
        app, app_cursor, oracle, oracle_cursor = compare(frames_path, cols, rows, count)
        incidents = incidents_of(app, oracle)
        if incidents:
            return f"{len(incidents)} row incident(s), first: |{incidents[0][1]}| vs |{incidents[0][2]}|"
        if app_cursor is not None and oracle_cursor is not None and app_cursor != oracle_cursor:
            return f"cursor {app_cursor} vs {oracle_cursor}"
        if attrs:
            styles = incidents_of(*attrs_of(frames_path, cols, rows, count))
            if styles:
                index, app_keys, oracle_keys = styles[0]
                return f"row {index} style: |{app_keys}| vs |{oracle_keys}|"
        return None

    coarse = None
    found = None
    for count in range(step, len(frames) + 1, step):
        found = divergence(count)
        if found:
            coarse = count
            break
    if coarse is None:
        last = divergence(len(frames))
        if last is None:
            print(f"no divergence at any of {(len(frames) - 1) // step + 1} sampled prefixes, the full replay included")
            if step != 1:
                print("(every prefix is not the same claim; rerun with --step 1 to check them all)")
            return 0
        coarse, found = len(frames), last

    low = max(0, coarse - step)
    for count in range(low + 1, coarse + 1):
        if divergence(count):
            offset = sum(len(f) + 4 for f in frames[: count - 1])
            print(f"\nfirst diverging read: {count} of {len(frames)} (byte offset {offset} in the recording)")
            print(f"  {escape(frames[count - 1])}")
            print("\nfull report at that read:")
            app, app_cursor, oracle, oracle_cursor = compare(frames_path, cols, rows, count)
            styles = incidents_of(*attrs_of(frames_path, cols, rows, count)) if attrs else None
            report(app, oracle, app_cursor, oracle_cursor, count, styles=styles)
            return 1
    # A flagged prefix that will not reproduce is the oracle's problem, not the app's: tmux was captured
    # mid-parse. Saying so beats exiting 1 with no report, which reads as a divergence nobody can find.
    print(f"read {coarse} diverged in the coarse scan ({found}) and not on re-check", file=sys.stderr)
    print("the comparison is not reproducible at that prefix; rerun before believing either answer", file=sys.stderr)
    return 2


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--frames")
    parser.add_argument("--cols", type=int, default=52)
    parser.add_argument("--rows", type=int, default=60)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--app")
    parser.add_argument("--oracle")
    parser.add_argument("--bisect", action="store_true")
    parser.add_argument("--step", type=int, default=5)
    parser.add_argument("--attrs", action="store_true", help="compare each row's styles as well as its text")
    args = parser.parse_args()

    try:
        if args.app and args.oracle:
            with open(args.app) as f:
                app_text = f.read()
            with open(args.oracle) as f:
                oracle_text = f.read()
            styles = None
            if args.attrs:
                styles = incidents_of(app_attrs(app_text), oracle_keys(oracle_attrs(oracle_text), args.cols))
            return report(
                rows_of(app_text, header=True),
                rows_of(oracle_text, header=True),
                cursor_of(app_text),
                cursor_of(oracle_text),
                styles=styles,
            )
        if not args.frames:
            parser.error("--frames, or --app with --oracle, is required")
        if args.bisect:
            return bisect(args.frames, args.cols, args.rows, args.step, args.attrs)
        app, app_cursor, oracle, oracle_cursor = compare(args.frames, args.cols, args.rows, args.limit)
        styles = incidents_of(*attrs_of(args.frames, args.cols, args.rows, args.limit)) if args.attrs else None
        return report(app, oracle, app_cursor, oracle_cursor, args.limit, styles=styles)
    except HarnessError as error:
        print(f"harness failure: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
