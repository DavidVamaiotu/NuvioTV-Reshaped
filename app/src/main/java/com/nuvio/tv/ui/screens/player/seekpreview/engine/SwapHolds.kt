// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

/** Decodes a session held back for low swap, for one log line when it starts and one summary at the end. Worker only. */
internal class SwapHolds {
    var count = 0
        private set
    var whilePaused = 0
        private set
    var funded = 0
        private set
    /** Refused after the keyframe was fetched, just before the decode would have started. */
    var afterFetch = 0
        private set
    var decoderCloses = 0
        private set
    var minSwapFreeMb = -1L
        private set
    private var firstAtMs = -1L
    private var lastAtMs = -1L

    /** True for the first refusal of the session. */
    fun refused(
        verdict: GovernorVerdict, paused: Boolean, funded: Boolean, afterFetch: Boolean, closed: Boolean, nowMs: Long,
    ): Boolean {
        if (!verdict.swapLow) return false
        count++
        if (paused) whilePaused++
        if (funded) this.funded++
        if (afterFetch) this.afterFetch++
        if (closed) decoderCloses++
        if (minSwapFreeMb < 0 || verdict.swapFreeMb < minSwapFreeMb) minSwapFreeMb = verdict.swapFreeMb
        if (firstAtMs < 0) firstAtMs = nowMs
        lastAtMs = nowMs
        return count == 1
    }

    fun firstLine(verdict: GovernorVerdict, paused: Boolean, funded: Boolean, afterFetch: Boolean, closed: Boolean): String =
        "decodes held for swap: ${verdict.reason} (${if (paused) "paused" else "playing"}" +
            "${if (funded) ", funded" else ""}${if (afterFetch) ", after the fetch" else ""})" +
            if (closed) ", decoders closed until it recovers" else ""

    /** Null when swap never held a decode back. */
    fun summary(): String? {
        if (count == 0) return null
        return "swap holds this session: refused $count (paused $whilePaused, funded $funded, after the fetch $afterFetch), " +
            "lowest SwapFree $minSwapFreeMb MB, decoders closed $decoderCloses, over ${(lastAtMs - firstAtMs) / 1000} s"
    }
}
