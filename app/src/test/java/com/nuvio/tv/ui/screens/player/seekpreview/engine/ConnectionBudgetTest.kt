// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thumbnail connections on top of playback's on one server, and how that level moves and is kept. */
class ConnectionBudgetTest {
    private val day = 24L * 60 * 60 * 1000

    /** Clean, busy fetches until the level steps up (or [max] tries); returns the time of the last one. */
    private fun climb(b: ConnectionBudget, from: Long, playback: Int = 2, generating: Boolean = false, max: Int = 8): Long {
        var t = from
        repeat(max) {
            t += 10
            if (b.succeeded(t, t, healthy = true, busy = true, playback = playback, generating = generating)) return t
        }
        return t
    }

    @Test fun anUnknownServerStartsOneAbovePlayback() {
        val b = ConnectionBudget(knownTotal = null)
        assertEquals(1, b.level(2, generating = false))
        assertEquals(1, b.thumbSlots(playback = 2, live = 2, quiet = false, generating = false))
        assertEquals(3, b.thumbSlots(playback = 2, live = 0, quiet = false, generating = false))
    }

    @Test fun neverLessThanOneAbovePlayback() {
        val b = ConnectionBudget(knownTotal = null)
        assertEquals(1, b.thumbSlots(playback = 2, live = 5, quiet = false, generating = false))
        var t = 0L
        repeat(6) { b.refused(Refusal.TOO_MANY, t + 1, t + 2, 2, false); t += 10 }
        assertEquals(1, b.level(2, false))
        assertEquals(1, b.thumbSlots(playback = 2, live = 2, quiet = false, generating = false))
    }

    @Test fun torboxGetsWhatPlaybackLeavesOfFour() {
        val b = ConnectionBudget(knownTotal = 4)
        assertEquals(3, b.thumbSlots(playback = 1, live = 1, quiet = false, generating = false))
        assertEquals(2, b.thumbSlots(playback = 2, live = 2, quiet = false, generating = false))
        assertEquals(0, b.thumbSlots(playback = 4, live = 4, quiet = false, generating = false))
        assertEquals(4, b.thumbSlots(playback = 4, live = 0, quiet = false, generating = false))
    }

    @Test fun torboxNeverNoneWhilePausedOrGenerating() {
        val b = ConnectionBudget(knownTotal = 4)
        assertEquals(1, b.thumbSlots(playback = 4, live = 4, quiet = true, generating = false))
        assertEquals(1, b.thumbSlots(playback = 4, live = 4, quiet = true, generating = true))
    }

    @Test fun stepsUpAfterEightBusyCleanFetchesUpToFour() {
        val b = ConnectionBudget(knownTotal = null)
        var t = 0L
        for (expected in 2..4) {
            val before = t
            t = climb(b, t)
            assertEquals(before + 8 * 10, t)
            assertEquals(expected, b.level(2, false))
            t += 30_000
        }
        climb(b, t, max = 20)
        assertEquals(4, b.level(2, false))
    }

    @Test fun idleFetchesDoNotCount() {
        val b = ConnectionBudget(knownTotal = null)
        repeat(20) { assertFalse(b.succeeded(it.toLong(), it.toLong(), healthy = true, busy = false, playback = 2, generating = false)) }
        repeat(20) { assertFalse(b.succeeded(100L + it, 100L + it, healthy = false, busy = true, playback = 2, generating = false)) }
        assertEquals(1, b.level(2, false))
    }

    @Test fun theGeneratingScreenStartsHigherStepsFasterAndGoesFurther() {
        val b = ConnectionBudget(knownTotal = null)
        assertEquals(2, b.level(2, generating = true))
        var t = 0L
        for (expected in 3..6) {
            t = climb(b, t, generating = true, max = 4)
            assertEquals(expected, b.level(2, generating = true))
            t += 30_000
        }
        climb(b, t, generating = true, max = 20)
        assertEquals(6, b.level(2, generating = true))
        assertEquals(4, b.level(2, generating = false))
    }

    @Test fun aClearRefusalSoonAfterAStepUpGoesBackAndKeepsIt() {
        val b = ConnectionBudget(knownTotal = null)
        val stepAt = climb(b, 0L)
        assertEquals(2, b.level(2, false))
        assertTrue(b.refused(Refusal.TOO_MANY, stepAt + 100, stepAt + 5_000, 2, false))
        assertEquals(1, b.level(2, false))
        assertEquals(1, b.cap)
        assertEquals(ConnectionBudget.Remembered(1, true, 77L), b.toSave(stepAt + 5_000, 77L))
        climb(b, stepAt + 60_000, max = 30)
        assertEquals(1, b.level(2, false))
    }

