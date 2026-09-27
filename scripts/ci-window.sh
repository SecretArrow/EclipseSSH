#!/usr/bin/env bash
# The CI window: run GitHub Actions on this repository, then close the door
# again.
#
# WHY THIS EXISTS. Actions minutes are free on a public repository and billed
# on a private one, and this repository is private. The obvious automation -
# a workflow that flips the repository public, runs the build, flips it back -
# cannot work: a GitHub-hosted runner is exactly what the account cannot
# afford, so no job in this repository can run the command that would make the
# minutes free. The first flip has to come from outside GitHub, which is what
# this script is. Every later step could be a job, and one of them is
# (.github/workflows/close-ci-window.yml); this script is the part that cannot
# be.
#
# THE WINDOW. `open` flips the repository public, records a deadline in the
# repository variable CI_WINDOW_DEADLINE, dispatches the workflow, waits for
# it, and closes the window. The close is installed as an EXIT trap, so it
# also runs when the script is interrupted or fails - the failure mode this
# guards against is a window left open, and a Ctrl-C must not be one of the
# ways that happens. It does not guard against the *machine* dying; that is
# what CI_WINDOW_DEADLINE is for, and scripts/ci-window-watchdog.sh reads it.
#
# PROTECTION. Opening the window is also the only moment `main` can be guarded
# at all: branch protection is a paid feature on a private repository for this
# account and a free one on a public repository, and a close *deletes* the rule
# rather than suspending it. So `open` applies it - the same rule
# docs/branch-protection.md writes down, whose check names that page's own table
# pins against the workflows - and then reads it back, because a PUT returning
# 200 says the request was accepted and not that the rule is what was asked for.
# A failure here does not close the window: the run is worth having either way,
# so it warns instead, in the plainest words it has, that the window is open and
# unguarded. `protect` re-applies the rule on its own and `status` reports it.
#
# WHAT MAKING THE REPOSITORY PUBLIC COSTS. Everything in the history, to
# everyone, permanently: forks and third-party archives (Software Heritage,
# GH Archive) copy it within minutes and flipping back to private does not
# recall those copies. That is a decision to take deliberately, not a side
# effect of wanting a free runner - so `open` makes you say it out loud with
# --yes, and refuses without it.
#
# A token with administrative access to the repository is required; the token
# in ~/.netrc is read by the same rule the rest of this repository uses, and
# is never printed.
#
# Usage:
#   ci-window.sh status
#   ci-window.sh open  [--workflow release.yml] [--ref main] [--minutes 120] --yes
#   ci-window.sh protect
#   ci-window.sh close
set -euo pipefail

REPO="${CI_WINDOW_REPO:-maragung/EclipseSSH}"
DEADLINE_VARIABLE="CI_WINDOW_DEADLINE"
DEFAULT_WORKFLOW="release.yml"
DEFAULT_REF="main"
DEFAULT_MINUTES=120

# The checks a window requires on `main`, in the order docs/branch-protection.md
# lists them. That page's table is where this list is *written down*, and it is
# already checked against the workflows - scripts/check-doc-figures.sh resolves
# every row's job key against the workflow it names and fails if the job calls
# itself something else. This copy is checked against that table by the same
# script, because the two drifting apart is the worst failure available here: a
# required context that no workflow reports is a check that can never pass, and
# `main` becomes permanently unmergeable rather than merely unguarded. Read the
# reasoning for each row there - in particular why the closer's job and the idle
# stress test are deliberately absent.
PROTECTION_CONTEXTS=(
  "Documentation figures"
  "FreeRDP native (4 ABIs)"
  "Lint (release variant)"
  "Unit and integration tests"
  "APKs, AAB and signatures"
  "Boot the built APKs (crash-on-open gate)"
  "connectedAndroidTest"
)

# How many times to try the rule before believing it will not take, and how long
# to wait between tries. The first attempt is not a fair test of the token: the
# endpoint answers with the paid-feature 403 until the visibility PATCH has
# propagated, so the attempt `open` makes seconds after the flip can be refused
# by a repository that is, a minute later, perfectly able to carry the rule.
# Measured 2026-09-27 - the PUT inside `open` failed there and the identical call
# succeeded by hand afterwards - which is why one attempt cannot distinguish
# "this token may not" from "not yet". `check-doc-figures.sh` reads the array
# above and stops at the `)`, so anything added here must stay below it.
PROTECTION_ATTEMPTS=5
PROTECTION_RETRY_SECONDS=5

die() { echo "ci-window: $*" >&2; exit 1; }
note() { echo "ci-window: $*"; }

