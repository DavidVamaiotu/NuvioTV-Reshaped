# Detached Arc preview runtime

## Boundary and ownership

Arc's generation scheduler and policies are ported at `447d8833f7914ec6f9a13fdfb0436ad99daeee69`. They live inside the existing seek-preview feature. `ArcPreviewAdapter` is the boundary for Nuvio's controller, Stream model, resolved-link cache, buffer budget and network counters. The runtime accepts `PreviewPlayer`, `PlaybackBufferReserve`, immutable `ThumbSource` values and `CopySources`; it imports no Nuvio player, repository, settings model or networking class.

The existing Generate previews switch activates the runtime. Seekr and its manual sync remain independent. MPV retains Seekr; generated previews still attach to ExoPlayer's existing extractor hook. Segmented sources keep playback capture and server images rather than independent range extraction. The existing tap forwards playback bytes unchanged and feeds one active generator; it does not run a second video decoder beside the Arc runtime.

Sessions stop on source replacement or disabling. Session ownership and player/load-control identity checks prevent obsolete requests from starting another session or borrowing a rebuilt player's buffers. Providers hold weak controller references; closing a player detaches the active sink; any observation retained for bounded after-playback work uses weak references. App backgrounding suspends active-player generation; after-playback continuation retains Arc's bounded policy. A fresh foreground player stops older after-playback work before starting its own session.

## Arc behavior retained

- Latest scrub target first, then neighbors, followed by local and whole-title sparse-to-fine coverage. Known keyframe landings avoid repeated seeks to the same frame.
- Adaptive fetch and decode lanes, independent pacing, warm decoder reuse and idle/pressure trimming. Parallel fetching is bounded by phase, device class, rate-limit history and the feature's connection ledger.
- Native keyframe buffers use standalone allocations from the already-bundled allocator API. They never allocate through the playing stream's allocator. Container readers and hardened cached-index parsing remain shared with playback capture.
- Live memory, swap and 32-bit address-space checks; small-device 4K pacing reacts to observed drops and stalls. Small-device 4K remux generation waits for pause or player close.
- A temporary read-ahead reserve can fund one decode. `PreviewLoadControl` decorates the existing load control; every method delegates except the leased loading hold. It honors the original startup/rebuffer thresholds, cancels on attributed stalls and expires unless renewed. Unknown stock byte budgets disable reservation; preparation can still yield only after its startup buffer is ready. The existing load-control implementation and allocator stay in place.
- Optional skippable preparation, with Full/Faster/Fastest coverage. Preparation never resumes a user-paused player, a replaced source, or an app left in the background. Autoplayed next episodes are not held.
- Smaller-copy generation, timing/edition checks, visual verification, fitted image reuse, and cached-release borrowing. Discovery can begin before the requested original target has been cached. Ready HTTP file links and already-resolved app debrid links are provided through `CopySources`. No new torrent/account jobs are created for previews.
- Next-episode preparation consumes the source search Nuvio already preloads; it does not launch another addon search. Strong devices may prepare 4K; small devices retain Arc's 1080p restriction for this side work. Unfinished original 4K coverage may continue for at most ten minutes after closing the player; media-server streams never do.

## Reshaped improvements and retained providers

Decoded frame caches are charged by bitmap allocation bytes, including unusual aspect ratios. Cache parsing bounds dimensions, image/index lengths and entry counts. File-cache identity includes a hash of the initial bytes as well as content identity and length, distinguishing equal-length releases with different initial bytes while surviving URL rotation. Known file-size-only caches are not provided by the adapter, so generation verifies the initial bytes before opening a persisted image store.

The shared preview HTTP pool uses explicit bounded redirects, strips origin credentials on foreign origins (including cached CDN destinations), rejects HTTPS downgrades and validates Content-Range and stable lengths. Expired CDN links retry through the original address. Cancellation stops in-flight reads without poisoning subsequent requests.

Seekr continues bounded sheet caching and tile decoding. Server-owned Jellyfin/Silo trickplay and Emby BIF images are tried before starting native generation. The server provider warms the center and four neighboring tiles, keeps exact cue intervals even below ten seconds, and persists them independently. Time lookups use a sorted index rather than scanning all saved frames. The filmstrip applies detected black-bar crops through source rectangles, without allocating cropped bitmaps.

## Deliberate integration limits

Arc's account-managed TorBox/Premiumize copy creation/removal and its newer media-server account/session repository are not imported into this older Tester base. The resolver boundary accepts ready links and existing resolved-cache results instead. Server metadata uses the exact item/media-source URL available in this branch. Unsupported or unavailable server previews fall back to independent generation for single files. This preserves generation, verification, playback protections, preparation and seeking behavior without adding account-management dependencies.

## Verification

The feature CI workflow compiles the Android integration, runs preview tests and builds all native ABIs. Host checks compile the actual engine against the bundled Media3 AARs, run the inherited Arc policy tests plus Reshaped transport/cache/tap tests, and run `scripts/test_seek_preview_native.py`. Host boundary stubs are only an additional syntax check; they do not replace an Android app build.

Real-device checks remain necessary for sustained 1080p/4K HDR/DV playback on 2 GB TVs, scrubbing under slow/rate-limited networks, source changes, pause/resume, low storage and long sessions. No physical-TV performance or crash-rate claim is implied by source and host tests.
