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
| `record.py` | Attaches a tmux client to a pty of a given size and writes what tmux sends it, one length-prefixed frame per read. `--send SECONDS:TEXT` types into that client on a schedule, for a program that draws nothing worth recording until it has been answered. `--answer` replies to tmux's terminal queries the way the app does, which is what makes tmux choose its supported paths. |
| `mkframes.py` | Wraps raw bytes (`--escapes` for a readable `\x1b` fixture, `--chunk N` to choose the boundaries) as a recording. |
| `run-griddump.sh` + `GridDump.kt` | Compiles the real `app/src/main/.../terminal` package with the cached Kotlin compiler and runs the recording through `AnsiTerminalBuffer`, printing the grid. |
| `oracle.sh` | Replays the same recording into a tmux pane and prints the grid tmux itself ends on, plus its cursor as the app dump spells one. |
| `griddiff.py` | Row-by-row diff with an incident count, and `--bisect`: the first read whose prefix already diverges. The cursor is compared too - a grid that agrees while the cursor does not is the reported "the cursor is somewhere else than the text" failure, and a row diff alone cannot see it. |
| `replay.sh` | The whole loop. |

## Running it

```bash
# reproduce the passthrough framing tmux sends for a pane that uses one
python3 mkframes.py --escapes reproductions/dcs-passthrough.raw /tmp/dcs.frames --chunk 7
./replay.sh /tmp/dcs.frames --cols 20 --rows 3

# the shapes an Ink TUI reaches for: wide glyphs at the margins, erase and insert families, a scroll
# region, a saved cursor, synchronized output. It never exits - it animates - so bound it with `timeout`.
timeout 1 python3 reproductions/edge-cases.py > /tmp/edge.raw
python3 mkframes.py /tmp/edge.raw /tmp/edge.frames --chunk 100
./replay.sh /tmp/edge.frames --cols 52 --rows 60

# a synchronized-output batch tmux composed itself. Both halves are needed: `sync` in the terminal
# features, because tmux 3.5a never asks its terminal about 2026, and `--answer`, so tmux is not on its
# fallback path. A recording made without them contains no `2026 h`/`l` at all, and replaying it proves
# nothing about what the app does with a batch.
python3 record.py /tmp/sync.frames 8 --cols 52 --rows 60 --config reproductions/sync.conf --answer \
  -- python3 reproductions/sync-frames.py
./replay.sh /tmp/sync.frames --cols 52 --rows 60

# or record a real one: the mimic paints the shapes from the bug report, through a real tmux client
python3 record.py /tmp/ink.frames 8 --cols 52 --rows 60 --config reproductions/no-status.conf \
  -- python3 reproductions/ink-mimic.py
./replay.sh /tmp/ink.frames --cols 52 --rows 60
./griddiff.py --frames /tmp/ink.frames --cols 52 --rows 60 --bisect     # if it diverges

# the two copies of a sent prompt: one the program truncated with `…` itself, one the program wrapped
# into its transcript. The app adds neither cut nor duplicate, and this pins that - see AUDIT-REPORT §97
python3 record.py /tmp/trunc.frames 8 --cols 52 --rows 60 --config reproductions/no-status.conf \
  -- python3 reproductions/prompt-truncation.py
./replay.sh /tmp/trunc.frames --cols 52 --rows 60

# or a real program that has to be answered first - a first-run dialog, a prompt - one keypress at a time
python3 record.py /tmp/claude.frames 150 --cols 52 --rows 60 --config reproductions/no-status.conf \
  --send "12:\x1b[B\r" -- claude "write a paragraph, then a markdown table"
./replay.sh /tmp/claude.frames --cols 52 --rows 60

# typing, one character per send, so the cursor moves a column at a time and the diff sees it happen
python3 - <<'PY'
import subprocess
cmd = ["python3", "record.py", "/tmp/keys.frames", "90", "--cols", "52", "--rows", "60",
       "--config", "reproductions/no-status.conf"]
t = 22.0
for ch in "coba tulis satu kalimat panjang disini supaya":
    cmd += ["--send", f"{t:.2f}:{ch}"]
    t += 0.15
subprocess.run(cmd + ["--", "env", "-C", "/tmp/scratch", "claude"])
PY
./griddiff.py --frames /tmp/keys.frames --cols 52 --rows 60 --bisect --step 1
```