# The token is pulled into the environment rather than written anywhere, and
# is never echoed. On this host the git credential helper rewrites a plaintext
# token into ~/.git-credentials for any *un-overridden* git command, so the
# netrc file is the source of truth and this script never calls that helper.
load_token() {
  if [ -n "${GH_TOKEN:-}" ]; then
    return 0
  fi
  [ -r "$HOME/.netrc" ] || die "no GH_TOKEN in the environment and no readable ~/.netrc"
  GH_TOKEN="$(awk '$1 == "password" { print $2; exit }' "$HOME/.netrc")"
  [ -n "$GH_TOKEN" ] || die "~/.netrc carries no password entry"
  export GH_TOKEN
}

api() { gh api "$@"; }

# The visibility the API reports right now, or the empty string if the call
# failed. Callers treat the empty string as "unknown" and refuse to act on it:
# guessing here could mean making a private repository public.
visibility() {
  local out
  out="$(api "repos/$REPO" --jq '.visibility' 2>/dev/null)" || return 1
  printf '%s' "$out"
}

# The recorded deadline, or the empty string if there is none.
#
# The shape check is not defensive padding. A missing variable is a 404, and
# `gh api` writes that error *document* to stdout - so `out="$(...)" || true`
# yields the string `{"message":"Not Found",...}` and not an empty value. The
# first version of this function did exactly that and reported a stale deadline
# on a repository that had none. Only a value shaped like the timestamp
# set_deadline writes is accepted.
deadline() {
  local out
  out="$(api "repos/$REPO/actions/variables/$DEADLINE_VARIABLE" --jq '.value' 2>/dev/null)" || out=""
  case "$out" in
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]Z) printf '%s' "$out" ;;
    *) printf '' ;;
  esac
}

set_deadline() {
  local value="$1"
  api -X PATCH "repos/$REPO/actions/variables/$DEADLINE_VARIABLE" \
    -f name="$DEADLINE_VARIABLE" -f value="$value" >/dev/null 2>&1 \
    || api -X POST "repos/$REPO/actions/variables" \
      -f name="$DEADLINE_VARIABLE" -f value="$value" >/dev/null
}

clear_deadline() {
  api -X DELETE "repos/$REPO/actions/variables/$DEADLINE_VARIABLE" >/dev/null 2>&1 || true
}

# A JSON string array built from the arguments. The names come from a table this
# repository checks, so the escaping is belt-and-braces rather than something
# expected to fire - but these strings are interpolated into a JSON document,
# which is the one place in this script where a stray character would be
# structural rather than cosmetic.
json_string_array() {
  local first=1 item
  printf '['
  for item in "$@"; do
    [ "$first" = 1 ] || printf ','
    first=0
    printf '"%s"' "$(printf '%s' "$item" | sed 's/[\\"]/\\&/g')"
  done
  printf ']'
}

# Applies the rule to `main` and verifies it by reading it back.
#
# It returns rather than exits, because whoever called it is the one that knows
# whether the window is still worth having - and the answer to that is yes: a
# window that could not be guarded is a worse window, not a useless one, and
# closing it would mean giving up the run as well as the guard. It says what
# went wrong on the way out, so the caller only has to add the consequence.
#
# The read-back is the evidence, not the PUT's exit status: a 200 says the
# request was accepted, not that the stored rule is the one that was sent. The
# two sides are compared as sorted sets, because the API is not obliged to hand
# the contexts back in the order they were sent.
#
# It retries, up to PROTECTION_ATTEMPTS times, because the first attempt is the
# one made in the seconds after the flip and that is exactly when the endpoint
# can still be answering with the paid-feature 403. See the constants above.
apply_protection() {
  local want body attempt put_err readback last
  want="$(printf '%s\n' "${PROTECTION_CONTEXTS[@]}" | LC_ALL=C sort | tr '\n' '|')"
  body="$(cat <<EOF
{
  "required_status_checks": {
    "strict": true,
    "contexts": $(json_string_array "${PROTECTION_CONTEXTS[@]}")
  },
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": false,
    "required_approving_review_count": 0
  },
  "restrictions": null,
  "required_linear_history": false,
  "allow_force_pushes": false,
  "allow_deletions": false
}
EOF
)"

  # The whole pair - apply, then read back - is what is retried, not just the
  # PUT, because both halves can lose the same race: a rule that was stored can
  # still read back short for a moment. Only the last failure is reported, so a
  # transient one cannot be mistaken for the reason the guard is missing.
  attempt=0
  while : ; do
    attempt=$((attempt + 1))
    if put_err="$(printf '%s' "$body" | api -X PUT "repos/$REPO/branches/main/protection" --input - 2>&1 >/dev/null)"; then
      readback="$(api "repos/$REPO/branches/main/protection" \
        --jq '"\(.enforce_admins.enabled)|\(.required_status_checks.contexts | sort | map(. + "|") | add)"' \
        2>/dev/null)" || readback=""
      [ "$readback" = "true|$want" ] && return 0
      last="the rule was accepted but the read-back does not match it
  asked for: true|$want
  read back: ${readback:-(could not read it back at all)}"
    else
      last="the API refused it: ${put_err:-no detail given}"
    fi

    if [ "$attempt" -ge "$PROTECTION_ATTEMPTS" ]; then
      note "could not apply the rule to $REPO/branches/main after $attempt attempts"
      note "$last"
      note "administrative access to the repository is needed for this; the default token does not have it"
      return 1
    fi
    note "attempt $attempt to apply the rule did not take; retrying in ${PROTECTION_RETRY_SECONDS}s"
    sleep "$PROTECTION_RETRY_SECONDS"
  done
}

