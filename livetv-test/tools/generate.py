#!/usr/bin/env python3
"""
Builds the Nuvio Reshaped Live TV test playlists, guides and logos.

    python3 generate.py OUTDIR [BASE_URL]

Guides cover up to 8 days back and 10 days ahead of the day this runs, so re-run it (and push)
when the guide runs out. Every stream is a public test stream or a broadcaster's own free
stream; catch-up replays play free sample films (the servers ignore the time in the link).
"""
import gzip
import os
import sys
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo
from xml.sax.saxutils import escape, quoteattr

OUT = sys.argv[1]
BASE = (sys.argv[2] if len(sys.argv) > 2 else
        "https://raw.githubusercontent.com/DavidVamaiotu/NuvioTV-Reshaped/refs/heads/claude/project-thread-5lm00k/livetv-test/")
LOGO = BASE + "logos/"
ROMANIA = ZoneInfo("Europe/Bucharest")
UTC = timezone.utc

now = datetime.now(UTC)
day0 = datetime(now.year, now.month, now.day, tzinfo=UTC)
PAST_DAYS, AHEAD_DAYS = 8, 10
WINDOW_START = day0 - timedelta(days=PAST_DAYS)
WINDOW_END = day0 + timedelta(days=AHEAD_DAYS)

# ---------------------------------------------------------------- streams
LIVE = {
    "trt": "https://tv-trtworld.medya.trt.com.tr/master.m3u8",
    "usp": "https://demo.unified-streaming.com/k8s/live/stable/live.isml/.m3u8",
    "usp_scte": "https://demo.unified-streaming.com/k8s/live/stable/scte35.isml/.m3u8",
    "usp_http": "http://demo.unified-streaming.com/k8s/live/stable/live.isml/.m3u8",
    "dash": "https://livesim2.dashif.org/livesim2/testpic_2s/Manifest.mpd",
    "dw": "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8",
    "dw2": "https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8",
    "redbull": "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8",
}
VOD = {
    "bbb_hls": "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
    "tos_hls": "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
    "bipbop_ts": "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8",
    "tos_mov": "https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov",
    "trailer_mp4": "https://download.blender.org/durian/trailer/sintel_trailer-720p.mp4",
    "bbb_dash": "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd",
}
DEAD_STREAM = BASE + "missing/no-such-stream.m3u8"

SHOWS = [
    ("Morning Report", "News and weather to start the day."),
    ("Nature Hour", "Forests, oceans and the animals that live in them."),
    ("City Kitchens", "Chefs cook one dish from their home town."),
    ("Track Day", "Motorsport highlights and interviews."),
    ("Night Cinema", "A feature film."),
    ("World Desk", "International news, live."),
    ("Studio Sessions", "Live music from the studio."),
    ("Science Now", "What researchers found this week."),
]


def ro(t):
    return t.astimezone(ROMANIA).strftime("%H:%M")


def ro_day(t):
    return t.astimezone(ROMANIA).strftime("%a %d %b %H:%M")


# ---------------------------------------------------------------- guide writing
class Guide:
    def __init__(self):
        self.parts = ['<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="nuvio-reshaped-test">\n']

    def channel(self, cid, names, icon=None):
        s = f'  <channel id={quoteattr(cid)}>\n'
        for n in names:
            s += f'    <display-name>{escape(n)}</display-name>\n'
        if icon:
            s += f'    <icon src={quoteattr(icon)}/>\n'
        s += '  </channel>\n'
        self.parts.append(s)

    def programme(self, cid, start, stop, title, desc=None, fmt=None, extra=""):
        fmt = fmt or fmt_offset
        stop_attr = f' stop="{fmt(stop)}"' if stop is not None else ""
        s = f'  <programme start="{fmt(start)}"{stop_attr} channel={quoteattr(cid)}>\n    <title lang="en">{escape(title)}</title>\n'
        if desc:
            s += f'    <desc lang="en">{escape(desc)}</desc>\n'
        s += extra
        s += '  </programme>\n'
        self.parts.append(s)

    def raw(self, text):
        self.parts.append(text)

    def text(self):
        return "".join(self.parts) + "</tv>\n"


