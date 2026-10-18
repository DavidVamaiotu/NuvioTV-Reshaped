package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.util.LinkedHashMap
import kotlin.math.abs

/** Pure policy: no player objects, network, global settings or allocator changes. */
internal data class PreviewBudget(val small: Boolean) {
    val bitmapBytes = if (small) 4 * 1024 * 1024 else 8 * 1024 * 1024
    val diskBytes = if (small) 100L * 1024 * 1024 else 200L * 1024 * 1024
    val coverageBytes = if (small) 32L * 1024 * 1024 else 128L * 1024 * 1024
    val outputWidth = if (small) 320 else 480
    fun canDecode(width: Int, height: Int, availableMb: Long, heapFreeMb: Long): Boolean {
        val fourK = width.toLong() * height > 1920L * 1088
        if (fourK && small) return false
        val cost = if (fourK) 100 else 45
        return availableMb >= (if (small) 180 else 250) + cost && heapFreeMb >= 24
    }
}

/** One latest foreground target, a bounded neighbourhood, then a sparse-to-fine coverage pass. */
internal class PreviewPlanner(private val durationMs: Long) {
    data class Request(val positionMs: Long, val generation: Long, val direction: Int)
    var latest: Request? = null
        private set
    private var generation = 0L
    private val covered = HashSet<Long>()
    private var strideIndex = 0
    private var cursor = 0L
    private val strides = longArrayOf(300_000, 60_000, 30_000, 10_000)
    @Synchronized fun request(positionMs: Long): Request {
        val p = positionMs.coerceIn(0, (durationMs - 1).coerceAtLeast(0))
        val previous = latest
        if (previous != null && previous.positionMs == p) return previous
        return Request(p, ++generation, previous?.let { p.compareTo(it.positionMs) } ?: 1).also { latest = it }
    }
    @Synchronized fun isCurrent(request: Request): Boolean = latest?.generation == request.generation
    @Synchronized fun next(has: (Long) -> Boolean, coverage: Boolean): Long? {
        latest?.let { r ->
            val direction = r.direction.takeIf { it != 0 } ?: 1
            for (step in intArrayOf(0, direction, 2 * direction, -direction)) {
                val p = (r.positionMs / 10_000 * 10_000 + step * 10_000).coerceIn(0, (durationMs - 1).coerceAtLeast(0))
                if (!has(p) && p !in covered) return p
            }
        }
        if (!coverage) return null
        while (strideIndex < strides.size) {
            if (cursor >= durationMs) { strideIndex++; cursor = 0; continue }
            val p = cursor
            cursor += strides[strideIndex]
            if (!has(p) && p !in covered) return p
        }
        return null
    }
    @Synchronized fun completed(positionMs: Long) { covered += positionMs }
}

/** Byte-based eviction; frame counts do not bound bitmap memory. */
internal class PreviewLru<K, V>(private val maxBytes: Int, private val sizeOf: (V) -> Int) {
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    private var bytes = 0L
    @Synchronized operator fun get(key: K): V? = values[key]
    @Synchronized fun put(key: K, value: V) {
        values.remove(key)?.let { bytes -= sizeOf(it) }
        val size = sizeOf(value)
        if (size > maxBytes || size < 0) return
        values[key] = value
        bytes += size
        val iterator = values.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            bytes -= sizeOf(iterator.next().value)
            iterator.remove()
        }
    }
    @Synchronized fun clear() { values.clear(); bytes = 0 }
    @Synchronized fun usedBytes(): Long = bytes
}

internal object PreviewTiming {
    /** A true local frame is shown only close to the requested time, never across a distant scene. */
    fun near(frameMs: Long, requestedMs: Long, toleranceMs: Long = 5_000): Boolean = abs(frameMs - requestedMs) <= toleranceMs
    fun frameCueEnd(frameMs: Long, durationMs: Long): Long = minOf(durationMs, frameMs + 10_000).coerceAtLeast(frameMs + 1)
}

/** Observed playback always wins. Counter resets do not look like new frame drops. */
internal class PreviewPlaybackHealth {
    private var dropped = -1
    private var decodedAt = -1L
    private var heldUntil = 0L
    @Synchronized fun decoded(now: Long) { decodedAt = now }
    @Synchronized fun observe(now: Long, playing: Boolean, buffering: Boolean, droppedFrames: Int) {
        if (buffering) heldUntil = maxOf(heldUntil, now + 30_000)
        if (playing && droppedFrames >= 0 && dropped >= 0 && droppedFrames > dropped &&
            decodedAt >= 0 && now - decodedAt <= 20_000) heldUntil = maxOf(heldUntil, now + 60_000)
        dropped = droppedFrames
    }
    @Synchronized fun allows(now: Long, playing: Boolean): Boolean = !playing || now >= heldUntil
}
