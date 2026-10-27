package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import com.nuvio.tv.ui.screens.player.seekpreview.colourmatch.PreviewColourPlayback
import com.nuvio.tv.ui.screens.player.seekpreview.colourmatch.copyPreviewVideoBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Passive, bounded readbacks. Never seeks, pauses, decodes another video, or delays previews. */
internal suspend fun alignPreviewFingerprints(
    playback: suspend () -> PreviewColourPlayback?,
    observe: (Long, PreviewHash128) -> Boolean,
): Boolean = try {
    withTimeoutOrNull(300_000L) {
        var captures = 0
        while (captures < 360) {
            delay(500)
            val before = playback() ?: continue
            val image = copyPreviewVideoBuffer(before.view, 320, 180) ?: continue
            try {
                captures++
                val after = playback() ?: continue
                if (after.sourceToken != before.sourceToken || after.view !== before.view ||
                    after.positionMs < before.positionMs || after.positionMs - before.positionMs > 300) continue
                val hash = withContext(Dispatchers.Default) {
                    val pixels = IntArray(image.width * image.height)
                    image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                    TorboxVisualFingerprint.hash(image.width, image.height, pixels)
                }
                if (observe((before.positionMs + after.positionMs) / 2, hash)) return@withTimeoutOrNull true
            } finally { image.recycle() }
        }
        false
    } ?: false
} catch (cancel: CancellationException) { throw cancel }
catch (_: Exception) { false }
catch (_: OutOfMemoryError) { false }
