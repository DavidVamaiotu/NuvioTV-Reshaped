package com.nuvio.tv.ui.screens.player.seekpreview.colourmatch

import kotlin.math.abs
import kotlin.math.sqrt

/** A bounded display adjustment. Independent of Android, Nuvio and sprite storage. */
data class PreviewColourAdjustment(
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val redOffset: Float = 0f,
    val greenOffset: Float = 0f,
    val blueOffset: Float = 0f,
) {
    val isIdentity: Boolean get() = abs(contrast - 1f) < .001f && abs(saturation - 1f) < .001f &&
        maxOf(abs(redOffset), abs(greenOffset), abs(blueOffset)) < .001f

    /** Android/Compose 4x5 color matrix. Alpha is preserved; offsets use 0..255 units. */
    fun matrixValues(): FloatArray {
        val c = contrast; val s = saturation; val k = 1f - s
        return floatArrayOf(
            c * (s + k * .2126f), c * k * .7152f, c * k * .0722f, 0f, redOffset * 255f,
            c * k * .2126f, c * (s + k * .7152f), c * k * .0722f, 0f, greenOffset * 255f,
            c * k * .2126f, c * k * .7152f, c * (s + k * .0722f), 0f, blueOffset * 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
}

internal data class PreviewColourPixels(val width: Int, val height: Int, val argb: IntArray) {
    init { require(width > 0 && height > 0 && width.toLong() * height == argb.size.toLong() && argb.size <= 1_000_000) }
}

/** One small matched pair, not a movie-wide histogram pass. Reject uncertain scenes. */
internal object PreviewColourMath {
    private const val W = 32
    private const val H = 18
    private val weights = doubleArrayOf(.2126, .7152, .0722)

    fun estimate(sprite: PreviewColourPixels, playback: PreviewColourPixels): PreviewColourAdjustment? {
        val source = sample(sprite); val target = sample(playback)
        val a = stats(source); val b = stats(target)
        if (a.sd < .035 || b.sd < .035 || a.meanL !in .07.. .90 || b.meanL !in .07.. .90 ||
            a.chroma < .035 || b.chroma < .035 || a.clipped > .35 || b.clipped > .35) return null
        val correlation = source.indices.sumOf { i -> (luma(source[i]) - a.meanL) * (luma(target[i]) - b.meanL) } /
            (source.size * a.sd * b.sd)
        if (!correlation.isFinite() || correlation < .96) return null
        val before = error(source, target, PreviewColourAdjustment())
        if (before < .00001) return PreviewColourAdjustment()
        if (before > .04) return null

        // Strong regularization: a single scene must never impose an extreme grade on the movie.
        val contrast = (1.0 + ((b.sd / a.sd).coerceIn(.88, 1.12) - 1.0) * .75).toFloat()
        val saturation = (1.0 + ((b.chroma / a.chroma / contrast).coerceIn(.85, 1.25) - 1.0) * .75).toFloat()
        val base = PreviewColourAdjustment(contrast, saturation)
        val transformedMean = transform(a.mean, base)
        val residual = DoubleArray(3) { b.mean[it] - transformedMean[it] }
        val brightness = residual.indices.sumOf { weights[it] * residual[it] }.coerceIn(-.05, .05)
        val offsets = DoubleArray(3) { (brightness + (residual[it] - brightness).coerceIn(-.025, .025)) * .75 }
        val result = base.copy(redOffset = offsets[0].toFloat(), greenOffset = offsets[1].toFloat(), blueOffset = offsets[2].toFloat())
        return result.takeIf { error(source, target, it) < before * .90 }
    }

    fun isUseful(image: PreviewColourPixels): Boolean {
        val s = stats(sample(image))
        return s.sd >= .035 && s.meanL in .07.. .90 && s.chroma >= .035 && s.clipped <= .35
    }

    private data class Stats(val mean: DoubleArray, val meanL: Double, val sd: Double, val chroma: Double, val clipped: Double)
    private fun stats(values: Array<DoubleArray>): Stats {
        val mean = DoubleArray(3) { c -> values.sumOf { it[c] } / values.size }
        val meanL = luma(mean)
        val sd = sqrt(values.sumOf { val d = luma(it) - meanL; d * d } / values.size)
        val chroma = sqrt(values.sumOf { rgb -> val y = luma(rgb); rgb.sumOf { (it - y) * (it - y) } / 3 } / values.size)
        val clipped = values.count { luma(it) < .025 || luma(it) > .975 }.toDouble() / values.size
        return Stats(mean, meanL, sd, chroma, clipped)
    }

    private fun sample(image: PreviewColourPixels): Array<DoubleArray> {
        fun black(x: Int, y: Int): Boolean {
            val p = image.argb[y * image.width + x]
            return maxOf((p ushr 16) and 255, (p ushr 8) and 255, p and 255) <= 8
        }
        fun blackRow(y: Int) = (0 until image.width).count { black(it, y) } >= image.width * .98
        var top = 0; var bottom = image.height
        while (top < image.height / 4 && blackRow(top)) top++
        while (bottom > image.height * 3 / 4 && blackRow(bottom - 1)) bottom--
        fun blackColumn(x: Int) = (top until bottom).count { black(x, it) } >= (bottom - top) * .98
        var left = 0; var right = image.width
        while (left < image.width / 4 && blackColumn(left)) left++
        while (right > image.width * 3 / 4 && blackColumn(right - 1)) right--
        return Array(W * H) { i ->
            val gx = i % W; val gy = i / W
            val x0 = left + gx * (right - left) / W
            val x1 = maxOf(x0 + 1, left + (gx + 1) * (right - left) / W).coerceAtMost(right)
            val y0 = top + gy * (bottom - top) / H
            val y1 = maxOf(y0 + 1, top + (gy + 1) * (bottom - top) / H).coerceAtMost(bottom)
            val rgb = DoubleArray(3)
            for (y in y0 until y1) for (x in x0 until x1) {
                val p = image.argb[y * image.width + x]
                rgb[0] += (p ushr 16) and 255; rgb[1] += (p ushr 8) and 255; rgb[2] += p and 255
            }
            val count = (x1 - x0) * (y1 - y0) * 255.0
            for (c in 0..2) rgb[c] /= count
            rgb
        }
    }

    private fun luma(rgb: DoubleArray) = weights.indices.sumOf { weights[it] * rgb[it] }
    private fun transform(rgb: DoubleArray, adjustment: PreviewColourAdjustment): DoubleArray {
        val y = luma(rgb); val offsets = floatArrayOf(adjustment.redOffset, adjustment.greenOffset, adjustment.blueOffset)
        return DoubleArray(3) { c ->
            (adjustment.contrast * (y + adjustment.saturation * (rgb[c] - y)) + offsets[c]).coerceIn(0.0, 1.0)
        }
    }
    private fun error(a: Array<DoubleArray>, b: Array<DoubleArray>, adjustment: PreviewColourAdjustment) =
        a.indices.sumOf { i -> val p = transform(a[i], adjustment); (0..2).sumOf { c -> val d = p[c] - b[i][c]; d * d } } / (a.size * 3)
}

/** Timestamps and a source token keep captures out of seeks/source switches. */
internal object PreviewColourCapturePolicy {
    fun matches(targetMs: Long, beforeMs: Long, afterMs: Long, sameSource: Boolean): Boolean =
        sameSource && targetMs >= 0 && afterMs >= beforeMs && afterMs - beforeMs <= 400 &&
            abs(beforeMs - targetMs) <= 220 && abs(afterMs - targetMs) <= 400
}
