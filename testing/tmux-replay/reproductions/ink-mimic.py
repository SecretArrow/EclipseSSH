#!/usr/bin/env python3
"""A stand-in for the Ink TUI, painting the shapes the bug report describes.

Runs inside a tmux pane while record.py captures what tmux sends its client, so the recording exercises
the whole chain (program -> tmux -> client bytes) without needing the real Claude Code on the recording
host. Every shape here comes from the report: a wrapped paragraph, a spinner that redraws in place, a
box-drawing table whose cell changes width, a line rewritten shorter, and the emoji that appear on screen.

  python3 ink-mimic.py
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


def paragraph(row, marker, text):
    """Ink wraps its own text; the marker sits in column 1 and the body is indented two columns."""
    width = cols - 4
    words = text.split()
    current = ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if len(candidate) > width and current:
            line(row, current)
            row += 1
            current = word
        else:
            current = candidate
    line(row, current)
    return row


write("\x1b[2J")
write("\x1b[?25l")

line(1, "─" * min(cols, 52))
line(2, "  Claude Code   tmux 3.5a   workspace")
line(3, "─" * min(cols, 52))

paragraph(5, "●", "Ada. Mari saya baca implementasinya dulu sebelum mengubah apa pun di sana.")
line(7, "❯  Bash(cat app/build.gradle.kts)")
line(8, "  Reading app/src/main/java/dev/eclipse/ssh/terminal/AnsiTerminalBuffer.kt")

table_head = ["Tool", "Status", "Duration"]
table_rows = [
    ["Bash", "⏳", "1.2s"],
    ["Read", "✅", "0.3s"],
]
line(10, "┌" + "─" * 16 + "┬" + "─" * 8 + "┬" + "─" * 10 + "┐")
line(11, "│ " + table_head[0].ljust(15) + "│ " + table_head[1].ljust(7) + "│ " + table_head[2].ljust(9) + "│")
line(12, "├" + "─" * 16 + "┼" + "─" * 8 + "┼" + "─" * 10 + "┤")
for offset, row in enumerate(table_rows):
    line(13 + offset, "│ " + row[0].ljust(15) + "│ " + row[1].ljust(7) + "│ " + row[2].ljust(9) + "│")
line(15, "└" + "─" * 16 + "┴" + "─" * 8 + "┴" + "─" * 10 + "┘")
time.sleep(0.4)

# The cell that changes, longer than what it replaces: the whole rest of the row shifts.
table_rows[0] = ["Bash (sleep 4)", "✅", "4.1s"]
line(13, "│ " + table_rows[0][0].ljust(15) + "│ " + table_rows[0][1].ljust(7) + "│ " + table_rows[0][2].ljust(9) + "│")

spinner = ["✳", "✽", "✻", "✲"]
thoughts = [
    "Thought for 1s",
    "Thought for 2s, searched for 1 pattern",
    "Thought for 3s, searched for 1 pattern, ran 1 shell command",
]
for tick in range(9):
    line(18, f"{spinner[tick % len(spinner)]} {thoughts[min(tick // 3, len(thoughts) - 1)]}")
    time.sleep(0.3)

# A line rewritten shorter through the same row: the tail of the old one is what survives as a fragment
# when a parser and tmux disagree about where the cursor is.
line(20, "ThThought for 4s")
time.sleep(0.3)
at(20)
write("\r\x1b[2KThought for 4s")
time.sleep(0.3)
line(21, "Pe" + " " * 40)
at(21)
write("\r\x1b[2KPekerjaan selesai pada berkas itu.")

time.sleep(0.3)
line(23, "\U0001f534 merah · ✳ bintang · 全角 · ─── box ───")
line(24, "gecko 38074588806 dan 15 dan anjalan")

# A full repaint, the way a window switch does it: everything is redrawn from the top.
time.sleep(0.4)
write("\x1b[2J")
line(1, "─" * min(cols, 52))
line(2, "  Claude Code   tmux 3.5a   workspace")
line(3, "─" * min(cols, 52))
paragraph(5, "●", "Sudah selesai. Perubahannya ada di parser dan sudah diuji.")
line(7, "❯  Done")
for row in range(25, rows):
    line(row, "")
line(rows, "⏵⏵ accept edits on · ? for shortcuts")
write("\x1b[?25h")
time.sleep(600)
