package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.*
import org.junit.Test

class PreviewWorkBudgetTest {
    @Test fun measuredCostControlsTheRestBetweenBackgroundJobs() {
        val small = PreviewWorkBudget(true)
        val strong = PreviewWorkBudget(false)
        for (budget in listOf(small, strong)) {
            budget.observe(0L, true, false, 20_000L, 0)
            budget.completed(100L, 50L, true)
        }
        assertEquals(450L, small.remaining(100L))
        assertEquals(150L, strong.remaining(100L))
    }

    @Test fun aDrainingBufferReducesWorkBeforeAStall() {
        val budget = PreviewWorkBudget(true)
        budget.observe(0L, true, false, 20_000L, 0)
        budget.observe(1_000L, true, false, 18_000L, 0)
        budget.completed(1_000L, 50L, true)
        assertEquals(900L, budget.remaining(1_000L))
    }

    @Test fun dropsPauseBackgroundWorkButPauseReleasesTheCpuForPreviews() {
        val budget = PreviewWorkBudget(true)
        budget.observe(0L, true, false, 20_000L, 0)
        budget.observe(100L, true, false, 20_000L, 1)
        assertEquals(10_000L, budget.remaining(100L))
        budget.observe(200L, false, false, 20_000L, 1)
        assertEquals(0L, budget.remaining(200L))
    }

    @Test fun counterResetsAndSeekDropsDoNotCauseFalseHolds() {
        val budget = PreviewWorkBudget(true)
        budget.observe(0L, true, false, 20_000L, 100)
        budget.observe(100L, true, false, 20_000L, 0)
        budget.observe(200L, true, false, 20_000L, 5, excluded = true)
        assertEquals(0L, budget.remaining(200L))
    }
}
