package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.math.roundToLong

/** Conservative constant-delay matcher for sparse embedded subtitle timelines. */
internal object AutoSyncSampledReferenceAligner {
    private const val MAX_OFFSET_MS = 180_000L
    private const val HISTOGRAM_BIN_MS = 250L
    private const val MAX_HISTOGRAM_BIN = (MAX_OFFSET_MS / HISTOGRAM_BIN_MS).toInt()
    private const val HISTOGRAM_SIZE = MAX_HISTOGRAM_BIN * 2 + 1
    private const val MATCH_TOLERANCE_MS = 1_250L
    private const val DISTINCT_OFFSET_MS = 3_000L
    private const val MIN_MATCHED_CUES = 8
    private const val MIN_COVERAGE = 0.68
    private const val MIN_MARGIN = 0.08
    private const val REQUIRED_SEGMENTS = 3
    private const val MAX_HISTOGRAM_CANDIDATES = 12

    fun findDelay(
        reference: List<SubtitleSyncCue>,
        target: List<SubtitleSyncCue>,
    ): AutoSyncDelayOnlyAlignment? {
        if (reference.size < MIN_MATCHED_CUES || target.size < MIN_MATCHED_CUES) return null
        val referenceStarts = sortedStarts(reference)
        val targetStarts = sortedStarts(target)
        val histogram = IntArray(HISTOGRAM_SIZE)
        val invBinMs = 1.0 / HISTOGRAM_BIN_MS.toDouble()

        var windowStart = 0
        val targetSize = targetStarts.size
        for (rIdx in referenceStarts.indices) {
            val referenceMs = referenceStarts[rIdx]
            val minimum = referenceMs - MAX_OFFSET_MS
            val maximum = referenceMs + MAX_OFFSET_MS
            while (windowStart < targetSize && targetStarts[windowStart] < minimum) {
                windowStart++
            }
            var index = windowStart
            while (index < targetSize && targetStarts[index] <= maximum) {
                val offset = referenceMs - targetStarts[index]
                val bin = (offset.toDouble() * invBinMs).roundToLong().toInt() + MAX_HISTOGRAM_BIN
                if (bin in 0 until HISTOGRAM_SIZE) {
                    histogram[bin]++
                }
                index++
            }
        }

        // Select top MAX_HISTOGRAM_CANDIDATES distinct bins by descending count without sorting empty bins.
        val nonZeroBins = IntArray(HISTOGRAM_SIZE)
        var nonZeroCount = 0
        for (b in 0 until HISTOGRAM_SIZE) {
            if (histogram[b] > 0) nonZeroBins[nonZeroCount++] = b
        }
        if (nonZeroCount == 0) return null

        val candidateSeeds = LongArray(MAX_HISTOGRAM_CANDIDATES)
        var seedCount = 0
        val used = BooleanArray(nonZeroCount)
        while (seedCount < MAX_HISTOGRAM_CANDIDATES) {
            var bestPos = -1
            var bestCount = 0
            for (i in 0 until nonZeroCount) {
                if (used[i]) continue
                val c = histogram[nonZeroBins[i]]
                if (c > bestCount) {
                    bestCount = c
                    bestPos = i
                }
            }
            if (bestPos < 0) break
            used[bestPos] = true
            val offset = (nonZeroBins[bestPos] - MAX_HISTOGRAM_BIN).toLong() * HISTOGRAM_BIN_MS
            var distinct = true
            for (j in 0 until seedCount) {
                if (abs(candidateSeeds[j] - offset) < DISTINCT_OFFSET_MS) {
                    distinct = false
                    break
                }
            }
            if (distinct) {
                candidateSeeds[seedCount++] = offset
            }
        }
        if (seedCount == 0) return null

        val scratchDeltas = LongArray(referenceStarts.size)
        val candidates = Array(seedCount) { idx ->
            evaluate(candidateSeeds[idx], referenceStarts, targetStarts, scratchDeltas)
        }
        candidates.sortByDescending { it.score }
        val best = candidates[0]
        var secondScore = 0.0
        for (i in 1 until candidates.size) {
            if (abs(candidates[i].offsetMs - best.offsetMs) >= DISTINCT_OFFSET_MS) {
                secondScore = candidates[i].score
                break
            }
        }
        val margin = best.score - secondScore

        if (best.matches < MIN_MATCHED_CUES || best.score < MIN_COVERAGE ||
            best.segments < REQUIRED_SEGMENTS || margin < MIN_MARGIN
        ) {
            return null
        }

        return AutoSyncDelayOnlyAlignment(
            offsetMs = best.offsetMs.toDouble(),
            score = best.score,
            margin = margin,
            segmentsPassed = best.segments,
        )
    }