def fmt_offset(t):  # 20261008170000 +0300 (Romania time with its offset)
    return t.astimezone(ROMANIA).strftime("%Y%m%d%H%M%S %z")


def fmt_utc0(t):
    return t.astimezone(UTC).strftime("%Y%m%d%H%M%S +0000")


def fmt_z(t):
    return t.astimezone(UTC).strftime("%Y%m%d%H%M%SZ")


def fmt_colon(t):
    s = t.astimezone(ROMANIA).strftime("%Y%m%d%H%M%S %z")
    return s[:-2] + ":" + s[-2:]


def fmt_nospace(t):
    return t.astimezone(ROMANIA).strftime("%Y%m%d%H%M%S%z")


def fmt_noseconds(t):
    return t.astimezone(ROMANIA).strftime("%Y%m%d%H%M %z")


def fmt_fraction(t):
    return t.astimezone(ROMANIA).strftime("%Y%m%d%H%M%S.000 %z")


def fmt_region(t):
    return t.astimezone(ROMANIA).strftime("%Y%m%d%H%M%S") + " Europe/Bucharest"


def fmt_local(t):  # no offset at all: the TV's own time zone
    return t.astimezone(ROMANIA).strftime("%Y%m%d%H%M%S")


def fmt_newyork(t):
    return t.astimezone(ZoneInfo("America/New_York")).strftime("%Y%m%d%H%M%S %z")


def slots(minutes, start=WINDOW_START, end=WINDOW_END):
    t = start
    while t < end:
        yield t, t + timedelta(minutes=minutes)
        t += timedelta(minutes=minutes)


def regular(g, cid, minutes=30, start=WINDOW_START, end=WINDOW_END, label="", fmt=None, shift=timedelta(0)):
    """A plain schedule; [shift] writes every time that much early (for tvg-shift tests)."""
    for i, (a, b) in enumerate(slots(minutes, start, end)):
        name, desc = SHOWS[(i + sum(map(ord, cid))) % len(SHOWS)]
        g.programme(cid, a - shift, b - shift, f"{label}{name}", f"{desc} Should run {ro(a)}–{ro(b)} Romania time.", fmt)


def timing(g, cid, fmt, shift=timedelta(0), past=timedelta(hours=6)):
    for a, b in slots(60, now.replace(minute=0, second=0, microsecond=0) - past, WINDOW_END):
        g.programme(cid, a - shift, b - shift, f"Should be {ro(a)}–{ro(b)}", f"If the guide shows {ro(a)}–{ro(b)} (Romania time), this time format works.", fmt)


main = Guide()
extra = Guide()
plain = Guide()   # playlist B's guide (not gzipped)

# Channel elements first in the main guide, as most guides write them.
def chan(g, cid, name, logo=None):
    g.channel(cid, [name], LOGO + logo if logo else None)


# ---- 1 Live streams (one day back)
for key, name in [("trt", "TRT World"), ("usp", "Unified Streaming Live"), ("usp_scte", "Unified Streaming Ads Live"),
                  ("usp_http", "Plain HTTP Live"), ("dash", "DASH Live Sim"), ("dw", "DW English"), ("dw2", "DW Second Feed"),
                  ("redbull", "Red Bull TV")]:
    chan(main, f"live.{key}", name)

# ---- 2 Catch-up
CATCHUP = [
    ("cu.default", "Catch-up · Default 7 days (HLS film)"),
    ("cu.mp4", "Catch-up · Default 3 days (MOV film)"),
    ("cu.append", "Catch-up · Append (live server)"),
    ("cu.shift", "Catch-up · Shift utc/lutc (live server)"),
    ("cu.flussonic", "Catch-up · Flussonic (should fail)"),
    ("cu.tvgrec", "Catch-up · tvg-rec 2 days"),
    ("cu.14days", "Catch-up · 14 days (app keeps 7)"),
    ("cu.formatted", "Catch-up · Date template (TS film)"),
    ("cu.dvr", "Catch-up · Real archive, last minutes"),
    ("cu.relative", "Catch-up · Relative template"),
    ("cu.dash", "Catch-up · DASH film"),
]
for cid, name in CATCHUP:
    chan(main, cid, name)

