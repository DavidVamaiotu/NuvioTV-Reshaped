package com.nuvio.tv.ui.reshaped.livetv

import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTvScrubStepsTest {
    private val minute = 60_000L
    private val show = LiveTvProgramme(title = "News", startEpochMs = 100 * minute, stopEpochMs = 160 * minute)

    @Test fun theBarStartsAtTheShowOnAtThePlayhead() {
        assertEquals(100 * minute, LiveTvScrubSteps.rangeStart(listOf(show), 130 * minute, 0L))
    }

    @Test fun withoutAGuideTheBarReachesBackTwoHours() {
        assertEquals(10 * minute, LiveTvScrubSteps.rangeStart(emptyList(), 130 * minute, 0L))
    }

    @Test fun theBarNeverStartsBeforeWhatTheProviderKeeps() {
        assertEquals(120 * minute, LiveTvScrubSteps.rangeStart(listOf(show), 130 * minute, 120 * minute))
    }

    @Test fun heldKeysMoveLikeThePlayer() {
        assertEquals(10_000L, LiveTvScrubSteps.stepMs(0))
        assertEquals(minute, LiveTvScrubSteps.stepMs(100))
    }
}
