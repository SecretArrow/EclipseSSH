#!/usr/bin/env python3
"""End-to-end test of the OPENCODE optional package, through the app's real UI.

The question this answers: does ticking "opencode" give the user a working
`opencode` command inside the Ubuntu userspace? Nothing is mocked - the
pipeline installs Node.js (the implied dependency), runs npm inside proot
against the registry, and this script then drives the app's own terminal and
runs `opencode --version` in it.

The optional-package checkboxes live on the INSTALL CONFIRMATION DIALOG (the
"Preinstall (optional)" list), not on the window - so the flow is: Install ->
the dialog -> scroll the dialog's list to opencode -> tick it -> the dialog's
Install. Reuses the e2e driver's guarded-adb + navigation helpers; runs on the
instabox Android 14 container (x86_64), package dev.eclipse.ssh.debug.

Geometry: adbutil parses a node's bounds as (center_x, center_y, w, h) and its
rect as (x1, y1, x2, y2) - the band tests below read rect, never bounds.
"""
import json
import sys
import time

sys.path.insert(0, "testing/ubuntu-e2e")
sys.path.insert(0, "testing/universal")

from adbutil import Adb  # noqa: E402
from driver import (  # noqa: E402
    BUTTON_INSTALL,
    LABEL_LOCAL_CARD,
    E2eDriver,
)

PACKAGE = "dev.eclipse.ssh.debug"
OUT = "out/opencode-test"


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def row_checked(els, label):
    """Whether a checked='true' checkable node shares a horizontal band with the
    `label` text node - Compose draws the Checkbox and the label as siblings."""
    node = next((e for e in els if e.attrs.get("text", "") == label), None)
    if not node:
        return False
    band = node.rect
    for el in els:
        a = el.attrs
        if a.get("checkable") == "true" and a.get("checked") == "true":
            eb = el.rect
            if not (eb[3] < band[1] or eb[1] > band[3]):
                return True
    return False


def tick_in_dialog(d, label, swipes=14):
    """Scroll the confirmation dialog's optional-package list until the `label`
    row is on screen, tap it, and prove the toggle took."""
    for attempt in range(swipes):
        els = d.dump()
        node = next((e for e in els if e.attrs.get("text", "") == label), None)
        if node:
            log("found '%s' row (attempt %d), tapping the label" % (label, attempt))
            d.tap(node)
            time.sleep(1.5)
            if row_checked(d.dump(), label):
                return True
            # The label may not be the toggle: tap the checkbox itself, the
            # checkable node on the same row.
            els2 = d.dump()
            band = node.rect
            for el in els2:
                if el.attrs.get("checkable") == "true":
                    eb = el.rect
                    if not (eb[3] < band[1] or eb[1] > band[3]):
                        log("tapping the row's checkbox node directly")
                        d.tap(el)
                        time.sleep(1.5)
                        break
            if row_checked(d.dump(), label):
                return True
            log("tap did not register yet; continuing the walk")
        # Scroll the dialog's own list (the dialog occupies the middle band of
        # the screen; a centered swipe reaches it and not the window behind).
        d.adb.shell("input swipe 360 930 360 640 300", timeout=30)
        time.sleep(1.2)
    return False


def main():
    adb = Adb()
    d = E2eDriver(
        adb, PACKAGE, PACKAGE + ".test", OUT, "SMOKE", 30,
        launch_activity="dev.eclipse.ssh.MainActivity",
    )
    summary = {"phases": {}}

    log("phase 1: clean state + install dialog with opencode ticked")
    d._clean_state()
    d.launch()
    d.open_settings()
    d.require_linux_section()
    d.open_ubuntu_window()
    if not d.scroll_to_action_button():
        raise RuntimeError("the Install button was not found")
    if not d.tap_last(d.dump(), BUTTON_INSTALL, exact=True):
        raise RuntimeError("Install button tap failed")
    time.sleep(1.5)
    if not d.find(d.dump(), "root filesystem"):
        raise RuntimeError("the install confirmation dialog did not appear")
    log("confirmation dialog is up; scrolling to the opencode row")
    if not tick_in_dialog(d, "opencode"):
        raise RuntimeError("the opencode checkbox was never ticked")
    summary["phases"]["checkbox"] = "ticked+verified"
    log("opencode ticked (Node.js is implied by OptionalPackages.resolve)")

    if not d.tap_last(d.dump(), BUTTON_INSTALL, exact=True):
        raise RuntimeError("confirmation dialog Install tap failed")
    summary["phases"]["install-started"] = True
    log("install started; waiting (rootfs + apt + node + npm opencode)")

    end = d.wait_install_done(30)
    log("install finished: %s" % end)
    summary["phases"]["install"] = end

    log("phase 3: objective check - npm global inside the rootfs")
    _, out = adb.shell(
        "run-as %s sh -c 'find files/linux -type d -name opencode-ai 2>/dev/null | head -3'"
        % PACKAGE,
        timeout=60,
    )
    log("opencode-ai dirs: %s" % (out.strip() or "(none)"))
    summary["npm_dir"] = out.strip()
    _, out2 = adb.shell(
        "run-as %s sh -c 'find files/linux -type f -name node 2>/dev/null | head -3'"
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
