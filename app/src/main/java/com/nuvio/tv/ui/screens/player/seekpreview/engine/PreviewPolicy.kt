package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.util.LinkedHashMap
import kotlin.math.abs

/** Pure policy: no player objects, network, global settings or allocator changes. */
internal data class PreviewBudget(val small: Boolean) {
    val bitmapBytes = if (small) 4 * 1024 * 1024 else 8 * 1024 * 1024
    val diskBytes = if (small) 100L * 1024 * 1024 else 200L * 1024 * 1024
    val coverageBytes = if (small) 32L * 1024 * 1024 else 128L * 1024 * 1024
    val outputWidth = if (small) 320 else 480
    fun canDecode(width: Int, height: Int, availableMb: Long, heapFreeMb: Long,
                  paused: Boolean = false, explicit: Boolean = false): Boolean {
        if (width !in 1..4096 || height !in 1..2304) return false
        val fourK = width.toLong() * height > 1920L * 1088
        // A single paused target is affordable on some 2 GB TVs; a coverage pass is not.
        if (fourK && small) return paused && explicit && availableMb >= 450 && heapFreeMb >= 64
        val cost = if (fourK) 100 else 45
        return availableMb >= (if (small) 180 else 250) + cost && heapFreeMb >= 24
    }
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

/** Busy and unavailable are different: queued samples must survive transient contention. */
internal enum class PreviewWorkResult { COMPLETE, DEFERRED }

/** Includes store and entry version, so providers and replacements never share the wrong bitmap. */
internal data class PreviewImageKey(val store: String, val slot: Long, val offset: Long, val size: Int)

/** Observed playback always wins. Counter resets do not look like new frame drops. */
internal class PreviewPlaybackHealth {
    private var dropped = -1
    private var heldUntil = 0L
    @Synchronized fun observe(now: Long, playing: Boolean, buffering: Boolean, droppedFrames: Int) {
        if (buffering) heldUntil = maxOf(heldUntil, now + 30_000)
        if (playing && droppedFrames >= 0 && dropped >= 0 && droppedFrames > dropped)
            heldUntil = maxOf(heldUntil, now + 60_000)
        dropped = droppedFrames
    }
    @Synchronized fun allows(now: Long, playing: Boolean): Boolean = !playing || now >= heldUntil
}
