@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual bundled Java API: Kotlin delegation skips its default methods. */
class PreviewLoadControlTest {
    private val playerId = PlayerId.UNSET
    private val periodId = MediaPeriodId(Any())
    private fun parameters(bufferedUs: Long = 0L, play: Boolean = true) = LoadControl.Parameters(
        playerId, Timeline.EMPTY, periodId, 0L, bufferedUs, 1f, play, false,
        C.TIME_UNSET, C.TIME_UNSET,
    )
    private fun wrap(control: DefaultLoadControl) =
        PreviewLoadControl.wrap(control, { 64L * 1024 * 1024 }, 1_000, 2_000)!!

    @Test fun stockBackBufferSettingsSurviveWrapping() {
        for ((durationMs, retain) in listOf(0 to false, 1_500 to true, 8_000 to false)) {
            val original = DefaultLoadControl.Builder().setBackBuffer(durationMs, retain).build()
            val wrapped: LoadControl = wrap(original)
            assertSame(original.allocator, wrapped.allocator)
            assertEquals(durationMs * 1_000L, wrapped.getBackBufferDurationUs(playerId))
            assertEquals(retain, wrapped.retainBackBufferFromKeyframe(playerId))
        }
    }

    @Test fun forwardsLifecycleAndTrackSelectionToTheOriginal() {
        val original = RecordingControl()
        val wrapped: LoadControl = wrap(original)
        val parameters = parameters()
        val tracks = emptyArray<ExoTrackSelection?>()
        wrapped.onPrepared(playerId)
        wrapped.onTracksSelected(parameters, TrackGroupArray.EMPTY, tracks)
        wrapped.onStopped(playerId)
        wrapped.onReleased(playerId)
        assertEquals(listOf("prepared", "tracks", "stopped", "released"), original.calls)
        assertSame(parameters, original.selectedParameters)
        assertSame(tracks, original.selectedTracks)
    }

    @Test fun forwardsPlaybackAndPreloadDecisionsWithoutALease() {
        val original = RecordingControl()
        val wrapped: LoadControl = wrap(original)
        val parameters = parameters()
        for (decision in listOf(true, false)) {
            original.decision = decision
            assertEquals(decision, wrapped.shouldContinueLoading(parameters))
            assertEquals(decision, wrapped.shouldStartPlayback(parameters))
            assertEquals(decision, wrapped.shouldContinuePreloading(Timeline.EMPTY, periodId, 0L))
        }
        assertEquals(listOf("load", "start", "preload", "load", "start", "preload"), original.calls)
        // The wrapper must read runtime changes, rather than snapshot the back buffer at construction.
        original.backBufferUs = 5_000_000L
        assertEquals(5_000_000L, wrapped.getBackBufferDurationUs(playerId))
    }

    @Test fun previewYieldOnlyHoldsLoadingAfterTheStartBufferAndReleasesOnPlay() {
        val original = RecordingControl()
        val wrapped = wrap(original)
        wrapped.yieldLoading(true)
        assertTrue(wrapped.shouldContinueLoading(parameters(1_000_000L, play = false)))
        assertFalse(wrapped.shouldContinueLoading(parameters(20_000_000L, play = false)))
        assertTrue(wrapped.shouldContinueLoading(parameters(20_000_000L, play = true)))
        assertEquals(listOf("load", "load"), original.calls)
    }

    private class RecordingControl : DefaultLoadControl() {
        val calls = mutableListOf<String>()
        var selectedParameters: LoadControl.Parameters? = null
        var selectedTracks: Array<out ExoTrackSelection?>? = null
        var decision = true
        var backBufferUs = 0L
        override fun onPrepared(playerId: PlayerId) { calls += "prepared" }
        override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray,
            trackSelections: Array<out ExoTrackSelection?>) {
            calls += "tracks"
            selectedParameters = parameters
            selectedTracks = trackSelections
        }
        override fun onStopped(playerId: PlayerId) { calls += "stopped" }
        override fun onReleased(playerId: PlayerId) { calls += "released" }
        override fun getBackBufferDurationUs(playerId: PlayerId) = backBufferUs
        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean { calls += "load"; return decision }
        override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean { calls += "start"; return decision }
        override fun shouldContinuePreloading(timeline: Timeline, mediaPeriodId: MediaPeriodId,
            bufferedDurationUs: Long): Boolean { calls += "preload"; return decision }
    }
}
