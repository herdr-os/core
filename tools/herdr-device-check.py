#!/usr/bin/env python3
"""End-to-end Herdr OS check on a real, provisioned R1 over USB.

Starts a fixture bridge on this computer, connects the R1 to it with `adb reverse`, pairs through
the R1's own screens, and drives every feature with real input: taps, and the R1's wheel
(/dev/input/event2) and side button (/dev/input/event0) through `sendevent`. Each check reads the
screen with uiautomator (Herdr screens are FLAG_SECURE, so no screenshots) and, for actions, the
bridge's audit log. It ends by revoking the R1, which leaves it unpaired.

usage: tools/herdr-device-check.py        (R1 on USB, provisioned, userdebug for `adb root`)
"""
import json
import os
import html
import re
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = shutil.which("adb") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PORT = 8787
WHEEL, BUTTON = "/dev/input/event2", "/dev/input/event0"
results = []


def adb(*args, check=False):
    try:
        return subprocess.run([ADB, *args], capture_output=True, text=True, check=check, timeout=60).stdout
    except subprocess.TimeoutExpired:
        return ""


def screen():
    """(text, bounds) for every labelled node on screen."""
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = adb("shell", "cat", "/sdcard/ui.xml")
        nodes = [(html.unescape(m[1] or m[2]), tuple(map(int, m.group(3, 4, 5, 6))))
                 for m in re.finditer(r'<node[^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
                 if m[1] or m[2]]
        if nodes:
            return nodes
        adb("shell", "input", "keyevent", "224")
        time.sleep(1)
    return []


def text():
    return "\n".join(t for t, _ in screen())


def tap(label, wait=1.5):
    nodes = screen()
    for t, (x1, y1, x2, y2) in [n for n in nodes if n[0] == label] + [n for n in nodes if n[0].startswith(label)]:
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + min(y2, 639)) // 2))
        time.sleep(wait)
        return True
    return False


def tap_scrolling(label, wait=1.5, tries=5):
    """Taps [label], swiping the page up until it is on screen."""
    for _ in range(tries):
        if tap(label, wait):
            return True
        adb("shell", "input", "swipe", "240", "520", "240", "160", "300")
        time.sleep(1)
    return False


def type_text(s):
    adb("shell", "input", "text", s.replace(" ", "%s"))


def key(device, code, hold=0.05):
    adb("shell", f"sendevent {device} 1 {code} 1; sendevent {device} 0 0 0; sleep {hold}; sendevent {device} 1 {code} 0; sendevent {device} 0 0 0")


def wheel(down=True, times=1):
    for _ in range(times):
        key(WHEEL, 108 if down else 103)
    time.sleep(1.2)


def front():
    """The sheep standing in front of the pasture: its name is the one exact harness label on screen."""
    return next((t for t, _ in screen() if t in ("Claude Code", "Codex", "Pi")), "")


def top():
    out = adb("shell", "dumpsys activity activities | grep -m1 topResumedActivity")
    return out.split()[-2] if out.split() else ""


