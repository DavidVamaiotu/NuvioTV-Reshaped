@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/** Optional preview leases around the unchanged playback load control. All other methods delegate. */
internal class PreviewLoadControl private constructor(
    val original: DefaultLoadControl,
    private val reserveCore: ReadAheadReserve,
) : LoadControl by original, PlaybackBufferReserve by reserveCore {
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        if (reserveCore.holdsLoading(parameters)) false else original.shouldContinueLoading(parameters)

    companion object {
        fun wrap(control: DefaultLoadControl, budget: () -> Long, startMs: Int, rebufferMs: Int): PreviewLoadControl? {
            val allocator = control.allocator as? DefaultAllocator ?: return null
            return PreviewLoadControl(control, ReadAheadReserve(allocator, budget,
                startMs, rebufferMs))
        }
    }
}
