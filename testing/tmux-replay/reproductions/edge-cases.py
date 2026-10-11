#!/usr/bin/env python3
"""The shapes an Ink TUI reaches for that a plain redraw does not: wide glyphs at the margins, the erase
and insert families, a scroll region, a saved cursor, and synchronized output.

Cheap to run and cheap to bisect, which is the point: if the app and tmux disagree anywhere, it is in
here, and griddiff.py --bisect names the read that did it.

  python3 edge-cases.py
"""
import shutil
import sys
import time

cols, rows = shutil.get_terminal_size((52, 60))


def write(text):
    sys.stdout.write(text)
    sys.stdout.flush()


def at(row, col=1):
    write(f"\x1b[{row};{col}H")


def line(row, text):
    at(row)
    write("\x1b[2K" + text)


write("\x1b[2J")

# 1. A double-width glyph into the last column: the cell before the margin has no room for it, so the
#    terminals that agree leave the last cell blank and put the glyph on the next row.
at(1, cols - 1)
write("全角")
at(2, 1)
write("after")

# 2. A wide glyph overwritten by a narrow one, then repainted: the half cell left behind is the classic
#    half-cell ghost.
at(4, 1)
write("全角全角")
at(4, 2)
write("x")
time.sleep(0.2)
at(4, 1)
write("全角全角")

# 3. A combining mark, and a zero-width joiner sequence: one grapheme made of several code points.
line(6, "école  \U0001f468‍\U0001f469‍\U0001f467  done")

# 4. Erase-in-line with every parameter.
line(8, "0123456789 erase to the right")
at(8, 6)
write("\x1b[0K")
line(9, "0123456789 erase to the left")
at(9, 6)
write("\x1b[1K")
line(10, "0123456789 erase the whole line")
at(10, 6)
write("\x1b[2K")

# 5. Erase characters, insert and delete characters.
line(12, "0123456789")
at(12, 3)
write("\x1b[4X")
line(13, "abcdefghij")
at(13, 3)
write("\x1b[4@")
at(13, 3)
write("XY")
line(14, "abcdefghij")
at(14, 3)
write("\x1b[4P")

# 6. Insert and delete lines inside a scroll region, then outside it.
for row in range(16, 21):
    line(row, f"line {row}")
write("\x1b[16;20r")
at(18, 1)
write("\x1b[2L")
at(16, 1)
write("\x1b[2M")
write("\x1b[r")

# 7. Save and restore the cursor around a paint.
at(22, 10)
write("\x1b7")
at(24, 1)
line(24, "painted while the cursor was saved")
write("\x1b8")
write("restored here")

# 8. Synchronized output, the way Ink brackets a frame.
write("\x1b[?2026h")
line(26, "frame one")
time.sleep(0.15)
line(26, "frame two")
write("\x1b[?2026l")

# 9. A line that ends exactly at the margin, then a character that must wrap.
at(28, 1)
write("x" * cols)
write("y")
at(30, 1)
write("wrapped")

time.sleep(0.3)
write("\x1b[?25h")
time.sleep(600)
