# TorBox seek-preview TV test

Released from subtitle-autosync-tester; original work is preserved on feat/torbox-seek-preview. Uses the existing seek bar and
filmstrip UI. This uses the normal signed TV Tester release and update channel.

## Tester setup

1. Select the Tester update channel and install the new TorBox preview Tester release.
   The build checks package, signing certificate and increasing versionCode against
   the prior Tester/Beta APKs before publication. Manual APK installation also works.
2. Connect your own TorBox account in the existing Debrid settings.
3. Open Settings > Nuvio Reshaped > Add preview library.
4. The button reads published-batches.json, skips batches already in your account,
   and adds missing ones with add_only_if_cached=true and allow_zip=false.
5. Open one of the supported movies and seek with the remote.

No Share link or manual torrent import is required for this button path. It uses
the viewer's own key, never the publisher's key. The button does not initiate
uncached downloads. If a published batch is no longer cached, it reports that
some previews are unavailable. Existing transfers and packs are preserved.

The first library has ten movies and 7,465 previews in 304 WebP sheets.
Playback looks up the root catalog.json in completed NuvioPreviews-* torrents.
It resolves account-local torrent/file IDs, validates the selected index and
sprite hashes, and prefetches compressed sheets using two background workers.
The resume/current sheet is first; new seek positions are prioritized.
Requests for an in-flight sheet join the existing download. Unrelated prefetches
do not hold the tile-decoder lock or block foreground requests.

Compressed sheet RAM cache is bounded to 8 MiB, decoded tiles to 3 MiB, and the
shared least-recently-used disk cache to 128 MiB, apart from in-flight buffers
and images still referenced by the UI. Only requested 320px tiles are decoded.
Verified disk sheets are reused across playback sessions. Content hashes key
the cache; credentials and temporary CDN URLs are never saved there.
Prefetch is bounded to 16 MiB / 96 sheets per movie; larger packs remain
available on demand. All ten initial packs fit these budgets.
Workers and outstanding HTTP calls stop when the player releases the track.

Exact original torrent hash + file ID is preferred. Other releases require an
IMDb/TMDB identity match, with no runtime or file-index rejection for the same movie.
The closest runtime wins before revision when several other releases are available.
Their timestamps are scaled across playback's full duration, while exact files keep
their original timestamps and retain the last image through a longer audio tail.
All sprites display normally, without an approximate label or reduced image quality.
Cross-release timing confidence remains internal and does not force playback onto
unverified frame timestamps. Duration scaling cannot perfectly align different cuts.
Existing background visual matching can replace the duration estimate when it finds
confident scene matches; it never withholds the initial thumbnails.
Live TV, series, and unrecognized identities do not get guessed movie previews.
A longer audio tail uses the last available preview rather than inventing a new image.

A TorBox track takes priority and suppresses on-device generation once loaded.
Unavailable files, decoding failures or rate limits drop back to the existing
preview providers. One isolated HTTP client keeps API authorization separate
from CDN requests. Calls retain the shared pacing and Retry-After cooldown.
A background download failure stops that worker and preserves cached previews.
A foreground failure falls back to existing providers. Expired CDN URLs get one refresh.

## Append

Publisher scripts, including prepare_batch.py, remain on feat/torbox-seek-preview.
Publish a new immutable preview batch with that helper, seed it, and verify
TorBox can serve its individual files. Then append its name and info_hash to
tools/torbox-preview/published-batches.json on subtitle-autosync-tester. Do not put tokens,
account-local torrent IDs or generated CDN URLs in that file.
Testers press Add preview library again; already-added batches are skipped.
The app reads that small list dynamically, so adding a batch does not require
another APK. Current limit is 32 published batches and eight discovered batches
per playback session. Discovery scans at most 2,000 account items.

## Validation

Pure Kotlin cache, prefetch-order, matching/timeline/path tests run in CI with
the Android build. Cache tests cover cross-session reuse, bounded eviction,
corruption, cancellation and coalescing foreground/background downloads.
Python upload/batch tests are on the publisher feature branch. A standalone JVM smoke
test also exercised the pure matching and crop rules locally. Actual on-TV
rendering, account-specific links and cross-release alignment require a device
test; a successful APK build alone does not prove those behaviors.
