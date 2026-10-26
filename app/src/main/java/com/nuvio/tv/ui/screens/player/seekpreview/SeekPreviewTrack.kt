package com.nuvio.tv.ui.screens.player.seekpreview

import android.graphics.Bitmap
import com.nuvio.tv.ui.screens.player.seekpreview.colourmatch.PreviewColourAdjustment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * A preview frame with the cue window it stands for.
 *
 * Cue times are on the preview timeline: the position asked for plus the track's [SeekPreviewTrack.offsetMs].
 * [approximate] marks a stand-in from a nearby moment (an on-device frame for a slot that has
 * none yet); its [bitmap] is already a low-resolution copy, so drawing it scaled up reads as
 * blurred on every API level without a blur shader.
 */
data class SeekPreviewThumbnail(
    val bitmap: Bitmap,
    val cueStartMs: Long,
    val cueEndMs: Long,
    val approximate: Boolean = false,
    /** Provenance is kept with the image, not inferred from which fallback answered last. */
    val origin: String = "UNKNOWN",
    val actualFrameMs: Long? = null,
    val crop: android.graphics.Rect? = null,
    /** Display-only calibration; original cached bytes and bitmaps stay unchanged. */
    val colourAdjustment: PreviewColourAdjustment? = null,
)

/**
 * Seek-preview thumbnails queried by playback position: Seekr sprite sheets, frames generated on
 * the device from the playing stream, or both (see [HybridSeekPreviewTrack]).
 */
interface SeekPreviewTrack {
    /** Signed milliseconds added to a requested position before the lookup (Preview Sync). */
    var offsetMs: Long

    /** Built from the playing stream itself, so it never needs Preview Sync. */
    val isLocal: Boolean get() = false

    /** Bumped whenever thumbnails are added, so a visible preview re-reads its frame. */
    val revision: StateFlow<Int> get() = StaticRevision

    /** The thumbnail covering [positionMs] (after [offsetMs]) with its cue window, or null. */
    suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail?

    /**
     * Like [thumbnailFor], for a frame shown beside the one being scrubbed to: it does not steer
     * background work (the on-device track decodes nearest the last [thumbnailFor] first).
     */
    suspend fun sideThumbnailFor(positionMs: Long): SeekPreviewThumbnail? = thumbnailFor(positionMs)

    /** Adjacent distinct frame; generated tracks use their index rather than guessing from cue windows. */
    suspend fun neighborThumbnailFor(frame: SeekPreviewThumbnail, forward: Boolean): SeekPreviewThumbnail? {
        val probe = if (forward) frame.cueEndMs - offsetMs else frame.cueStartMs - offsetMs - 1
        if (probe < 0L) return null
        return sideThumbnailFor(probe)?.takeIf {
            if (forward) it.cueStartMs > frame.cueStartMs else it.cueStartMs < frame.cueStartMs
        }
    }

    /**
     * The playing file's keyframe nearest [positionMs] (playback timeline) when one is within
     * [toleranceMs], read from the file's own index; null when unknown. Seeking onto a keyframe
     * is both exact and the fastest seek a player can make.
     */
    fun keyframeNear(positionMs: Long, toleranceMs: Long): Long? = null
    fun noteScrubDirection(direction: Int) = Unit
    fun externalPreviewAvailable(positionMs: Long) = Unit

    /** Stops background work; the track is not fed again. */
    fun close() = Unit
}

private val StaticRevision: StateFlow<Int> = MutableStateFlow(0)

/** Adapts the Seekr library's track. Its sheets are prefetched by [SeekPreviewState]. */
internal class SeekrPreviewTrack(private val seekr: BoundedSeekrTrack) : SeekPreviewTrack {
    override val revision: StateFlow<Int> get() = seekr.revision
    override var offsetMs: Long
        get() = seekr.offsetMs
        set(value) {
            seekr.offsetMs = value
        }

    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? =
        seekr.thumbnailFor(positionMs)?.let { thumbnail ->
            SeekPreviewThumbnail(
                bitmap = thumbnail.bitmap,
                cueStartMs = thumbnail.cueStartMs,
                cueEndMs = thumbnail.cueEndMs,
                origin = "SEEKR",
            )
        }
    override suspend fun sideThumbnailFor(positionMs: Long): SeekPreviewThumbnail? = seekr.sideThumbnailFor(positionMs)?.let {
        SeekPreviewThumbnail(it.bitmap, it.cueStartMs, it.cueEndMs, origin = "SEEKR")
    }
}

/**
 * On-device thumbnails where playback has already been, Seekr everywhere else.
 *
 * The on-device track only has frames for parts of the title that were buffered, now or on an
 * earlier watch. Where it has an exact frame that frame wins, since it always lines up; where it
 * would only offer a nearby stand-in, the Seekr frame is shown instead. The Preview Sync offset
 * only moves Seekr frames: on-device ones are already on playback's timeline, so their cue times
 * are shifted onto the preview timeline here to keep the caller's `cue - offset` conversion right.
 *
 * Owns neither track: [SeekPreviewState] opens and closes both.
 */
internal class HybridSeekPreviewTrack(
    private val local: SeekPreviewTrack,
    private val seekr: SeekPreviewTrack,
) : SeekPreviewTrack {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes = MutableStateFlow(0)
    override var offsetMs: Long
        get() = seekr.offsetMs
        set(value) {
            seekr.offsetMs = value
        }

    override val revision: StateFlow<Int> = changes
    init { scope.launch { combine(local.revision, seekr.revision) { a, b -> a to b }.collect { changes.value++ } } }
    override fun close() = scope.cancel()

    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? {
        val cached = local.sideThumbnailFor(positionMs)
        if (cached != null && !cached.approximate) {
            // Keep provider priority aligned even when the centre was already cached.
            return shifted(local.thumbnailFor(positionMs) ?: cached)
        }
        val external = seekr.thumbnailFor(positionMs)
        if (external != null) {
            local.externalPreviewAvailable(positionMs)
            return external
        }
        return pick(positionMs, local.thumbnailFor(positionMs))
    }

    override suspend fun sideThumbnailFor(positionMs: Long): SeekPreviewThumbnail? =
        pick(positionMs, local.sideThumbnailFor(positionMs), side = true)

    override suspend fun neighborThumbnailFor(frame: SeekPreviewThumbnail, forward: Boolean): SeekPreviewThumbnail? =
        if (frame.origin == "ORIGINAL" || frame.origin == "VERIFIED_COPY") {
            local.neighborThumbnailFor(frame.copy(cueStartMs = frame.cueStartMs - offsetMs,
                cueEndMs = frame.cueEndMs - offsetMs), forward)?.let(::shifted)
        } else super<SeekPreviewTrack>.neighborThumbnailFor(frame, forward)

    private suspend fun pick(positionMs: Long, localThumbnail: SeekPreviewThumbnail?, side: Boolean = false): SeekPreviewThumbnail? {
        val own = localThumbnail?.let(::shifted)
        if (own != null && !own.approximate) return own
        return (if (side) seekr.sideThumbnailFor(positionMs) else seekr.thumbnailFor(positionMs)) ?: own
    }

    private fun shifted(thumbnail: SeekPreviewThumbnail): SeekPreviewThumbnail =
        if (seekr.offsetMs == 0L) thumbnail else thumbnail.copy(
            cueStartMs = thumbnail.cueStartMs + seekr.offsetMs,
            cueEndMs = thumbnail.cueEndMs + seekr.offsetMs)

    override fun keyframeNear(positionMs: Long, toleranceMs: Long): Long? =
        local.keyframeNear(positionMs, toleranceMs)
}
