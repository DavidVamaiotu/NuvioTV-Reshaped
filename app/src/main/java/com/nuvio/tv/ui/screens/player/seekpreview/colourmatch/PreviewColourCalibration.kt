package com.nuvio.tv.ui.screens.player.seekpreview.colourmatch

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal data class PreviewColourTarget(val lookupMs: Long, val actualMs: Long)
internal data class PreviewColourPlayback(val view: View, val positionMs: Long, val sourceToken: String)

/**
 * Bounded, session-owned calibration. The host supplies only snapshots, a sprite lookup and a sink.
 * No player controls, video downloads, decoder instances or changes to stored images.
 */
internal suspend fun calibratePreviewColour(
    playback: suspend () -> PreviewColourPlayback?,
    nextTarget: (Long) -> PreviewColourTarget?,
    thumbnail: suspend (PreviewColourTarget) -> Bitmap?,
    accept: (PreviewColourAdjustment) -> Unit,
) {
    try {
    withTimeoutOrNull(90_000L) {
        var captures = 0
        var candidates = 0
        while (captures < 4 && candidates < 12) {
            val first = playback()
            if (first == null) { delay(400); continue }
            val target = nextTarget(first.positionMs + 700) ?: return@withTimeoutOrNull
            candidates++
            val sprite = thumbnail(target)?.let { bitmapPixels(it) } ?: continue
            if (!withContext(Dispatchers.Default) { PreviewColourMath.isUseful(sprite) }) {
                // Dark intros and credits do not consume a GPU readback.
                while (playback()?.positionMs?.let { it < target.actualMs + 100 } == true) delay(120)
                continue
            }
            var before: PreviewColourPlayback? = null
            while (true) {
                val current = playback() ?: break
                if (current.sourceToken != first.sourceToken) return@withTimeoutOrNull
                val remaining = target.actualMs - current.positionMs
                if (remaining < -220) break
                if (remaining <= 60) { before = current; break }
                delay(minOf(remaining - 60, 100).coerceAtLeast(10))
            }
            val snapshot = before ?: continue
            captures++
            val reference = copyVideoBuffer(snapshot.view) ?: continue
            try {
                val after = playback() ?: continue
                if (!PreviewColourCapturePolicy.matches(target.actualMs, snapshot.positionMs, after.positionMs,
                        snapshot.sourceToken == after.sourceToken && snapshot.view === after.view)) continue
                val pixels = bitmapPixels(reference)
                val adjustment = withContext(Dispatchers.Default) { PreviewColourMath.estimate(sprite, pixels) }
                    ?: continue
                accept(adjustment)
                return@withTimeoutOrNull
            } finally { reference.recycle() }
        }
    }
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { /* Calibration is optional; keep the original colors. */ }
    catch (_: OutOfMemoryError) { }
}

/** PixelCopy scales the already-rendered video buffer directly to 128x72. No second decoder. */
private suspend fun copyVideoBuffer(view: View): Bitmap? = withContext(Dispatchers.Main.immediate) {
    if (!view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0) return@withContext null
    withTimeoutOrNull(450L) {
        suspendCancellableCoroutine<Bitmap?> { continuation ->
            val bitmap = Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888)
            try {
                when (view) {
                    is SurfaceView -> {
                        if (!view.holder.surface.isValid) {
                            bitmap.recycle()
                            continuation.resume(null)
                        } else PixelCopy.request(view, bitmap, { result ->
                            // A canceled readback still owns its destination until this callback.
                            if (result == PixelCopy.SUCCESS && continuation.isActive) continuation.resume(bitmap)
                            else {
                                bitmap.recycle()
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }, Handler(Looper.getMainLooper()))
                    }
                    is TextureView -> {
                        val image = view.getBitmap(bitmap)
                        if (image != null && continuation.isActive) continuation.resume(image)
                        else { bitmap.recycle(); if (continuation.isActive) continuation.resume(null) }
                    }
                    else -> { bitmap.recycle(); continuation.resume(null) }
                }
            } catch (_: Exception) {
                if (!bitmap.isRecycled) bitmap.recycle()
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}

private suspend fun bitmapPixels(bitmap: Bitmap): PreviewColourPixels = withContext(Dispatchers.Default) {
    val small = Bitmap.createScaledBitmap(bitmap, 128, 72, true)
    try {
        val pixels = IntArray(128 * 72)
        small.getPixels(pixels, 0, 128, 0, 0, 128, 72)
        PreviewColourPixels(128, 72, pixels)
    } finally { if (small !== bitmap) small.recycle() }
}