CATCHUP_PAST = {"cu.default": 8, "cu.mp4": 4, "cu.append": 3, "cu.shift": 2, "cu.flussonic": 2, "cu.tvgrec": 3,
                "cu.14days": 8, "cu.formatted": 8, "cu.relative": 2, "cu.dash": 3}
for cid, _ in CATCHUP:
    if cid == "cu.dvr":
        continue
    for i, (a, b) in enumerate(slots(30, day0 - timedelta(days=CATCHUP_PAST[cid]))):
        name, desc = SHOWS[i % len(SHOWS)]
        title = f"{name} · {ro_day(a)}"
        if a.astimezone(ROMANIA).hour == 0 and a.minute == 0:
            title = f"Midnight · {ro_day(a)}"
        main.programme(cid, a, b, title,
                       f"Aired {ro_day(a)}–{ro(b)}. Replays play a sample film." if i % 4 == 0 else None)
# Real archive test: Unified Streaming keeps about 2 hours, so any block that started within
# the last 2 hours replays for real (the stream's own clock shows the time); older ones fail.
for a, b in slots(15, day0 - timedelta(days=1)):
    main.programme("cu.dvr", a, b, f"Block {ro(a)}–{ro(b)}",
                   "Replays for real if it started less than about 2 hours ago; older blocks fail.")

# ---- 3 Guide time formats
TIMING = [
    ("tz.offset", "Time · +0300 offset", fmt_offset, 0),
    ("tz.utc", "Time · +0000", fmt_utc0, 0),
    ("tz.z", "Time · Z suffix", fmt_z, 0),
    ("tz.colon", "Time · +03:00 colon", fmt_colon, 0),
    ("tz.nospace", "Time · no space +0300", fmt_nospace, 0),
    ("tz.noseconds", "Time · no seconds", fmt_noseconds, 0),
    ("tz.fraction", "Time · .000 fraction", fmt_fraction, 0),
    ("tz.region", "Time · Europe/Bucharest name", fmt_region, 0),
    ("tz.local", "Time · no offset (TV time)", fmt_local, 0),
    ("tz.newyork", "Time · New York -0400", fmt_newyork, 0),
    ("tz.shift2", "Time · tvg-shift +2", fmt_utc0, 120),
    ("tz.shiftm15", "Time · tvg-shift -1.5", fmt_utc0, -90),
]
for cid, name, fmt, _ in TIMING:
    chan(main, cid, name)
for cid, name, fmt, shift_min in TIMING:
    timing(main, cid, fmt, shift=timedelta(minutes=shift_min))

# ---- 4 Guide matching
main.channel("m.byname", ["Matching Name"], None)
main.channel("m.alias", ["Alias Guide Name"], None)
main.channel("m.accents", ["Tele Unicode"], None)
main.channel("één.be", ["Een Belgium"], None)  # NFC; the playlist writes it NFD
main.channel("m.idwins", ["Id Wins Channel"], None)
main.channel("m.decoy", ["Matching Decoy"], None)
main.channel("m.plus", ["Plus Channel"], None)
main.channel("m.plus1", ["Plus Channel +1"], None)
main.channel("m.cn.de", ["DE: Country News"], None)
main.channel("m.cn.uk", ["UK: Country News"], None)
main.channel("casetest.id", ["Case Test"], None)
main.channel("m.logo", ["Logo From Guide"], LOGO + "guide-logo.png")
main.channel("m.empty.a", ["Empty Id Channel"], None)   # no programmes
main.channel("m.empty.b", ["Empty Id Channel"], None)   # has programmes

def labelled(g, cid, label, minutes=60, past=timedelta(days=1)):
    for a, b in slots(minutes, day0 - past, WINDOW_END):
        g.programme(cid, a, b, f"{label} {ro(a)}", None)

