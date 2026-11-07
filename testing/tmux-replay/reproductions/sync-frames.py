#!/usr/bin/env python3
"""The shape that makes tmux batch a repaint, so the recording contains a synchronized-output pair.

`record.py --answer` and `reproductions/sync.conf` are the other two thirds of it: tmux 3.5a never asks
its terminal about `2026`, so it wraps its repaints in `ESC [ ? 2026 h`/`l` only when `sync` is declared
for the terminal. Without all three, no recording can contain a batch and a replay of one proves nothing
about what the app does with it.

Each frame is painted in several writes with a pause between them, which is what an Ink TUI does and
what a batch is for: the intermediate states are exactly what must not be shown.

  python3 record.py /tmp/sync.frames 6 --cols 52 --rows 60 --config reproductions/sync.conf --answer \
    -- python3 reproductions/sync-frames.py
  ./replay.sh /tmp/sync.frames --cols 52 --rows 60
"""
import sys
import time

cols = 52
rows = 60
SPINNER = "|/-\\"


def at(row: int, column: int = 1) -> None:
    sys.stdout.write(f"\x1b[{row};{column}H")


for step in range(12):
    # One "frame", written the way a repainting TUI writes one: a status line and a progress line, each
    # in its own write, so tmux has a repaint to batch rather than a single line to append.
    at(10)
    sys.stdout.write("\x1b[2K" + f"{SPINNER[step % 4]} Building {step}/12".ljust(cols - 1))
    sys.stdout.flush()
    time.sleep(0.05)
    at(11)
    sys.stdout.write("\x1b[2K" + ("#" * (step + 1)).ljust(cols - 1))
    sys.stdout.flush()
    time.sleep(0.05)

at(rows - 1)
sys.stdout.write("done\r\n")
sys.stdout.flush()
