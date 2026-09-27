#!/usr/bin/env bash
# The CI window's backstop: if the repository has been left public past the
# deadline its opener recorded, close it.
#
# WHY THIS IS NOT A WORKFLOW. A scheduled workflow in this repository cannot do
# this job. While the repository is private, scheduled runs are billed and die
# before their first step - which is fine, because a private repository is the
# state we want. But it means the schedule is exactly as absent as the runner
# budget is, and the one failure this script exists for - a window left open
# because the machine that opened it died - is a failure the repository's own
# CI can only observe once someone has already paid to make it public.
#
# So this runs from cron, on a machine that is not GitHub. It is safe to run
# as often as you like: it acts only when the repository is public AND carries
# a deadline that has passed, and it does nothing at all to a public
# repository that has no deadline - a repository someone made public by hand
# is a decision, not a leak, and this script does not overrule decisions.
#
# Install (every five minutes):
#   */5 * * * * /home/dev/eclipse/scripts/ci-window-watchdog.sh >/dev/null 2>&1
#
# The redirect goes to /dev/null, and that is a decision rather than tidiness.
# Cron's shell applies a redirection *before* it runs the command, so a redirect
# it cannot open is a command that never runs at all - the watchdog would be
# absent for exactly the reason it exists. A log file is also the wrong thing
# for this script to depend on, because the filesystem most likely to be full is
# the one the log would be written to. What this script has to report, it writes
# itself, to $LOG below, and it writes it in a way that cannot take the run down
# with it. Both halves were measured; see AUDIT-REPORT.md §74, which reproduces
# and corrects the hypothesis §71 left standing.
#
# Usage: ci-window-watchdog.sh [--install-cron] [--verbose]
set -euo pipefail

REPO="${CI_WINDOW_REPO:-maragung/EclipseSSH}"
DEADLINE_VARIABLE="CI_WINDOW_DEADLINE"
SELF="$(readlink -f "$0")"
VERBOSE=""

# Where this script's own account of what it did goes. Deliberately not /tmp:
# on this host that is a 3.9 GB tmpfs shared with every other process on the
# machine, and it is the filesystem most likely to be full at the moment a
# watchdog is needed - it was at 93% when this was written, and full on
# 2026-09-26. Overridable so a test can point it at a scratch file.
LOG="${CI_WINDOW_WATCHDOG_LOG:-${XDG_STATE_HOME:-$HOME/.local/state}/ci-window-watchdog.log}"

for arg in "$@"; do
  case "$arg" in
    --verbose) VERBOSE=1 ;;
    --install-cron)
      line="*/5 * * * * $SELF >/dev/null 2>&1"
      current="$(crontab -l 2>/dev/null || true)"
      if printf '%s\n' "$current" | grep -qxF "$line"; then
        echo "ci-window-watchdog: already installed"
        exit 0
      fi
      # Everything already in the crontab is kept, in order, on both paths below
      # - `crontab -` with only this line would delete every other job.
      if printf '%s\n' "$current" | grep -qF "$SELF"; then
        # A line naming this script that is not the line above is an earlier
        # form of this entry - the one that appended to /tmp - and it is
        # replaced rather than left beside the new one. Two entries would run
        # the watchdog twice every tick, and the earlier one is the one that
        # cannot survive a full filesystem.
        updated="$( { printf '%s\n' "$current" | grep -vF "$SELF" || true
                      printf '%s\n' "$line"; } | sed '/^$/d' )"
        printf '%s\n' "$updated" | crontab -
        echo "ci-window-watchdog: replaced an earlier entry - $line"
        exit 0
      fi
      printf '%s\n%s\n' "$current" "$line" | sed '/^$/d' | crontab -
      echo "ci-window-watchdog: installed - $line"
      exit 0
      ;;
    *) echo "ci-window-watchdog: unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# `say` and `act` differ in who they are talking to, not in how careful they
# are. Every write in both is guarded, and that is the load-bearing part of this
# file rather than defensive noise: a script that logs with a bare `echo` under
# `set -e` dies at that echo when its stdout cannot be written. That is not
# hypothetical - it is measured, and it is the shape this watchdog was in on
# 2026-09-26. With /tmp full, the run ended at its first log line, one line
# before the flip it exists to perform, and left the 0-byte file the redirect
# had just created. A watchdog that cannot report what it is doing must still
# do it.
say() {
  [ -n "$VERBOSE" ] || return 0
  { printf 'ci-window-watchdog: %s\n' "$*"; } 2>/dev/null || true
  return 0
}

