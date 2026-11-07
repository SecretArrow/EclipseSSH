#!/usr/bin/env python3
"""Probe 3d: open the Ubuntu terminal RELIABLY, then run npm by hand to capture
why `npm install -g opencode-ai` refused (the app's install log says exit=1).

Hard-won sequencing, each step verified:
  - a previous run's typing may have landed in the host list's SEARCH field,
    whose filter then hides the Local card entirely - clear it first;
  - tap the card's explicit "Open terminal on ..." affordance, and require the
    terminal's own markers (the session state line / the cols x rows readout)
    before typing anything;
  - retry, with a force-stop + relaunch as the last resort.
"""
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


def clear_search(adb):
    """Empty the host list's search box: tap it, then delete every character a
    previous mis-aimed typing left there."""
    els = adb_dump(adb)
    field = None
    for el in els:
        desc = el.attrs.get("content-desc") or ""
        if desc.startswith("Search"):
            field = el
            break
    if not field:
        return
    adb.tap(*el_center(field))
    time.sleep(1)
    for _ in range(40):
        adb.keyevent("KEYCODE_DEL")
    adb.keyevent("KEYCODE_BACK")  # put the keyboard away; the text is gone
    time.sleep(1.2)


def adb_dump(adb):
    """A dump without the driver's out-dir bookkeeping."""
    return adb.ui_dump() or []


def el_center(el):
    b = el.bounds  # (center_x, center_y, w, h)
    return b[0], b[1]


def ensure_card(d, adb):
    for _ in range(6):
        els, joined = screen_joined(d)
        card = d.find(els, LABEL_LOCAL_CARD)
        if card:
            return card
        if "Search hosts" in joined and "Ubuntu 26.04 LTS" not in joined:
            clear_search(adb)
            continue
        if d.find(els, "Hosts", exact=True):
            d.tap_last(els, "Hosts", exact=True)
        else:
            adb.back()
        time.sleep(2.5)
    return None


def terminal_open(d):
    els, joined = screen_joined(d)
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
        log("terminal did not open on attempt %d; recovering" % attempt)
        adb.back()
        time.sleep(1.5)
    return False


def type_cmd(adb, cmd, wait, d, shot):
    time.sleep(1.6)
    adb.shell("input text '%s'" % cmd.replace(" ", "%s"), timeout=30)
    time.sleep(1)
    adb.keyevent("KEYCODE_ENTER")
    time.sleep(wait)
    path = d.screenshot(shot)
    log("%s -> %s" % (cmd, path))


def main():
    d = E2eDriver(
        Adb(), PACKAGE, PACKAGE + ".test", "out/oc-probe3d", "SMOKE", 10,
        launch_activity="dev.eclipse.ssh.MainActivity",
    )
    adb = d.adb
    if not open_terminal(d, adb):
        raise RuntimeError("the terminal never opened")
    type_cmd(adb, "which npm; npm --version", 5, d, "p3d-npm")
    type_cmd(adb, "npm install -g opencode-ai 2>&1 | tail -n 6; echo EXIT:$?", 90, d, "p3d-npminstall")
    type_cmd(adb, "opencode --version; echo EXIT:$?", 12, d, "p3d-opencode")
    log("PROBE_DONE")


if __name__ == "__main__":
    main()
