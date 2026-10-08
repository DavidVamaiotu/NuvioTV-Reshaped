#!/usr/bin/env python3
"""Checks every link of the test playlists from wherever this runs: python3 check.py check-urls.txt BASE_URL"""
import re, sys, urllib.request, urllib.error
from datetime import datetime, timedelta, timezone
from urllib.parse import urljoin

UA = {"User-Agent": "VLC/3.0.0 LibVLC/3.0.0"}

def get(url, limit=200_000):
    req = urllib.request.Request(url, headers=UA)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.headers.get("Content-Type", ""), r.read(limit)
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type", ""), b""
    except Exception as e:
        return "ERR " + type(e).__name__ + ": " + str(e)[:80], "", b""

def first_uri(text, base):
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            return urljoin(base, line)
        m = re.search(r'URI="([^"]+)"', line)
        if m and line.startswith("#EXT-X-MAP"):
            return urljoin(base, m.group(1))
    return None

def probe(url):
    status, ctype, body = get(url)
    note = ""
    text = body.decode("utf-8", "replace")
    if status == 200 and text.lstrip().startswith("#EXTM3U"):
        live = "#EXT-X-ENDLIST" not in text and "#EXT-X-STREAM-INF" not in text
        kind = "master" if "#EXT-X-STREAM-INF" in text else ("live media" if live else "VOD media")
        note = kind
        nxt = first_uri(text, url)
        if nxt and kind == "master":
            s2, _, b2 = get(nxt)
            t2 = b2.decode("utf-8", "replace")
            media_kind = "live" if "#EXT-X-ENDLIST" not in t2 else "VOD"
            note += f" -> variant {s2} {media_kind}"
            seg = first_uri(t2, nxt)
            if seg:
                s3, _, _ = get(seg, 2000)
                note += f" -> segment {s3}"
                pdt = re.search(r"#EXT-X-PROGRAM-DATE-TIME:(\S+)", t2)
                if pdt:
                    note += f" (first PDT {pdt.group(1)})"
        elif nxt:
            s3, _, _ = get(nxt, 2000)
            note += f" -> segment {s3}"
    elif status == 200 and "<MPD" in text[:2000]:
        note = "DASH " + ("live" if 'type="dynamic"' in text else "VOD")
    return status, ctype, note

urls = [l.strip() for l in open(sys.argv[1]) if l.strip()]
now = datetime.now(timezone.utc).replace(second=0, microsecond=0)
fmt = lambda t: t.strftime("%Y-%m-%dT%H:%M:%SZ")
fresh = []
for u in urls:
    if "vbegin=" in u:
        for back in (10, 60, 180, 600):
            a = now - timedelta(minutes=back)
            fresh.append(re.sub(r"vbegin=[^&]+&vend=[^&]+", f"vbegin={fmt(a)}&vend={fmt(a + timedelta(minutes=5))}", u))
    else:
        fresh.append(u)
if len(sys.argv) > 2:
    base = sys.argv[2]
    fresh = [base + n for n in ("main.m3u", "second-source.m3u", "broken.m3u", "guide.xml.gz", "guide-extra.xml", "guide-b.xml", "logos/cu-default.png")] + fresh

print("| status | link | type | notes |\n|---|---|---|---|")
for u in fresh:
    s, c, n = probe(u)
    print(f"| {s} | {u} | {c.split(';')[0]} | {n} |")