act() {
  local line="ci-window-watchdog: $(date -u +%Y-%m-%dT%H:%M:%SZ) $*"
  { printf '%s\n' "$line"; } 2>/dev/null || true
  { mkdir -p -- "$(dirname -- "$LOG")" && printf '%s\n' "$line" >>"$LOG"; } 2>/dev/null || true
  return 0
}

# Same rule as scripts/ci-window.sh: the netrc token, pulled into the
# environment, never printed, and never routed through the git credential
# helper that rewrites a plaintext token onto this host's disk.
if [ -z "${GH_TOKEN:-}" ]; then
  if [ ! -r "$HOME/.netrc" ]; then
    act "no GH_TOKEN and no readable ~/.netrc - cannot check the window; not failing the cron job"
    exit 0
  fi
  GH_TOKEN="$(awk '$1 == "password" { print $2; exit }' "$HOME/.netrc")"
  export GH_TOKEN
fi

# A failure to reach the API is not a reason to exit non-zero: cron would mail
# about it every five minutes, and there is nothing to fix here. Log and stop.
repo_json="$(gh api "repos/$REPO" --jq '{v: .visibility}' 2>/dev/null)" || {
  act "cannot read $REPO from the API - nothing to do"
  exit 0
}
current="$(printf '%s' "$repo_json" | jq -r '.v')"

# Accepted only in the shape scripts/ci-window.sh writes. A missing variable is
# a 404, and `gh api` writes that error document to stdout - so a bare
# `$(gh api ... || true)` yields `{"message":"Not Found",...}`, which is a
# non-empty string and would read as "a deadline exists, and it does not
# parse". Both of those readings are wrong in the same direction: they make the
# watchdog believe a window is open when none is.
deadline_raw="$(gh api "repos/$REPO/actions/variables/$DEADLINE_VARIABLE" --jq '.value' 2>/dev/null)" || deadline_raw=""
case "$deadline_raw" in
  [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]Z) deadline="$deadline_raw" ;;
  *) deadline="" ;;
esac

if [ "$current" != "public" ]; then
  say "repository is $current"
  if [ -n "$deadline" ]; then
    # Deliberately not cleared here. The opener records the deadline and *then*
    # flips the repository public, and this script may run in the gap between
    # those two calls; clearing a deadline it saw while private would strip the
    # backstop off a window that is about to open.
    say "a deadline ($deadline) is recorded while private - \`ci-window.sh close\` clears it"
  fi
  exit 0
fi

if [ -z "$deadline" ]; then
  act "the repository is public with no deadline recorded - left alone, because a
     repository made public by hand is a decision. Run \`ci-window.sh close\` to
     close it, or \`ci-window.sh open\` to open a window that closes itself."
  exit 0
fi

now="$(date -u +%s)"
due="$(date -u -d "$deadline" +%s 2>/dev/null)" || {
  act "the recorded deadline '$deadline' is not a timestamp this host can parse - fixing it by closing"
  due=0
}

if [ "$now" -lt "$due" ]; then
  say "window is open and inside its deadline ($deadline)"
  exit 0
fi

act "window past its deadline ($deadline) - closing $REPO"
if ! gh api -X PATCH "repos/$REPO" -F private=true >/dev/null; then
  act "the flip failed - $REPO IS STILL PUBLIC and needs a hand"
  exit 1
fi

after="$(gh api "repos/$REPO" --jq '.visibility' 2>/dev/null || echo unknown)"
if [ "$after" != "private" ]; then
  act "the API still reports '$after' after the flip - $REPO IS STILL PUBLIC and needs a hand"
  exit 1
fi

# Only now is the deadline cleared: it is the record that someone opened a
# window, and dropping it before the flip succeeded would lose the evidence
# that the repository was ever meant to be private.
gh api -X DELETE "repos/$REPO/actions/variables/$DEADLINE_VARIABLE" >/dev/null 2>&1 || true
act "closed and verified: $REPO is private again"
