# Seek preview engine attribution

Container readers (`MediaIndex`, `MkvIndexReader`, `Mp4IndexReader`, `AviIndexReader`), codec configuration, keyframe extraction, `ThumbDecoder`, colour classification, crop/signature helpers, index serialisation, native decoder/colour sources, and their adapted reader/matching tests originate in **Nuvio Arc**, by **ysosrs123**:

https://github.com/ysosrs123/NuvioTV-Fork/tree/ae6212ef58d25df819a716f2a38314732e719a2c

They are adapted under the repository's GPL-3.0 licence. Original source and attribution are retained here; changes include feature-owned bounded buffers, namespace/JNI isolation, stricter cache parsing and an independent session coordinator. This is not an import of Arc's player, settings, connection ledger, playback allocator or engine singleton.

The FFmpeg headers under `app/src/main/cpp/seekpreview/third_party` retain their original copyright and LGPL notices. No additional FFmpeg runtime is distributed by this change. The native decoder loads the existing mpv-android-lib 0.1.12 runtimes (version-gated), or the SHA-256-pinned FFmpeg 6.0 runtime in nextlib-mediainfo-local.aar. Updating either backend requires matching headers and ABI review.

The scheduler, resource policy, session/provider composition, append-only preview storage, request transport, copy verification integration and Nuvio adapter are Reshaped implementation work. All remain under this repository's GPL-3.0 licence.