    private fun sortedStarts(cues: List<SubtitleSyncCue>): LongArray {
        val starts = LongArray(cues.size)
        var sorted = true
        var prev = Long.MIN_VALUE
        for (i in cues.indices) {
            val s = cues[i].startTimeMs
            starts[i] = s
            if (s < prev) sorted = false
            prev = s
        }
        if (!sorted) starts.sort()
        return starts
    }

    private fun evaluate(
        seedOffsetMs: Long,
        reference: LongArray,
        target: LongArray,
        scratchDeltas: LongArray,
    ): Candidate {
        val initialCount = matchDeltas(seedOffsetMs, reference, target, scratchDeltas)
        if (initialCount == 0) return Candidate(seedOffsetMs, 0, 0.0, 0)
        scratchDeltas.sort(0, initialCount)
        val refinedOffset = scratchDeltas[initialCount / 2]

        val first = reference[0]
        val span = (reference[reference.lastIndex] - first).coerceAtLeast(1L)
        val spanPlusOne = span + 1L
        var availableTimeMask = 0
        for (rIdx in reference.indices) {
            val seg = ((reference[rIdx] - first) * 3L / spanPlusOne).toInt().coerceIn(0, 2)
            availableTimeMask = availableTimeMask or (1 shl seg)
        }
        var matchCount = 0
        var timeSegmentMask = 0
        var indexSegmentMask = 0
        var minimumTargetIndex = 0
        var searchStart = 0
        val targetSize = target.size
        val refSize = reference.size
        for (rIdx in reference.indices) {
            val referenceMs = reference[rIdx]
            val expectedTargetMs = referenceMs - refinedOffset
            val minTarget = expectedTargetMs - MATCH_TOLERANCE_MS
            val maxTarget = expectedTargetMs + MATCH_TOLERANCE_MS
            if (searchStart < minimumTargetIndex) searchStart = minimumTargetIndex
            while (searchStart < targetSize && target[searchStart] < minTarget) {
                searchStart++
            }
            var index = searchStart
            var bestIndex = -1
            var bestResidual = Long.MAX_VALUE
            while (index < targetSize && target[index] <= maxTarget) {
                val residual = abs((target[index] + refinedOffset) - referenceMs)
                if (residual < bestResidual) {
                    bestResidual = residual
                    bestIndex = index
                }
                index++
            }
            if (bestIndex >= 0) {
                matchCount++
                val timeSeg = ((referenceMs - first) * 3L / spanPlusOne).toInt().coerceIn(0, 2)
                timeSegmentMask = timeSegmentMask or (1 shl timeSeg)
                val idxSeg = (rIdx * 3 / refSize).coerceIn(0, 2)
                indexSegmentMask = indexSegmentMask or (1 shl idxSeg)
                minimumTargetIndex = bestIndex + 1
            }
        }
        val score = matchCount.toDouble() / reference.size.toDouble()
        // If the sparse reference itself has a silent middle third (fewer than 3 populated time
        // thirds), accept when every populated time third AND all 3 cue-index thirds matched.
        val timeSegments = timeSegmentMask.countOneBits()
        val availableTimeSegments = availableTimeMask.countOneBits()
        val segments = if (availableTimeSegments < REQUIRED_SEGMENTS && timeSegments == availableTimeSegments) {
            indexSegmentMask.countOneBits()
        } else {
            timeSegments
        }
        return Candidate(refinedOffset, matchCount, score, segments)
    }

    private fun matchDeltas(
        offsetMs: Long,
        reference: LongArray,
        target: LongArray,
        outDeltas: LongArray,
    ): Int {
        var count = 0
        var minimumTargetIndex = 0
        var searchStart = 0
        val targetSize = target.size
        for (rIdx in reference.indices) {
            val referenceMs = reference[rIdx]
            val expectedTargetMs = referenceMs - offsetMs
            val minTarget = expectedTargetMs - MATCH_TOLERANCE_MS
            val maxTarget = expectedTargetMs + MATCH_TOLERANCE_MS
            if (searchStart < minimumTargetIndex) searchStart = minimumTargetIndex
            while (searchStart < targetSize && target[searchStart] < minTarget) {
                searchStart++
            }
            var index = searchStart
            var bestIndex = -1
            var bestResidual = Long.MAX_VALUE
            while (index < targetSize && target[index] <= maxTarget) {
                val residual = abs((target[index] + offsetMs) - referenceMs)
                if (residual < bestResidual) {
                    bestResidual = residual
                    bestIndex = index
                }
                index++
            }
            if (bestIndex >= 0) {
                outDeltas[count++] = referenceMs - target[bestIndex]
                minimumTargetIndex = bestIndex + 1
            }
        }
        return count
    }

    private data class Candidate(
        val offsetMs: Long,
        val matches: Int,
        val score: Double,
        val segments: Int,
    )
}
