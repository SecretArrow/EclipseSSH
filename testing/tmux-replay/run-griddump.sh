#!/usr/bin/env bash
# Build and run GridDump: the terminal package plus the tool, compiled with the cached Kotlin compiler.
#
# Gradle is deliberately not involved. The tool is a harness, not app code, and a replay should not cost
# a build - but it does have to compile the *real* parser, so the sources come from app/src/main.
#
# Usage: run-griddump.sh <columns> <rows> [--frames N] < recording.frames
#
# The output directory is kept between runs and only rebuilt when asked: the bisection in griddiff.py
# probes this tool a dozen times, and recompiling the terminal package each time would dominate it.
#
# Environment: GRIDDUMP_OUT (where to build, default $TMPDIR/griddump-out), GRIDDUMP_REBUILD=1 (compile
# again), GRIDDUMP_KC (where the Kotlin jars are, when there is no kotlinc and no Gradle cache).
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)

export JAVA_HOME=${JAVA_HOME:-/home/dev/jvm/jdk-17.0.20.1+1}
export PATH="$JAVA_HOME/bin:$PATH"
# The Kotlin jars, in a Gradle module cache by default. A machine that has never run the build has no
# such cache, and this is the name the failure below has always told the reader to set - reading it is
# the difference between that message being an instruction and being a dead end.
cache=${GRIDDUMP_KC:-$HOME/.gradle/caches/modules-2/files-2.1}
out=${GRIDDUMP_OUT:-${TMPDIR:-/tmp}/griddump-out}
pick() { find "$1" -name "$2" 2>/dev/null | grep -v sources | head -1; }

if command -v kotlinc >/dev/null; then
  have_kotlinc=1
  stdlib=""
else
  have_kotlinc=0
  stdlib=$(pick "$cache/org.jetbrains.kotlin/kotlin-stdlib/2.0.21" 'kotlin-stdlib-2.0.21.jar')
fi

if [ -f "$out/GridDumpKt.class" ] && [ "${GRIDDUMP_REBUILD:-0}" != 1 ]; then
  exec java -Xmx1100m -cp "$out${stdlib:+:$stdlib}" GridDumpKt "$@"
fi

if [ "$have_kotlinc" = 1 ]; then
  compiler=(kotlinc -nowarn -jvm-target 17 -d "$out")
else
  KC=$(pick "$cache/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21" '*.jar')
  TROVE=$(pick "$cache/org.jetbrains.intellij.deps" 'trove4j-*.jar')
  ANNO=$(pick "$cache/org.jetbrains/annotations" "annotations-*.jar")
  CORO=$(pick "$cache/org.jetbrains.kotlinx" "kotlinx-coroutines-core-jvm-*.jar")
  [ -n "$KC" ] && [ -n "$stdlib" ] || { echo "no Kotlin compiler found; set GRIDDUMP_KC or install kotlinc" >&2; exit 1; }
  compiler=(java -Xmx1100m -cp "$KC:$stdlib:$TROVE:$ANNO:$CORO" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
    -nowarn -no-stdlib -jvm-target 17 -d "$out")
fi

mains=()
for f in "$repo"/app/src/main/java/dev/eclipse/ssh/terminal/*.kt; do
  case "$f" in *TerminalExportRenderer.kt) continue ;; esac
  mains+=("$f")
done

rm -rf "$out"; mkdir -p "$out"
"${compiler[@]}" ${stdlib:+-classpath "$stdlib"} "$here/GridDump.kt" "${mains[@]}" 2>&1 |
  grep -vE '^warning: unable to find|^warning: .*is deprecated|^warning: .*never used' | head -20
[ -f "$out/GridDumpKt.class" ] || { echo "COMPILE FAILED" >&2; exit 1; }

exec java -Xmx1100m -cp "$out${stdlib:+:$stdlib}" GridDumpKt "$@"