    @Test fun twoUnavailablesInARowAreClear() {
        val b = ConnectionBudget(knownTotal = null)
        val stepAt = climb(b, 0L)
        assertTrue(b.refused(Refusal.UNAVAILABLE, stepAt + 100, stepAt + 1_000, 2, false))
        assertEquals(1, b.level(2, false))
        assertNull(b.toSave(stepAt + 1_000, 1L))
        assertNull(b.cap)
        assertTrue(b.refused(Refusal.UNAVAILABLE, stepAt + 1_500, stepAt + 1_600, 2, false))
        assertEquals(1, b.cap)
        assertEquals(ConnectionBudget.Remembered(1, true, 1L), b.toSave(stepAt + 1_600, 1L))
    }

    @Test fun aSuccessBreaksTheUnavailableRun() {
        val b = ConnectionBudget(knownTotal = null)
        val stepAt = climb(b, 0L)
        b.refused(Refusal.UNAVAILABLE, stepAt + 100, stepAt + 1_000, 2, false)
        b.succeeded(stepAt + 2_000, stepAt + 2_000, healthy = true, busy = true, playback = 2, generating = false)
        b.refused(Refusal.UNAVAILABLE, stepAt + 2_500, stepAt + 2_600, 2, false)
        assertNull(b.cap)
    }

    @Test fun aConnectionErrorOnlySlowsThisSession() {
        val b = ConnectionBudget(null, ConnectionBudget.Remembered(3, capped = false, savedAtMs = 0L), wallNowMs = day)
        assertEquals(3, b.level(2, false))
        assertTrue(b.refused(Refusal.CONNECTION, 10L, 20L, 2, false))
        assertEquals(2, b.level(2, false))
        assertNull(b.cap)
        assertNull(b.toSave(1_000_000L, day))
        val stepAt = climb(b, 100L)
        assertTrue(b.refused(Refusal.CONNECTION, stepAt + 1, stepAt + 2, 2, false))
        assertNull(b.toSave(stepAt + 1_000_000L, day))
    }

    @Test fun aRefusalAtTheStartingLevelIsNotKept() {
        val b = ConnectionBudget(null, ConnectionBudget.Remembered(3, capped = false, savedAtMs = 0L), wallNowMs = day)
        assertTrue(b.refused(Refusal.TOO_MANY, 10L, 20L, 2, false))
        assertEquals(2, b.level(2, false))
        assertNull(b.cap)
        assertNull(b.toSave(1_000_000L, day))
        climb(b, 100L, max = 30)
        assertEquals(2, b.level(2, false))
    }

    @Test fun requestsTurnedAwayTogetherCountOnce() {
        val b = ConnectionBudget(knownTotal = 4)
        assertEquals(3, b.level(1, false))
        assertTrue(b.refused(Refusal.CONNECTION, 1_000L, 2_000L, 1, false))
        assertFalse(b.refused(Refusal.CONNECTION, 1_100L, 2_050L, 1, false))
        assertFalse(b.refused(Refusal.CONNECTION, 2_000L, 2_100L, 1, false))
        assertEquals(2, b.level(1, false))
    }

    @Test fun playbackTroubleSoonAfterAStepUpGoesBackForTheSession() {
        val b = ConnectionBudget(knownTotal = null)
        val stepAt = climb(b, 10_000L)
        assertTrue(b.playbackTrouble(null, stepAt + 15_000, stepAt + 15_100, 2, false))
        assertEquals(1, b.level(2, false))
        assertNull(b.cap)
        assertNull(b.toSave(stepAt + 1_000_000L, 1L))
        climb(b, stepAt + 60_000, max = 30)
        assertEquals(1, b.level(2, false))
    }

    @Test fun playbackTurnedAwayWithTooManySoonAfterAStepUpIsKept() {
        val b = ConnectionBudget(knownTotal = null)
        val stepAt = climb(b, 10_000L)
        assertTrue(b.playbackTrouble(Refusal.TOO_MANY, stepAt + 1_000, stepAt + 1_000, 2, false))
        assertEquals(1, b.cap)
    }

