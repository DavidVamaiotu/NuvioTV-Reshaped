# Seek preview engine attribution

The container readers (`MediaIndex`, `MkvIndexReader`, `Mp4IndexReader`, `AviIndexReader`), codec configuration, keyframe extraction, decoder/colour code, crop/signature helpers, index serialisation, native sources and adapted reader tests originate in **Nuvio Arc**, by **ysosrs123**, commit `ae6212ef58d25df819a716f2a38314732e719a2c`:

https://github.com/ysosrs123/NuvioTV-Fork/tree/ae6212ef58d25df819a716f2a38314732e719a2c

The generation scheduler, adaptive fetch/decode lanes, copy generation and verification policies, connection ledger, memory governor, frame store, preparation policies, read-ahead reserve, next-episode worker and corresponding tests are adapted from Arc commit `447d8833f7914ec6f9a13fdfb0436ad99daeee69`:

https://github.com/ysosrs123/NuvioTV-Fork/tree/447d8833f7914ec6f9a13fdfb0436ad99daeee69

These are GPL-3.0 adaptations. Original source attribution is retained in the adapted files. Reshaped changes include feature-owned playback interfaces and a load-control decorator, a weak-reference lifecycle boundary, sorted server-cue storage, bitmap byte charging, stricter cache parsing, file fingerprints, bounded credential-safe redirects, and integration with the existing filmstrip, Seekr, settings and source-preload hooks. Arc's underlying player and playback allocator are not imported.

The FFmpeg headers under `app/src/main/cpp/seekpreview/third_party` retain their copyright and LGPL notices. No additional FFmpeg runtime is distributed by this port. The native decoder reuses the existing mpv-android-lib 0.1.12 runtimes (version-gated), or the SHA-256-pinned FFmpeg 6.0 runtime in `nextlib-mediainfo-local.aar`. Backend updates require matching headers and ABI review.
