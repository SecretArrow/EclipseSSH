#!/usr/bin/env bash
# Build and run GridDump: the terminal package plus the tool, compiled with the cached Kotlin compiler.
#
# Gradle is deliberately not involved. The tool is a harness, not app code, and a replay should not cost
# a build - but it does have to compile the *real* parser, so the sources come from app/src/main.
#
# Usage: run-griddump.sh <columns> <rows> [--frames N] < recording.frames
#
# The output directory is kept between runs and only rebuilt when a source it was built from has
# changed since: the bisection in griddiff.py probes this tool a dozen times, and recompiling the
# terminal package each time would dominate it.
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
pick() { find "$1" -name "$2" 2>/dev/null | grep -v sources | sort -V | tail -1; }

# GRIDDUMP_KC names where the jars are, and there are two shapes of that: the module cache above, and
# a directory holding the jars themselves - which is what a Gradle distribution's own `lib/` is, and
# the shape left on a host whose module cache has been cleared. Both are searched, the cache layout
# first, so a setup that resolved before resolves to the same jars now. The highest version wins, so
# the choice does not depend on the order find happens to walk in; where several versions are present
# and the compiler and the stdlib disagree, the compile says so, and pinning GRIDDUMP_KC at one
# directory is the way out - a directory of jars cannot disagree with itself.
pickJar() {  # pickJar <path under the cache> <glob>
  local hit
  hit=$(pick "$cache/$1" "$2")
  [ -n "$hit" ] || hit=$(pick "$cache" "$2")
  printf '%s' "$hit"
}

if command -v kotlinc >/dev/null; then
  have_kotlinc=1
  stdlib=""
else
  have_kotlinc=0
  stdlib=$(pickJar "org.jetbrains.kotlin/kotlin-stdlib" 'kotlin-stdlib-*.jar')
fi

mains=()
for f in "$repo"/app/src/main/java/dev/eclipse/ssh/terminal/*.kt; do
  case "$f" in *TerminalExportRenderer.kt) continue ;; esac
  mains+=("$f")
done

# A cached class is a claim about the parser as it stood when it was built, so it is only reused while
# it is newer than everything it was built from. Left at "does the class exist", editing the terminal
# package and replaying answered with the *previous* emulator - and the answer looked like a verdict
# about the current one.
stale=0
for f in "$here/GridDump.kt" "${mains[@]}"; do
  [ "$f" -nt "$out/GridDumpKt.class" ] && stale=1
done

if [ -f "$out/GridDumpKt.class" ] && [ "${GRIDDUMP_REBUILD:-0}" != 1 ] && [ "$stale" = 0 ]; then
  exec java -Xmx1100m -cp "$out${stdlib:+:$stdlib}" GridDumpKt "$@"
fi

if [ "$have_kotlinc" = 1 ]; then
  compiler=(kotlinc -nowarn -jvm-target 17 -d "$out")
else
  KC=$(pickJar "org.jetbrains.kotlin/kotlin-compiler-embeddable" 'kotlin-compiler-embeddable-*.jar')
  TROVE=$(pickJar "org.jetbrains.intellij.deps" 'trove4j-*.jar')
  ANNO=$(pickJar "org.jetbrains/annotations" 'annotations-*.jar')
  CORO=$(pickJar "org.jetbrains.kotlinx" 'kotlinx-coroutines-core-jvm-*.jar')
  [ -n "$KC" ] && [ -n "$stdlib" ] || {
    echo "no Kotlin compiler found: GRIDDUMP_KC names neither a Gradle module cache nor a directory" >&2
    echo "holding the Kotlin jars (a Gradle distribution's lib/ is one); install kotlinc instead" >&2
    exit 1
  }
  compiler=(java -Xmx1100m -cp "$KC:$stdlib:$TROVE:$ANNO:$CORO" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
    -nowarn -no-stdlib -jvm-target 17 -d "$out")
fi

rm -rf "$out"; mkdir -p "$out"
"${compiler[@]}" ${stdlib:+-classpath "$stdlib"} "$here/GridDump.kt" "${mains[@]}" 2>&1 |
  grep -vE '^warning: unable to find|^warning: .*is deprecated|^warning: .*never used' | head -20
[ -f "$out/GridDumpKt.class" ] || { echo "COMPILE FAILED" >&2; exit 1; }

exec java -Xmx1100m -cp "$out${stdlib:+:$stdlib}" GridDumpKt "$@"
