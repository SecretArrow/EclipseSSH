#!/usr/bin/env python3
"""Compare the app's grid with tmux's, and - with --bisect - name the read that first diverges.

The oracle is tmux itself, so this compares an emulator against an emulator. A differing row is an
incident: the app would show the user something tmux does not believe is on screen. tmux sends only the
cells it believes changed, so once a row differs tmux never resends it, and the difference stays for the
rest of the session - which is what makes this a count worth gating on.

  griddiff.py --frames rec.frames [--cols 52] [--rows 60] [--limit N]
  griddiff.py --app dump.txt --oracle oracle.txt
  griddiff.py --bisect rec.frames [--cols 52] [--rows 60] [--step 5]

Exit 0 when the grids match, 1 when they differ, 2 on a harness failure.

Only row *text* is compared. Colour, attributes and cell widths are not, so a green run says the ink is
in the right place, not that it is drawn right.
"""
import argparse
import pathlib
import re
import struct
import subprocess
import sys

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
HEADER = re.compile(r"^# (frames|cursor|reply)\b")


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


def app_dump(frames_path, cols, rows, limit=None):
    cmd = [str(HERE / "run-griddump.sh"), str(cols), str(rows)]
    if limit:
        cmd += ["--frames", str(limit)]
    with open(frames_path, "rb") as stdin:
        done = subprocess.run(cmd, stdin=stdin, capture_output=True, text=True)
    if done.returncode != 0:
        raise HarnessError(f"run-griddump.sh failed: {done.stderr.strip()}")
    return done.stdout


def oracle_dump(frames_path, cols, rows, limit=None):
    cmd = [str(HERE / "oracle.sh"), str(frames_path), str(cols), str(rows)]
    if limit:
        cmd += ["--frames", str(limit)]
    done = subprocess.run(cmd, capture_output=True, text=True)
    if done.returncode != 0:
        raise HarnessError(f"oracle.sh failed: {done.stderr.strip()}")
    return done.stdout


def app_rows(frames_path, cols, rows, limit=None):
    return rows_of(app_dump(frames_path, cols, rows, limit), header=True)


def oracle_rows(frames_path, cols, rows, limit=None):
    return rows_of(oracle_dump(frames_path, cols, rows, limit), header=True)


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


def report(app, oracle, app_cursor=None, oracle_cursor=None, limit=None, quiet=False):
    incidents = incidents_of(app, oracle)
    kinds = {}
    for _, a, o in incidents:
        kind = classify(a, o)
        kinds[kind] = kinds.get(kind, 0) + 1
    # Only when both sides reported one. A dump given to `--app`/`--oracle` carries no cursor, and
    # "neither said anything" is not agreement.
    cursor_differs = app_cursor is not None and oracle_cursor is not None and app_cursor != oracle_cursor

    if not quiet or incidents or cursor_differs:
        scope = f" (first {limit} reads)" if limit else ""
        width = max((len(row) for row in app), default=0)
        print(f"grid {len(app)} rows, widest {width} columns{scope}")
        if not incidents and not cursor_differs:
            print("verdict: IDENTICAL - every row matches tmux, and so does the cursor")
            return 0
        if incidents:
            print(
                f"verdict: DIVERGES - {len(incidents)} incident(s): "
                + " ".join(f"{k} {v}" for k, v in sorted(kinds.items()))
            )
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
        if cursor_differs:
            print(f"  cursor: app {app_cursor[0]},{app_cursor[1]} visible {app_cursor[2]}")
            print(f"          tmux {oracle_cursor[0]},{oracle_cursor[1]} visible {oracle_cursor[2]}")
    return 1 if (incidents or cursor_differs) else 0


def bisect(frames_path, cols, rows, step):
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
            report(app, oracle, app_cursor, oracle_cursor, count)
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
    args = parser.parse_args()

    try:
        if args.app and args.oracle:
            with open(args.app) as f:
                app_text = f.read()
            with open(args.oracle) as f:
                oracle_text = f.read()
            return report(
                rows_of(app_text, header=True),
                rows_of(oracle_text, header=True),
                cursor_of(app_text),
                cursor_of(oracle_text),
            )
        if not args.frames:
            parser.error("--frames, or --app with --oracle, is required")
        if args.bisect:
            return bisect(args.frames, args.cols, args.rows, args.step)
        app, app_cursor, oracle, oracle_cursor = compare(args.frames, args.cols, args.rows, args.limit)
        return report(app, oracle, app_cursor, oracle_cursor, args.limit)
    except HarnessError as error:
        print(f"harness failure: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
