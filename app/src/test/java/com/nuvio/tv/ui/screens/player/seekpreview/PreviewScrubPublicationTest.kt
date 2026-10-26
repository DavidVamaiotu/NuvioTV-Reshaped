package com.nuvio.tv.ui.screens.player.seekpreview
import org.junit.Assert.*
import org.junit.Test
class PreviewScrubPublicationTest {
    @Test fun heldRightPublishesCompletedFramesWhileInputMoves() {
        for(position in 10_000L..100_000L step 10_000L)
            assertTrue(PreviewScrubPublication.mayPaint(position,position+20_000,10_000,true,position-10_000))
    }
    @Test fun heldLeftPublishesToo() {
        assertTrue(PreviewScrubPublication.mayPaint(90_000,70_000,10_000,true,100_000))
    }
    @Test fun reversalsAndLargeJumpsRejectOldScenes() {
        assertFalse(PreviewScrubPublication.mayPaint(90_000,70_000,10_000,true,80_000))
        assertFalse(PreviewScrubPublication.mayPaint(90_000,900_000,10_000,true,80_000))
    }
    @Test fun changedOffsetsAndRevisionsRejectOldCompletions() {
        assertFalse(PreviewScrubPublication.mayPaint(90_000,90_000,10_000,false,80_000))
        assertTrue(PreviewScrubPublication.mayPaint(90_000,90_000,10_000,true,80_000))
    }
}
