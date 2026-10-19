@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.player.seekpreview

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.nuvio.tv.ui.screens.player.seekpreview.engine.*
import com.nuvio.tv.ui.screens.player.seekpreview.local.KeyframeSink
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalPreviewSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Adapts Arc's generator to the existing filmstrip/Seekr contract. It never owns playback. */
internal class ArcPreviewTrack(
    context: Context,
    private val source: LocalPreviewSource,
    private val identity: String,
    private val identityExact: Boolean,
    private val durationMs: Long,
    private val adapter: ArcPreviewAdapter,
    startImmediately: Boolean = true,
) : SeekPreviewTrack, KeyframeSink {
    private val appContext = context.applicationContext
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val closed = AtomicBoolean(false)
    override var offsetMs = 0L
    override val isLocal = true
    override val revision = SeekThumbnails.tick
    init {
        current = this
        SeekThumbnails.stopSession(handBackPlayer = false)
        source.keyframeSink = this
        NextEpisodeThumbs.resume()
        PreviewLifecycle.register(context)
        SeekThumbnails.scheduleStartupHousekeeping(context)
        if (startImmediately) start()
    }
    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        PreviewDiagnostics.note("background generation scheduled")
        scope.launch {
            try {
                SeekThumbnails.startWhenEligible(appContext,
                    ThumbSource(source.sourceKey, source.headers, identity, holdBeforePlay = false,
                        identityExact = identityExact, playbackConnections = PreviewHostSignals.playbackConnections, copySources = adapter.copySources(),
                        mediaServer = previewServerSource(source.sourceKey) != null, usenet = CopyChoice.isUsenet(source.sourceKey)),
                    adapter::player, adapter::reserve,
                    skipReason = { if (closed.get() || current !== this@ArcPreviewTrack) "session replaced" else null })
            } finally {
                if (closed.get() && current === this@ArcPreviewTrack) SeekThumbnails.stopSession(handBackPlayer = false)
            }
        }
    }
    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? {
        if (!ownsSession()) return null
        adapter.sampleSignals()
        SeekThumbnails.notePriority(positionMs)
        return SeekThumbnails.shownFor(positionMs)?.picture()
    }
    override suspend fun sideThumbnailFor(positionMs: Long): SeekPreviewThumbnail? =
        if (ownsSession()) SeekThumbnails.tileFor(positionMs)?.picture() else null
    override suspend fun neighborThumbnailFor(frame: SeekPreviewThumbnail, forward: Boolean): SeekPreviewThumbnail? =
        if (ownsSession()) SeekThumbnails.neighborFor(frame.actualFrameMs ?: frame.cueStartMs, forward)?.picture() else null
    override fun keyframeNear(positionMs: Long, toleranceMs: Long): Long? =
        if (ownsSession()) SeekThumbnails.landingFor(positionMs)?.takeIf { kotlin.math.abs(it - positionMs) <= toleranceMs } else null
    fun gridStep(positionMs: Long, deltaMs: Long): Long? {
        if (!ownsSession()) return null
        val grid = SeekThumbnails.gridStep(positionMs, deltaMs) ?: return null
        val landing = SeekThumbnails.landingFor(grid) ?: grid
        // Repeated keyframes and title boundaries must never reverse or swallow a tap.
        return SeekGrid.landingTarget(positionMs, deltaMs, landing, durationMs)
    }
    private fun SeekThumbnails.Shown.picture(): SeekPreviewThumbnail = SeekPreviewThumbnail(bitmap,
        ptsMs, minOf(durationMs, ptsMs + (SeekThumbnails.stripSpacingMs() ?: SeekThumbnails.SPACING_MS)).coerceAtLeast(ptsMs + 1),
        approximate = !exact, origin = if (borrowed) "VERIFIED_COPY" else "ORIGINAL", actualFrameMs = ptsMs,
        crop = crop?.takeIf { it.fits(bitmap.width, bitmap.height) }?.let {
            android.graphics.Rect(it.left, it.top, bitmap.width - it.right, bitmap.height - it.bottom)
        })
    private fun ownsSession() = !closed.get() && current === this
    override fun wantsKeyframes() = ownsSession()
    override fun acceptsFormat(format: Format) = format.width in 1..4096 && format.height in 1..2304 &&
        format.sampleMimeType in setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
    override fun wantsKeyframe(timeUs: Long) = ownsSession() && SeekThumbnails.tapAccepts(timeUs)
    override fun mayWantKeyframesNear(timeUs: Long) = ownsSession() && (SeekThumbnails.tapWants(timeUs) || SeekThumbnails.tapAccepts(timeUs))
    override fun onKeyframe(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
        if (MemoryGovernor.isLowRam && size > 2 * 1024 * 1024) return
        if (wantsKeyframe(timeUs)) SeekThumbnails.tapOffer(timeUs, data.copyOfRange(offset, offset + size))
    }
    override fun close() = close(afterPlayback = false)
    fun close(afterPlayback: Boolean, resumeIfHeld: Boolean = false) {
        if (!closed.compareAndSet(false, true)) return
        if (source.keyframeSink === this) source.keyframeSink = null
        scope.cancel()
        if (current === this) {
            if (resumeIfHeld) SeekThumbnails.startWatchingNow()
            if (afterPlayback) SeekThumbnails.playerClosed() else SeekThumbnails.stopSession(handBackPlayer = false)
            NextEpisodeThumbs.cancel()
            current = null
        }
        adapter.reserve()?.let { it.yieldLoading(false); it.release() }
        adapter.detach(keepPlaybackObservation = afterPlayback)
    }
    companion object { @Volatile private var current: ArcPreviewTrack? = null }
}
