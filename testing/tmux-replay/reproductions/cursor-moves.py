#!/usr/bin/env python3
"""The cursor-movement family, one case per band of rows, so a divergence names its own case.

Everything the bug report's B6 asks for by name - CUP/HVP, CUU/CUD/CUF/CUB, DECSC/DECRC - plus the
forms the emulator has to answer to stay in step with a program: CHA, HPA, VPA, CNL, CPL, and the same
moves inside a DECSTBM region, where the reference emulator stops the cursor at the margin.

Each case homes the cursor to a row of its own and then writes a marker, so the row the marker lands on
*is* the row the move went to. Bands are cleared between cases, so the evidence is in the prefixes: run
this through `griddiff.py --bisect --step 1`, which compares the grid and the cursor after every read.

Two of the bands also clear the screen, and clearing it on a session with history is the state in which
`frame`'s window anchor puts the whole screen a row or more lower than tmux does. That divergence is in
the window rather than in the cursor and is not what this fixture is for, so `--bisect` stops on it
first. The moves themselves are judged by the row the marker lands on, which each case names in its
comment.

  python3 cursor-moves.py
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


def marker(text):
    write(text)


write("\x1b[2J")

# 1. Relative moves, bare and with a parameter. Home is 5,10 each time.
at(5, 10); write("\x1b[3A"); marker("U3")     # up 3  -> 2,10
at(5, 10); write("\x1b[A"); marker("U1")      # up 1  -> 4,10
at(5, 10); write("\x1b[3B"); marker("D3")     # down 3 -> 8,10
at(5, 10); write("\x1b[4C"); marker("F4")     # forward 4 -> 5,14
at(5, 10); write("\x1b[4D"); marker("B4")     # back 4 -> 5,6
at(5, 10); write("\x1b[0C"); marker("F1")     # a parameter of zero means one -> 5,11

# 2. The same moves at the edges: up from row 1 and left from column 1 are stops, not wraps.
at(1, 1); write("\x1b[5A"); marker("U0")      # clamps at 1,1
at(rows, cols); write("\x1b[5B"); marker("D9")  # clamps at the last row, last column
at(3, 1); write("\x1b[5D"); marker("B0")      # clamps at 3,1
at(3, cols); write("\x1b[5C"); marker("F9")   # clamps at 3,last

# 3. The named forms: CNL, CPL, CHA, HPA, VPA.
at(11, 20); write("\x1b[2E"); marker("E2")    # next line, column 1 -> 13,1
at(11, 20); write("\x1b[2F"); marker("F2")    # previous line, column 1 -> 9,1
at(15, 1); write("\x1b[30G"); marker("G3")    # absolute column -> 15,30
at(16, 1); write("\x1b[35`"); marker("H3")    # absolute column -> 16,35
at(1, 1); write("\x1b[18d"); marker("V1")     # absolute row -> 18,1

# 4. Inside a scroll region. The reference stops an up-move at the region's top row and a down-move at
#    its bottom row, so a marker on the margin's row is the evidence that the clamp is the region's.
write("\x1b[2J")
write("\x1b[22;28r")
at(25, 5); write("\x1b[9A"); marker("RU")     # up 9 from inside -> stops at 22,5
at(25, 5); write("\x1b[9B"); marker("RD")     # down 9 from inside -> stops at 28,5
at(24, 5); write("\x1b[2B"); marker("R2")     # down 2 from inside -> 26,5
at(20, 3); write("\x1b[3A"); marker("AU")     # from above the region -> 17,3
at(30, 3); write("\x1b[20A"); marker("BU")    # from below the region -> where the reference stops
at(19, 3); write("\x1b[3B"); marker("AD")     # from above, down -> 22,3
at(30, 3); write("\x1b[3B"); marker("BD")     # from below, down -> 33,3
write("\x1b[r")

# 4b. A cursor exactly *on* a margin, moving further out: it stays there. Each case gets a band of its
#     own, because the marker that shows it lands on the margin's row and the next case overwrites it -
#     run in sequence, neither of these is visible at all.
write("\x1b[2J"); write("\x1b[22;28r")
at(22, 1); write("\x1b[1A"); marker("MT")     # on the region's top row, up -> still the top row
write("\x1b[r")
write("\x1b[2J"); write("\x1b[22;28r")
at(28, 1); write("\x1b[1B"); marker("MB")     # on the region's bottom row, down -> still the bottom
write("\x1b[r")

# 5. DECSC/DECRC, bare, and with a move between them.
write("\x1b[2J")
at(40, 8)
write("\x1b7")
at(45, 1); marker("painted")
write("\x1b8"); marker("S1")                  # back to 40,8
at(50, 20)
write("\x1b7")
at(52, 1); marker("painted2")
write("\x1b8"); marker("S2")                  # back to 50,20

# 6. An up-move past the top of the screen after the screen has scrolled. The reference keeps the cursor
#    on the screen; an emulator that clamps against its whole history instead puts the cursor into the
#    scrollback, and the next character printed lands there - off the visible screen and out of sight.
write("\x1b[2J")
for line in range(1, 101):
    write(f"line {line:03d}\r\n")
write("\x1b[60A")                             # 60 rows up from the bottom row: past the screen's top
marker("X")
write("\x1b[200A")                            # and far past it
marker("Y")

time.sleep(0.3)
write("\x1b[?25h")
time.sleep(600)
