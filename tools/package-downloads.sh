#!/usr/bin/env bash
# Puts the Herdr OS build on the website: the app, the Magisk module, Magisk itself and the bridge
# source, with SHA256SUMS and a manifest.json the Chrome installer verifies before using a file.
# Local builds are preview builds signed with this machine's key; a signed release comes from
# tools/release/build-release.sh with the stable keystore.
# Usage: [HERDR_VERSION=0.1.0] tools/package-downloads.sh [--no-build]
set -euo pipefail
source "$(dirname "$0")/lib.sh"

DL="$ROOT/site/public/downloads"
[ "${1:-}" = --no-build ] || "$ROOT/tools/build.sh"
MAGISK=$(find "$ROOT/firmware" -maxdepth 1 -name 'Magisk-*.apk' | sort | tail -1)
[ -n "$MAGISK" ] || die "No Magisk APK in firmware/ (github.com/topjohnwu/Magisk/releases)"

rm -rf "$DL" && mkdir -p "$DL"
cp "$OUT/r1ptt.apk" "$DL/herdr-os.apk"
cp "$OUT/r1ptt-system.zip" "$DL/herdr-os-module.zip"
cp "$MAGISK" "$DL/$(basename "$MAGISK")"
tar -C "$ROOT" -czf "$DL/herdr-bridge.tar.gz" --exclude 'node_modules' --exclude 'data' --exclude '*.db' bridge/package.json bridge/src bridge/tests protocol docs/HERDR.md

VERSION="${HERDR_VERSION:-preview-$(date -u +%Y%m%d)}"
(cd "$DL" && shasum -a 256 *.apk *.zip *.tar.gz > SHA256SUMS)
node - "$DL" "$VERSION" "$(basename "$MAGISK")" <<'JS'
const fs = require("fs"), path = require("path");
const [dir, version, magisk] = process.argv.slice(2);
const what = {
  "herdr-os.apk": "The Herdr OS app for the R1 (preview build)",
  "herdr-os-module.zip": "Magisk module: wheel and button mapping, power defaults, no Android lock screen, Herdr boot animation",
  [magisk]: "Magisk, to root the R1 (GPLv3, github.com/topjohnwu/Magisk)",
  "herdr-bridge.tar.gz": "The bridge for your computer (Node 25, no dependencies)",
};
const sums = Object.fromEntries(fs.readFileSync(path.join(dir, "SHA256SUMS"), "utf8").trim().split("\n").map((l) => l.split(/\s+/).reverse()));
const files = Object.keys(what).map((name) => ({ name, url: `/downloads/${name}`, sha256: sums[name], bytes: fs.statSync(path.join(dir, name)).size, what: what[name] }));
fs.writeFileSync(path.join(dir, "manifest.json"), JSON.stringify({ version, built: new Date().toISOString(), files }, null, 2) + "\n");
JS
ls -la "$DL"
