package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal data class PreviewHash128(val low: Long, val high: Long, val usable: Boolean = true) {
    fun distance(other: PreviewHash128) = java.lang.Long.bitCount(low xor other.low) + java.lang.Long.bitCount(high xor other.high)
    fun hex() = "%016x%016x".format(high, low)
    companion object {
        fun parse(hex: String, usable: Boolean): PreviewHash128 {
            require(hex.matches(Regex("[0-9a-f]{32}")))
            return PreviewHash128(java.lang.Long.parseUnsignedLong(hex.substring(16), 16),
                java.lang.Long.parseUnsignedLong(hex.substring(0, 16), 16), usable)
        }
    }
}

/** center-dhash128-v1: PIL L weights, center 80% x 60%, separable Lanczos3, little-endian bits. */
internal object TorboxVisualFingerprint {
    fun hash(width: Int, height: Int, argb: IntArray): PreviewHash128 {
        require(width >= 9 && height >= 9 && width.toLong() * height == argb.size.toLong())
        val left = width / 10; val top = height / 5
        val w = width - 2 * left; val h = height - 2 * top
        val gray = IntArray(w * h) { i ->
            val p = argb[(top + i / w) * width + left + i % w]
            (19595 * ((p ushr 16) and 255) + 38470 * ((p ushr 8) and 255) + 7471 * (p and 255) + 32768) ushr 16
        }
        fun weights(size: Int): List<Pair<Int, DoubleArray>> = (0..8).map { i ->
            val scale = maxOf(1.0, size / 9.0)
            val center = (i + .5) * size / 9.0
            val from = maxOf(0, floor(center - 3 * scale + .5).toInt())
            val until = minOf(size, floor(center + 3 * scale + .5).toInt())
            val raw = DoubleArray(until - from) { j ->
                val x = abs((from + j - center + .5) / scale)
                if (x < 1e-12) 1.0 else if (x >= 3) 0.0 else sin(PI * x) * sin(PI * x / 3) / (PI * PI * x * x / 3)
            }
            val sum = raw.sum()
            from to DoubleArray(raw.size) { raw[it] / sum }
        }
        val wx = weights(w); val wy = weights(h)
        val horizontal = IntArray(9 * h) { i ->
            val (from, values) = wx[i % 9]
            values.indices.sumOf { values[it] * gray[(i / 9) * w + from + it] }.roundToInt().coerceIn(0, 255)
        }
        val pixels = IntArray(81) { i ->
            val (from, values) = wy[i / 9]
            values.indices.sumOf { values[it] * horizontal[(from + it) * 9 + i % 9] }.roundToInt().coerceIn(0, 255)
        }
        var low = 0L; var high = 0L
        for (y in 0..7) for (x in 0..7) {
            val bit = y * 8 + x
            if (pixels[y * 9 + x] > pixels[y * 9 + x + 1]) low = low or (1L shl bit)
            if (pixels[y * 9 + x] > pixels[(y + 1) * 9 + x]) high = high or (1L shl bit)
        }
        val mean = gray.average()
        val sd = sqrt(gray.sumOf { (it - mean) * (it - mean) } / gray.size)
        val ones = java.lang.Long.bitCount(low) + java.lang.Long.bitCount(high)
        return PreviewHash128(low, high, mean > 12 && mean < 243 && sd >= 12 && ones in 12..116)
    }
}

internal data class TorboxVisualAnchor(val frame: Int, val positionMs: Long, val hash: PreviewHash128)
internal data class TorboxVisualMatch(val anchor: TorboxVisualAnchor, val playbackMs: Long, val distance: Int) {
    val offsetMs: Long get() = anchor.positionMs - playbackMs
}

/** A provisional constant offset; three independent, unambiguous scenes must agree. */
internal data class TorboxVisualMapping(val scale: Double = 1.0, val offsetMs: Double = 0.0) {
    fun reference(playbackMs: Long) = floor(playbackMs * scale + offsetMs + 1e-7).toLong()
    fun playback(referenceMs: Long) = ceil((referenceMs - offsetMs) / scale).toLong()
}

internal class TorboxVisualMatcher(private val anchors: List<TorboxVisualAnchor>, private val scale: Double = 1.0) {
    private val evidence = linkedMapOf<Int, TorboxVisualMatch>()
    private var lastPlaybackMs: Long? = null
    fun observe(playbackMs: Long, hash: PreviewHash128): TorboxVisualMatch? {
        val last = lastPlaybackMs
        if (last != null && (playbackMs < last || playbackMs - last > 10_000)) evidence.clear()
        lastPlaybackMs = playbackMs
        if (!hash.usable) return null
        val scores = anchors.asSequence().filter { it.hash.usable && abs(it.positionMs - playbackMs * scale) <= 120_000 }
            .map { TorboxVisualMatch(it, playbackMs, it.hash.distance(hash)) }.sortedBy { it.distance }.take(2).toList()
        val best = scores.firstOrNull() ?: return null
        if (best.distance > 12 || scores.size < 2 || scores[1].distance - best.distance < 6) return null
        val old = evidence[best.anchor.frame]
        if (old == null || best.distance < old.distance) evidence[best.anchor.frame] = best
        while (evidence.size > 12) evidence.remove(evidence.keys.first())
        return best
    }

    fun mapping(): TorboxVisualMapping? {
        val pairs = evidence.values.sortedBy { it.playbackMs }
        val speedChange = abs(scale - 1) > .002
        if (pairs.size < (if (speedChange) 5 else 3) ||
            pairs.last().playbackMs - pairs.first().playbackMs < (if (speedChange) 120_000 else 18_000)) return null
        if (pairs.zipWithNext().any { (a, b) -> b.anchor.positionMs <= a.anchor.positionMs }) return null
        val offsets = pairs.map { it.anchor.positionMs - it.playbackMs * scale }.sorted()
        val median = offsets[offsets.size / 2]
        if (abs(median) > 120_000 || offsets.any { abs(it - median) > 650 } ||
            pairs.map { it.distance }.average() > 8) return null
        return TorboxVisualMapping(scale, median)
    }
}