# One line describing the guard, for `status`. Empty when the rule cannot be read,
# which is the expected answer on a private repository rather than a failure.
protection_summary() {
  api "repos/$REPO/branches/main/protection" --jq \
    'if (.enforce_admins.enabled // false)
       then "\(.required_status_checks.contexts | length) required checks, and they bind the administrator"
       else "the rule is stored but does not bind administrators" end' 2>/dev/null
}

# `close` is the only way this script ever makes the repository private, and it
# verifies the result: a close that silently did nothing would leave the window
# open while reporting success, which is the one outcome worth failing loudly
# over.
close_window() {
  local current
  current="$(visibility)" || { note "could not read the visibility; not assuming anything"; return 1; }
  if [ "$current" = "private" ]; then
    clear_deadline
    note "already private"
    return 0
  fi
  note "flipping $REPO back to private"
  api -X PATCH "repos/$REPO" -F private=true >/dev/null
  clear_deadline
  current="$(visibility)" || die "flipped but could not read the visibility back"
  [ "$current" = "private" ] || die "the API still reports '$current' after the flip - close it by hand"
  note "verified: $REPO is private again"
}

cmd_status() {
  local current dl guard
  current="$(visibility)" || die "cannot read $REPO - is the token still valid?"
  dl="$(deadline)"
  guard="$(protection_summary)" || guard=""
  printf 'repository    : %s\n' "$REPO"
  printf 'visibility    : %s\n' "$current"
  # Reported beside the visibility because the same window decides both, and
  # because an unguarded window is otherwise indistinguishable from a guarded one
  # until somebody reads mergeStateStatus on a pull request.
  if [ "$current" = "public" ]; then
    if [ -n "$guard" ]; then
      printf 'branch guard  : %s\n' "$guard"
    else
      printf 'branch guard  : NOT APPLIED - main is unguarded for as long as this window is open\n'
    fi
  else
    printf 'branch guard  : n/a - a private repository cannot carry the rule\n'
  fi
  if [ -n "$dl" ]; then
    printf 'window deadline: %s\n' "$dl"
    if [ "$current" = "public" ] && [ "$(date -u +%s)" -ge "$(date -u -d "$dl" +%s)" ]; then
      printf 'window state  : OPEN AND PAST ITS DEADLINE - the watchdog should have closed it\n'
    elif [ "$current" = "public" ]; then
      printf 'window state  : open\n'
    else
      printf 'window state  : closed (stale deadline - run `close` to clear it)\n'
    fi
  else
    printf 'window deadline: (unset)\n'
    printf 'window state  : %s\n' \
      "$([ "$current" = "public" ] && echo 'OPEN, with no deadline recorded - nobody is scheduled to close it' || echo closed)"
  fi
}

