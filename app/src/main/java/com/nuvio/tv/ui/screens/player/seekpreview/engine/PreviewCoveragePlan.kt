package com.nuvio.tv.ui.screens.player.seekpreview.engine

/** Cover the title before spending the background budget on detail near the playhead. */
internal object PreviewCoveragePlan {
    fun order(count: Int, strides: List<Int>, anchor: Int, before: Int, after: Int): List<Int> {
        if (count <= 0 || strides.isEmpty()) return emptyList()
        val result = LinkedHashSet<Int>()
        val coverageStride = maxOf(6, strides.last())
        // Show a first frame near playback immediately, then both edges and successively smaller gaps.
        result += anchor.coerceIn(0, count - 1)
        for (stride in strides.filter { it >= coverageStride }) {
            val slots = (0 until count step stride.coerceAtLeast(1)).toMutableList()
            if (slots.last() != count - 1) slots += count - 1
            result += slots.first()
            result += slots.last()
            val gaps = ArrayDeque<Pair<Int, Int>>()
            gaps += 0 to slots.lastIndex
            while (gaps.isNotEmpty()) {
                val (left, right) = gaps.removeFirst()
                if (right - left <= 1) continue
                val middle = (left + right) / 2
                result += slots[middle]
                gaps += left to middle
                gaps += middle to right
            }
        }
        result += SeekGrid.latticeOrder(count, strides, anchor, before, after)
        return result.toList()
    }
}

/** A bounded foreground allowance renews; exhausting background work must never disable seeking for the session. */
internal class PreviewRequestWindow(private val allowance: Int = 250, private val windowMs: Long = 300_000L) {
    private var startedAt = -1L
    private var initialRequests = 0

    fun waitMs(nowMs: Long, requests: Int): Long {
        if (startedAt < 0 || nowMs - startedAt >= windowMs) {
            startedAt = nowMs
            initialRequests = requests
        }
        return if (requests - initialRequests < allowance) 0L else (windowMs - (nowMs - startedAt)).coerceAtLeast(0L)
    }
}

/** Two background tries; a later foreground request can retry after a cooldown, never on every UI refresh. */
internal class PreviewFrameAttempts(private val maxAttempts: Int = 2, private val cooldownMs: Long = 30_000L) {
    private data class Entry(var count: Int, var startedAt: Long)
    private val entries = HashMap<Int, Entry>()
    fun count(key: Int): Int = entries[key]?.count ?: 0
    fun available(key: Int, nowMs: Long, foreground: Boolean = false): Boolean {
        val entry = entries[key] ?: return true
        return entry.count < maxAttempts || foreground && nowMs - entry.startedAt >= cooldownMs
    }
    fun started(key: Int, nowMs: Long) {
        val entry = entries.getOrPut(key) { Entry(0, nowMs) }
        if (entry.count >= maxAttempts && nowMs - entry.startedAt >= cooldownMs) entry.count = 0
        entry.count++
        entry.startedAt = nowMs
    }
    fun postponed(key: Int) { entries[key]?.let { it.count = (it.count - 1).coerceAtLeast(0) } }
}
