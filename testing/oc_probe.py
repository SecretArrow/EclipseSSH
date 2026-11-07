#!/usr/bin/env python3
"""Probe: where does the opencode checkbox actually live? Dumps the Ubuntu
window's texts, taps Install, then dumps the confirmation dialog's texts."""
import sys
import time

sys.path.insert(0, "testing/ubuntu-e2e")
sys.path.insert(0, "testing/universal")

from adbutil import Adb  # noqa: E402
from driver import BUTTON_INSTALL, E2eDriver  # noqa: E402

PACKAGE = "dev.eclipse.ssh.debug"


def texts(d, tag):
    els = d.dump()
    out = []
    for el in els:
        t = el.attrs.get("text", "")
        c = el.attrs.get("content-desc", "")
        ck = el.attrs.get("checked", "")
        if t or c:
            out.append("text=%r desc=%r checked=%s bounds=%s" % (t[:60], c[:40], ck, el.bounds))
    print("=== %s (%d nodes) ===" % (tag, len(out)), flush=True)
    for line in out:
        print("  " + line, flush=True)


def main():
    d = E2eDriver(
        Adb(), PACKAGE, PACKAGE + ".test", "out/oc-probe", "SMOKE", 10,
        launch_activity="dev.eclipse.ssh.MainActivity",
    )
    d.launch()
    d.open_settings()
    d.require_linux_section()
    d.open_ubuntu_window()
    texts(d, "WINDOW before Install")
    if not d.scroll_to_action_button():
        print("Install button NOT found", flush=True)
    if d.tap_last(d.dump(), BUTTON_INSTALL, exact=True):
        time.sleep(1.5)
        texts(d, "CONFIRM DIALOG")
    else:
        print("Install tap failed", flush=True)


if __name__ == "__main__":
    main()
