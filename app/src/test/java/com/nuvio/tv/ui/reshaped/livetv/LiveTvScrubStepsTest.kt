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

    @Test fun aHeldRewindStopsAtTheStartOfAShow() {
        val before = LiveTvProgramme(title = "Film", startEpochMs = 40 * minute, stopEpochMs = 100 * minute)
        val schedule = listOf(before, show)
        // Inside a show it moves as far as the step goes.
        assertEquals(129 * minute, LiveTvScrubSteps.heldBackTo(schedule, 130 * minute, 129 * minute))
        // A step past the show's start stops on it, and stays there while the key is held.
        assertEquals(100 * minute, LiveTvScrubSteps.heldBackTo(schedule, 101 * minute, 99 * minute))
        assertEquals(100 * minute, LiveTvScrubSteps.heldBackTo(schedule, 100 * minute, 99 * minute))
        // Once a press has gone past it, holding goes on through the show before.
        assertEquals(98 * minute, LiveTvScrubSteps.heldBackTo(schedule, 99 * minute, 98 * minute))
        assertEquals(40 * minute, LiveTvScrubSteps.heldBackTo(schedule, 41 * minute, 10 * minute))
    }
}
