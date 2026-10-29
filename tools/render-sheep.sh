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
  # 192 px: the largest sheep is about 150 px on the R1's panel, and every extra pixel is decoded
  # and uploaded to the GPU each time a sheep appears.
  rsvg-convert -w 192 -h 192 "$svg" -o "$dst/$name.png"
done
ls "$dst"
# Harness logos for the chips on the inbox, briefing and herd cards; one color, tinted where used.
# pi.svg is Pi's mark in its own colors; pi-mono.svg is the one-color version used here.
for svg in "$ROOT"/design-reference/harness-logos/*.svg; do
  logo=$(basename "$svg" .svg)
  [ "$logo" = pi ] && continue
  rsvg-convert -w 96 -h 96 "$svg" -o "$dst/logo_${logo%-mono}.png"
done