Exit 0 means every row matches tmux, and so does the cursor. The dump directory (`GRIDDUMP_OUT`, default `/tmp/griddump-out`) is
reused between runs; set `GRIDDUMP_REBUILD=1` after changing app code.

## What it does not prove

- **Text only.** Colour, attributes and the direction of a wide character are not compared, so a green
  run says the ink is in the right place, not that it is drawn right. The cursor is compared by position,
  not by whether it is painted there.
- **No terminal answers, unless you ask for them.** tmux asks its client for DA1 and DA2, and by default
  this client answers neither, so tmux falls back to its own defaults. The recording is verbatim either
  way; what differs is which sequences tmux would *choose*, never how it frames them. `record.py
  --answer` replies the way the app does, which matters for one mode in particular: tmux wraps its
  repaints in `ESC [ ? 2026 h`/`l` only when told the terminal has synchronized output, and on this host
  it is told only through `reproductions/sync.conf`'s `sync` terminal-feature rather than by asking. A
  recording made without both cannot contain a batch.
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
| `reproductions/ink-mimic.py`, 52x60, 8s recording | - | IDENTICAL |
| `reproductions/prompt-truncation.py`, 52x60, 8s recording | - | IDENTICAL |
| `reproductions/edge-cases.py`, 52x60, 705 B raw | - | IDENTICAL |

Real Claude Code in tmux, 52x60, every prefix checked with `--bisect --step 1`, rows and cursor both:

| Recording | Reads | Verdict |
|---|---|---|
| session picker, first-run dialog answered | 291 | IDENTICAL |
| a prompt streaming prose, a markdown table, a dialog over it | 86 | IDENTICAL |
| the banner, a wrapped warning, an idle prompt box | 9 | IDENTICAL |
| the above stream, typed into one character per send | 635 | IDENTICAL |
| a 190-character prompt passed on the command line | 211 | IDENTICAL |
| an 84-character prompt typed one character per send, then Enter | 378 | IDENTICAL |

The last two are the B4 case, and they are why §97 of the audit exists: a prompt long enough for [CC] to
truncate it in one of its own columns with `…` and to wrap it in its transcript. Both copies arrive as
bytes from [CC], both land in the grid tmux produces, and the app neither cuts nor duplicates either one.
Across the two recordings every `…` in the stream is inside a string [CC] wrote - 16 in the first, 19 in
the second - and one of them is the user's own prompt, shortened to `* tolong lanjutkan tugas…`.

The last one is the cursor case: the column advances one per read (`43,44,...,51`, then wraps to column 4
on the next row and runs on to 23), and at every one of the 635 prefixes the app's cursor equals tmux's.
So the parser and the buffer track the cursor through a partial update of exactly the kind reported as
wrong. Drawing the cursor is the view's job and is not compared here.

`edge-cases.py` covers a wide glyph into the last column, half-cell overwrite, combining marks, a ZWJ
emoji, ECH/ICH/DCH, insert and delete lines inside a scroll region, save and restore cursor,
synchronized output, and a line that ends exactly at the margin. The bisection on the mutated parser
names read 4 of 7 at byte offset 33 and prints `\x1b\x1b[?25l` - the first doubled `ESC`.

## What a batch looks like on the wire

`reproductions/sync-frames.py` recorded through `reproductions/sync.conf` with `--answer`: tmux emits
`ESC [ ? 2026 h`/`l` around each client repaint, and **always balances them inside one write**. Across
30 reads, two carry a pair (`read 1: h=2 l=2`, `read 4: h=1 l=1`) and none ends with the batch open. The
same holds for a 300-line burst: 19 pairs over 7 reads, all balanced.

That is worth knowing before blaming a mid-batch frame for a torn screen. The app reads over SSH, where
a write can be split, so the mode still has to be honoured - but a recording of a tmux session cannot
show the case, and this harness's read boundaries are not the app's.
