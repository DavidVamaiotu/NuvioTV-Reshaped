# TorBox seek-preview TV test

Experimental branch: feat/torbox-seek-preview. Uses the existing seek bar and
filmstrip UI. Stable, Beta and Tester source branches are not modified. This feature branch
publishes a signed Tester prerelease using the existing update channel.

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
sprite hashes, fetches one required sheet at a time, and region-decodes 320px
tiles. Compressed sheet cache is bounded to 2 MiB; decoded tile cache to 3 MiB,
apart from the current in-flight sheet/tile and images still referenced by UI.

Exact original torrent hash + file ID is preferred. Other releases require an
IMDb/TMDB identity match and a duration difference of at most two seconds, and
are marked approximate. Similar runtime is not proof of scene alignment.
Live TV, series, and unrecognized identities do not get guessed movie previews.
A longer audio tail is allowed for exact-file matches, with no invented frames
after the source video ends.

A TorBox track takes priority and suppresses on-device generation once loaded.
Unavailable files, decoding failures or rate limits drop back to the existing
preview providers. One isolated HTTP client keeps API authorization separate
from CDN requests. Calls are paced, 429 stops this track and shares the
Retry-After cooldown across later tracks, and expired CDN URLs get one refresh.

## Append

Publish a new immutable preview batch with prepare_batch.py, seed it, and verify
TorBox can serve its individual files. Then append its name and info_hash to
tools/torbox-preview/published-batches.json on this branch. Do not put tokens,
account-local torrent IDs or generated CDN URLs in that file.
Testers press Add preview library again; already-added batches are skipped.
The app reads that small list dynamically, so adding a batch does not require
another APK. Current limit is 32 published batches and eight discovered batches
per playback session. Discovery scans at most 2,000 account items.

## Validation

Pure Kotlin matching/timeline/path tests run in CI with the Android build.
Python upload/batch tests are under tools/torbox-preview. A standalone JVM smoke
test also exercised the pure matching and crop rules locally. Actual on-TV
rendering, account-specific links and cross-release alignment require a device
test; a successful APK build alone does not prove those behaviors.
