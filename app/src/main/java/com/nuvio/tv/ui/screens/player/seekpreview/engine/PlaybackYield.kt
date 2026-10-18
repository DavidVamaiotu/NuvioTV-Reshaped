// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

/**
 * The generating screen: playback loads its start buffer, then stops loading so the thumbnails get its connections.
 * It loads again as soon as the screen lets go, playback is set to play, or the hold is not renewed within [LEASE_MS].
 * Loading is only ever held with at least [startBufferUs] buffered, so playback never starts without it.
 */
class PlaybackYield(minStartBufferUs: Long) {
    companion object {
        const val START_BUFFER_US = 15_000_000L
        const val LEASE_MS = 3_000L
    }

    val startBufferUs: Long = maxOf(START_BUFFER_US, minStartBufferUs)

    @Volatile private var untilMs = Long.MIN_VALUE

    @Volatile var holding = false
        private set

    fun renew(nowMs: Long) {
        untilMs = nowMs + LEASE_MS
    }

    fun end() {
        untilMs = Long.MIN_VALUE
        holding = false
    }

    fun active(nowMs: Long): Boolean = nowMs < untilMs

    /** True while playback should not load more now. */
    fun holds(nowMs: Long, bufferedUs: Long, playWhenReady: Boolean): Boolean {
        holding = !playWhenReady && active(nowMs) && bufferedUs >= startBufferUs
        return holding
    }
}
