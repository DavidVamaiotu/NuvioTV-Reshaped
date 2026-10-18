// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generating screen: playback keeps its start buffer, then leaves the connections to the thumbnails. */
class PlaybackYieldTest {
    private val s = 1_000_000L

    @Test fun noScreenNoHold() {
        val y = PlaybackYield(minStartBufferUs = 2 * s)
        assertFalse(y.holds(nowMs = 0L, bufferedUs = 60 * s, playWhenReady = false))
    }

    @Test fun loadsTheStartBufferFirst() {
        val y = PlaybackYield(minStartBufferUs = 2 * s)
        y.renew(1_000L)
        assertFalse(y.holds(1_100L, bufferedUs = 0L, playWhenReady = false))
        assertFalse(y.holds(1_200L, bufferedUs = 14 * s, playWhenReady = false))
        assertTrue(y.holds(1_300L, bufferedUs = 15 * s, playWhenReady = false))
        assertTrue(y.holding)
    }

    @Test fun aLargerPlaybackStartSettingWins() {
        val y = PlaybackYield(minStartBufferUs = 25 * s)
        assertEquals(25 * s, y.startBufferUs)
        y.renew(0L)
        assertFalse(y.holds(10L, bufferedUs = 20 * s, playWhenReady = false))
        assertTrue(y.holds(10L, bufferedUs = 25 * s, playWhenReady = false))
    }

    @Test fun pressingPlayLoadsAgainAtOnce() {
        val y = PlaybackYield(minStartBufferUs = 2 * s)
        y.renew(0L)
        assertTrue(y.holds(10L, bufferedUs = 20 * s, playWhenReady = false))
        assertFalse(y.holds(20L, bufferedUs = 20 * s, playWhenReady = true))
        assertFalse(y.holding)
    }

    @Test fun closingTheScreenLoadsAgainAtOnce() {
        val y = PlaybackYield(minStartBufferUs = 2 * s)
        y.renew(0L)
        assertTrue(y.holds(10L, bufferedUs = 20 * s, playWhenReady = false))
        y.end()
        assertFalse(y.holding)
        assertFalse(y.holds(20L, bufferedUs = 20 * s, playWhenReady = false))
    }

    @Test fun aScreenThatStopsRenewingLetsGo() {
        val y = PlaybackYield(minStartBufferUs = 2 * s)
        y.renew(0L)
        assertTrue(y.holds(PlaybackYield.LEASE_MS - 1, bufferedUs = 20 * s, playWhenReady = false))
        assertFalse(y.holds(PlaybackYield.LEASE_MS, bufferedUs = 20 * s, playWhenReady = false))
        y.renew(PlaybackYield.LEASE_MS + 100)
        assertTrue(y.holds(PlaybackYield.LEASE_MS + 200, bufferedUs = 20 * s, playWhenReady = false))
    }
}
