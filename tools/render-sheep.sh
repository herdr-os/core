#!/usr/bin/env bash
# Renders the herd's sheep from design-reference/sheep/*.svg into app drawables. The agent sheep
# carry their harness logo on a skewed face that Android vector drawables can't express, so they
# ship as PNGs. Needs rsvg-convert (brew install librsvg). Rerun after editing the SVGs.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

src="$ROOT/design-reference/sheep"
dst="$ROOT/app/src/main/res/drawable-nodpi"
mkdir -p "$dst"
for svg in "$src"/*.svg; do
  name=$(basename "$svg" .svg | tr '-' '_')
  case "$name" in sheep_*) ;; *) name="sheep_$name" ;; esac
  rsvg-convert -w 384 -h 384 "$svg" -o "$dst/$name.png"
done
ls "$dst"
# Harness logos for the chips on the inbox, briefing and herd cards; one color, tinted where used.
for logo in claude codex pi-mono opencode; do
  name=$(echo "logo_${logo%-mono}")
  rsvg-convert -w 96 -h 96 "$ROOT/design-reference/harness-logos/$logo.svg" -o "$dst/$name.png"
done
