package com.nuvio.tv.ui.screens.player.seekpreview.engine

/** Measured work pacing, shared by original/copy generation. No player, Android or allocator dependencies. */
internal class PreviewWorkBudget(private val small: Boolean) {
    private var nextWorkAt = 0L
    private var heldUntil = 0L
    private var aheadMs = 0L
    private var lastAheadMs = -1L
    private var observedAt = -1L
    private var dropped = -1
    private var draining = false

    @Synchronized fun observe(nowMs: Long, playing: Boolean, buffering: Boolean, bufferAheadMs: Long,
                              droppedFrames: Int, excluded: Boolean = false) {
        if (!playing && !buffering) {
            nextWorkAt = 0L
            heldUntil = 0L
            draining = false
            lastAheadMs = -1L
        } else {
            if (buffering || (!excluded && dropped >= 0 && droppedFrames > dropped))
                heldUntil = maxOf(heldUntil, nowMs + 10_000L)
            if (observedAt < 0 || nowMs - observedAt >= 1_000L) {
                draining = lastAheadMs >= 0 && bufferAheadMs < lastAheadMs - 1_000L
                lastAheadMs = bufferAheadMs
                observedAt = nowMs
            }
        }
        dropped = droppedFrames
        aheadMs = bufferAheadMs
    }

    @Synchronized fun completed(nowMs: Long, workMs: Long, playing: Boolean) {
        if (!playing) return
        // Target a 10%/25% duty ratio of measured preview work; this is wall time, not a CPU usage guarantee.
        val factor = (if (small) 9L else 3L) * (if (draining || aheadMs < 8_000L) 2 else 1)
        val rest = workMs.coerceIn(1L, 5_000L) * factor
        nextWorkAt = maxOf(nextWorkAt, nowMs) + rest.coerceAtMost(if (small) 3_000L else 1_500L)
    }

    @Synchronized fun remaining(nowMs: Long): Long = (maxOf(nextWorkAt, heldUntil) - nowMs).coerceAtLeast(0L)
}
