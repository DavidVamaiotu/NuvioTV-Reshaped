@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/** Optional preview leases around the existing playback load control. */
internal class PreviewLoadControl private constructor(
    val original: DefaultLoadControl,
    private val reserveCore: ReadAheadReserve,
) : LoadControl, PlaybackBufferReserve by reserveCore {
    // Kotlin `by` does not forward Java default methods. Media3's defaults call deprecated
    // fallbacks that throw "not implemented", so forward its current API explicitly.
    override fun onPrepared(playerId: PlayerId) = original.onPrepared(playerId)

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>) =
        original.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = original.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = original.onReleased(playerId)

    override fun getAllocator(): Allocator = original.allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = original.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = original.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        if (reserveCore.holdsLoading(parameters)) false else original.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = original.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(timeline: Timeline, mediaPeriodId: MediaPeriodId,
        bufferedDurationUs: Long): Boolean = original.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    companion object {
        fun wrap(control: DefaultLoadControl, budget: () -> Long, startMs: Int, rebufferMs: Int): PreviewLoadControl? {
            val allocator = control.allocator as? DefaultAllocator ?: return null
            return PreviewLoadControl(control, ReadAheadReserve(allocator, budget,
                startMs, rebufferMs))
        }
    }
}
