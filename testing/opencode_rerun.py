#!/usr/bin/env python3
"""Re-run only phases 3-4 of the opencode UI test against the already-installed
userspace: the Local Ubuntu card -> terminal -> node --version -> opencode --version.
Skips the install phases (the rootfs and the npm global are already in place from
run 1); a clean install cycle is opencode_ui_test.py's own job.
"""
import json
import sys
import time

sys.path.insert(0, "testing/ubuntu-e2e")
sys.path.insert(0, "testing/universal")

from adbutil import Adb  # noqa: E402
from driver import LABEL_LOCAL_CARD, E2eDriver  # noqa: E402

PACKAGE = "dev.eclipse.ssh.debug"
OUT = "out/opencode-rerun"


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def main():
    adb = Adb()
    d = E2eDriver(
        adb, PACKAGE, PACKAGE + ".test", OUT, "RERUN", 30,
        launch_activity="dev.eclipse.ssh.MainActivity",
    )
    summary = {"phases": {}}

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
    if not d._open_tab("Hosts"):
        d.launch()
        if not d._open_tab("Hosts"):
            raise RuntimeError("the Hosts tab was never reachable")
    if not d.wait_visible((LABEL_LOCAL_CARD,), 90, poll=5):
        raise RuntimeError("the Local Ubuntu card never appeared")
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
