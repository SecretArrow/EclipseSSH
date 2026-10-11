#!/usr/bin/env bash
# tmux as the reference emulator: replay a recording into a pane and print the grid it ends on.
#
#   oracle.sh <recording.frames> <columns> <rows> [--frames N]
#
# [--frames N] replays only the first N reads, so a prefix can be compared against what tmux would have
# shown after seeing exactly the same prefix. That is what makes the bisection in griddiff.py honest.
set -uo pipefail
frames=$1 cols=$2 rows=$3
limit=""
if [ "${4:-}" = "--frames" ]; then limit=${5:?--frames needs a count}; fi

bin=$(mktemp -t oracle-raw.XXXXXX)
cfg=$(mktemp -t oracle-conf.XXXXXX)
trap 'rm -f "$bin" "$cfg"; tmux -L eclipse-oracle kill-server 2>/dev/null' EXIT
# The recording already contains whatever tmux composed for its client, its own status line included, so
# this emulator must not reserve a row for a status line of its own and must use the given size verbatim.
printf 'set -g status off\nset -g default-terminal screen-256color\n' > "$cfg"

python3 - "$frames" "$bin" "${limit:-0}" <<'PY'
import struct, sys
src, dst, limit = sys.argv[1], sys.argv[2], int(sys.argv[3])
with open(src, "rb") as f, open(dst, "wb") as o:
    n = 0
    while not limit or n < limit:
        head = f.read(4)
        if len(head) < 4:
            break
        o.write(f.read(struct.unpack(">I", head)[0]))
        n += 1
PY

tmux -L eclipse-oracle kill-server 2>/dev/null
# `cat` writes the whole recording; tmux keeps parsing it into the pane.
#
# Echo is off deliberately. The recording contains the queries tmux sent its client (`ESC [ c`,
# `ESC [ > q`), and this emulator answers them into the pane's input - which the tty would otherwise echo
# back onto the screen, scrolling the grid and shifting every row. Nothing reads that input, so with echo
# off it stays invisible, which is what a real client that answers its own queries produces.
tmux -L eclipse-oracle -f "$cfg" new-session -d -s ora -x "$cols" -y "$rows" \
  "sh -c 'stty -echo; cat \"$bin\"; sleep 600'"

# Replay is asynchronous: capture-pane can run before tmux has parsed the last read. Settle first.
prev=""
for _ in $(seq 1 40); do
  cur=$(tmux -L eclipse-oracle capture-pane -p -t ora 2>/dev/null)
  [ "$cur" = "$prev" ] && break
  prev=$cur
  sleep 0.1
done
printf '%s\n' "$prev"
