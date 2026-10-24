package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.*
import org.junit.Test

class PreviewBufferGateTest {
    @Test fun aSmallSteadyBufferCanStartIndexDecodeAndCopyWorkEvenWhenLoadingNeverTurnsOff() {
        for (target in listOf(10_000L, 14_000L, 30_000L)) {
            val gate = PreviewBufferGate(target)
            assertFalse(gate.ready(1_000, true, false, true, 5_000, false))
            assertFalse(gate.ready(5_999, true, false, true, 5_100, false))
            assertTrue(gate.ready(6_000, true, false, true, 4_900, false))
        }
    }
    @Test fun bufferingResetsTheSteadyWindow() {
        val gate = PreviewBufferGate(10_000)
        assertFalse(gate.ready(0, true, false, true, 5_000, false))
        assertFalse(gate.ready(4_999, true, true, true, 5_000, false))
        assertFalse(gate.ready(5_000, true, false, true, 5_000, false))
        assertFalse(gate.ready(9_999, true, false, true, 5_000, false))
        assertTrue(gate.ready(10_000, true, false, true, 5_000, false))
    }
    @Test fun nearlyEmptyOrDrainingBuffersCannotBecomeReadyJustByWaiting() {
        val gate = PreviewBufferGate(14_000)
        assertFalse(gate.ready(0, true, false, true, 5_000, false))
        assertFalse(gate.ready(4_000, true, false, true, 3_000, false))
        assertFalse(gate.ready(60_000, true, false, false, 1_000, false))
        assertFalse(gate.ready(61_000, true, false, true, 5_000, false))
        assertTrue(gate.ready(66_000, true, false, true, 5_000, false))
    }
    @Test fun growingBuffersStartANewObservationWindow() {
        val gate = PreviewBufferGate(14_000)
        assertFalse(gate.ready(0, true, false, true, 4_000, false))
        assertFalse(gate.ready(4_000, true, false, true, 6_000, false))
        assertFalse(gate.ready(5_000, true, false, true, 6_000, false))
        assertTrue(gate.ready(9_000, true, false, true, 6_000, false))
    }
    @Test fun healthyPauseAndEndOfFileKeepTheirFastPathsAndCopyKeepsItsFullBufferFloor() {
        val gate = PreviewBufferGate(30_000, 14_000)
        assertTrue(gate.ready(0, true, false, true, 30_000, false))
        assertTrue(gate.ready(0, false, false, true, 0, false))
        assertTrue(gate.ready(0, true, false, true, 500, true))
        assertFalse(gate.ready(0, true, true, false, 30_000, true))
        assertFalse(gate.ready(0, true, false, false, 5_000, false))
        assertTrue(gate.ready(5_000, true, false, false, 5_000, false))
    }
}
