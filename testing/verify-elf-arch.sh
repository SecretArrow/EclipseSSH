#!/usr/bin/env bash
# Asserts that an ELF file is the architecture an Android ABI names, and is the
# one place that mapping is written down.
#
# Why readelf rather than `file`. `file` answers "which architecture is this" in
# English prose, and that prose is not stable across versions: `file` 5.46
# renames e_machine 3 to `Intel i386`, where 5.45 - which is what the GitHub
# runner image carries today - spells it `Intel 80386`. A check that matches
# against the wording therefore reports a *correct* x86 binary as "is not a
# Intel 80386 ELF", which is the same sentence a genuinely wrong-ABI split
# produces, so the failure is worse than a false red: it is a false red that
# reads like the defect the check exists to catch. It has already cost one
# release's verification its x86 split (AUDIT-REPORT.md section 55), and the
# only reason CI never saw it is that the runner happened to carry the older
# `file`. readelf prints the machine name from binutils' own table, which has
# not been reworded, so the expectation here is stable and every host agrees.
# `file` is still the right tool for a question it answers structurally - the
# static-linking assertion in ci.yml uses its own wording for exactly that
# reason, and reads it out of the dynamic section instead.
#
# Usage: verify-elf-arch.sh <elf> <abi>
#   e.g. verify-elf-arch.sh linux/build/native/jniLibs/arm64-v8a/libproot.so arm64-v8a
#
# The ABI names both the class and the machine, so a caller names one thing and
# cannot name it half-right - which is what the three copies of this table used
# to allow, one in each of two workflows and one in testing/.
set -euo pipefail

ELF="${1:?the ELF file to check must be named}"
ABI="${2:?the ABI it has to be must be named}"

declare -A ELF_CLASS=(
  [arm64-v8a]='ELF64'
  [armeabi-v7a]='ELF32'
  [x86_64]='ELF64'
  [x86]='ELF32'
)
declare -A ELF_MACHINE=(
  [arm64-v8a]='AArch64'
  [armeabi-v7a]='ARM'
  [x86_64]='Advanced Micro Devices X86-64'
  [x86]='Intel 80386'
)

if [ -z "${ELF_CLASS[${ABI}]:-}" ]; then
  # An ABI that is not in the table would otherwise compare against two empty
  # strings and pass every file handed to it.
  echo "verify-elf-arch.sh: '$ABI' is not an ABI this table carries" >&2
  exit 1
fi
if [ ! -f "$ELF" ]; then
  echo "verify-elf-arch.sh: no such file: $ELF" >&2
  exit 1
fi

if ! header="$(readelf -h "$ELF" 2>/dev/null)"; then
  echo "verify-elf-arch.sh: readelf could not read $ELF as an ELF, so it is not one" >&2
  exit 1
fi
class="$(printf '%s\n' "$header" | sed -nE 's/^[[:space:]]*Class:[[:space:]]*([^[:space:]]+).*$/\1/p' | head -1)"
machine="$(printf '%s\n' "$header" | sed -nE 's/^[[:space:]]*Machine:[[:space:]]*(.*[^[:space:]])[[:space:]]*$/\1/p' | head -1)"

if [ "$class" != "${ELF_CLASS[${ABI}]}" ] || [ "$machine" != "${ELF_MACHINE[${ABI}]}" ]; then
  printf '%s is not a %s ELF: readelf reports Class: %s, Machine: %s (expected Class: %s, Machine: %s)\n' \
    "$ELF" "$ABI" "${class:-unreadable}" "${machine:-unreadable}" \
    "${ELF_CLASS[${ABI}]}" "${ELF_MACHINE[${ABI}]}" >&2
  exit 1
fi

echo "verified $ABI: ${class}/${machine} (${ELF})"
