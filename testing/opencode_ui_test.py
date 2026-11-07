#!/usr/bin/env python3
"""End-to-end test of the OPENCODE optional package, through the app's real UI.

The question this answers: does ticking "opencode" on the install dialog give
the user a working `opencode` command inside the Ubuntu userspace? Nothing is
mocked - the pipeline installs Node.js (implied dependency), runs npm inside
proot against the registry, and this script then drives the app's own terminal
and runs `opencode --version` in it.

Reuses the e2e driver's guarded-adb + navigation helpers; runs on the instabox
Android 14 container (x86_64), package dev.eclipse.ssh.debug.

Phases:
  1. clean state (pm clear) + fresh install with ONLY the opencode checkbox
     ticked - the pipeline must pull Node.js in itself (OptionalPackages.resolve)
  2. wait for "Installed and verified"
  3. objective file check: the npm global landed in the rootfs (run-as)
  4. open the Local Ubuntu card's terminal, capture the Linux notification's
     title (the dynamic-title fix), run `node --version` and
     `opencode --version; echo EXIT:$?`, screenshot each
"""
import json
import sys
import time

sys.path.insert(0, "testing/ubuntu-e2e")
sys.path.insert(0, "testing/universal")

from adbutil import Adb  # noqa: E402
from driver import (  # noqa: E402
    BUTTON_INSTALL,
    LABEL_INSTALLED,
    LABEL_INSTALL_ROW,
    LABEL_LOCAL_CARD,
    E2eDriver,
)

PACKAGE = "dev.eclipse.ssh.debug"
OUT = "out/opencode-test"


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def scroll_up(d, times=10):
    """Swipe down-towards-top (content moves down = scroll up), the reverse of
    _scroll_swipe, so the walk can return to the action row after ticking a
    checkbox near the bottom of the window."""
    for _ in range(times):
        els = d.dump()
        w, h = d._window_extent(els)
        if not w or not h:
            time.sleep(1)
            continue
        d.adb.shell("input swipe %d %d %d %d 400" % (w // 2, int(h * 0.35), w // 2, int(h * 0.75)))
        time.sleep(1.2)
        els = d.dump()
        if d.find(els, BUTTON_INSTALL, exact=True):
            return True
    return d.find(d.dump(), BUTTON_INSTALL, exact=True) is not None


def find_and_tick(d, label):
    """Scroll the window until the `label` optional-package row is on screen,
    tap it, and prove the toggle took by reading the checkable node on the same
    row. Returns True once a checked box shares the label's row band."""
    for attempt in range(24):
        els = d.dump()
        node = d.find(els, label, exact=True)
        if node:
            log("found '%s' row, tapping" % label)
            d.tap(node)
            time.sleep(1.5)
            for verify_attempt in range(3):
                els2 = d.dump()
                if row_checked(d, els2, label):
                    return True
                # The label may not be the toggle itself: tap the checkbox,
                # which sits at the row's leading edge.
                b = node.bounds
                d.adb.tap(b[0] // 2 + 24, (b[1] + b[3]) // 2)
                time.sleep(1.5)
            return row_checked(d, d.dump(), label)
        d._scroll_swipe(els)
        time.sleep(1.2)
    return False


def row_checked(d, els, label):
    """Whether a checked='true' checkable node shares a horizontal band with the
    `label` text node - Compose draws the Checkbox and the label as siblings."""
    node = d.find(els, label, exact=True)
    if not node:
        return False
    band = node.bounds
    for el in els:
        a = el.attrs
        if a.get("checkable") == "true" and a.get("checked") == "true":
            eb = el.bounds
            if not (eb[3] < band[1] or eb[1] > band[3]):
                return True
    return False


def main():
    adb = Adb()
    d = E2eDriver(adb, PACKAGE, PACKAGE + ".test", OUT, "SMOKE", 30)
    summary = {"phases": {}}

    log("phase 1: clean state + fresh install with opencode ticked")
    d._clean_state()
    d.launch()
    d.open_settings()
    d.require_linux_section()
    d.open_ubuntu_window()
    if not find_and_tick(d, "opencode"):
        raise RuntimeError("the opencode checkbox was never ticked")
    summary["phases"]["checkbox"] = "ticked+verified"
    log("opencode ticked (Node.js is implied by resolve)")
    if not scroll_up(d):
        raise RuntimeError("the Install button was not reachable after scrolling up")
    if not d.tap_last(d.dump(), BUTTON_INSTALL, exact=True):
        raise RuntimeError("Install button tap failed")
    time.sleep(1.5)
    if not d.find(d.dump(), "root filesystem"):
        raise RuntimeError("the install confirmation dialog did not appear")
    if not d.tap_last(d.dump(), BUTTON_INSTALL, exact=True):
        raise RuntimeError("confirmation dialog Install tap failed")
    summary["phases"]["install-started"] = True
    log("install started; waiting (rootfs + apt + node + npm opencode)")

    end = d.wait_install_done(30)
    log("install finished: %s" % end)
    summary["phases"]["install"] = end

    log("phase 3: objective check - npm global inside the rootfs")
    rc, out = adb.shell(
        "run-as %s sh -c 'find files/linux -type d -name opencode-ai 2>/dev/null | head -3'"
        % PACKAGE,
        timeout=60,
    )
    log("opencode-ai dirs: %s" % (out.strip() or "(none)"))
    summary["npm_dir"] = out.strip()
    rc, out2 = adb.shell(
        "run-as %s sh -c 'find files/linux -path \"*node*\" -name node -type f 2>/dev/null | head -3'"
        % PACKAGE,
        timeout=60,
    )
    log("node binary: %s" % (out2.strip() or "(none)"))
    summary["node_bin"] = out2.strip()

    log("phase 4: terminal through the UI + run opencode")
    d.launch()
    if not d.wait_visible((LABEL_LOCAL_CARD,), 90, poll=5):
        raise RuntimeError("the Local Ubuntu card never appeared after install")
    card = d.find(d.dump(), LABEL_LOCAL_CARD)
    if not d.tap(card):
        raise RuntimeError("could not tap the Local Ubuntu card")
    time.sleep(12)  # session start (proot) + first draw

    _, notif = adb.shell(
        "dumpsys notification | grep -E 'android.title=' | head -4", timeout=30
    )
    log("notification titles: %s" % " | ".join(notif.strip().splitlines()))
    summary["notif_titles"] = notif.strip().splitlines()

    for i, (cmd, shot) in enumerate(
        [
            ("node --version", "node-version"),
            ("opencode --version; echo EXIT:$?", "opencode-version"),
        ]
    ):
        adb.shell("input text '%s'" % cmd.replace(" ", "%s"), timeout=30)
        time.sleep(1)
        adb.keyevent("KEYCODE_ENTER")
        time.sleep(12 if i == 1 else 4)
        path = d.screenshot(shot)
        log("screenshot %s -> %s" % (cmd, path))
        summary.setdefault("screenshots", []).append(path)

    if adb.pid_of(PACKAGE) is None:
        raise RuntimeError("the app process died while driving the terminal")
    summary["app_alive"] = True
    d.adb.back()

    with open("%s/summary.json" % OUT, "w") as fh:
        json.dump(summary, fh, indent=2)
    log("SUMMARY %s" % json.dumps(summary))
    print("ALL_DONE", flush=True)


if __name__ == "__main__":
    main()
