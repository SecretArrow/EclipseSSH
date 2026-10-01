#!/usr/bin/env bash
#
# Free the parts of the hosted runner image an Android build never touches, and
# then state how much room is left.
#
# Why this exists. The emulator does not create its userdata partition until it
# has the free space for the whole of it, and on the `ubuntu-24.04` pool that
# partition is 7,372.80 MB. A runner that is short answers with
#
#     FATAL | Not enough space to create userdata partition.
#             Available: 4208.32 MB at /home/runner/.android/avd/..., need 7372.80 MB.
#
# from an emulator the step has already backgrounded, so the job dies at the
# `adb wait-for-device` below it with "adb never saw the emulator" and the real
# reason 200 lines up the emulator log. That is exactly what happened to
# instrumentation run 36804895870 (2026-10-01, PR #207) and to run 36805590303
# (PR #208): two pull requests whose diffs touch no emulator at all were
# reddened by the runner they landed on, while the same workflow passed on main
# and on every branch that day. The image always has the room to give - it
# carries several gigabytes of toolchains this project has never invoked - so
# the fix is to take that room before the AVD asks for it, and to say the
# requirement out loud where the number is meaningful.
#
# Every path below is removed only if it is there, so a leaner image is not an
# error and a path that moves in a future image is not either. Nothing here
# touches the JDK under $AGENT_TOOLSDIRECTORY, the Android SDK's platform,
# emulator or system images, or the pinned NDK.
#
# Usage: free-runner-disk.sh [--require-mb N] [--dry-run]
#   --require-mb N  exit 1 unless the root filesystem has at least N MB free
#                   once the removals are done. Default 7400 - the emulator's
#                   own figure, rounded up, and the one requirement this script
#                   can name without inventing a margin.
#   --dry-run       report what would be removed and what is free, remove nothing.
#
# It is safe to call more than once in a job: a path that is already gone is
# skipped, and the assertion is a reading rather than a change.

set -euo pipefail

REQUIRE_MB=7400
DRY_RUN=false

while [ $# -gt 0 ]; do
  case "$1" in
    --require-mb)
      [ $# -ge 2 ] || { echo "free-runner-disk: --require-mb needs a value" >&2; exit 2; }
      REQUIRE_MB="$2"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    *)
      echo "free-runner-disk: unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

case "$REQUIRE_MB" in
  ''|*[!0-9]*) echo "free-runner-disk: --require-mb must be a whole number of MB" >&2; exit 2 ;;
esac

# Available MB on the filesystem the AVD will live on. The emulator measures
# the filesystem its userdata partition lands on - it names the path in its own
# FATAL - and that path is under $HOME, so $HOME is what is measured here. On
# this image that is the same filesystem as /; if a future image splits them,
# this reads the one that decides.
TARGET="${HOME:-/}"

free_mb() {
  df -Pk "$TARGET" | awk 'NR==2 {print int($4 / 1024)}'
}

report() {
  echo "free-runner-disk: $1"
  df -h "$TARGET" | sed 's/^/  /'
}

# Removal is one function so that --dry-run cannot rot into a second, divergent
# copy of the list: there is exactly one place that decides what goes.
remove() {
  local path="$1"
  [ -e "$path" ] || return 0
  local size
  size="$(du -sh "$path" 2>/dev/null | awk '{print $1}')"
  if [ "$DRY_RUN" = true ]; then
    printf '  would remove %s\t%s\n' "${size:-?}" "$path"
    return 0
  fi
  printf '  removing %s\t%s\n' "${size:-?}" "$path"
  sudo rm -rf --one-file-system "$path" || echo "  could not remove $path (continuing)"
}

report "before:"
BEFORE="$(free_mb)"

# The toolchains the hosted image carries that an Android build never invokes.
# Sizes are what they usually are, not a promise; each is skipped when absent.
UNUSED=(
  /usr/share/dotnet            # ~2.5 GB
  /usr/share/swift             # ~2.0 GB
  /opt/ghc                     # ~1.5 GB
  /usr/local/.ghcup            # ~2.0 GB
  /opt/az                      # ~1.0 GB
  /usr/local/share/boost       # ~0.5 GB
  /usr/share/miniconda
  /opt/google-cloud-sdk
)

# CodeQL's bundle is the single largest thing on the image and belongs to a
# workflow this repository does not run. It is addressed through the tool cache
# variable when the runner sets one, and by its usual path when it does not.
UNUSED+=("${AGENT_TOOLSDIRECTORY:-/opt/hostedtoolcache}/CodeQL")

for path in "${UNUSED[@]}"; do
  remove "$path"
done

# The image ships more than one NDK. The build pins exactly one - the same
# FREERDP_NDK_VERSION the workflows hand to the SDK manager - and every other
# version is several gigabytes of a compiler this build will not use.
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
  for ndk in "$ANDROID_HOME"/ndk/*; do
    [ -e "$ndk" ] || continue
    if [ -n "${FREERDP_NDK_VERSION:-}" ] && [ "$(basename "$ndk")" = "$FREERDP_NDK_VERSION" ]; then
      printf '  keeping the pinned NDK\t%s\n' "$ndk"
      continue
    fi
    remove "$ndk"
  done
fi

if [ "$DRY_RUN" = true ]; then
  report "dry run, nothing removed:"
  echo "free-runner-disk: would require ${REQUIRE_MB} MB free (not asserted in a dry run)"
  exit 0
fi

report "after:"
AFTER="$(free_mb)"
echo "free-runner-disk: reclaimed $((AFTER - BEFORE)) MB, ${AFTER} MB free on $TARGET"

if [ "$AFTER" -lt "$REQUIRE_MB" ]; then
  echo "free-runner-disk: ${AFTER} MB free on $TARGET, and ${REQUIRE_MB} MB is required." >&2
  echo "The emulator cannot create its 7372.80 MB userdata partition on this runner," >&2
  echo "and it would fail later as 'adb never saw the emulator' with the real reason" >&2
  echo "buried in the emulator log. Failing here instead, with the number." >&2
  exit 1
fi
