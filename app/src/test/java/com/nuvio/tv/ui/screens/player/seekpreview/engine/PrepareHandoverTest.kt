// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import com.nuvio.tv.ui.screens.player.seekpreview.engine.PrepareHandover.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the generating screen stops waiting and playback starts. */
class PrepareHandoverTest {
    private fun reason(
        sinceStartMs: Long = 2_000L,
        sinceProgressMs: Long = 1_000L,
        leftS: Int = 90,
        done: Int = 10,
        total: Int = 105,
        finished: Boolean = false,
        rateLimited: Boolean = false,
    ): Reason? {
        val now = 100_000L
        return PrepareHandover.reason(now, now - sinceStartMs, now - sinceProgressMs, leftS, done, total, finished, rateLimited)
    }

    @Test fun aHealthySourceKeepsTheScreenUp() {
        assertNull(reason())
        assertNull(reason(sinceStartMs = 60_000L, sinceProgressMs = 19_999L, leftS = 240))
    }

    @Test fun aRateLimitedSourceStartsPlaybackAtOnce() {
        assertEquals(Reason.RATE_LIMITED, reason(rateLimited = true, sinceStartMs = 0L, sinceProgressMs = 0L, done = 0))
        assertEquals(Reason.RATE_LIMITED, reason(rateLimited = true, sinceProgressMs = 25_000L))
    }

    @Test fun noProgressForTwentySecondsGivesUp() {
        assertEquals(Reason.STALLED, reason(sinceStartMs = 20_000L, sinceProgressMs = 20_000L, done = 0))
    }

    @Test fun tooMuchLeftAfterTenSecondsGivesUp() {
        assertNull(reason(sinceStartMs = 9_999L, leftS = 500))
        assertEquals(Reason.HOPELESS, reason(sinceStartMs = 10_000L, leftS = 241))
    }

    @Test fun doneOrFinishedIsNeverAHandover() {
        assertNull(reason(done = 105, rateLimited = true))
        assertNull(reason(finished = true, rateLimited = true, sinceProgressMs = 30_000L))
    }

    @Test fun aHeldStartOrRebuildBehindTheScreenDoesNotStopThumbnails() {
        assertFalse(PrepareHandover.waitsForBuffering(buffering = true, screenUp = true, playWhenReady = false, small4K = false))
        assertFalse(PrepareHandover.waitsForBuffering(buffering = false, screenUp = false, playWhenReady = true, small4K = false))
    }

    @Test fun aRebufferTheViewerSeesStillWaits() {
        assertTrue(PrepareHandover.waitsForBuffering(buffering = true, screenUp = false, playWhenReady = false, small4K = false))
        assertTrue(PrepareHandover.waitsForBuffering(buffering = true, screenUp = true, playWhenReady = true, small4K = false))
        assertTrue(PrepareHandover.waitsForBuffering(buffering = true, screenUp = true, playWhenReady = false, small4K = true))
    }

    @Test fun aDolbyVisionStartRefusedBeforeItsFirstFrameIsAskedAgain() {
        // The Mummy: refused at 0 s, first frame 0.6 s later, then a 2 s display switch hold with nothing played.
        assertTrue(PrepareHandover.retriesHold(sinceRefusedMs = 600L, playedMs = 0L))
        assertTrue(PrepareHandover.retriesHold(sinceRefusedMs = 2_600L, playedMs = 0L))
        assertTrue(PrepareHandover.retriesHold(sinceRefusedMs = 11_999L, playedMs = 3_000L))
    }

    @Test fun aLateFirstFrameOrAStartAlreadyWatchedIsLeftPlaying() {
        assertFalse(PrepareHandover.retriesHold(sinceRefusedMs = 12_000L, playedMs = 0L))
        assertFalse(PrepareHandover.retriesHold(sinceRefusedMs = 4_000L, playedMs = 3_001L))
    }

    @Test fun workLeftCountsTheFinerSlotsThePassMakesOnTheWay() {
        // Every 10 s slot near the start first, then the 30 s grid further out: 7 to make, only 3 of them on the grid.
        val order = listOf(0, 1, 2, 3, 4, 5, 6, 9, 12)
        val stored = setOf(0)
        assertEquals(8, PrepareHandover.workLeft(order, 3) { it !in stored })
    }

    @Test fun workLeftStopsAtTheLastGridSlotStillMissing() {
        val order = listOf(0, 1, 2, 3, 4, 5, 6, 9, 12)
        val stored = setOf(0, 3, 6, 9, 12)
        assertEquals(0, PrepareHandover.workLeft(order, 3) { it !in stored })
        assertEquals(3, PrepareHandover.workLeft(order, 3) { it !in stored - 3 })
    }

    @Test fun aLongFilmIsJudgedOnEveryThumbnailMade() {
        // 0.6 s per thumbnail: 135 grid slots left with 270 finer ones on the way is about 4 min.
        val order = (0 until 405).toList()
        val left = (PrepareHandover.workLeft(order, 3) { true } * 0.6).toInt()
        assertEquals(241, left)
    }
}
