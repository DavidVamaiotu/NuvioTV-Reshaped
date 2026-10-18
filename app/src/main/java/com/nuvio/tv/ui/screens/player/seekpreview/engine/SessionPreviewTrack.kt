package com.nuvio.tv.ui.screens.player.seekpreview.engine

import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewThumbnail
import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Combines providers, owns only the independent provider; the caller owns the existing playback tap. */
internal class SessionPreviewTrack(private val tap: SeekPreviewTrack, private val range: RangePreviewTrack) : SeekPreviewTrack {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes = MutableStateFlow(0)
    override val revision: StateFlow<Int> = changes
    override var offsetMs = 0L
    override val isLocal = true
    init { scope.launch { combine(tap.revision, range.revision) { a, b -> a to b }.collect { changes.value++ } } }
    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? {
        val local = tap.thumbnailFor(positionMs)
        if (local != null && !local.approximate) return local
        return range.thumbnailFor(positionMs) ?: local
    }
    override suspend fun sideThumbnailFor(positionMs: Long): SeekPreviewThumbnail? {
        val local = tap.sideThumbnailFor(positionMs)
        if (local != null && !local.approximate) return local
        return range.sideThumbnailFor(positionMs) ?: local
    }
    override fun keyframeNear(positionMs: Long, toleranceMs: Long): Long? =
        range.keyframeNear(positionMs, toleranceMs) ?: tap.keyframeNear(positionMs, toleranceMs)
    override fun noteScrubDirection(direction: Int) = tap.noteScrubDirection(direction)
    override fun externalPreviewAvailable(positionMs: Long) = range.externalPreviewAvailable(positionMs)
    override fun close() { range.close(); scope.cancel() }
}
