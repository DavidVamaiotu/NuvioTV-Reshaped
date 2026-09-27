package com.nuvio.tv.reshaped.livetv

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * A paused or stalled live stream falls out of its playlist window (BEHIND_LIVE_WINDOW); the
 * player then only needs to jump back to the live edge. A few rejoins per minute at most, so a
 * stream that keeps failing still reaches Nuvio's own error handling.
 */
object LiveEdgeRecovery {
    private const val MAX_REJOINS = 3
    private const val WINDOW_MS = 60_000L

    private var windowStartMs = 0L
    private var rejoins = 0

    /** True when [error] was handled by rejoining the live edge of [player]. */
    fun tryRejoin(error: PlaybackException, player: Player?): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW || player == null) return false
        val now = SystemClock.elapsedRealtime()
        if (now - windowStartMs > WINDOW_MS) {
            windowStartMs = now
            rejoins = 0
        }
        if (rejoins >= MAX_REJOINS) return false
        rejoins++
        player.seekToDefaultPosition()
        player.prepare()
        return true
    }
}
