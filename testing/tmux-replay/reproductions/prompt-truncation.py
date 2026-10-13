#!/usr/bin/env python3
"""A stand-in for the Ink TUI, painting the one shape B4 is about: a program's own ellipsis.

Runs inside a tmux pane while record.py captures what tmux sends its client, so the recording exercises
the whole chain (program -> tmux -> client bytes) without needing the real [CC] on the recording host.

The report's B4 says a sent prompt appears twice, the first copy cut with a UI-style ellipsis, and asks
the app to find the render path that ellipsizes terminal text. There is no such path, and this is what
the two copies actually are: the *program* wraps the prompt into its transcript, and the *program* also
prints the same prompt into a narrow column of its own where it truncates it with `…`. Both are output;
the app's job is to carry both without adding a cut or a duplicate of its own.

  python3 prompt-truncation.py
"""
import shutil
import sys
import time

cols, rows = shutil.get_terminal_size((52, 60))

PROMPT = (
    "tolong lanjutkan tugas yang mati karena saldo habis sebelumnya lalu periksa "
    "apakah semua perubahan sudah ter-commit dengan benar"
)


def write(text):
    sys.stdout.write(text)
    sys.stdout.flush()


def at(row, col=1):
    write(f"\x1b[{row};{col}H")


def line(row, text):
    at(row)
    write("\x1b[2K" + text)


def wrap(text, width):
    """Greedy wrapping, which is what the program does to its own prose before the app ever sees it."""
    out = []
    current = ""
    for word in text.split():
        candidate = f"{current} {word}".strip()
        if len(candidate) > width and current:
            out.append(current)
            current = word
        else:
            current = candidate
    if current:
        out.append(current)
    return out


write("\x1b[2J")
write("\x1b[?25l")

line(1, "─" * min(cols, 52))
line(2, "  [CC]   the prompt, as the program echoes it")
line(3, "─" * min(cols, 52))

# Copy two: the program wraps its own echo of the prompt across rows. Nothing here is cut.
row = 5
for piece in wrap("❯ " + PROMPT, cols - 2):
    line(row, piece)
    row += 1

# Copy one: the same prompt in a column the program made narrow, so *the program* truncates it and
# writes the ellipsis itself. This is the copy the report reads as the app's doing.
narrow = cols - 24
head = PROMPT[:narrow - 1] + "…"
line(row + 1, f"✻ {head}   37s")
line(row + 2, "  ✻ another task with a long name…   2m")

# And a status row the program truncates at the pane's own edge, which is where the report's
# `⏵⏵ bypass permissions on · ← 1 agent · PR #1 · 1 monitor` ends up too.
line(rows - 1, ("⏵⏵ auto mode on (shift+tab to cycle) · ← for agents · esc to interrupt")[: cols - 1])

# One tick of movement, so this is a live TUI rather than a still: a recording of a still cannot show
# whether a repaint duplicates what it replaces.
time.sleep(0.4)
line(row + 1, f"✻ {head}   38s")
line(row + 2, "  ✻ another task with a long name…   2m")

write("\x1b[?25h")
time.sleep(600)
