#!/usr/bin/env python3
"""Builds Herdr OS's Android boot animation from the Paper "Run · Boot" screen
(design-reference/boot/boot.png, a 480x640 export): the orange bar sweeps its track while the R1
boots. Writes magisk/r1ptt-system/system/product/media/bootanimation.zip (stored, as Android needs)."""
import io, math, zipfile
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
base = Image.open(ROOT / "design-reference/boot/boot.png").convert("RGB")
TRACK = (180, 411, 300, 413)   # x0, y0, x1, y1 of the bar's track in the design
SEG, FRAMES, FPS = 54, 36, 30
track_rgb = base.getpixel((TRACK[2] - 2, TRACK[1]))
orange = base.getpixel((TRACK[0] + 2, TRACK[1]))

out = ROOT / "magisk/r1ptt-system/system/product/media/bootanimation.zip"
out.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
    z.writestr("desc.txt", f"{base.width} {base.height} {FPS}\np 0 0 part0\n")
    for i in range(FRAMES):
        frame = base.copy()
        d = ImageDraw.Draw(frame)
        # Clear the exported bar with its anti-aliased edges, then lay the track back down.
        d.rectangle((TRACK[0] - 2, TRACK[1] - 3, TRACK[2] + 2, TRACK[3] + 2), fill=base.getpixel((8, TRACK[1])))
        d.rectangle((TRACK[0], TRACK[1], TRACK[2] - 1, TRACK[3] - 1), fill=track_rgb)
        # Ease in and out across the track and back, like the app's splash.
        t = (1 - math.cos(2 * math.pi * i / FRAMES)) / 2
        x = TRACK[0] + round(t * (TRACK[2] - TRACK[0] - SEG))
        d.rectangle((x, TRACK[1], x + SEG - 1, TRACK[3] - 1), fill=orange)
        buf = io.BytesIO()
        frame.save(buf, "PNG", optimize=True)
        z.writestr(f"part0/{i:03d}.png", buf.getvalue())
print(out, out.stat().st_size // 1024, "KB")
