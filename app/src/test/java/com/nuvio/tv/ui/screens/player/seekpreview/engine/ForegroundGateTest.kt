// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thumbnails wait while the app is not in front, and pick up where they were when it comes back. */
class ForegroundGateTest {
    @Test fun homeDuringATitleMakesThumbnailsWait() {
        assertTrue(ForegroundGate.waits(hostResumed = false, playerClosed = false))
        assertFalse(ForegroundGate.waits(hostResumed = true, playerClosed = false))
    }

    @Test fun thePassThatFinishesAfterThePlayerClosedCarriesOn() {
        assertFalse(ForegroundGate.waits(hostResumed = false, playerClosed = true))
    }

    @Test fun timeAwayIsNotAStallOnTheGeneratingScreen() {
        val started = 100_000L
        val lastPicture = 110_000L
        val home = lastPicture + 5_000L
        val back = home + 5 * 60_000L
        fun reason(startedAt: Long, progressAt: Long) = PrepareHandover.reason(
            nowMs = back, startedAtMs = startedAt, lastProgressAtMs = progressAt, leftS = 60,
            done = 20, total = 105, finished = false, rateLimited = false,
        )

        assertEquals(PrepareHandover.Reason.STALLED, reason(started, lastPicture))
        assertNull(reason(ForegroundGate.shifted(started, home, back), ForegroundGate.shifted(lastPicture, home, back)))
        assertEquals(lastPicture + 5 * 60_000L, ForegroundGate.shifted(lastPicture, home, back))
    }

    @Test fun awayCountsFromHomeEvenWhenTheWaitStartsLater() {
        val lastPicture = 110_000L
        val home = 120_000L
        val back = home + 60_000L
        // The worker only noticed 12 s after Home (a download under way): those 12 s are time away too.
        assertEquals(lastPicture + 60_000L, ForegroundGate.shifted(lastPicture, home, back))
        assertNull(PrepareHandover.reason(
            nowMs = back, startedAtMs = 100_000L, lastProgressAtMs = ForegroundGate.shifted(lastPicture, home, back),
            leftS = 60, done = 20, total = 105, finished = false, rateLimited = false,
        ))
    }

    @Test fun aClockSetWhileAwayStartsAgainOnReturn() {
        assertEquals(180_000L, ForegroundGate.shifted(150_000L, 120_000L, 180_000L))
    }

    @Test fun anUnsetClockStaysUnset() {
        assertEquals(0L, ForegroundGate.shifted(0L, 1_000L, 30_000L))
        assertEquals(5_000L, ForegroundGate.shifted(5_000L, 30_000L, 30_000L))
    }
}