    @Test fun playbackTroubleLongAfterAStepUpOrBeforeItIsNotOurs() {
        val b = ConnectionBudget(knownTotal = null)
        assertFalse(b.playbackTrouble(null, 500L, 500L, 2, false))
        val stepAt = climb(b, 10_000L)
        assertFalse(b.playbackTrouble(null, stepAt - 1_000L, stepAt + 1_000L, 2, false))
        assertFalse(b.playbackTrouble(null, stepAt + ConnectionBudget.TROUBLE_WINDOW_MS + 1, stepAt + 30_000L, 2, false))
        assertEquals(2, b.level(2, false))
    }

    @Test fun aStepUpIsKeptOnlyOnceItHasSettled() {
        val b = ConnectionBudget(knownTotal = null)
        val stepAt = climb(b, 0L)
        assertNull(b.toSave(stepAt + 1_000L, 5L))
        assertEquals(ConnectionBudget.Remembered(2, false, 5L), b.toSave(stepAt + ConnectionBudget.TROUBLE_WINDOW_MS, 5L))
        assertNull(b.toSave(stepAt + 60_000L, 5L))
    }

    @Test fun theLevelIsKeptRelativeToPlayback() {
        val learned = ConnectionBudget(null, ConnectionBudget.Remembered(2, capped = true, savedAtMs = 0L), wallNowMs = 1L)
        assertEquals(3, learned.thumbSlots(playback = 1, live = 0, quiet = false, generating = false))
        assertEquals(6, learned.thumbSlots(playback = 4, live = 0, quiet = false, generating = false))
        assertEquals(2, learned.thumbSlots(playback = 4, live = 4, quiet = false, generating = false))
    }

    @Test fun aCapHoldsForADayThenGetsOneMoreTry() {
        val fresh = ConnectionBudget(null, ConnectionBudget.Remembered(2, capped = true, savedAtMs = 0L), wallNowMs = day - 1)
        climb(fresh, 0L, max = 40)
        assertEquals(2, fresh.level(2, false))

        val due = ConnectionBudget(null, ConnectionBudget.Remembered(2, capped = true, savedAtMs = 0L), wallNowMs = day)
        val stepAt = climb(due, 0L)
        assertEquals(3, due.level(2, false))
        climb(due, stepAt + 30_000, max = 40)
        assertEquals(3, due.level(2, false))
        assertEquals(ConnectionBudget.Remembered(3, true, 9L), due.toSave(stepAt + ConnectionBudget.TROUBLE_WINDOW_MS, 9L))
    }

    @Test fun aFailedRetryKeepsTheOldCapAgain() {
        val due = ConnectionBudget(null, ConnectionBudget.Remembered(2, capped = true, savedAtMs = 0L), wallNowMs = day)
        val stepAt = climb(due, 0L)
        due.refused(Refusal.TOO_MANY, stepAt + 1, stepAt + 500, 2, false)
        assertEquals(2, due.level(2, false))
        assertEquals(2, due.cap)
        assertEquals(ConnectionBudget.Remembered(2, true, 9L), due.toSave(stepAt + 500, 9L))
    }

    @Test fun aGeneratingStepAboveFourIsKeptAsFour() {
        val b = ConnectionBudget(knownTotal = null)
        var t = 0L
        repeat(4) { t = climb(b, t, generating = true, max = 4) + 30_000 }
        assertEquals(6, b.level(2, true))
        assertEquals(4, b.toSave(t, 1L)?.extra)
    }

    @Test fun memoryRoundTripsAndForgetsAfterThirtyDays() {
        val now = 1_000L * day
        val r = ConnectionBudget.Remembered(extra = 2, capped = true, savedAtMs = now - 29 * day)
        assertEquals(r, ConnectionMemory.decode(ConnectionMemory.encode(r), now))
        val open = ConnectionBudget.Remembered(extra = 4, capped = false, savedAtMs = now)
        assertEquals(open, ConnectionMemory.decode(ConnectionMemory.encode(open), now))
        assertNull(ConnectionMemory.decode(ConnectionMemory.encode(r.copy(savedAtMs = now - 30 * day)), now))
        assertNull(ConnectionMemory.decode(ConnectionMemory.encode(r.copy(savedAtMs = now + day)), now))
    }

    @Test fun memoryRejectsDamagedEntries() {
        for (bad in listOf(null, "", "x", "1,1", "9,0,5", "-1,0,5", "1,2,5", "1,1,x", "1,1,3,4")) {
            assertNull(bad, ConnectionMemory.decode(bad, 10L))
        }
    }
}
