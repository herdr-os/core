#!/usr/bin/env bash
# Puts the design mocks from design-reference/preview/ on the R1 at native size, to judge legibility
# and touch targets in the hand. Builds and installs the app, pushes the PNGs, opens the viewer.
# The wheel or a tap flips between screens.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

ADB="${ADB:-adb}"
"$ADB" start-server >/dev/null 2>&1
"$ADB" wait-for-device

"$(dirname "$0")/build.sh" >/dev/null
"$ADB" install -r "$OUT/r1ptt.apk" >/dev/null

"$ADB" root >/dev/null; "$ADB" wait-for-device
dest=/sdcard/Android/data/dev.r1ptt/files/preview
"$ADB" shell "rm -rf $dest && mkdir -p $dest"
"$ADB" push "$ROOT"/design-reference/preview/*.png "$dest/" >/dev/null
# Pushed as root, the files get root's owner and a generic SELinux label the app can't read.
"$ADB" shell 'd=/data/media/0/Android/data/dev.r1ptt/files; chown -R $(stat -c %u:%g $d) $d/preview; chcon -R $(ls -dZ $d | cut -d" " -f1) $d/preview'

"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell am start -n dev.r1ptt/.herdr.ui.DesignPreviewActivity >/dev/null
say "Showing $(ls "$ROOT"/design-reference/preview/*.png | wc -l | tr -d ' ') screens on the R1. Turn the wheel to flip."
