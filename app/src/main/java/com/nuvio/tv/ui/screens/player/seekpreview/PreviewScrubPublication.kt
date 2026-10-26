package com.nuvio.tv.ui.screens.player.seekpreview

import kotlin.math.abs

/** Finished work may paint while a held key advances; only the latest target may move the playhead. */
internal object PreviewScrubPublication {
    fun mayPaint(requestMs: Long, latestMs: Long, cueMs: Long, sameInputs: Boolean, previousCenterMs: Long?): Boolean {
        if (!sameInputs) return false
        if (requestMs == latestMs) return true
        if (abs(latestMs - requestMs) > maxOf(120_000L, cueMs * 8)) return false
        if (previousCenterMs != null) {
            val displayedDirection = requestMs.compareTo(previousCenterMs)
            val pendingDirection = latestMs.compareTo(requestMs)
            if (displayedDirection != 0 && pendingDirection != 0 && displayedDirection != pendingDirection) return false
        }
        return true
    }
}
