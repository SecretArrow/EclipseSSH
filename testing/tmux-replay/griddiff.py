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


def rows_of(text, header=False):
    rows = []
    for line in text.splitlines():
        if header and line.startswith("#"):
            continue
        rows.append(line.rstrip())
    return rows


def oracle_rows(frames_path, cols, rows, limit=None):
    cmd = [str(HERE / "oracle.sh"), str(frames_path), str(cols), str(rows)]
    if limit:
        cmd += ["--frames", str(limit)]
    done = subprocess.run(cmd, capture_output=True, text=True)
    if done.returncode != 0:
        raise HarnessError(f"oracle.sh failed: {done.stderr.strip()}")
    return rows_of(done.stdout)


def app_rows(frames_path, cols, rows, limit=None):
    cmd = [str(HERE / "run-griddump.sh"), str(cols), str(rows)]
    if limit:
        cmd += ["--frames", str(limit)]
    with open(frames_path, "rb") as stdin:
        done = subprocess.run(cmd, stdin=stdin, capture_output=True, text=True)
    if done.returncode != 0:
        raise HarnessError(f"run-griddump.sh failed: {done.stderr.strip()}")
    return rows_of(done.stdout, header=True)


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


def report(app, oracle, limit=None, quiet=False):
    incidents = incidents_of(app, oracle)
    kinds = {}
    for _, a, o in incidents:
        kind = classify(a, o)
        kinds[kind] = kinds.get(kind, 0) + 1

    if not quiet or incidents:
        scope = f" (first {limit} reads)" if limit else ""
        width = max((len(row) for row in app), default=0)
        print(f"grid {len(app)} rows, widest {width} columns{scope}")
        if not incidents:
            print("verdict: IDENTICAL - every row matches tmux")
            return 0
        print(
            f"verdict: DIVERGES - {len(incidents)} incident(s): "
            + " ".join(f"{k} {v}" for k, v in sorted(kinds.items()))
        )
        for index, a, o in incidents[:12]:
            column, _, _ = first_difference(a, o) or (0, None, None)
            print(f"  row {index}: {classify(a, o)}")
            print(f"    app    |{a}|")
            print(f"    oracle |{o}|")
            print(f"    first difference at column {column}")
        if len(incidents) > 12:
            print(f"  ... and {len(incidents) - 12} more")
    return 1 if incidents else 0


def bisect(frames_path, cols, rows, step):
    frames = read_frames(frames_path)
    print(f"{len(frames)} reads recorded")

    def diverges(count):
        return bool(incidents_of(app_rows(frames_path, cols, rows, count), oracle_rows(frames_path, cols, rows, count)))

    coarse = None
    for count in range(step, len(frames) + 1, step):
        if diverges(count):
            coarse = count
            break
    if coarse is None:
        if not diverges(len(frames)):
            print("no prefix diverges; the grids differ only in the complete replay")
            return 0
        coarse = len(frames)

    low = max(0, coarse - step)
    for count in range(low + 1, coarse + 1):
        if diverges(count):
            offset = sum(len(f) + 4 for f in frames[: count - 1])
            print(f"\nfirst diverging read: {count} of {len(frames)} (byte offset {offset} in the recording)")
            print(f"  {escape(frames[count - 1])}")
            print("\nfull report at that read:")
            report(app_rows(frames_path, cols, rows, count), oracle_rows(frames_path, cols, rows, count), count)
            return 1
    return 1


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
                app = rows_of(f.read(), header=True)
            with open(args.oracle) as f:
                oracle = rows_of(f.read())
            return report(app, oracle)
        if not args.frames:
            parser.error("--frames, or --app with --oracle, is required")
        if args.bisect:
            return bisect(args.frames, args.cols, args.rows, args.step)
        return report(
            app_rows(args.frames, args.cols, args.rows, args.limit),
            oracle_rows(args.frames, args.cols, args.rows, args.limit),
            args.limit,
        )
    except HarnessError as error:
        print(f"harness failure: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
