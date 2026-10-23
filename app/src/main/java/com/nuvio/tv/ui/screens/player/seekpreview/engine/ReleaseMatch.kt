// SPDX-License-Identifier: GPL-3.0-only
// Adapted from ysosrs123/NuvioTV-Fork (Nuvio Arc), commit ae6212ef58d25df819a716f2a38314732e719a2c.
// Original container/keyframe/colour implementation by ysosrs123; see NOTICE.md in this package.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reuse of another release's thumbnails (same title, same length): timing match, slot mapping, and the picture check
 * on small contrast-normalised greyscale signatures.
 */
internal object ReleaseMatch {
    /** Lengths this close share the picture timing as is. */
    const val SAME_LENGTH_MS = 2_000L
    /** 25 fps against 23.976 fps, either way round. */
    const val PAL_MIN = 0.956
    const val PAL_MAX = 0.963
    const val SIG_W = 32
    const val SIG_H = 18
    /** Below this spread (0-255 grey) a picture is too flat to compare. */
    const val FLAT_STDEV = 4.0
    const val MATCH_MIN = 0.90f
    const val DIFFERENT_MAX = 0.40f
    /** An undecided picture this alike still fails the check: a near miss is never taken for a match. */
    const val FAIL_MIN = 0.60f
    /** A neighbour slot this much better than the slot itself means the timing is shifted. */
    const val SHIFT_MARGIN = 0.08f
    const val CHECKS_NEEDED = 3
    const val CHECK_SPARES = 3
    /** Own and borrowed keyframe within one frame (24 fps): the same picture, so a mismatch is real. */
    const val SAME_FRAME_US = 42_000L
    const val NEAR_US = 250_000L
    /** Farther apart than this a pair is not compared, and a borrowed picture is not copied into the slot. */
    const val WIDE_US = 1_000_000L

    /** Candidate time per own time (other / own), 1.0 for the same timing, null when not reusable. */
    fun ratio(ownMs: Long, otherMs: Long): Double? {
        if (ownMs <= 0L || otherMs <= 0L) return null
        if (abs(ownMs - otherMs) <= SAME_LENGTH_MS) return 1.0
        val r = otherMs.toDouble() / ownMs
        return if (r in PAL_MIN..PAL_MAX || 1.0 / r in PAL_MIN..PAL_MAX) r else null
    }

    /** The candidate's slot holding the same moment as own [slot] (slot centres), -1 outside it. */
    fun mapSlot(slot: Int, ratio: Double, otherSlotCount: Int): Int {
        if (slot < 0) return -1
        val m = if (ratio == 1.0) slot else ((slot + 0.5) * ratio - 0.5).roundToInt()
        return if (m in 0 until otherSlotCount) m else -1
    }

    fun slotCount(durationMs: Long, spacingMs: Long): Int =
        if (durationMs <= 0L) 0 else ((durationMs - 1) / spacingMs).toInt() + 1

    /**
     * Check slots from [eligible] (slot to own/borrowed keyframe gap in us), closest pairs first: same-frame pairs,
     * then within [NEAR_US], then [WIDE_US]. Within a tier spread over the title: near 1/6, 1/2 and 5/6, then near
     * 1/4, 1/2 and 3/4.
     */
    fun pickCheckSlots(eligible: List<Pair<Int, Long>>, slotCount: Int): List<Int> {
        if (eligible.isEmpty() || slotCount <= 0) return emptyList()
        val total = CHECKS_NEEDED + CHECK_SPARES
        val targets = (0 until CHECKS_NEEDED).map { (2 * it + 1).toDouble() / (2 * CHECKS_NEEDED) } +
            (0 until CHECK_SPARES).map { (it + 1).toDouble() / (CHECK_SPARES + 1) }
        val picked = ArrayList<Int>()
        for (tier in longArrayOf(SAME_FRAME_US, NEAR_US, WIDE_US)) {
            val pool = eligible.filter { it.second <= tier && it.first !in picked }.map { it.first }.toMutableList()
            for (f in targets) {
                if (picked.size >= total || pool.isEmpty()) break
                val want = f * slotCount
                val s = pool.minByOrNull { abs(it + 0.5 - want) } ?: break
                picked += s
                pool.remove(s)
            }
        }
        return picked
    }

    /**
     * [SIG_W] x [SIG_H] area-averaged grey of the picture inside the given margins, mean 0 and spread 1, so a
     * brightness or contrast change (SDR against a tone-mapped HDR copy) keeps the shape. Null when flat.
     */
    fun signature(px: IntArray, w: Int, h: Int, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0): FloatArray? {
        val cw = w - left - right
        val ch = h - top - bottom
        if (cw < 2 || ch < 2 || px.size < w * h) return null
        val sig = FloatArray(SIG_W * SIG_H)
        for (gy in 0 until SIG_H) {
            val y0 = top + gy * ch / SIG_H
            val y1 = maxOf(y0 + 1, top + (gy + 1) * ch / SIG_H)
            for (gx in 0 until SIG_W) {
                val x0 = left + gx * cw / SIG_W
                val x1 = maxOf(x0 + 1, left + (gx + 1) * cw / SIG_W)
                var sum = 0.0
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        val c = px[row + x]
                        sum += 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
                    }
                }
                sig[gy * SIG_W + gx] = (sum / ((y1 - y0) * (x1 - x0))).toFloat()
            }
        }
        val mean = sig.average()
        val sd = sqrt(sig.sumOf { (it - mean) * (it - mean) } / sig.size)
        if (sd < FLAT_STDEV) return null
        for (i in sig.indices) sig[i] = ((sig[i] - mean) / sd).toFloat()
        return sig
    }

    /** Pearson correlation of two signatures, -1..1. */
    fun correlation(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return (s / a.size).toFloat()
    }

    enum class Verdict { MATCH, SHIFTED, DIFFERENT, UNSURE }

    /**
     * One own picture against the candidate's at the mapped slot ([same]) and the slots either side. Null = no
     * picture or a flat one. Only an unshifted match counts.
     */
    fun verdict(same: Float?, before: Float?, after: Float?): Verdict {
        if (same == null) return Verdict.UNSURE
        val nb = maxOf(before ?: -1f, after ?: -1f)
        return when {
            nb >= MATCH_MIN && nb > same + SHIFT_MARGIN -> Verdict.SHIFTED
            same >= MATCH_MIN -> Verdict.MATCH
            same <= DIFFERENT_MAX && nb < MATCH_MIN -> Verdict.DIFFERENT
            else -> Verdict.UNSURE
        }
    }

    /** What one compared pair does to the check. */
    enum class Outcome {
        /** Counts towards the matches needed. */
        PASS,
        /** Decides nothing: a spare pair is tried. */
        SKIP,
        /** Too alike to call different, too unlike to call the same: the borrow ends for this session. */
        FAIL,
        /** A clear mismatch on the same frame. */
        MISMATCH,
    }

    /** [gapUs]: own keyframe to borrowed keyframe on the own timeline. Only a same-frame pair can show a real mismatch. */
    fun outcome(same: Float?, before: Float?, after: Float?, gapUs: Long): Outcome {
        val undecided = if ((same ?: -1f) >= FAIL_MIN) Outcome.FAIL else Outcome.SKIP
        return when (verdict(same, before, after)) {
            Verdict.MATCH -> Outcome.PASS
            Verdict.SHIFTED, Verdict.DIFFERENT -> if (gapUs <= SAME_FRAME_US) Outcome.MISMATCH else undecided
            Verdict.UNSURE -> undecided
        }
    }
}
