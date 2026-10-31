package com.nuvio.tv.ui.screens.player.seekpreview

import kotlin.math.abs

/** Finished work may paint while a held key advances; only the latest target may move the playhead. */
internal object PreviewScrubPublication {
    /**
     * [held]: a remote key is held. A frame that is still on the way to the cursor paints however
     * far the cursor has run ahead, so a fast hold shows frames instead of discarding each one;
     * the frame for the position where the key is released is always looked up again exactly.
     */
    fun mayPaint(requestMs: Long, latestMs: Long, cueMs: Long, sameInputs: Boolean, previousCenterMs: Long?,
                 held: Boolean = false): Boolean {
        if (!sameInputs) return false
        if (requestMs == latestMs) return true
        if (!held && abs(latestMs - requestMs) > maxOf(120_000L, cueMs * 8)) return false
        if (previousCenterMs != null) {
            val displayedDirection = requestMs.compareTo(previousCenterMs)
            val pendingDirection = latestMs.compareTo(requestMs)
            if (displayedDirection != 0 && pendingDirection != 0 && displayedDirection != pendingDirection) return false
        }
        return true
    }
}
