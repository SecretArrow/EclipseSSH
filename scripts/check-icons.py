#!/usr/bin/env python3
"""Fails when a Material icon is used without the import that makes it resolve.

Compose's Material icons are *extension properties*, not members of `Icons`: `Icons.Filled.Person`
compiles only in a file that imports `androidx.compose.material.icons.filled.Person`. A use without
its import is an UNRESOLVED_REFERENCE, and nothing earlier in the toolchain notices -- the IDE might,
if it has indexed the file, and the compiler does, ~10 minutes into a CI run. This change shipped two
such uses of `Icons.Default.Person` in `MainActivity.kt` and CI's first compile was where they were
found, which is what this exists to prevent.

Every icon in this repository happens to be in a `filled`/`outlined`/... package, so the check is
mechanical: the group segment of the use spells the package segment of the required import, with
`Default` spelled `filled`. A use whose import is present anywhere in the file passes, which is what
Kotlin requires.

Usage: scripts/check-icons.py [path ...]        (default: app/src)
"""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent

GROUPS = {
    "Default": "filled",
    "Filled": "filled",
    "Outlined": "outlined",
    "Rounded": "rounded",
    "Sharp": "sharp",
    "TwoTone": "twotone",
}
USE = re.compile(r"\bIcons\.(AutoMirrored\.)?(Default|Filled|Outlined|Rounded|Sharp|TwoTone)\.([A-Za-z0-9_]+)")
IMPORT = re.compile(r"^import (androidx\.compose\.material\.icons\.[a-z.]+\.\w+)$", re.M)

targets = sys.argv[1:] or [str(root / "app/src")]
bad = 0
scanned = 0
for arg in targets:
    path = pathlib.Path(arg)
    files = [path] if path.is_file() else sorted(path.rglob("*.kt"))
    for p in files:
        scanned += 1
        src = p.read_text(encoding="utf-8")
        imports = set(IMPORT.findall(src))
        for match in USE.finditer(src):
            mirrored, group, name = match.group(1), match.group(2), match.group(3)
            package = "androidx.compose.material.icons." + ("automirrored." if mirrored else "") + GROUPS[group]
            if f"{package}.{name}" not in imports:
                line = src.count("\n", 0, match.start()) + 1
                print(f"FAIL {p}:{line}  Icons.{mirrored or ''}{group}.{name} needs `import {package}.{name}`")
                bad += 1

if bad:
    print(f"\n{bad} unresolved icon use(s) in {scanned} file(s)")
    sys.exit(1)
print(f"All icon uses resolve: {scanned} file(s) scanned")
