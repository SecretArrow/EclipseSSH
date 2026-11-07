#!/usr/bin/env python3
"""Probe 6: the fix test - run npm through node (bypassing the broken
env-shebang shim) and install opencode-ai with it, then run opencode."""
import sys
import time

sys.path.insert(0, "testing/ubuntu-e2e")
sys.path.insert(0, "testing/universal")

from adbutil import Adb  # noqa: E402
from driver import E2eDriver, LABEL_LOCAL_CARD  # noqa: E402

PACKAGE = "dev.eclipse.ssh.debug"


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def screen_joined(d):
    els = d.dump()
    parts = []
    for el in els:
        parts.append(el.attrs.get("text") or "")
        parts.append(el.attrs.get("content-desc") or "")
    return els, " || ".join(p for p in parts if p)


def ensure_card(d, adb):
    for _ in range(6):
        els, joined = screen_joined(d)
        card = d.find(els, LABEL_LOCAL_CARD)
        if card:
            return card
        if d.find(els, "Hosts", exact=True):
            d.tap_last(els, "Hosts", exact=True)
        else:
            adb.back()
        time.sleep(2.5)
    return None


def terminal_open(d):
    _, joined = screen_joined(d)
    return "Connected" in joined and "80x" in joined


def open_terminal(d, adb):
    for attempt in range(4):
        card = ensure_card(d, adb)
        if not card:
            adb.force_stop(PACKAGE)
            time.sleep(2)
            d.launch()
            time.sleep(3)
            continue
        els, _ = screen_joined(d)
        btn = None
        for el in els:
            if "Open terminal" in (el.attrs.get("content-desc") or ""):
                btn = el
                break
        d.tap(btn or card)
        time.sleep(10)
        if terminal_open(d):
            log("terminal open (attempt %d)" % attempt)
            return True
        adb.back()
        time.sleep(1.5)
    return False


def type_cmd(adb, cmd, wait, d, shot):
    time.sleep(3.5)
    adb.shell("input text '%s'" % cmd.replace(" ", "%s"), timeout=30)
    time.sleep(1)
    adb.keyevent("KEYCODE_ENTER")
    time.sleep(wait)
    path = d.screenshot(shot)
    log("%s -> %s" % (cmd, path))


def main():
    d = E2eDriver(
        Adb(), PACKAGE, PACKAGE + ".test", "out/oc-probe6", "SMOKE", 10,
        launch_activity="dev.eclipse.ssh.MainActivity",
    )
    adb = d.adb
    if not open_terminal(d, adb):
        raise RuntimeError("the terminal never opened")
    type_cmd(
        adb,
        "node /usr/share/nodejs/npm/bin/npm-cli.js install -g opencode-ai 2>&1 | tail -n 5; echo EXIT:$?",
        120, d, "p6-install",
    )
    type_cmd(adb, "opencode --version; echo EXIT:$?", 12, d, "p6-opencode")


if __name__ == "__main__":
    main()
