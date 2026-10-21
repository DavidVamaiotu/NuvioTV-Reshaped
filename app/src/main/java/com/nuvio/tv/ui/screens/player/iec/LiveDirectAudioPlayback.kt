package com.nuvio.tv.ui.screens.player.iec

import android.os.SystemClock
import androidx.media3.common.Format
import com.nuvio.tv.ui.screens.player.PassthroughWaterLevelPacer

internal object LiveDirectAudioPlayback {
    @Volatile
    private var passthroughLive: Boolean = false

    @Volatile
    private var onPassthroughLiveCleared: (() -> Unit)? = null

    @Volatile
    private var ownTrackOpen: Boolean = false

    @Volatile
    private var ownTrackClosedAtMs: Long? = null

    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    fun setPassthroughLive(live: Boolean) {
        val wasLive = passthroughLive
        passthroughLive = live
        if (wasLive && !live) {
            onPassthroughLiveCleared?.invoke()
        }
    }

    fun isPassthroughLive(): Boolean = passthroughLive

    /** A bitstream AudioTrack of ours really opened; "live" alone is set before any track exists. */
    fun noteOwnTrackOpened() {
        ownTrackOpen = true
    }

    fun noteOwnTrackClosed() {
        if (!ownTrackOpen) return
        ownTrackOpen = false
        ownTrackClosedAtMs = clock()
    }

    fun ownTrackOpen(): Boolean = ownTrackOpen

    fun msSinceOwnTrackClosed(): Long? = ownTrackClosedAtMs?.let { clock() - it }

    fun setOnPassthroughLiveCleared(listener: (() -> Unit)?) {
        onPassthroughLiveCleared = listener
    }

    fun resetForTest() {
        passthroughLive = false
        ownTrackOpen = false
        ownTrackClosedAtMs = null
        clock = { SystemClock.elapsedRealtime() }
        onPassthroughLiveCleared = null
    }

    fun isDirectPassthroughFormat(format: Format): Boolean {
        return PassthroughWaterLevelPacer.isPassthroughMime(format.sampleMimeType)
    }
}
