# Automatic TorBox library and passive release matching

The current shared torrent is NuvioPreviews-comet-1085-20261027-v1, hash 82681a29c5ff6849dd168594acde29eaeb890c69. It contains 1,085 movies, 807,100 thumbnails and 32,799 sheets. The batch manifest selects this library; older torrents already in an account are not deleted.

The preview switch starts account setup from settings. Opening a movie with previews enabled also checks setup, covering existing enabled installations and later TorBox connections. Setup is serialized and deduplicated per account digest, with a ten-minute success cache and one-minute retry delay. The existing shared request gate and Retry-After apply. Discovery scans the account before adding; incomplete discovery does not add a possible duplicate. Only the published library is added. If not cached, normal TorBox server-side retrieval can occur; the TV downloads metadata and current-movie sheets, not the entire torrent. Pending availability is polled with bounded backoff while other providers remain usable.

A streaming JSON reader handles the large torrent file listing, retaining the requested IMDb movie's paths and catalog. The API response is capped at 32 MiB. Other providers and manual Preview Sync remain available.

## Immediate display, background matching

Runtime and movie identity select an approximate initial candidate, which is emitted and prefetched immediately after its index loads. Fingerprint metadata, playback captures and color calibration never gate the first preview. Cold network/cache misses still require fetching a sheet.

Different sources load the packaged center-dhash128-v1 fingerprints in the background. Their frame indices, timestamps, duration and movie identity must match the selected index. Sparse 320 by 180 PixelCopy captures observe normal playback; no extra decoder, video download or player seek is used. At most 360 captures over five minutes are attempted, spaced at least 500 ms, and none while paused, seeking or buffering.

Hash processing reproduces PIL grayscale weights, center crop and separable Lanczos3. Ambiguous best matches, black/flat frames, duplicate scenes and inconsistent timing are rejected. Three distinct scenes spanning at least 18 seconds must agree within 650 ms, with mean Hamming distance at most eight bits. A 24/25 or 25/24 speed hypothesis additionally needs five scenes across two minutes; runtime alone never applies a correction. A correction changes thumbnail lookup and cue times, never playback position. Manual Preview Sync takes precedence.

Verification is provisional for the sampled scenes. Alternate edits, inserted scenes, protected surfaces or insufficient observations may leave the initial approximate timing. Another release never becomes an exact grid-aligned source merely because its fingerprints match. The correction is session-scoped; cached sprites remain unchanged.

## Validation

JVM tests cover Pillow golden vectors, offset and frame-rate evidence, ambiguous/dark/repeated scene rejection, cuts, seek discontinuities, cue-boundary rounding, duplicate-free setup, concurrent callers, account isolation, cancellation, TTLs and Retry-After. Sixty stored fingerprints sampled across twenty real movie packs were reproduced bit for bit locally.

Device checks: enable previews with an existing account, enable before connecting TorBox, start a movie on an already-enabled installation, disable during setup, reopen supported movies and confirm no duplicate torrent. Hold and reverse the remote while background matching runs; thumbnails must remain visible. Compare a shifted release and a speed-changed release against playback, and verify manual Preview Sync overrides automatic corrections.
