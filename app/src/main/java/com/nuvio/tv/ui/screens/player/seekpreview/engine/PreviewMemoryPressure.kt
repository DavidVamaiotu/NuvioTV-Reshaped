package com.nuvio.tv.ui.screens.player.seekpreview.engine

/** Kernel PSI measures time actually lost to reclaim, rather than how full a device's zram happens to be. */
internal data class PreviewMemoryStalls(val somePercent: Double, val fullPercent: Double) {
    val quiet: Boolean get() = somePercent < 2.0 && fullPercent < 0.5

    companion object {
        fun parse(text: String): PreviewMemoryStalls? {
            fun average(kind: String): Double? = text.lineSequence().firstOrNull { it.startsWith("$kind ") }
                ?.split(Regex("\\s+"))?.firstOrNull { it.startsWith("avg10=") }
                ?.substringAfter('=')?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..100.0 }
            val some = average("some") ?: return null
            val full = average("full") ?: return null
            return PreviewMemoryStalls(some, full)
        }
    }
}

/** Shared by the original/copy workers: stable headroom first, then at most one transient HD decode per interval. */
internal class PreviewCompactGate(private val settleMs: Long = 2_000L, private val intervalMs: Long = 3_000L) {
    private var readySince = -1L
    private var nextAt = 0L
    private var heldUntil = 0L
    private var droppedFrames = -1

    @Synchronized fun observe(nowMs: Long, buffering: Boolean, dropped: Int) {
        if (buffering || droppedFrames >= 0 && dropped > droppedFrames) heldUntil = maxOf(heldUntil, nowMs + 10_000L)
        droppedFrames = dropped
    }

    @Synchronized fun reset() { readySince = -1L }

    /** Zero reserves this turn. Repeated UI refreshes cannot extend settlement or grant a second worker a turn. */
    @Synchronized fun acquire(nowMs: Long): Long {
        if (readySince < 0) readySince = nowMs
        val wait = (maxOf(readySince + settleMs, nextAt, heldUntil) - nowMs).coerceAtLeast(0L)
        if (wait == 0L) nextAt = nowMs + intervalMs
        return wait
    }
}
