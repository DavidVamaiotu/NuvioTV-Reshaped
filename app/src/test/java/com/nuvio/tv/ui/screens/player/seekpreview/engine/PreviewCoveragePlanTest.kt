package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.*
import org.junit.Test

class PreviewCoveragePlanTest {
    @Test fun aLongMovieGetsBothEdgesAndDistributedCoverageBeforeLocalDetail() {
        val count = 1080 // three hours, ten-second slots
        val order = PreviewCoveragePlan.order(count, listOf(30, 6, 3, 1), 0, 60, 120)
        assertEquals(0, order.first())
        assertTrue(order.take(3).contains(count - 1))
        val early = order.take(12)
        for (quarter in 0..3) assertTrue(early.any { it in quarter * 270 until (quarter + 1) * 270 })
        // Every minute, plus the title edge, before the fine local window.
        val coverage = ((0 until count step 6).toList() + (count - 1)).toSet()
        assertEquals(coverage, order.take(coverage.size).toSet())
        assertEquals((0 until count).toSet(), order.toSet())
        assertEquals(count, order.size)
    }

    @Test fun resumeGetsItsFirstFrameWithoutStarvingTheRestOfTheTitle() {
        val order = PreviewCoveragePlan.order(874, listOf(30, 12, 6, 3), 451, 60, 120)
        assertEquals(listOf(451, 0, 873), order.take(3))
        assertEquals(1, order.count { it == 451 })
        assertTrue(order.take(12).any { it > 750 })
        assertTrue(order.take(12).any { it in 100..300 })
    }

    @Test fun sparseAndShortTitlesKeepTheirBoundedGridAndReachTheEnding() {
        for (count in listOf(1, 2, 5, 100, 17280)) {
            val order = PreviewCoveragePlan.order(count, listOf(30, 12, 6), 0, 60, 120)
            val expected = ((0 until count step 30) + (0 until count step 12) +
                (0 until count step 6) + (count - 1)).toSet()
            assertEquals(expected, order.toSet())
            assertEquals(expected.size, order.size)
        }
        assertTrue(PreviewCoveragePlan.order(0, listOf(6), 0, 0, 0).isEmpty())
    }

    @Test fun exhaustedBackgroundAllowanceStillAdmitsLaterSeeksAtABoundedRate() {
        val budget = PreviewRequestWindow(allowance = 3, windowMs = 60_000)
        assertEquals(0L, budget.waitMs(100_000, 1250))
        assertEquals(0L, budget.waitMs(101_000, 1252))
        assertEquals(58_000L, budget.waitMs(102_000, 1253))
        // Repeated refreshes cannot extend the window or refill it early.
        assertEquals(10_000L, budget.waitMs(150_000, 1253))
        assertEquals(0L, budget.waitMs(160_000, 1253))
        assertEquals(59_000L, budget.waitMs(161_000, 1256))
    }

    @Test fun aFailedSectionCanBeRetriedWithoutRestartingPlaybackOrLoopingOnRefreshes() {
        val attempts = PreviewFrameAttempts()
        attempts.started(50, 0)
        assertTrue(attempts.available(50, 1))
        attempts.started(50, 1000)
        assertFalse(attempts.available(50, 20_000, foreground = true))
        assertFalse(attempts.available(50, 40_000))
        assertTrue(attempts.available(50, 31_000, foreground = true))
        attempts.started(50, 31_000)
        assertEquals(1, attempts.count(50))
        assertTrue(attempts.available(80, 31_000)) // another section has its own budget
    }

    @Test fun supersededOrMemoryDeferredFramesDoNotSpendDecodeAttempts() {
        val attempts = PreviewFrameAttempts()
        repeat(20) { attempts.started(9, it.toLong()); attempts.postponed(9) }
        assertEquals(0, attempts.count(9))
        assertTrue(attempts.available(9, 100))
    }
}
