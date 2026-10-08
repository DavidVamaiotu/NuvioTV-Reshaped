#!/usr/bin/env python3
"""Small logos and programme pictures for the test playlist: python3 logos.py OUTDIR/logos"""
import os, re, sys
from PIL import Image, ImageDraw, ImageFont
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
FONT = "/usr/share/fonts/opentype/inter/Inter-SemiBold.otf"
logos = {
  "live-akamai": "AKAMAI", "live-usp": "USP", "live-usp_scte": "USP ADS", "live-usp_http": "HTTP", "live-dash": "DASH",
  "live-dw": "DW", "live-aje": "AJE", "live-redbull": "RED BULL",
  "cu-default": "CU 7D", "cu-mp4": "CU MP4", "cu-append": "APPEND", "cu-shift": "SHIFT", "cu-flussonic": "FLUSS",
  "cu-tvgrec": "TVG-REC", "cu-14days": "CU 14D", "cu-formatted": "DATES", "cu-dvr": "DVR", "cu-relative": "RELATIVE",
  "cu-dash": "CU DASH", "m-byname": "NAME", "guide-logo": "GUIDE", "h-mp4": "MP4", "h-vod": "HLS VOD",
  "h-dashvod": "DASH VOD", "h-ts": "TS",
}
def card(w, h, text, size):
    img = Image.new("RGB", (w, h), (24, 24, 28))
    d = ImageDraw.Draw(img)
    for y in range(h):  # soft top light, like glass
        c = int(24 + 22 * (1 - y / h))
        d.line([(0, y), (w, y)], fill=(c, c, c + 4))
    f = ImageFont.truetype(FONT, size)
    box = d.textbbox((0, 0), text, font=f)
    d.text(((w - box[2]) / 2, (h - box[3]) / 2 - box[1] / 2), text, font=f, fill=(240, 240, 245))
    return img
for name, text in logos.items():
    card(320, 180, text, 44 if len(text) < 7 else 34).save(os.path.join(out, name + ".png"), optimize=True)
for n in (1, 2):
    card(480, 270, f"PROGRAMME PICTURE {n}", 30).save(os.path.join(out, f"programme-{n}.jpg"), quality=80)
