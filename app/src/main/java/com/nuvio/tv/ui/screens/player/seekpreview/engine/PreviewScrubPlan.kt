package com.nuvio.tv.ui.screens.player.seekpreview.engine

/** Latest-target planning, independent of the player/UI. Repeated image refreshes do not restart settlement. */
internal class PreviewScrubPlan(private val settleMs: Long = 120L) {
    @Volatile var direction = 0
        private set
    @Volatile private var target = -1
    @Volatile private var changedAt = 0L

    fun direction(value: Int) { direction = value.compareTo(0) }

    fun target(slot: Int, nowMs: Long) {
        if (slot == target) return
        if (target >= 0) direction = slot.compareTo(target)
        target = slot
        changedAt = nowMs
    }

    fun remaining(nowMs: Long): Long = if (direction == 0) 0 else (settleMs - (nowMs - changedAt)).coerceAtLeast(0)

    fun neighbors(center: Int, count: Int, radius: Int): List<Int> = buildList {
        if (center !in 0 until count) return@buildList
        add(center)
        val step = if (direction < 0) -1 else 1
        for (distance in 1..radius) {
            val next = center + step * distance
            val previous = center - step * distance
            if (next in 0 until count) add(next)
            if (previous in 0 until count) add(previous)
        }
    }
}
