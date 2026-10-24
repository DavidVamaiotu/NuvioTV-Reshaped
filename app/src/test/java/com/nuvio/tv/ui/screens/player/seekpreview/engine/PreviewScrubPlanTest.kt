package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.*
import org.junit.Test

class PreviewScrubPlanTest {
    @Test fun reverseScrubbingPrefetchesBehindTheTargetFirst() {
        val plan = PreviewScrubPlan()
        plan.target(10, 0L)
        plan.target(9, 20L)
        assertEquals(listOf(9, 8, 10, 7, 11), plan.neighbors(9, 20, 2))
        plan.target(10, 30L)
        assertEquals(listOf(10, 11, 9, 12, 8), plan.neighbors(10, 20, 2))
    }

    @Test fun imageRefreshesDoNotPostponeSettlement() {
        val plan = PreviewScrubPlan(120L)
        plan.target(2, 0L)
        assertEquals(0L, plan.remaining(0L))
        plan.target(3, 10L)
        plan.target(3, 80L)
        assertEquals(40L, plan.remaining(90L))
        assertEquals(0L, plan.remaining(130L))
        plan.direction(0)
        assertEquals(0L, plan.remaining(100L))
    }

    @Test fun titleBoundariesNeverProduceInvalidNeighbors() {
        val plan = PreviewScrubPlan()
        plan.direction(-1)
        assertEquals(listOf(0, 1, 2), plan.neighbors(0, 3, 3))
        assertTrue(plan.neighbors(-1, 3, 3).isEmpty())
    }

    @Test fun copySeeksJumpOverUnfinishedBackgroundCoverage() {
        val plan = PreviewScrubPlan()
        assertEquals(700, plan.next(700, 720, 0L) { true })
        val saved = setOf(700, 701)
        assertEquals(699, plan.next(700, 720, 1L) { it !in saved })
        // A jump back supersedes the previous landing; refreshing it cannot extend settlement.
        assertNull(plan.next(200, 720, 10L) { true })
        assertNull(plan.next(200, 720, 100L) { true })
        assertEquals(200, plan.next(200, 720, 130L) { true })
        assertNull(plan.next(200, 720, 131L) { false })
    }
}
