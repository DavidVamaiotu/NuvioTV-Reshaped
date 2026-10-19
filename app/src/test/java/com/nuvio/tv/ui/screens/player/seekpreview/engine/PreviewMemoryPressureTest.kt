package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.*
import org.junit.Test

class PreviewMemoryPressureTest {
    private val quiet = PreviewMemoryStalls(0.1, 0.0)
    private fun recovery(available: Long, swap: Long, stalls: PreviewMemoryStalls? = quiet, low: Boolean = false) =
        MemoryGovernor.compactHdVerdict(MemoryGovernor.Meminfo(available, 512, swap), 80, low, stalls)

    @Test fun lowSwapDoesNotPermanentlyBlockHdWhenMeasuredPressureIsQuietAndRamHasHeadroom() {
        for (swap in listOf(0L, 2L, 28L, 41L, 49L, 54L)) {
            val strict = MemoryGovernor.decodeVerdict(MemoryGovernor.Meminfo(400, 512, swap), 80, false, true)
            assertFalse(strict.allowed)
            val transient = recovery(400, swap)!!
            assertTrue(transient.allowed)
            assertTrue(transient.compactHd)
            assertFalse(transient.swapLow)
        }
    }

    @Test fun recoveryKeepsColdDecoderChargeAndExtraRamMargin() {
        assertNotNull(recovery(344, 28))
        assertNull(recovery(343, 28))
        assertNull(recovery(200, 49))
        assertNull(recovery(-1, 54))
        assertNull(recovery(900, 54, low = true))
    }

    @Test fun realReclaimStallsRetainTheStrictGate() {
        assertNull(recovery(900, 49, PreviewMemoryStalls(2.0, 0.0)))
        assertNull(recovery(900, 49, PreviewMemoryStalls(0.0, 0.5)))
        // The general and 4K gate is unchanged; recovery requires explicit HD admission.
        assertFalse(MemoryGovernor.decodeVerdict(MemoryGovernor.Meminfo(900, 512, 49), 512, false, true).allowed)
    }

    @Test fun androidPressureFileRestrictionsRequireMoreRamRatherThanDisablingHdForTheSession() {
        assertNull(recovery(407, 49, null))
        assertTrue(recovery(408, 49, null)!!.compactHd)
        assertNull(recovery(900, 49, null, low = true))
        val mem = MemoryGovernor.Meminfo(500, 512, 28)
        // A device with a higher platform low-memory threshold keeps that threshold after allocating the decoder.
        assertNull(MemoryGovernor.compactHdVerdict(mem, 80, false, null, platformThresholdMb = 300))
        assertNotNull(MemoryGovernor.compactHdVerdict(mem, 80, false, quiet, platformThresholdMb = 300))
    }

    @Test fun psiParsingRequiresBothValidMeasurements() {
        assertEquals(quiet, PreviewMemoryStalls.parse("some avg10=0.10 avg60=0.00 total=17\nfull avg10=0.00 avg60=0.00 total=0\n"))
        for (text in listOf("", "some avg10=0.0", "some avg10=NaN\nfull avg10=0.0",
            "some avg10=-1\nfull avg10=0.0", "some avg10=0.0\nfull avg10=101")) {
            assertNull(PreviewMemoryStalls.parse(text))
        }
    }

    @Test fun aStableCompactTurnIsSharedAndCannotBeAcceleratedByRepeatedLookups() {
        val gate = PreviewCompactGate()
        assertEquals(2_000L, gate.acquire(0))
        assertEquals(1_000L, gate.acquire(1_000))
        assertEquals(0L, gate.acquire(2_000))
        assertEquals(3_000L, gate.acquire(2_000)) // another worker cannot take the same turn
        assertEquals(0L, gate.acquire(5_000))
        gate.reset() // pressure returned: settlement starts again; the rate limit still holds
        assertEquals(2_500L, gate.acquire(5_500))
        assertEquals(0L, gate.acquire(8_000))
    }

    @Test fun bufferingAndDroppedPlaybackFramesHoldEvenAForegroundCompactTurn() {
        val gate = PreviewCompactGate()
        gate.observe(0, false, 10)
        gate.acquire(0)
        gate.observe(1_000, false, 11)
        assertEquals(9_000L, gate.acquire(2_000))
        gate.observe(2_000, false, 0) // counters reset after a player rebuild
        assertEquals(0L, gate.acquire(11_000))
        gate.observe(12_000, true, 0)
        assertEquals(9_000L, gate.acquire(13_000))
        assertEquals(0L, gate.acquire(22_000))
    }
}
