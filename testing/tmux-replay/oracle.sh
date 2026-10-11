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

# Replay is asynchronous: capture-pane can run before tmux has parsed the last read. `pane_current_command`
# is no help - it stays `sh` for this pane's whole life (measured: waiting for it to become `sleep` costs
# the full 10s timeout), so the end of the write cannot be seen from outside. Instead the capture must stop
# changing for three polls in a row: one repeat can land inside a pause in the parse, three cannot.
stable=0
prev=""
for _ in $(seq 1 60); do
  cur=$(tmux -L eclipse-oracle capture-pane -p -t ora 2>/dev/null)
  if [ "$cur" = "$prev" ]; then stable=$((stable + 1)); else stable=0; fi
  prev=$cur
  [ "$stable" -ge 3 ] && break
  sleep 0.1
done
# The cursor is reported as well, in the app dump's own vocabulary: the app's `cursor row,column` is
# tmux's `cursor_y,cursor_x`, and tmux's `cursor_flag` 1 is the app's `visible true`. A grid that agrees
# while the cursor does not is the reported "the cursor is somewhere else than the text" failure, and a
# comparison that only looks at rows cannot see it.
cursor=$(tmux -L eclipse-oracle display -p -t ora '#{cursor_y},#{cursor_x}')
flag=$(tmux -L eclipse-oracle display -p -t ora '#{cursor_flag}')
if [ "$flag" = "1" ]; then visible=true; else visible=false; fi
printf '# cursor %s visible %s\n' "$cursor" "$visible"
printf '%s\n' "$prev"
