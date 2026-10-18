// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

/**
 * Seek grid stepping. Slots are spacingMs wide, each served by one keyframe (slotPtsMs, -1 when unknown) nearest the
 * slot centre.
 */
internal object SeekGrid {
    /** The adjacent grid point from [baseMs] in the direction of [deltaMs]. */
    fun gridPoint(baseMs: Long, deltaMs: Long, spacingMs: Long): Long {
        val raw = baseMs + deltaMs
        return if (deltaMs > 0) {
            val g = Math.floorDiv(raw, spacingMs) * spacingMs
            if (g > baseMs) g else g + spacingMs
        } else {
            val g = -Math.floorDiv(-raw, spacingMs) * spacingMs
            if (g < baseMs) g else g - spacingMs
        }
    }

    /**
     * [gridMs] moved on by whole slots until its slot's keyframe lies past both the base and the base slot's own
     * keyframe. Without this a back step from mid-slot lands on the same keyframe again. Unchanged when no slot
     * qualifies.
     */
    fun stepPastBase(gridMs: Long, baseMs: Long, forward: Boolean, spacingMs: Long, slotCount: Int,
                     slotPtsMs: (Int) -> Long): Long {
        if (slotCount <= 0) return gridMs
        fun slotOf(ms: Long) = (ms / spacingMs).toInt().coerceIn(0, slotCount - 1)
        val basePts = slotPtsMs(slotOf(baseMs))
        val bound = when {
            basePts < 0 -> baseMs
            forward -> maxOf(baseMs, basePts)
            else -> minOf(baseMs, basePts)
        }
        val first = slotOf(gridMs)
        var slot = first
        while (slot in 0 until slotCount) {
            val pts = slotPtsMs(slot)
            if (pts >= 0 && (if (forward) pts > bound else pts < bound)) {
                return if (slot == first) gridMs else slot.toLong() * spacingMs
            }
            slot += if (forward) 1 else -1
        }
        return gridMs
    }

    /**
     * Coverage-pass order: first the finest-stride slots around [anchorSlot] (the playback position), nearest first
     * and ahead before behind, then the whole title coarse to fine ([strides]). With [gridFirst] above 0 the sweeps
     * down to that stride come before the slots around the anchor: the generating screen's grid first, the fine
     * detail near the start once playback runs.
     */
    fun latticeOrder(
        slotCount: Int, strides: List<Int>, anchorSlot: Int, beforeSlots: Int, afterSlots: Int, gridFirst: Int = 0,
    ): List<Int> {
        if (slotCount <= 0 || strides.isEmpty()) return emptyList()
        val fine = strides.last().coerceAtLeast(1)
        val seen = HashSet<Int>()
        val out = ArrayList<Int>()
        fun sweep(stride: Int) {
            var s = 0
            while (s < slotCount) {
                if (seen.add(s)) out += s
                s += stride
            }
        }
        val anchor = anchorSlot.coerceIn(0, slotCount - 1)
        val first = maxOf(0, anchor - beforeSlots)
        val last = minOf(slotCount - 1, anchor + afterSlots)
        val local = ArrayList<Int>()
        var sl = (first + fine - 1) / fine * fine
        while (sl <= last) {
            local += sl
            sl += fine
        }
        local.sortWith(compareBy<Int> { kotlin.math.abs(it - anchor) }.thenBy { if (it >= anchor) 0 else 1 })
        if (gridFirst > 0) for (stride in strides) if (stride >= gridFirst) sweep(stride)
        for (s in local) if (seen.add(s)) out += s
        for (stride in strides) sweep(stride)
        return out
    }
}
