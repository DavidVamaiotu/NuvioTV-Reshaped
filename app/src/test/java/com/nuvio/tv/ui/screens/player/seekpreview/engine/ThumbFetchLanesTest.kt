// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How many keyframes are fetched at once, and which ones start next. */
class ThumbFetchLanesTest {
    private fun lanes(phase: FetchPhase, budget: Int = ThumbFetchLanes.MAX, small: Boolean = false, rl: Boolean = false) =
        ThumbFetchLanes.limit(phase, rl, small, budget)

    @Test fun otherServersFetchEightBeforePlayAndThreeWhilePlaying() {
        assertEquals(8, lanes(FetchPhase.GENERATING))
        assertEquals(8, lanes(FetchPhase.PLAYER_CLOSED))
        assertEquals(3, lanes(FetchPhase.PLAYBACK))
    }

    @Test fun theLedgerBudgetCapsEveryPhase() {
        assertEquals(4, lanes(FetchPhase.GENERATING, budget = 4))
        assertEquals(4, lanes(FetchPhase.PLAYER_CLOSED, budget = 4))
        assertEquals(3, lanes(FetchPhase.PLAYBACK, budget = 4))
        assertEquals(2, lanes(FetchPhase.PLAYBACK, budget = 2))
        assertEquals(1, lanes(FetchPhase.GENERATING, budget = 1))
        assertEquals(8, lanes(FetchPhase.GENERATING, budget = ConnectionLedger.UNLIMITED))
    }

    @Test fun aRateLimitMeansOneAtATimeEverywhere() {
        for (phase in FetchPhase.values()) for (small in listOf(false, true)) for (budget in listOf(1, 4, 8)) {
            assertEquals(1, lanes(phase, budget = budget, small = small, rl = true))
        }
    }

    @Test fun smallBox4K() {
        assertEquals(2, lanes(FetchPhase.GENERATING, small = true))
        assertEquals(2, lanes(FetchPhase.PLAYER_CLOSED, small = true))
        assertEquals(1, lanes(FetchPhase.PLAYBACK, small = true))
        assertEquals(1, lanes(FetchPhase.PLAYBACK, budget = 4, small = true))
    }

    @Test fun alwaysBetweenOneAndMax() {
        for (phase in FetchPhase.values()) for (rl in listOf(false, true)) for (small in listOf(false, true)) {
            for (budget in -1..10) {
                val n = ThumbFetchLanes.limit(phase, rl, small, budget)
                assertTrue("$phase $rl $small $budget -> $n", n in 1..ThumbFetchLanes.MAX)
            }
        }
    }

    @Test fun pickKeepsTheOrderAndSkipsWhatIsUnderWay() {
        assertEquals(listOf(7, 3), ThumbFetchLanes.pick(listOf(7, 5, 3, 9), underWay = setOf(5), room = 2))
    }

    @Test fun pickTakesEachKeyframeOnce() {
        // Long GOPs: neighbouring slots share a keyframe.
        assertEquals(listOf(4, 6, 8), ThumbFetchLanes.pick(listOf(4, 4, 6, 4, 8), underWay = emptySet(), room = 3))
    }

    @Test fun pickWithoutRoomStartsNothing() {
        assertEquals(emptyList<Int>(), ThumbFetchLanes.pick(listOf(1, 2), underWay = emptySet(), room = 0))
        assertEquals(emptyList<Int>(), ThumbFetchLanes.pick(listOf(1, 2), underWay = emptySet(), room = -1))
        assertEquals(emptyList<Int>(), ThumbFetchLanes.pick(listOf(1, 2), underWay = setOf(1, 2), room = 4))
    }

    @Test fun pickIgnoresMissingKeyframes() {
        assertEquals(listOf(2), ThumbFetchLanes.pick(listOf(-1, 2), underWay = emptySet(), room = 2))
    }

    @Test fun theSettleTargetGoesFirst() {
        // The worker lists the settle target, then its neighbours, then the coverage pass.
        val p0 = 40
        val neighbours = listOf(41, 39, 42)
        val lattice = listOf(10, 20, 30)
        assertEquals(listOf(p0, 41, 39, 42), ThumbFetchLanes.pick(listOf(p0) + neighbours + lattice, emptySet(), room = 4))
        assertEquals(listOf(41, 39), ThumbFetchLanes.pick(listOf(p0) + neighbours + lattice, setOf(p0, 42), room = 2))
    }

    @Test fun aSlowRequestNoLongerHoldsUpTheOnesBehindIt() {
        val st = mapOf(720 to ThumbFetchLanes.State.RUNNING, 730 to ThumbFetchLanes.State.RUNNING,
            740 to ThumbFetchLanes.State.READY, 750 to ThumbFetchLanes.State.READY)
        assertEquals(740, ThumbFetchLanes.nextReady(listOf(720, 730, 740, 750)) { st.getValue(it) })
    }

    @Test fun theHeadComesFirstOnceItIsInOrNotStarted() {
        val ready = { _: Int -> ThumbFetchLanes.State.READY }
        assertEquals(720, ThumbFetchLanes.nextReady(listOf(720, 730), ready))
        val failed = mapOf(720 to ThumbFetchLanes.State.FAILED, 730 to ThumbFetchLanes.State.READY)
        assertEquals(720, ThumbFetchLanes.nextReady(listOf(720, 730)) { failed.getValue(it) })
        val fresh = mapOf(720 to ThumbFetchLanes.State.NOT_STARTED, 730 to ThumbFetchLanes.State.READY)
        assertEquals(720, ThumbFetchLanes.nextReady(listOf(720, 730)) { fresh.getValue(it) })
    }

    @Test fun nothingBehindASlowHeadIsInSoTheWorkerWaits() {
        val st = mapOf(720 to ThumbFetchLanes.State.RUNNING, 730 to ThumbFetchLanes.State.RUNNING,
            740 to ThumbFetchLanes.State.NOT_STARTED)
        assertEquals(-1, ThumbFetchLanes.nextReady(listOf(720, 730, 740)) { st.getValue(it) })
        assertEquals(-1, ThumbFetchLanes.nextReady(emptyList()) { ThumbFetchLanes.State.READY })
    }
}
