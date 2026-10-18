// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

/** How long a Usenet title's thumbnails wait for a copy, and the one picture a scrub stop still reads from the stream. */
internal object UsenetCopy {
    /** No copy confirmed this long after the copy pass started: the stream makes the thumbnails. */
    const val TOTAL_MS = 60_000L
    /** No new copy thumbnail for this long while one is being made: the stream makes the thumbnails. */
    const val STALL_MS = 20_000L
    /** The scrub rested this long: that spot is a stop, not a step. */
    const val SCRUB_STOP_MS = 700L
    /** Copy pictures fetched at once behind the generating screen, at most. */
    const val SCREEN_FETCHES = 4

    /**
     * The copy's [order] with the slots the generating screen counts first (copy slots serving an own slot on every
     * [stride]th), the rest after, each part in its own order. [owners]: own slots by copy slot.
     */
    fun screenFirst(order: List<Int>, owners: Map<Int, IntArray>, stride: Int): List<Int> {
        if (stride <= 1) return order
        val (counted, rest) = order.partition { m -> owners[m]?.any { it % stride == 0 } == true }
        return counted + rest
    }

    /** The own slot a copy slot's picture is taken for: of those it [served], the one the screen counts, if any. */
    fun pictureFor(served: IntArray, stride: Int): Int =
        if (stride > 1) served.firstOrNull { it % stride == 0 } ?: served[0] else served[0]

    /**
     * Copy pictures fetched at once: one while playback runs, without the generating screen, or when the server
     * [rateLimited] us lately; else as many as generating would use on that server ([serverCap]), up to [SCREEN_FETCHES].
     */
    fun fetches(screenUp: Boolean, playing: Boolean, serverCap: Int, smallBox: Boolean, rateLimited: Boolean): Int =
        if (!screenUp || playing) 1
        else ThumbFetchLanes.limit(FetchPhase.GENERATING, rateLimited, smallBox4K = smallBox, ledgerCap = serverCap)
            .coerceAtMost(SCREEN_FETCHES)

    /** Why the copy is given up, null while it may go on. [sinceProgressMs]: since its last thumbnail, null while none is being made. */
    fun giveUp(confirmed: Boolean, sinceStartMs: Long, sinceProgressMs: Long?): String? = when {
        sinceProgressMs != null && sinceProgressMs >= STALL_MS -> "no copy thumbnail in ${STALL_MS / 1000} s"
        !confirmed && sinceStartMs >= TOTAL_MS -> "no copy confirmed in ${TOTAL_MS / 1000} s"
        else -> null
    }

    /**
     * The slot read from the stream for a scrub stop, -1 for none: [slot] once the scrub has rested since [lastScrubAt],
     * when [needed] (no thumbnail there yet), and once per stop ([servedStopAt] is the stop already read for).
     */
    fun landingSlot(slot: Int, nowMs: Long, lastScrubAt: Long, servedStopAt: Long, needed: Boolean): Int = when {
        slot < 0 || !needed || lastScrubAt <= 0L -> -1
        nowMs - lastScrubAt < SCRUB_STOP_MS -> -1
        servedStopAt == lastScrubAt -> -1
        else -> slot
    }
}
