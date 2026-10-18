// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failed decoder load is tried again later instead of staying off until the app restarts. */
class NativeLoadBackoffTest {
    private val minute = 60_000L

    @Test fun theFirstLoadIsTriedAtOnce() {
        val b = NativeLoadBackoff()
        assertTrue(b.mayTry(0L))
        assertEquals(0L, b.waitMs(0L))
    }

    @Test fun aFailureWaitsOneMinuteThenDoublesUpToFifteen() {
        val b = NativeLoadBackoff()
        var t = 1_000_000L
        val waits = ArrayList<Long>()
        repeat(6) {
            b.failed(t)
            val w = b.waitMs(t)
            waits.add(w)
            assertFalse(b.mayTry(t + w - 1))
            t += w
            assertTrue(b.mayTry(t))
        }
        assertEquals(listOf(1L, 2L, 4L, 8L, 15L, 15L).map { it * minute }, waits)
        assertEquals(6, b.failures)
    }

    @Test fun manyFailuresNeverOverflowTheWait() {
        val b = NativeLoadBackoff()
        repeat(100) { b.failed(0L) }
        assertEquals(15 * minute, b.waitMs(0L))
        assertTrue(b.mayTry(15 * minute))
    }
}