cmd_open() {
  local workflow="$DEFAULT_WORKFLOW" ref="$DEFAULT_REF" minutes="$DEFAULT_MINUTES" confirmed="" dry_run=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --workflow) workflow="${2:?--workflow needs a value}"; shift 2 ;;
      --ref)      ref="${2:?--ref needs a value}"; shift 2 ;;
      --minutes)  minutes="${2:?--minutes needs a value}"; shift 2 ;;
      --yes)      confirmed="yes"; shift ;;
      # Everything except the two calls that change the repository's
      # visibility. This exists because the flip is one-way and there was no
      # way to exercise the dispatch-and-wait half of this script without
      # taking that step for real.
      --dry-run)  dry_run="yes"; shift ;;
      *) die "unknown argument: $1" ;;
    esac
  done

  case "$minutes" in
    ''|*[!0-9]*) die "--minutes must be a whole number of minutes" ;;
  esac
  [ "$minutes" -ge 10 ] || die "--minutes must be at least 10"

  local current
  current="$(visibility)" || die "cannot read $REPO - is the token still valid?"

  if [ "$current" = "public" ]; then
    die "$REPO is already public. If that is a window someone left open, run
       \`ci-window.sh close\` first; this script will not take over a window it
       did not open, because it would also close one it was not asked to."
  fi

  if [ -z "$confirmed" ] && [ -z "$dry_run" ]; then
    cat >&2 <<EOF
ci-window: refusing to run without --yes.

  Making $REPO public publishes its entire history - every commit, branch and
  tag - to everyone, permanently. Forks and third-party archives copy it within
  minutes, and flipping it back to private does not recall those copies.
  The audit on 2026-09-22 found no credential in the history, so what is exposed
  is the source code itself - which is the point, but it is a one-way decision.

  Re-run with --yes to accept that.
EOF
    exit 2
  fi

  # From here on the trap owns the close. `INT TERM` are listed explicitly
  # because a trapped EXIT alone does not run when the shell is killed by a
  # signal it has not trapped.
  if [ -z "$dry_run" ]; then
    local deadline_at
    deadline_at="$(date -u -d "+$minutes minutes" +%Y-%m-%dT%H:%M:%SZ)"

    # The deadline is recorded BEFORE the flip, so a crash between the two
    # leaves a public repository the watchdog knows to close rather than a
    # public repository nobody has a record of.
    set_deadline "$deadline_at"
    note "deadline recorded: $deadline_at (the watchdog closes the window after it)"

    trap 'close_window' EXIT INT TERM

    note "flipping $REPO to public"
    api -X PATCH "repos/$REPO" -F private=false >/dev/null
    current="$(visibility)" || die "flipped but could not read the visibility back"
    [ "$current" = "public" ] || die "the API reports '$current' after the flip - not dispatching"
    note "window open"

    # Now, and only now: the endpoint answers 403 while the repository is
    # private, so this is the first moment in the window when the rule can be
    # applied - and applying it before the dispatch means the runs this window
    # exists for are gated from their first check. A failure warns instead of
    # dying deliberately: the flip is one-way and already taken, and closing the
    # window would throw away the run as well as the guard.
    if apply_protection; then
      note "protection verified: ${#PROTECTION_CONTEXTS[@]} required checks on main, and they bind the administrator"
    else
      note "WARNING: the window is open and main is UNGUARDED."
      note "         A red check will not block a merge until the rule is applied."
      note "         Re-apply it with \`ci-window.sh protect\` before merging anything."
    fi
  else
    # No deadline either: a deadline recorded for a window that never opens is
    # the state the watchdog reports as a stale marker, and it would be one this
    # script created.
    note "dry run: $REPO stays $current, no deadline recorded, nothing will be flipped"
  fi

  # A run id captured before the dispatch, so the wait below cannot latch onto
  # a run that was already there. Without it the script would watch the
  # previous run and close the window while the new one was still queued.
  local before
  before="$(api "repos/$REPO/actions/workflows/$workflow/runs?per_page=1" --jq '.workflow_runs[0].id // 0')"

  note "dispatching $workflow on $ref"
  api -X POST "repos/$REPO/actions/workflows/$workflow/dispatches" -f ref="$ref" >/dev/null

  local run_id="" tries=0
  while [ "$tries" -lt 30 ]; do
    run_id="$(api "repos/$REPO/actions/workflows/$workflow/runs?per_page=1" --jq '.workflow_runs[0].id // 0')"
    [ -n "$run_id" ] && [ "$run_id" != "$before" ] && [ "$run_id" != "0" ] && break
    run_id=""
    tries=$((tries + 1))
    sleep 4
  done
  [ -n "$run_id" ] || die "dispatched $workflow but no new run appeared within two minutes"

  note "watching run $run_id - the window closes when it settles, or at the deadline"
  gh run watch "$run_id" --repo "$REPO" --exit-status >/dev/null 2>&1 \
    || note "run $run_id finished red or was cancelled; the window still closes"

  note "run finished"
}

# Re-applies the rule on its own, and the reason it has to exist is the design of
# the call inside `open`: that one is allowed to fail without consequence, so
# there must be a way to retry it that is not "open another window". It is also
# what an operator reaches for when `status` reports NOT APPLIED.
#
# Unlike the call in `open`, this one dies on failure - nothing here is worth
# salvaging, and the command's whole purpose is the rule.
cmd_protect() {
  local current
  current="$(visibility)" || die "cannot read $REPO - is the token still valid?"
  [ "$current" = "public" ] || die "$REPO is $current, so the rule cannot be applied:
       branch protection is a paid feature on a private repository for this account,
       so it exists only while a window is open - and a close deletes it rather than
       suspending it. Open a window first."
  apply_protection || die "the rule was not verified - read it back by hand before relying on it"
  note "protection verified: ${#PROTECTION_CONTEXTS[@]} required checks on main, binding the administrator"
}

cmd_close() { close_window; }

load_token
command="${1:-}"
[ $# -gt 0 ] && shift
case "$command" in
  status)  cmd_status "$@" ;;
  open)    cmd_open "$@" ;;
  protect) cmd_protect "$@" ;;
  close)   cmd_close "$@" ;;
  *) die "usage: ci-window.sh {status|open|protect|close} [options]" ;;
esac