labelled(main, "m.byname", "RIGHT: name match ·")
labelled(main, "m.alias", "RIGHT: tvg-name match ·")
labelled(main, "m.accents", "RIGHT: accents match ·")
labelled(main, "één.be", "RIGHT: accented id ·")
labelled(main, "m.idwins", "RIGHT: id match ·")
labelled(main, "m.decoy", "WRONG: name decoy ·")
labelled(main, "m.plus", "WRONG: not the +1 ·")
labelled(main, "m.plus1", "RIGHT: +1 channel ·")
labelled(main, "m.cn.de", "WRONG: DE copy ·")
labelled(main, "m.cn.uk", "RIGHT: UK copy ·")
labelled(main, "casetest.id", "RIGHT: id in other case ·")
labelled(main, "m.logo", "Logo should come from the guide ·")
labelled(main, "m.empty.b", "RIGHT: entry with programmes ·")
labelled(main, "noelement.test", "RIGHT: programmes without <channel> ·")

# ---- 5 Guide shapes
for cid, name in [("s.short", "Shapes · very short programmes"), ("s.long", "Shapes · 6 hour programmes"),
                  ("s.gaps", "Shapes · gaps"), ("s.overlap", "Shapes · overlaps"), ("s.nostop", "Shapes · no stop times"),
                  ("s.repeat", "Shapes · same show repeated"), ("s.details", "Shapes · rich details"),
                  ("s.runsout", "Shapes · guide runs out in 3 h"), ("s.future", "Shapes · starts in 2 h")]:
    chan(main, cid, name, None)

# very short: 2, 3, 5, 20 minute pattern
SHAPES_END = day0 + timedelta(days=3)
t = now.replace(minute=0, second=0, microsecond=0) - timedelta(hours=6)
pattern = [2, 3, 5, 20]
i = 0
while t < now + timedelta(hours=18):
    m = pattern[i % len(pattern)]
    main.programme("s.short", t, t + timedelta(minutes=m), f"{m} min · {ro(t)}", f"A {m} minute programme.")
    t += timedelta(minutes=m)
    i += 1
regular(main, "s.short", minutes=30, start=t, end=SHAPES_END, label="After the short ones · ")
regular(main, "s.long", minutes=360, start=day0 - timedelta(days=1), label="6 h · ")
# gaps: 4 h of programmes, then 2 h nothing
for a, b in slots(30, day0 - timedelta(days=1), SHAPES_END):
    if (a.hour % 6) < 4:
        main.programme("s.gaps", a, b, f"Before a gap · {ro(a)}", "Nothing is listed for 2 hours every 6 hours.")
# overlaps: a 4 h "To Be Announced" umbrella plus normal programmes inside it
for a, b in slots(240, day0 - timedelta(days=1), SHAPES_END):
    main.programme("s.overlap", a, b, "To Be Announced", "An umbrella entry spanning the real programmes; the guide should hide it.")
    for c, d in slots(60, a, b):
        main.programme("s.overlap", c, d + (timedelta(minutes=10) if c.hour % 2 else timedelta(0)),
                       f"Real show · {ro(c)}", "Odd hours run 10 minutes into the next one.")
# no stop: every programme lacks stop; the last one too
for a, _ in slots(45, day0 - timedelta(days=1), SHAPES_END):
    main.programme("s.nostop", a, None, f"No stop · {ro(a)}", "Ends when the next starts.")
