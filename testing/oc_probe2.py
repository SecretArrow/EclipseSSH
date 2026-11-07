#!/usr/bin/env python3
"""Probe 2: read the app's own install log (the npm step's verdict) and check
the Hosts tab for the Local Ubuntu card."""
import sys
import time

sys.path.insert(0, "testing/ubuntu-e2e")
sys.path.insert(0, "testing/universal")

from adbutil import Adb  # noqa: E402
from driver import E2eDriver, LABEL_LOCAL_CARD  # noqa: E402

PACKAGE = "dev.eclipse.ssh.debug"


def main():
    d = E2eDriver(
        Adb(), PACKAGE, PACKAGE + ".test", "out/oc-probe2", "SMOKE", 10,
        launch_activity="dev.eclipse.ssh.MainActivity",
    )
    d.launch()
    d.open_settings()
    d.require_linux_section()
    d.open_ubuntu_window()
    print("=== install log (app's own record) ===", flush=True)
    try:
        d._read_install_log("installed")
    except Exception as exc:  # the method raises with the lines in the message
        print("install log read: %s" % exc, flush=True)
    time.sleep(1)
    d.adb.back()
    time.sleep(1)
    # Hosts tab: is the Local Ubuntu card there?
    els = d.dump()
    if not d.find(els, LABEL_LOCAL_CARD):
        d.tap_last(els, "Hosts", exact=True)
        time.sleep(2)
    els = d.dump()
    print("=== Hosts tab: card present? %s ===" % bool(d.find(els, LABEL_LOCAL_CARD)), flush=True)
    for el in els[:60]:
        t = el.attrs.get("text", "")
        c = el.attrs.get("content-desc", "")
        if t or c:
            print("  text=%r desc=%r" % (t[:70], c[:50]), flush=True)


if __name__ == "__main__":
    main()
