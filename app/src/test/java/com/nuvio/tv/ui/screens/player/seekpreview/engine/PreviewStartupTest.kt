package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.*
import org.junit.Test

class PreviewStartupTest {
    @Test fun fullSmallBuffersDoNotWaitForAnUnreachableTenOrFourteenSeconds() {
        for (target in listOf(10_000L, 14_000L)) {
            assertTrue(previewBufferReady(true, false, false, 5_000, false, target))
            assertFalse(previewBufferReady(true, false, true, 5_000, false, target))
            assertTrue(previewBufferReady(true, false, true, target, false, target))
        }
    }
    @Test fun bufferingAndNearlyEmptyBuffersKeepPlaybackFirst() {
        assertFalse(previewBufferReady(true, true, false, 20_000, false, 10_000))
        assertFalse(previewBufferReady(true, false, false, 500, false, 10_000))
        assertFalse(previewBufferReady(true, false, true, 5_000, false, 10_000))
    }
    @Test fun pauseAndEndOfFileAllowBackgroundWorkWithoutHoldingPlayback() {
        assertTrue(previewBufferReady(false, false, true, 0, false, 10_000))
        assertTrue(previewBufferReady(true, false, true, 1_000, true, 10_000))
    }
    @Test fun lowRamNeverRampsUpConcurrentFetchesOrDecoders() {
        for (fourK in listOf(true, false)) for (phase in FetchPhase.entries) {
            val lanes = ThumbDecodeLanes(false, fourK, 8, phase, memoryConstrained = true)
            repeat(100) { lanes.onDecode(10, 1, false) }
            assertEquals(1, lanes.lanes)
            assertEquals(1, lanes.ceiling())
            assertEquals(1, ThumbFetchLanes.limit(phase, false, fourK, memoryConstrained = true))
            assertEquals(1, ThumbBufferPool.limitFor(6, fourK, false, memoryConstrained = true))
        }
    }
    @Test fun diagnosticsAreBoundedAndDoNotExportStreamUrls() {
        repeat(120) { PreviewDiagnostics.note("event-$it https://example.test/private?token=secret") }
        val text = PreviewDiagnostics.text()
        assertFalse(text.contains("token=secret"))
        assertFalse(text.contains("example.test"))
        assertTrue(text.contains("event-119 <url>"))
        assertFalse(text.contains("event-0 "))
        assertTrue(text.lines().size <= 97)
    }
}