# repeats: the same show three times in a row, then another
for i, (a, b) in enumerate(slots(30, day0 - timedelta(days=1), SHAPES_END)):
    main.programme("s.repeat", a, b, "Back to Back Show" if (i // 3) % 2 == 0 else "Something Else", None)
# rich details
for i, (a, b) in enumerate(slots(60, day0 - timedelta(days=1), SHAPES_END)):
    main.raw(
        f'  <programme start="{fmt_offset(a)}" stop="{fmt_offset(b)}" channel="s.details">\n'
        f'    <title lang="en">Details &amp; Extras · {ro(a)}</title>\n'
        f'    <sub-title lang="en">Episode {i % 12 + 1}: "Quotes", &lt;angles&gt; &amp; ampersands</sub-title>\n'
        f'    <desc lang="en"><![CDATA[Line one of the description.<br/>Line two after a <br/> tag. '
        f'Accents: Ștefan, Brașov, Zürich, Ελληνικά. A long sentence to check that the header cuts the text '
        f'cleanly after two lines without breaking a word in a strange place or overflowing the box.]]></desc>\n'
        f'    <category lang="en">Documentary</category>\n'
        f'    <episode-num system="xmltv_ns">2.{i % 12}.</episode-num>\n'
        f'    <icon src="{LOGO}programme-{i % 2 + 1}.jpg"/>\n'
        f'    <rating system="MPAA"><value>PG</value></rating>\n'
        f'  </programme>\n')
regular(main, "s.runsout", start=day0 - timedelta(days=1), end=now + timedelta(hours=3), label="Ends soon · ")
regular(main, "s.future", start=now.replace(minute=0, second=0, microsecond=0) + timedelta(hours=2), label="Later · ")

# ---- 6 Headers and formats (one day of guide each)
HEADERS = ["h.pipe", "h.vlcopt", "h.exthttp", "h.extgrp", "h.comma", "h.long", "h.badlogo", "h.nologo", "h.mp4", "h.vod", "h.dashvod", "h.ts"]
for cid in HEADERS:
    chan(main, cid, cid)
    regular(main, cid, minutes=60, start=day0 - timedelta(days=1), end=day0 + timedelta(days=3))

for key in LIVE:
    regular(main, f"live.{key}", minutes=60, start=day0 - timedelta(days=1))

# ---------------------------------------------------------------- second guide (guide-extra.xml)
# Programmes before their <channel>, and a channel only this guide has.
labelled(extra, "x.interleaved", "RIGHT: channel listed after its programmes ·")
extra.channel("x.interleaved", ["Interleaved Channel"], None)
extra.channel("x.second", ["Second Guide Channel"], None)
labelled(extra, "x.second", "RIGHT: from the second guide ·")

# ---------------------------------------------------------------- playlist B's guide (written 1 h early for tvg-shift=1)
plain.channel("b.header", ["Header Catch-up"], None)
plain.channel("b.days5", ["Own Five Days"], None)
plain.channel("b.disabled", ["Catch-up Disabled"], None)
plain.channel("b.noshift", ["No Shift Here"], None)
for cid, past in [("b.header", 3), ("b.days5", 6), ("b.disabled", 1)]:
    for a, b in slots(30, day0 - timedelta(days=past)):
        plain.programme(cid, a - timedelta(hours=1), b - timedelta(hours=1), f"Should be {ro(a)} · {ro_day(a)}",
                        "Written an hour early; the playlist header's tvg-shift=1 puts it right.", fmt_utc0)
for a, b in slots(30, day0 - timedelta(days=1)):
    plain.programme("b.noshift", a, b, f"Should be {ro(a)}", "Own tvg-shift=0 overrides the header's 1.", fmt_utc0)

# ---------------------------------------------------------------- playlists
def entry(name, url, **attrs):
    a = " ".join(f'{k}="{v}"' for k, v in attrs.items() if v is not None)
    return f"#EXTINF:-1 {a},{name}\n{url}\n"


def logo(n):
    return LOGO + n + ".png"


A = [f'#EXTM3U url-tvg="{BASE}guide.xml.gz,{BASE}guide-extra.xml"\n']
G1, G2, G3, G4, G5, G6 = "1 Live streams", "2 Catch-up", "3 Guide time formats", "4 Guide matching", "5 Guide shapes", "6 Links and headers"
for key, name in [("trt", "TRT World"), ("usp", "Unified Streaming Live"), ("usp_scte", "Unified Streaming Ads Live"),
                  ("usp_http", "Plain HTTP Live"), ("dash", "DASH Live Sim"), ("dw", "DW English"), ("dw2", "DW Second Feed"),
                  ("redbull", "Red Bull TV")]:
    url = LIVE[key] if key == "trt" else LIVE[key] + f"#live-{key}"
    A.append(entry(name, url, **{"tvg-id": f"live.{key}", "tvg-logo": logo(f"live-{key}"), "group-title": G1}))

def cu(name, cid, url, **c):
    A.append(entry(name, url, **{"tvg-id": cid, "tvg-logo": logo(cid.replace(".", "-")), "group-title": G2}, **c))

cu("Catch-up · Default 7 days (HLS film)", "cu.default", LIVE["trt"] + "?nuvio=cu1",
   catchup="default", **{"catchup-days": "7", "catchup-source": VOD["bbb_hls"] + "?utc={utc}&end={utcend}&d={duration}"})
cu("Catch-up · Default 3 days (MOV film)", "cu.mp4", LIVE["usp"] + "?nuvio=cu2",
   catchup="default", **{"catchup-days": "3", "catchup-source": VOD["tos_mov"] + "?start=${start}&dur={duration:60}"})
cu("Catch-up · Append (live server)", "cu.append", LIVE["dw"] + "?nuvio=cu3",
   catchup="append", **{"catchup-days": "2", "catchup-source": "&utc={utc}&lutc={lutc}"})
cu("Catch-up · Shift utc/lutc (live server)", "cu.shift", LIVE["usp_scte"] + "?nuvio=cu4",
   catchup="shift", **{"catchup-days": "1"})
cu("Catch-up · Flussonic (should fail)", "cu.flussonic", LIVE["trt"] + "?nuvio=cu5",
   catchup="flussonic", **{"catchup-days": "1"})
cu("Catch-up · tvg-rec 2 days", "cu.tvgrec", LIVE["dw2"] + "?nuvio=cu6", **{"tvg-rec": "2"})
cu("Catch-up · 14 days (app keeps 7)", "cu.14days", LIVE["redbull"] + "?nuvio=cu7",
   catchup="default", **{"catchup-days": "14", "catchup-source": VOD["tos_hls"] + "?from={utc}&to={utcend}"})
cu("Catch-up · Date template (TS film)", "cu.formatted", LIVE["usp"] + "?nuvio=cu8",
   catchup="default", **{"catchup-days": "7", "catchup-source": VOD["bipbop_ts"] + "?day={Y}-{m}-{d}&at={H}{M}{S}&utcdate={utc:Y-m-dTH:M:SZ}&ago={offset:60}"})
cu("Catch-up · Real archive, last minutes", "cu.dvr", LIVE["usp"],
   catchup="append", **{"catchup-days": "1", "catchup-source": "?vbegin={utc:Y-m-dTH:M:SZ}&vend={utcend:Y-m-dTH:M:SZ}"})
cu("Catch-up · Relative template", "cu.relative", LIVE["usp_scte"],
   catchup="default", **{"catchup-days": "1", "catchup-source": "?archive={utc}-{duration}"})
cu("Catch-up · DASH film", "cu.dash", LIVE["dash"] + "?nuvio=cu11",
   catchup="default", **{"catchup-days": "2", "catchup-source": VOD["bbb_dash"] + "?utc={utc}"})

for n, (cid, name, _, shift_min) in enumerate(TIMING):
    attrs = {"tvg-id": cid, "group-title": G3}
    if shift_min:
        attrs["tvg-shift"] = {120: "+2", -90: "-1.5"}[shift_min]
    A.append(entry(name, LIVE["usp"] + f"#t{n}", **attrs))

M = [
    ("RO: Matching Name HD", {"tvg-logo": logo("m-byname")}),
    ("Some Shown Name", {"tvg-name": "Alias Guide Name"}),
    ("Télé Ünïcode", {}),
    ("Een (accented id)", {"tvg-id": "één.be"}),  # NFD form of één.be
    ("Matching Decoy", {"tvg-id": "m.idwins"}),
    ("Plus Channel +1", {}),
    ("UK: Country News", {}),
    ("Case Test", {"tvg-id": "CaseTest.ID"}),
    ("Logo From Guide", {"tvg-id": "m.logo"}),
    ("Empty Id Channel", {"tvg-id": ""}),
    ("No Channel Element", {"tvg-id": "noelement.test"}),
    ("Interleaved Channel", {"tvg-id": "x.interleaved"}),
    ("Second Guide Channel", {"tvg-id": "x.second"}),
    ("No Guide At All", {"tvg-id": "missing.none"}),
]
for n, (name, attrs) in enumerate(M):
    A.append(entry(name, LIVE["trt"] + f"#m{n}", **{**attrs, "group-title": G4}))

for n, (cid, name) in enumerate([("s.short", "Shapes · very short programmes"), ("s.long", "Shapes · 6 hour programmes"),
                                 ("s.gaps", "Shapes · gaps"), ("s.overlap", "Shapes · overlaps"), ("s.nostop", "Shapes · no stop times"),
                                 ("s.repeat", "Shapes · same show repeated"), ("s.details", "Shapes · rich details"),
                                 ("s.runsout", "Shapes · guide runs out in 3 h"), ("s.future", "Shapes · starts in 2 h")]):
    A.append(entry(name, LIVE["dw"] + f"#s{n}", **{"tvg-id": cid, "group-title": G5}))

A.append(entry("Kodi pipe headers", LIVE["dw2"] + "#h0|User-Agent=NuvioReshapedTest/1.0&Referer=https%3A%2F%2Fexample.com%2F",
               **{"tvg-id": "h.pipe", "group-title": G6}))
A.append('#EXTINF:-1 tvg-id="h.vlcopt" group-title="6 Links and headers",VLC options headers\n'
         '#EXTVLCOPT:http-user-agent=NuvioReshapedTest/1.0\n#EXTVLCOPT:http-referrer=https://example.com/\n'
         f'{LIVE["dw2"]}#h1\n')
A.append('#EXTINF:-1 tvg-id="h.exthttp" group-title="6 Links and headers",EXTHTTP headers\n'
         '#EXTHTTP:{"User-Agent":"Mozilla/5.0 (KHTML, like Gecko) NuvioTest","Origin":"https://example.com"}\n'
         f'{LIVE["dw2"]}#h2\n')
A.append('#EXTINF:-1 tvg-id="h.extgrp",Group from EXTGRP\n#EXTGRP:6 Links and headers\n' + LIVE["dw"] + "#h3\n")
A.append(entry("News, Weather & Sport (comma in name)", LIVE["dw"] + "#h4", **{"tvg-id": "h.comma", "group-title": G6}))
A.append(entry("A very long channel name that goes on and on to check how the guide column cuts it off", LIVE["dw"] + "#h5",
               **{"tvg-id": "h.long", "group-title": G6}))
A.append(entry("Broken logo link", LIVE["redbull"] + "#h6", **{"tvg-id": "h.badlogo", "tvg-logo": BASE + "logos/missing.png", "group-title": G6}))
A.append(entry("No logo", LIVE["redbull"] + "#h7", **{"tvg-id": "h.nologo", "group-title": G6}))
A.append(entry("MP4 trailer as a channel", VOD["trailer_mp4"], **{"tvg-id": "h.mp4", "tvg-logo": logo("h-mp4"), "group-title": G6}))
A.append(entry("HLS film as a channel", VOD["tos_hls"], **{"tvg-id": "h.vod", "tvg-logo": logo("h-vod"), "group-title": G6}))
A.append(entry("DASH film as a channel", VOD["bbb_dash"], **{"tvg-id": "h.dashvod", "tvg-logo": logo("h-dashvod"), "group-title": G6}))
A.append(entry("TS segments HLS (Apple)", VOD["bipbop_ts"], **{"tvg-id": "h.ts", "tvg-logo": logo("h-ts"), "group-title": G6}))

# ---- Playlist B: header catch-up and header tvg-shift; one channel shares a link with playlist A
B = [f'#EXTM3U x-tvg-url="{BASE}guide-b.xml" catchup="default" catchup-days="2" '
     f'catchup-source="{VOD["bbb_hls"]}?utc={{utc}}" tvg-shift="1"\n']
B.append(entry("Header Catch-up", LIVE["usp"] + "?nuvio=b1", **{"tvg-id": "b.header", "group-title": "B Second source"}))
B.append(entry("Own Five Days", LIVE["usp"] + "?nuvio=b2", **{"tvg-id": "b.days5", "catchup-days": "5", "group-title": "B Second source"}))
B.append(entry("Catch-up Disabled", LIVE["usp"] + "?nuvio=b3", **{"tvg-id": "b.disabled", "catchup": "disabled", "group-title": "B Second source"}))
B.append(entry("No Shift Here", LIVE["usp"] + "?nuvio=b4", **{"tvg-id": "b.noshift", "tvg-shift": "0", "catchup": "disabled", "group-title": "B Second source"}))
B.append(entry("Same link as TRT World", LIVE["trt"], **{"tvg-id": "shared.nolist", "tvg-shift": "0", "catchup": "disabled", "group-title": "B Second source"}))

# ---- Playlist C: things that should fail cleanly (BOM, CRLF line ends)
C = ['﻿#EXTM3U url-tvg="' + BASE + 'missing/no-such-guide.xml,' + BASE + 'guide-extra.xml"\n']
C.append(entry("Dead link (404)", DEAD_STREAM, **{"group-title": "C Broken"}))
C.append(entry("Unknown host", "https://nuvio-reshaped-test.invalid/live.m3u8", **{"group-title": "C Broken"}))
C.append(entry("Web page, not a stream", "https://example.com/", **{"group-title": "C Broken"}))
C.append(entry("##### SPORTS #####", "https://example.com/heading-should-be-hidden", **{"group-title": "C Broken"}))
C.append(entry("Duplicate of the dead link (hidden)", DEAD_STREAM, **{"group-title": "C Broken"}))
C.append(entry("Relative link", "streams/relative.m3u8", **{"group-title": "C Broken"}))
C.append(LIVE["redbull"] + "#noextinf\n")  # no #EXTINF: shown as "Channel N"
C.append(entry("Second Guide Channel (guide still loads)", LIVE["redbull"] + "#c1", **{"tvg-id": "x.second", "group-title": "C Broken"}))
C.append(entry("Catch-up to a dead link", LIVE["redbull"] + "?nuvio=c2",
               **{"tvg-id": "x.second", "catchup": "default", "catchup-days": "1", "catchup-source": DEAD_STREAM + "?utc={utc}", "group-title": "C Broken"}))

# ---- Playlist D: one channel with a non-ASCII header (a known open issue)
D = ['#EXTM3U\n',
     '#EXTINF:-1 group-title="D Header check",Non-ASCII user agent\n',
     '#EXTVLCOPT:http-user-agent=Nuvio Tést Ăgent\n',
     LIVE["redbull"] + "#d1\n"]

os.makedirs(OUT, exist_ok=True)
def write(name, text, newline="\n"):
    with open(os.path.join(OUT, name), "w", encoding="utf-8", newline=newline) as f:
        f.write(text)

write("main.m3u", "".join(A))
write("second-source.m3u", "".join(B))
write("broken.m3u", "".join(C), newline="\r\n")
write("header-check.m3u", "".join(D))
main_text = main.text()
with gzip.open(os.path.join(OUT, "guide.xml.gz"), "wb", compresslevel=9) as f:
    f.write(main_text.encode("utf-8"))
write("guide-extra.xml", extra.text())
write("guide-b.xml", plain.text())
with open(os.path.join(OUT, "generated.txt"), "w") as f:
    f.write(f"Generated {now.isoformat(timespec='minutes')}; guides cover {WINDOW_START.date()} to {WINDOW_END.date()} (UTC days).\n")
print("main guide", len(main_text) // 1024, "KB plain")
