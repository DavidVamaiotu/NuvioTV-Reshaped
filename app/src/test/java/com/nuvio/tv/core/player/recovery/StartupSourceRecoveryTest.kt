package com.nuvio.tv.core.player.recovery

import org.junit.Assert.*
import org.junit.Test
import com.nuvio.tv.core.player.recovery.StartupSourceRecovery.Decision.*

class StartupSourceRecoveryTest {
    @Test fun `no startup is abandoned before fifteen seconds`() {
        val session = StartupSourceRecovery(1_000)
        assertEquals(Wait, session.evaluate(15_999, false, false, false))
        assertEquals(NextSource, session.evaluate(16_000, false, false, false))
    }

    @Test fun `same link recovery and repeated observations never renew deadline`() {
        val session = StartupSourceRecovery(0)
        for (now in 0L..14_000L step 1_000L) assertEquals(Wait, session.evaluate(now, false, false, false))
        assertEquals(0L, session.remainingMs(15_000))
        assertEquals(NextSource, session.evaluate(15_000, false, false, false))
    }

    @Test fun `fatal failure can advance immediately`() {
        val session = StartupSourceRecovery(0)
        assertEquals(NextSource, session.evaluate(500, false, false, true))
        assertTrue(session.beginNextAttempt(500))
        assertEquals(15_000L, session.remainingMs(500))
    }

    @Test fun `three attempts is a hard limit including initial source`() {
        val session = StartupSourceRecovery(0)
        assertTrue(session.beginNextAttempt(15_000))
        assertTrue(session.beginNextAttempt(30_000))
        assertEquals(Stop, session.evaluate(45_000, false, false, false))
        assertFalse(session.beginNextAttempt(45_000))
    }

    @Test fun `fast failures also obey attempt limit`() {
        val session = StartupSourceRecovery(0)
        assertTrue(session.beginNextAttempt(100))
        assertTrue(session.beginNextAttempt(200))
        assertEquals(Stop, session.evaluate(300, false, false, true))
    }

    @Test fun `session deadline limits a late attempt`() {
        val session = StartupSourceRecovery(0)
        assertTrue(session.beginNextAttempt(40_000))
        assertEquals(5_000L, session.remainingMs(40_000))
        assertEquals(Stop, session.evaluate(45_000, false, false, false))
    }

    @Test fun `healthy playback completes even at deadline and never starts recovering later`() {
        val session = StartupSourceRecovery(0)
        assertEquals(Complete, session.evaluate(15_000, true, false, false))
        assertEquals(Stop, session.evaluate(40_000, false, false, true))
        assertFalse(session.beginNextAttempt(40_000))
    }

    @Test fun `manual action or background cancels permanently`() {
        val session = StartupSourceRecovery(0)
        assertEquals(Stop, session.evaluate(100, false, true, true))
        assertEquals(Stop, session.evaluate(200, false, false, true))
        assertFalse(session.beginNextAttempt(200))
    }

    @Test fun `clock values before start do not extend an attempt`() {
        val session = StartupSourceRecovery(1_000)
        assertEquals(15_000L, session.remainingMs(0))
    }
}
