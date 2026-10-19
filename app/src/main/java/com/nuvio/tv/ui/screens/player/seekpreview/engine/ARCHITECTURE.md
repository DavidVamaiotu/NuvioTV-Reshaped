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

### Preview latency and resource ownership

`PreviewScrubPlan`, `PreviewWorkBudget`, `PreviewFrameCache` and `PreviewFrameWriter` are
feature-owned helpers. They have no controller, repository, global settings, playback allocator,
or network implementation dependencies. The existing adapter forwards scrub direction and
immutable source size/duration hints; preview work never starts another source search.

The latest target is made before neighbors, with neighbors biased toward the scrub direction.
Missing frames during continuous movement have a 120 ms settling window; cached frames remain
immediate and repeated image refreshes cannot extend that window. A conflated wakeup interrupts
idle/pacing waits. On low-RAM devices, changing the target cancels an obsolete original-file range
read using the preview reader's existing cancellation epoch. Playback and smaller-copy readers
are separate. Superseded results do not consume failed-request attempts or start stale decodes.

Background ordering starts near playback, then covers both title edges and splits the remaining
gaps across the entire timeline. Whole-title minute coverage precedes fine local detail, on the
original and smaller-copy paths. A bounded second sweep revisits failed decodes. Exhausted frame
attempts can be retried by foreground seeks after a cooldown; skipped frames are never counted as
stored coverage. Five consecutive network failures cool down for a minute instead of permanently
disabling the session. Transport cancellation is identified separately from host failures.

The background request/byte limits still stop background filling. Foreground requests retain a
separate renewable allowance (250 requests per five minutes after the background cap). A copy
keeps its range reader available for later seek targets after background filling ends, closing its
decoder while idle. It follows the latest target and retains the same playback/memory gates and
edition/visual checks. Original gates re-plan when copy adoption satisfies the waiting slot.

Decoded images are published before WebP compression or disk writes. Each session's single writer
admits one outstanding image, including the in-progress write. Producer backpressure suspends the
preview worker, never playback or the UI. Cleanup drains that writer before flushing the store or
allowing a replacement session to take ownership. Publication versions prevent delayed writes
from overwriting newer images or adopted copies; completion markers wait for persistence.

The byte-bounded memory LRU stores each unique keyframe once and maps timeline aliases onto it.
Aliased disk images share their decoded bitmap too. Publication, durable metadata and image
decoding have separate synchronization, so slow flash never holds the UI lookup lock. Existing
disk formats and cache keys are retained. The writer can retain one additional output bitmap;
it does not retain another raw frame or native decoder.

The original/copy generators share a measured-work budget during playback. Decode/render and
original-image persistence time contribute to pacing; draining buffers and observed frame drops
reduce background work. Paused seeking bypasses background pacing, but memory, rate-limit and
buffering protections still apply. This is a duty-ratio heuristic, not a CPU percentage guarantee.
Existing 4K restrictions and the one-decoder/one-fetch/two-buffer low-RAM limits remain in force.
Low-RAM copies prefer 720p within the existing safe-source/SDR/codec ranking; newly rendered
images are bounded to 384 pixels per dimension on those devices, versus 480 elsewhere. This
reduces output conversion/encoding/cache cost, not the full-resolution video's decode charge.

### Low-swap HD recovery on small devices

The ordinary swap floors remain the default, including all 4K and next-episode work. The active
original/copy workers may opt into transient HD decoding on low-RAM devices when a cold decoder
still leaves the greater of the normal 200 MB RAM floor and Android's own low-memory threshold,
plus spare RAM, and Android reports no low-memory state. Readable kernel memory PSI must be quiet
(some avg10 below 2%, full avg10 below 0.5%); measured stalls block recovery. The spare margin is
64 MB with quiet PSI and 128 MB if Android restricts that file or its readings are unavailable.
Unknown pressure is never treated as a quiet reading. A full zram device alone need not mean active
reclaim stalls; admission follows platform memory thresholds instead of assuming every device
behaves like the Fire TV on which the original absolute swap rule was tuned.

This path waits for stable admission, shares a three-second turn limit between original/copy
workers, and backs off for ten seconds on buffering or dropped playback frames. It checks memory
again after fetching. Each admitted native decoder closes as soon as its bitmap is rendered.
Small-device fetching starts only after memory admission, uses one raw buffer without fetch/decode
overlap, and releases completed raw ranges and decoder contexts during memory deferrals. Playback
is never paused or delayed for this recovery path. Diagnostics record RAM and PSI at memory waits;
exports include device, app version, architecture and current heap/memory information.

Validation should compare target-to-visible-image latency (cold and cached), cancelled/wasted
work, dropped frames/rebuffers, and peak process memory including native allocations on a 2 GB
TV and a stronger box. Faster coverage alone is not evidence of an improved playback experience.

Decoded frame caches are charged by bitmap allocation bytes, including unusual aspect ratios. Cache parsing bounds dimensions, image/index lengths and entry counts. File-cache identity includes a hash of the initial bytes as well as content identity and length, distinguishing equal-length releases with different initial bytes while surviving URL rotation. Known file-size-only caches are not provided by the adapter, so generation verifies the initial bytes before opening a persisted image store.

The shared preview HTTP pool uses explicit bounded redirects, strips origin credentials on foreign origins (including cached CDN destinations), rejects HTTPS downgrades and validates Content-Range and stable lengths. Expired CDN links retry through the original address. Cancellation stops in-flight reads without poisoning subsequent requests.

Seekr continues bounded sheet caching and tile decoding. Server-owned Jellyfin/Silo trickplay and Emby BIF images are tried before starting native generation. The server provider warms the center and four neighboring tiles, keeps exact cue intervals even below ten seconds, and persists them independently. Time lookups use a sorted index rather than scanning all saved frames. The filmstrip applies detected black-bar crops through source rectangles, without allocating cropped bitmaps.

## Deliberate integration limits

Arc's account-managed TorBox/Premiumize copy creation/removal and its newer media-server account/session repository are not imported into this older Tester base. The resolver boundary accepts ready links and existing resolved-cache results instead. Server metadata uses the exact item/media-source URL available in this branch. Unsupported or unavailable server previews fall back to independent generation for single files. This preserves generation, verification, playback protections, preparation and seeking behavior without adding account-management dependencies.

## Verification

The feature CI workflow compiles the Android integration, runs preview tests and builds all native ABIs. Host checks compile the actual engine against the bundled Media3 AARs, run the inherited Arc policy tests plus Reshaped transport/cache/tap tests, and run `scripts/test_seek_preview_native.py`. Host boundary stubs are only an additional syntax check; they do not replace an Android app build.

Real-device checks remain necessary for sustained 1080p/4K HDR/DV playback on 2 GB TVs, scrubbing under slow/rate-limited networks, source changes, pause/resume, low storage and long sessions. No physical-TV performance or crash-rate claim is implied by source and host tests.
