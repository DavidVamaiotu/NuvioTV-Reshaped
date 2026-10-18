# Detached seek preview engine

## Boundary

All implementation is under `ui/screens/player/seekpreview` and `cpp/seekpreview`. The existing extractor-registration hook passes the playing stream's request headers. `PreviewEngineAdapter` is the only new code that reads Nuvio controller/Stream objects; the engine accepts values and immutable copy offers. There are no changes to ExoPlayer's allocator, playback buffers, source sorting/resolution, AutoSync scheduling, server repositories or Nuvio settings models.

The existing Generate previews on device switch controls both playback capture and independent extraction. Seekr remains independent of that switch. MPV retains Seekr support; its playback does not register an ExoPlayer keyframe tap or independent source.

## Ownership and providers

`SeekPreviewState` owns provider lifetimes. `SessionPreviewTrack` combines the existing local tap with `RangePreviewTrack`; `HybridSeekPreviewTrack` combines their revisions with Seekr. Source changes close sessions and cancel network requests. Shared `PreviewStore` state serialises overlapping handles for one exact release, avoiding two independent writers racing a manifest.

Ready exact-file images win. Seekr is asynchronous, using one conflated target, bounded compressed sheets, and JPEG region decoding of individual tiles; it does not download/decode every atlas at startup. Server providers support exact-media-source Jellyfin/Silo trickplay and ranged Emby BIF when the playing URL exposes a standard Videos endpoint. Missing server metadata does not prevent original-file generation. Ready external previews suppress unnecessary range work.

## Later, uncached positions

`IndexedPreviewFile` loads bounded MKV, MP4 or AVI indexes, checks the file size and hashes its initial bytes with content identity. Indexes are versioned and cached. `PreviewRangeReader` requires correct 206 Content-Range responses, rejects ignored ranges and changed file lengths, bounds requests, honours rate-limit cooldowns, cancels obsolete work and strips credentials on cross-origin redirects.

`PreviewPlanner` keeps the latest target and a small direction-biased neighbourhood. After a 150 ms settle, the target runs first; a sparse 5-minute grid refines to 60, 30 and 10 seconds when permitted. A maximum of two native failures is allowed per requested position. Source failures stop generation without discarding saved images.

## Accuracy

Every saved frame includes its actual timestamp, source and represented interval. Seek commits are exact; keyframe snapping is limited to 42 ms of timestamp rounding instead of moving to a different scene. Range extraction uses real indexed keyframes. The same native colour path handles captured and independently fetched samples; unsupported profile-5 metadata never falls through to an SDR colour conversion.

Smaller-copy candidates are already-resolved plain HTTP file links from the current source list. No addon searches, account additions, torrent resolution or media-server playback sessions are created for previews. SDR H.264 1080p/720p candidates with matching edition labels are tried first. Duration/recognised PAL ratios must agree, and three contrast-normalised visual anchors distributed through the title must correlate at least 0.92. Interrupted verification can retry; no unverified copy is displayed. This is conservative sampled verification, not proof of identical editing across an entire film.

## 2 GB policy

Only one preview video decode holds `PreviewDecodeGate`. Playback-capture decoding remains pause/scrub-only. Independent work on small TVs also waits for pause/scrub and available memory. Original 4K is not decoded for ordinary coverage on small devices; isolated verification frames may run while paused with at least 450 MiB system headroom and 48 MiB free Java heap. An unavailable smaller copy means external images or timestamps, not an unsafe forced 4K decode.

Decoded caches are byte bounded: tap 4 MiB, independent 4 MiB on small devices, Seekr tiles 2 MiB. Seekr/server compressed sheets are bounded separately. Raw keyframe reads cap at 8 MiB, tap samples at 4 MiB, its queue at two samples and its spool at 32 MiB (also limited by free storage). Persistent image storage uses a shared directory with an approximately 100 MiB eviction target; active stores are protected from eviction. Native working memory is separately gated by source resolution and live headroom. Healthy stronger devices may decode with at least 20 seconds buffered; observed buffering/dropped frames suspend that work.

Caches append compressed images and atomically replace small manifests. Compressed images are read lazily. Cache identity never uses duration alone; playback-tap caches conservatively include the full source URL hash and precise duration, while independently indexed caches survive URL rotation through file identity.

## Verification and limits

CI runs the preview unit tests and builds all four Android native ABIs. `python scripts/test_seek_preview_native.py` checks PQ reference values, neutral monotonic HDR ramps, and host syntax for both FFmpeg backends and JNI. Android ABI compilation uses the NDK; the host JNI declarations are syntax-only substitutes.

The native implementation and readers are adapted from Nuvio Arc with attribution in NOTICE.md. FFmpeg runtimes already present in the APK are reused; the fallback AAR is SHA-256 pinned and the mpv backend checks library majors.

Real-device validation is still required on a 2 GB TV for 1080p AVC/HEVC, 4K HDR/DV with/without a smaller copy, rapid forward/reverse scrubbing, source switches, low storage, network stalls and long pause/resume cycles. Neither instant uncached previews nor frame-identical cross-release matches can be guaranteed. Segmented HLS/DASH, encrypted media, unsupported indexes/decoders and nonstandard or self-signed server endpoints fall back gracefully. No Beta/Stable release is implied by this feature branch.
