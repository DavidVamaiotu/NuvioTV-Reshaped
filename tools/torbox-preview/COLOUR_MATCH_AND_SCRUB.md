# Colour matching and held-remote previews

The colour matcher is independent of Nuvio. Its inputs are playback-view snapshots, sprite timestamps and a thumbnail callback; its output is a small immutable colour adjustment. TorboxPreviewIntegration is the only adapter that knows the player controller. Other thumbnail providers retain their original colours.

Calibration runs once per TorBox track, with a 90-second deadline, at most 12 candidate thumbnails and four video-buffer captures. It waits for normal playback at the sprite's actual timestamp, without seeking or creating a decoder. PixelCopy (or TextureView) captures at 128 by 72; analysis averages to a 32 by 18 grid. Flat, dark, clipped and visually mismatched pairs are rejected. Playback/source changes invalidate the capture. Unsupported or protected surfaces and capture failures leave colours unchanged.

Contrast, saturation and small RGB offsets are bounded and accepted only when they reduce matched-pair error. A Compose colour matrix applies the result when drawing previews. Compressed sheets, cached bitmaps and files remain unchanged. This approximates the rendered video buffer; it does not reproduce television panel enhancements or guarantee HDR tone-mapping equivalence.

A held remote key conflates pending lookups but publishes completed frames that remain useful in the current direction. Only the latest request can align the seek position. Neighbor reads finish instead of being canceled by each key repeat, publish individually, and stop when their center becomes obsolete. Small sheets (up to two million pixels) decode once per nearby group, retaining only tiles in the existing 3 MiB LRU. The full sheet is transient, at most about 8 MiB. Larger sheets use region decoding.

## Validation

Pure JVM tests cover calibration identity, bounded grade/tint recovery, dark/flat/mismatched rejection, letterboxing, capture timing/source gates and publication during held left/right, reversals and changed inputs. Filmstrip tests cover incremental neighbor publication and obsolete-center termination.

On a TV, play a supported movie through a colorful scene, then compare seeking before/after calibration. Hold both directions across sheet boundaries, reverse direction while holding, change the manual offset, pause/resume and switch movies. Verify center updates during key repeats, neighbors fill progressively, and playback never jumps back to a stale lookup. Repeat with an unavailable/protected capture surface: previews should still work with their original colours.
