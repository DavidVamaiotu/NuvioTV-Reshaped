# Live TV test playlists (Nuvio Reshaped TV)

Free, legal test streams only: public test streams (Akamai, Unified Streaming, DASH-IF, Apple, Mux),
broadcasters' own free streams (DW, Al Jazeera English, Red Bull TV) and the Blender open films.
Catch-up replays play a sample film: these servers ignore the time in the link, so a replay
tests the app's catch-up flow (links, seeking, programme list, day labels), not the content.
Guide times are written for Romania time (Europe/Bucharest); titles say what you should see.

Add as M3U sources (Live TV > Sources > Add; the phone setup page saves typing):

| Source | Link |
|---|---|
| Main (add first) | `https://raw.githubusercontent.com/DavidVamaiotu/NuvioTV-Reshaped/refs/heads/claude/project-thread-5lm00k/livetv-test/main.m3u` |
| Second source | `…/livetv-test/second-source.m3u` |
| Broken things | `…/livetv-test/broken.m3u` |
| Header check (may crash) | `…/livetv-test/header-check.m3u` |

Guides are linked from the playlists (`url-tvg`), nothing to enter. They cover 8 days back to
10 days ahead of `generated.txt`; re-run `tools/generate.py livetv-test` and push for fresh ones.
Every push runs `.github/workflows/livetv-test-check.yml`, which opens every link from GitHub.

## What to check

**1 Live streams** – each plays; Plain HTTP (cleartext) plays; DASH Live Sim plays; zapping ▲▼ between them.

**2 Catch-up** – past programmes show the catch-up mark; OK on a past one replays it.
- Default 7 days (HLS film): reaches back 7 days (older days come from the saved archive); seek, Start over, programme list with day labels, midnight entries "Midnight · …".
- Default 3 days (MP4 film): replay of a progressive MP4 seeks.
- Append / Shift / tvg-rec: link goes to a live server that ignores the time, so the replay is the live stream; it must not crash or loop; it starts from the beginning of what the server gives.
- Flussonic: the replay link does not exist (404); expect a clean error, not a hang.
- 14 days: only 7 days are offered.
- Date template (TS film): replay of an HLS with TS segments.
- Real archive, last minutes: Unified Streaming keeps a short archive; a 5-minute block from the last few minutes should replay what aired then; older ones fail or play live.
- Relative template: the replay link is the live link plus `?archive=…`.
- DASH film: replay of a DASH film.

**3 Guide time formats** – every channel's programme now reads "Should be HH:MM–HH:MM" matching the clock.
"no offset (TV time)" is right only when the TV is set to Romania time. tvg-shift +2 and -1.5 are written
off by that much and must show right.

**4 Guide matching** – every row shows "RIGHT: …"; "WRONG" means the wrong guide entry won.
"No Guide At All" shows no programmes. "Logo From Guide" gets its logo from the guide.

**5 Guide shapes** – very short programmes (2/3/5 min) readable; 6-hour blocks; 2-hour gaps every 6 h;
"To Be Announced" umbrella hidden behind real shows (odd hours overlap by 10 min); no stop times
(each ends at the next start; last one 1 h); same show 3× in a row; rich details (picture, accents,
`<br/>` in the description); a guide that ends 3 h after generation (then "update failed"/no guide);
a channel whose guide starts 2 h after generation.

**6 Links and headers** – Kodi `|User-Agent=…`, `#EXTVLCOPT`, `#EXTHTTP` all play; `#EXTGRP` group;
comma and very long names; broken logo and no logo fall back cleanly; MP4, HLS, DASH films and an
Apple TS stream as channels.

**Second source** – header catch-up (2 days) applies to "Header Catch-up"; "Own Five Days" offers 5;
"Catch-up Disabled" offers none; header tvg-shift=1 corrects the guide ("Should be …"), "No Shift Here"
overrides it; "Same link as Akamai Test Live" has no guide of its own and borrows Akamai's.

**Broken things** – dead link, unknown host and web page fail cleanly; "##### SPORTS #####" and the
duplicate are not listed; relative link resolves (then 404s); an entry without #EXTINF shows as
"Channel 5"; one dead guide link plus one working guide (Second Guide Channel keeps its guide);
catch-up to a dead link fails cleanly. File has a byte order mark and Windows line ends.

**Header check** – one channel with a non-ASCII user agent ("Tést Ăgent"). Known open issue: this may
crash playback. Add it last and remove it afterwards.
