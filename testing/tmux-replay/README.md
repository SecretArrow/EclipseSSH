# tmux replay harness

Feeds a recording of what **tmux sent its client** through the app's own parser, and through tmux, and
diffs the two grids. It exists to answer one question with evidence instead of a guess: when the screen
is wrong, is the app's grid different from tmux's, and if so, which read of bytes made it different.

That is the whole failure mode behind the reported fragments and overlaps. tmux sends only the cells it
believes changed, so a cell the app got wrong once is never resent: the difference is permanent for the
session, and it looks like a one-character ghost or a row that is half old. A cell-level diff against
tmux is therefore not a nicety, it is the only way to tell a parser disagreement from a rendering one.

Diagnosis only. Nothing in here configures the server, and no fix is proposed as a server setting.

## Why the client-bound stream

`tmux pipe-pane` gives what a program wrote *to its pane*. That is not what arrives: tmux emulates it and
composes its own output for the client - repaints, incremental cell updates, its status line, and the
`ESC P tmux;` framing around anything the pane passes through. The app is the client, so the bytes it
receives are what a replay has to contain. `record.py` captures them by attaching a real tmux client to a
pty it owns, and keeps the read boundaries, because a control string split across two reads is a case of
its own.

## Pieces

| Tool | What it does |
|---|---|
| `record.py` | Attaches a tmux client to a pty of a given size and writes what tmux sends it, one length-prefixed frame per read. |
| `mkframes.py` | Wraps raw bytes (`--escapes` for a readable `\x1b` fixture, `--chunk N` to choose the boundaries) as a recording. |
| `run-griddump.sh` + `GridDump.kt` | Compiles the real `app/src/main/.../terminal` package with the cached Kotlin compiler and runs the recording through `AnsiTerminalBuffer`, printing the grid. |
| `oracle.sh` | Replays the same recording into a tmux pane and prints the grid tmux itself ends on. |
| `griddiff.py` | Row-by-row diff with an incident count, and `--bisect`: the first read whose prefix already diverges. |
| `replay.sh` | The whole loop. |

## Running it

```bash
# reproduce the passthrough framing tmux sends for a pane that uses one
python3 mkframes.py --escapes reproductions/dcs-passthrough.raw /tmp/dcs.frames --chunk 7
./replay.sh /tmp/dcs.frames --cols 20 --rows 3

# or record a real one: the mimic paints the shapes from the bug report, through a real tmux client
python3 record.py /tmp/ink.frames 8 --cols 52 --rows 60 --config reproductions/no-status.conf \
  -- python3 reproductions/ink-mimic.py
./replay.sh /tmp/ink.frames --cols 52 --rows 60
./griddiff.py --frames /tmp/ink.frames --cols 52 --rows 60 --bisect     # if it diverges
```

Exit 0 means every row matches tmux. The dump directory (`GRIDDUMP_OUT`, default `/tmp/griddump-out`) is
reused between runs; set `GRIDDUMP_REBUILD=1` after changing app code.

## What it does not prove

- **Text only.** Colour, attributes and the direction of a wide character are not compared, so a green
  run says the ink is in the right place, not that it is drawn right.
- **No terminal answers.** tmux asks its client for DA1, XTVERSION and the colours, and this client
  answers none of them, so tmux falls back to its own defaults. The recording is verbatim either way;
  what differs is which sequences tmux would *choose*, never how it frames them.
- **The oracle's own pty** is put in `-echo` mode and has no status line, and the pane size is the size
  given on the command line: the recording already contains the status line tmux composed, so the
  emulator must not reserve a row for one of its own, and it must not echo the answers it sends into the
  pane back onto the screen.
- **A green replay is not a green screen.** It clears the parser and the buffer. Layout, font fallback
  and the view are elsewhere.

## Measurements on this host (tmux 3.5a)

| Recording | Before the control-string fix | After |
|---|---|---|
| `reproductions/dcs-passthrough.raw` | DIVERGES - `[?25l[>4;2mready` where tmux has `ready` | IDENTICAL |
| `reproductions/ink-mimic.py`, 52x60, 24 reads | - | IDENTICAL |
| `reproductions/edge-cases.py`, 52x60, 5 reads | - | IDENTICAL |

`edge-cases.py` covers a wide glyph into the last column, half-cell overwrite, combining marks, a ZWJ
emoji, ECH/ICH/DCH, insert and delete lines inside a scroll region, save and restore cursor,
synchronized output, and a line that ends exactly at the margin. The bisection on the mutated parser
names read 4 of 7 at byte offset 33 and prints `\x1b\x1b[?25l` - the first doubled `ESC`.
