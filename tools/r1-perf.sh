#!/usr/bin/env bash
# Frame timings for one scripted pass through Herdr OS on a USB-connected R1: the pasture, a flock,
# bringing a sheep forward, an agent, back, the shade down and up, a thread. Prints the frame
# percentiles Android records (dumpsys gfxinfo), so a change is judged on the same path each time.
# Usage: tools/r1-perf.sh [runs] [--warm]   (R1 awake, paired, on the pasture)
# Cold (default) force-stops the app before each pass; --warm keeps it running, as in daily use,
# where Herdr OS is the launcher and stays alive.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
command -v adb >/dev/null || export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
RUNS=${1:-3}
WARM=0; [ "${2:-}" = --warm ] && WARM=1
PKG=dev.r1ptt

tap() {  # tap the first on-screen element whose text or description starts with $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local b
  b=$(adb shell cat /sdcard/ui.xml | python3 -c '
import sys,re,html
x=sys.stdin.read(); want=sys.argv[1]
for m in re.finditer(r"<node[^>]*?text=\"([^\"]*)\"[^>]*?content-desc=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",x):
  if html.unescape(m[1] or m[2]).startswith(want):
    print((int(m[3])+int(m[5]))//2,(int(m[4])+int(m[6]))//2); break' "$1")
  [ -n "$b" ] && adb shell input tap $b && sleep "${2:-1.2}"
}

adb shell input keyevent 224
for r in $(seq "$RUNS"); do
  if [ "$WARM" = 0 ] || [ "$r" = 1 ]; then
    adb shell am force-stop $PKG
    adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 6
    if [ "$WARM" = 1 ]; then tap "cerebro,"; tap "Claude Code on cerebro." 2; adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1; fi
  fi
  adb shell dumpsys gfxinfo $PKG reset >/dev/null
  [ "$WARM" = 1 ] && { adb shell input keyevent 4; sleep 1; }
  tap "cerebro,"
  tap "Claude Code on cerebro,"
  tap "Claude Code on cerebro." 2
  adb shell input keyevent 4; sleep 1.2
  adb shell input swipe 240 60 240 520 300; sleep 1.2
  adb shell input swipe 240 400 240 60 250; sleep 1.2
  tap "Claude Code on cerebro." 2
  tap "Message" 2
  adb shell input keyevent 4; sleep 1
  adb shell input keyevent 4; sleep 1
  adb shell dumpsys gfxinfo $PKG | awk -v r="$r" '
    /Total frames rendered/ {f=$4} /Janky frames:/ {j=$3" "$4} /50th percentile/ {p50=$3} /90th percentile/ {p90=$3}
    /95th percentile/ {p95=$3} /99th percentile/ {p99=$3} /Number Slow UI thread/ {ui=$5}
    END {printf "run %s: frames %s  janky %s  p50 %s  p90 %s  p95 %s  p99 %s  slow-ui %s\n", r, f, j, p50, p90, p95, p99, ui}'
done
