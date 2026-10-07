#!/usr/bin/env bash
# Asserts that no native library packaged into these archives still carries
# debug symbols.
#
# AGP's stripReleaseDebugSymbols step only *warns* when it cannot find
# llvm-strip ("Unable to strip the following libraries, packaging them as they
# are") and packages them anyway, so a build whose stripper could not be
# resolved is green, signed and installable - just 2.3x bigger. That is how the
# v1.21.0 draft shipped 74,952,058-byte universal APKs against v1.20.0's
# 32,537,022. Signature and split verification cannot see it: the APK is intact
# and complete, it simply still contains .debug_info and .symtab.
#
# The sections are read with readelf, whose table is stable; see
# verify-elf-arch.sh for why a tool's English prose is not used for this.
#
# Usage: verify-native-stripped.sh <apk-or-aab>...
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "verify-native-stripped.sh: at least one APK or AAB must be named" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failed=0
checked=0

for ARCHIVE in "$@"; do
  if [ ! -f "$ARCHIVE" ]; then
    echo "verify-native-stripped.sh: no such file: $ARCHIVE" >&2
    exit 1
  fi

  # Covers an APK's lib/<abi>/x.so and an AAB's base/lib/<abi>/x.so. A file that
  # is not a readable zip reaches the second message rather than the first.
  if ! unzip -Z1 "$ARCHIVE" > "$WORK/entries" 2>/dev/null; then
    echo "verify-native-stripped.sh: $ARCHIVE is not a zip unzip can read" >&2
    exit 1
  fi
  mapfile -t LIBS < <(grep -E '(^|/)lib/[^/]+/[^/]+\.so$' "$WORK/entries" | sort)
  if [ "${#LIBS[@]}" -eq 0 ]; then
    echo "verify-native-stripped.sh: $ARCHIVE carries no native libraries, which" \
         "this app never does - the check would pass without testing anything" >&2
    exit 1
  fi

  for ENTRY in "${LIBS[@]}"; do
    SO="$WORK/lib.so"
    unzip -p "$ARCHIVE" "$ENTRY" > "$SO"
    if ! SECTIONS="$(readelf -S --wide "$SO" 2>/dev/null)"; then
      echo "FAIL $ARCHIVE: $ENTRY is not an ELF readelf can read" >&2
      failed=1
      continue
    fi
    checked=$((checked + 1))
    if printf '%s\n' "$SECTIONS" | grep -qE '\] \.(debug|symtab)'; then
      echo "FAIL $ARCHIVE: $ENTRY still carries debug symbols:" >&2
      printf '%s\n' "$SECTIONS" | grep -E '\] \.(debug|symtab)' | sed 's/^/       /' >&2
      failed=1
    fi
  done
done

if [ "$failed" -ne 0 ]; then
  echo "verify-native-stripped.sh: the libraries above were not stripped; the" \
       "build packaged them as they were. Check that the module pins ndkVersion" \
       "to an NDK the runner installs." >&2
  exit 1
fi

echo "All $checked native libraries in $# archive(s) are stripped."
