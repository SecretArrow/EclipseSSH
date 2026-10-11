#!/usr/bin/env bash
# Replay a recording through the app and through tmux, and diff the two grids.
#
#   replay.sh <recording.frames> [--cols 52] [--rows 60] [--bisect] [--limit N]
#
# Exits 0 only when every row of the app's grid matches tmux's.
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
frames=${1:?usage: replay.sh <recording.frames> [--cols N] [--rows N] [--bisect]}
shift

cols=52
rows=60
extra=()
while [ $# -gt 0 ]; do
  case "$1" in
    --cols) cols=${2:?--cols needs a value}; shift 2 ;;
    --rows) rows=${2:?--rows needs a value}; shift 2 ;;
    *) extra+=("$1"); shift ;;
  esac
done

exec python3 "$here/griddiff.py" --frames "$frames" --cols "$cols" --rows "$rows" ${extra[@]+"${extra[@]}"}
