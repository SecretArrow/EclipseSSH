#!/usr/bin/env bash
# tmux as the reference emulator: replay a recording into a pane and print the grid it ends on.
#
#   oracle.sh <recording.frames> <columns> <rows> [--frames N] [--attrs]
#
# [--frames N] replays only the first N reads, so a prefix can be compared against what tmux would have
# shown after seeing exactly the same prefix. That is what makes the bisection in griddiff.py honest.
#
# [--attrs] adds one `# attrsraw <row> <capture -e row>` line per row. The plain capture cannot see a
# cell that carries a background and no glyph, and that is the cell an erase leaves behind, so the
# background is asked for separately. It is a second capture of the same frozen pane rather than a
# derivation from the plain one, because the two are not the same text: `-e` keeps the `SO`/`SI` a
# program's own charset shift emits and the plain capture drops them, so stripping escapes out of `-e`
# would quietly change what the text comparison sees.
set -uo pipefail
frames=$1 cols=$2 rows=$3
shift 3
limit=""
attrs=0
while [ $# -gt 0 ]; do
  case "$1" in
    --frames) limit=${2:?--frames needs a count}; shift 2 ;;
    --attrs) attrs=1; shift ;;
    *) echo "oracle.sh: unknown argument $1" >&2; exit 2 ;;
  esac
done

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

if [ "$attrs" = 1 ]; then
  # `IFS= read -r` so a row's own leading and trailing spaces survive: a trailing run of coloured blanks
  # is the shape a BCE erase leaves, and it is one of the things this section is here to see.
  row=0
  while IFS= read -r line; do
    printf '# attrsraw %s %s\n' "$row" "$line"
    row=$((row + 1))
  done < <(tmux -L eclipse-oracle capture-pane -p -e -t ora 2>/dev/null)
fi