def until(what, cond, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(1)
    return False


def check(name, ok, detail=""):
    results.append((name, ok))
    print(f"{'PASS' if ok else 'FAIL'}  {name}{('  (' + detail + ')') if detail and not ok else ''}", flush=True)
    if not ok:
        print(f"      on screen [{top().split('/')[-1]}]: " + " | ".join(text().splitlines()[:6]), flush=True)


def home():
    adb("shell", "input", "keyevent", "224")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    time.sleep(2)


class Bridge:
    def __init__(self):
        self.data = tempfile.mkdtemp(prefix="herdr-device-check-")
        self.proc = None

    def cli(self, *args):
        return subprocess.run(["node", "src/main.ts", *args, "--fixtures", "--data", self.data],
                              cwd=os.path.join(ROOT, "bridge"), capture_output=True, text=True).stdout

    def start(self):
        self.proc = subprocess.Popen(["node", "src/main.ts", "serve", "--fixtures", "--port", str(PORT), "--data", self.data],
                                     cwd=os.path.join(ROOT, "bridge"), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in self.proc.stdout:
            if "listening on" in line:
                return
        sys.exit("bridge did not start")

    def stop(self):
        if self.proc:
            self.proc.send_signal(signal.SIGTERM)
            self.proc.wait(10)
            self.proc = None

    def audit(self):
        with sqlite3.connect(os.path.join(self.data, "bridge-fixtures.db")) as db:
            return db.execute("SELECT action, outcome FROM audit ORDER BY rowid").fetchall()

    def repo(self, name):
        return os.path.join(self.data, "demo-repos", name)


def main():
    # Start adb's server with its output discarded: a server started by a captured call inherits the
    # pipe and keeps it open, and that call never returns.
    subprocess.run([ADB, "start-server"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if not adb("devices").strip().split("\n")[1:]:
        sys.exit("No R1 on adb.")
    adb("root"); time.sleep(3); adb("wait-for-device")
    adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    adb("shell", "svc", "power", "stayon", "true")
    bridge = Bridge()
    bridge.start()
    try:
        run(bridge)
    finally:
        bridge.stop()
        adb("reverse", "--remove-all")
        adb("shell", "svc", "power", "stayon", "false")
        shutil.rmtree(bridge.data, ignore_errors=True)
    failed = [n for n, ok in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    sys.exit(1 if failed else 0)


def run(bridge):
    home()
    check("Herdr is the home screen", top() == "dev.r1ptt/.herdr.ui.HerdActivity", top())
    if "NOT PAIRED" not in text():
        tap("Menu", 1); tap("Settings", 2); tap_scrolling("Unpair", 1); tap("Unpair", 2); home()
    check("starts unpaired", "NOT PAIRED" in text())

    code = re.search(r"Enrollment code: (\S+)", bridge.cli("enroll"))[1]
    tap("Pair a host", 2); tap("Bridge URL", 1); type_text(f"http://127.0.0.1:{PORT}"); tap("One-time code", 1); type_text(code)
    adb("shell", "input", "keyevent", "4"); time.sleep(1.5); tap("Pair", 1)
    check("pairs by typed code and goes Live", until("live", lambda: "LIVE" in text() and "3 AGENTS" in text(), 20))
    adb("shell", "input", "keyevent", "4"); time.sleep(2)

    check("Back on the home screen keeps the pasture", top() == "dev.r1ptt/.herdr.ui.HerdActivity")
    check("the asking sheep stands in front with its question", front() == "Claude Code" and "Which migration strategy should I use?" in text())
    wheel(True); a = front(); wheel(True); b = front(); wheel(False); c = front()
    check("wheel brings one sheep forward per detent, both ways", (a, b, c) == ("Codex", "Pi", "Codex"), f"{a}, {b}, {c}")
    wheel(True, 9)
    check("a burst of 9 detents lands exactly 9 sheep on", front() == "Codex" and top().endswith("HerdActivity"), front())
    wheel(False)

    tap("Claude Code asks", 2); tap("Full replacement", 1.5)
    check("decision shows a confirmation naming the destination", "To Claude on MacBook Pro" in text())
    key(BUTTON, 116, 0.1); time.sleep(2); key(BUTTON, 116, 2); time.sleep(2)
    # robotOS's review fence: a side-button tap backs out of the confirmation; nothing is ever sent.
    check("side button tap and hold do not answer", "Got it." not in text() and ("decision", "accepted") not in bridge.audit())
    if "CONFIRM ANSWER" not in text():
        tap("Full replacement", 1.5)
    tap("Send answer", 4)
    check("touch answer is accepted by the host", "Got it." in text() and ("decision", "accepted") in bridge.audit())
    tap("Back to the herd", 2)

    tap("Menu", 1); tap("New task", 2); tap("e.g. Ask Codex", 1)
    type_text("Ask Codex on my MacBook to write regression tests for authentication")
    adb("shell", "input", "keyevent", "4"); time.sleep(1); tap("Review", 3)
    check("typed task resolves to the right agent", "To Codex on MacBook Pro" in text())
    tap("Send", 4)
    check("delegated task is accepted", "Task started" in text() and ("delegate", "accepted") in bridge.audit())

    tap("Open Codex", 3); tap("Terminal", 5)
    check("terminal shows live, sanitized output", "LIVE" in text() and "Writing regression tests" in text())
    bridge.stop()
    check("bridge down: terminal says Offline", until("offline", lambda: "OFFLINE" in text(), 15))
    bridge.start()
    check("bridge back: terminal returns to Live", until("live", lambda: "LIVE ·" in text(), 45))
    adb("shell", "input", "keyevent", "4"); time.sleep(2)

    tap("Changes", 4); tap("tests/auth.test.ts", 3)
    check("diff shows one hunk at a time", "HUNK 1 OF 1" in text())
    tap("Explain this hunk", 1.5); tap("Send", 4)
    check("explain a hunk is sent only after consent and accepted", ("explain", "accepted") in bridge.audit())
    adb("shell", "input", "keyevent", "4"); time.sleep(2)
    check("Back from a diff returns to the file list", "Codex · Changes" in text())
    tap("Approve this revision", 1.5)
    with open(os.path.join(bridge.repo("tests-qa"), "tests", "auth.test.ts"), "a") as f:
        f.write("// edited during review\n")
    tap("Approve", 4)
    check("approval of a changed revision is refused", "The code changed" in text() and ("review", "rejected") in bridge.audit())
    tap("Approve this revision", 1.5); tap("Approve", 4)
    check("approval of the new revision is accepted", ("review", "accepted") in bridge.audit())

    # A hold that isn't dictation for a Herdr screen becomes a Hermes turn, and robotOS brings its
    # Hermes home forward at hold start. That is the one unambiguous sign; `turn_start` is not.
    def hold_stays_in_herdr():
        adb("shell", f"sendevent {BUTTON} 1 116 1; sendevent {BUTTON} 0 0 0")
        time.sleep(1.2); during = top()
        adb("shell", f"sendevent {BUTTON} 1 116 0; sendevent {BUTTON} 0 0 0")
        time.sleep(2.5); after = top()
        return ".herdr.ui." in during and ".herdr.ui." in after, f"{during} / {after}"
    home(); tap("Claude Code asks", 2); adb("shell", "input", "keyevent", "4"); time.sleep(1)
    wheel(True); tap("Codex on MacBook Pro.", 3); tap("Files", 4)
    check("files browse the repository with Git status", "tests/" in text())
    tap("tests/", 3); tap("auth.test.ts", 4)
    check("a changed file opens straight to its diff", "auth.test.ts" in text() and "HUNK 1 OF" in text())
    home()
    check("side button hold on the deck stays in Herdr, not Hermes", *hold_stays_in_herdr())
    wheel(True); tap("Codex on MacBook Pro.", 3); adb("shell", "input", "keyevent", "4"); time.sleep(2)
    check("after leaving an agent screen, a hold still goes to Herdr", *hold_stays_in_herdr())
    home()

    # On USB power the R1 is in Desk mode: the stream must stay open with the screen off.
    lost = lambda: adb("shell", "cat /data/data/dev.r1ptt/files/events*.log").count("herdr stream lost")
    adb("shell", "svc", "power", "stayon", "false"); adb("shell", "input", "keyevent", "223"); time.sleep(8)
    before = lost(); bridge.stop(); time.sleep(8)
    check("on power, the herd stays connected with the screen off (Desk mode)", lost() > before)
    bridge.start(); adb("shell", "svc", "power", "stayon", "true"); home(); time.sleep(3)
    until("live again", lambda: "LIVE" in text(), 45)

    device = next(l.split()[0] for l in bridge.cli("devices").splitlines() if "REVOKED" not in l)
    tap("Menu", 1); tap("Activity", 3)
    bridge.cli("revoke", device)
    check("revocation closes host content and unpairs", until("revoked", lambda: "NOT PAIRED" in text(), 30))


if __name__ == "__main__":
    main()
