#!/usr/bin/env python3
"""Fails when an Activity class in the app is not declared in the manifest.

Nothing in an Android build fails for an undeclared activity. The module compiles, `lintRelease` is
silent, the APK is built and signed, the boot gate boots it -- because nothing resolves an activity
class until an `Intent` names it. The class is packaged and simply unreachable: the only symptom is
`ActivityNotFoundException` the first time a user taps the button that reaches it, and by then the
APK is on someone's phone. `ExportProfilesActivity` reached CI in exactly that state: compiled,
linted, packaged, and with no `<activity>` entry, found by reading the manifest against the classes
rather than by any job. This is the mechanical version of that reading.

The gate that would catch it inside the build is a JVM test that lets Android resolve the class --
`createAndroidComposeRule<X>()` resolves its activity against the release manifest, so an activity
registered in none is one the rule cannot launch, which is why every settings destination in this
tree has an `XActivityRobolectricTest`. This script is the cheaper net in front of it.

Both spellings of a name are accepted: the fully-qualified one, and the leading-dot one relative to
the application namespace, which is what this tree writes and what AGP 9 keeps in the module's build
script rather than in a `package=` attribute. An `abstract` class is skipped: it is never launched.

Usage: scripts/check-manifest.py [app-dir] [manifest-path]
"""
import os
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent
APP = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / "app"
MANIFEST = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else APP / "src/main/AndroidManifest.xml"

namespace = re.search(r'^\s*namespace\s*=\s*"([\w.]+)"', (APP / "build.gradle.kts").read_text(encoding="utf-8"), re.M).group(1)
DECL = re.compile(r"^\s*(?:abstract\s+)?class\s+(\w+)\s*:\s*[\w.]*(Activity|Application)\s*\(", re.M)

declared = set(re.findall(r'android:name="([^"]+)"', MANIFEST.read_text(encoding="utf-8")))

missing = []
checked = 0
for path in sorted((APP / "src/main/java").rglob("*.kt")):
    text = path.read_text(encoding="utf-8")
    package = re.search(r"^package\s+([\w.]+)", text, re.M)
    if not package:
        continue
    pkg = package.group(1)
    for klass, base in DECL.findall(text):
        # An abstract base is never launched, so it needs no entry of its own.
        if re.search(r"^\s*abstract\s+class\s+%s\b" % klass, text, re.M):
            continue
        checked += 1
        if klass in declared or "%s.%s" % (pkg, klass) in declared:
            continue
        suffix = pkg[len(namespace):] if pkg.startswith(namespace) else "." + pkg
        relative = (".%s.%s" % (suffix.lstrip("."), klass)) if suffix.strip(".") else "." + klass
        if relative in declared:
            continue
        missing.append(f"FAIL {path}: {klass} extends {base}, and {MANIFEST.name} declares no activity for it")

for line in missing:
    print(line)
if missing:
    print(f"\n{len(missing)} of {checked} activity class(es) are unreachable")
    sys.exit(1)
print(f"Every activity is declared: {checked} class(es) checked, {len(declared)} names in the manifest")
