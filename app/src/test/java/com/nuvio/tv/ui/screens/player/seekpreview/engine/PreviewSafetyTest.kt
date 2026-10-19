package com.nuvio.tv.ui.screens.player.seekpreview.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PreviewSafetyTest {
    @Test fun previewIoLinkageAndAllocationFailuresDoNotKillTheirParent() = runBlocking {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        try {
            for (error in listOf(IOException("disk full"), UnsatisfiedLinkError("missing decoder"), OutOfMemoryError("bitmap"))) {
                var failed: Throwable? = null
                var cleaned = false
                scope.launch {
                    try { assertNull(previewSafely<Unit>("failure test", { failed = it }) { throw error }) }
                    finally { cleaned = true }
                }.join()
                assertSame(error, failed)
                assertTrue(cleaned)
                assertTrue(parent.isActive)
            }
        } finally { scope.cancel() }
    }
    @Test fun stoppingASessionStillCancelsItsImageWork() = runBlocking {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        var cleaned = false
        var failureCalled = false
        val job = scope.launch {
            try { previewSafely("cancel test", { failureCalled = true }) { awaitCancellation() } }
            finally { cleaned = true }
        }
        scope.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(cleaned)
        assertFalse(failureCalled)
    }
    @Test fun failureReportingCannotTurnAHandledPreviewErrorIntoAPlayerCrash() {
        assertNull(previewSafely<Unit>("callback test", { throw IOException("cleanup") }) { throw OutOfMemoryError("preview") })
    }
    @Test fun durationBoundsAreCheckedBeforeIntegerConversionOrSlotAllocation() {
        assertEquals(1, PreviewLimits.slotCount(1, 10_000))
        assertEquals(17_280, PreviewLimits.slotCount(PreviewLimits.MAX_DURATION_MS, 10_000))
        assertNull(PreviewLimits.slotCount(PreviewLimits.MAX_DURATION_MS + 1, 10_000))
        assertNull(PreviewLimits.slotCount(Long.MAX_VALUE, 10_000))
        assertNull(PreviewLimits.slotCount(0, 10_000))
        assertNull(PreviewLimits.slotCount(1, 0))
    }
}
