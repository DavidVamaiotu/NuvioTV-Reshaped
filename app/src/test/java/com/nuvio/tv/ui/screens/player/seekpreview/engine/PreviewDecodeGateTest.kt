package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PreviewDecodeGateTest {
    @Test fun requestedTargetGetsTheNextPermitBeforeBackgroundSpoolWork() {
        val lease = PreviewDecodeGate.prioritizeTarget()
        try {
            assertTrue(PreviewDecodeGate.busyForTap)
            assertNull(PreviewDecodeGate.tryRun { 1 })
            assertEquals(2, PreviewDecodeGate.tryRun(foreground = true) { 2 })
        } finally { PreviewDecodeGate.releaseTarget(lease) }
        assertFalse(PreviewDecodeGate.busyForTap)
        assertEquals(3, PreviewDecodeGate.tryRun { 3 })
    }
    @Test fun busyWorkCanRetryAndOnlyOneDecoderRunsAtATime() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Thread {
            try {
                PreviewDecodeGate.tryRun {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            } finally { finished.countDown() }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            var ran = false
            assertNull(PreviewDecodeGate.tryRun { ran = true; 1 })
            assertFalse(ran)
        } finally { release.countDown() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(7, PreviewDecodeGate.tryRun { 7 })
    }

    @Test fun failedDecodeAlwaysReleasesItsPermit() {
        try {
            PreviewDecodeGate.tryRun<Unit> { throw IllegalStateException("bad frame") }
            fail("expected decode failure")
        } catch (_: IllegalStateException) { }
        assertEquals(9, PreviewDecodeGate.tryRun { 9 })
    }
}
