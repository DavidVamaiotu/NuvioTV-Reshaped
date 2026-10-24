package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.*
import org.junit.Test

class PreviewCacheIdentityTest {
    @Test fun equalLengthReleasesWithDifferentHeadersDoNotShareImages() {
        val a = ThumbStore.keyFor("movie", 1000, "head-a")
        val b = ThumbStore.keyFor("movie", 1000, "head-b")
        assertNotEquals(a, b)
        assertTrue(a.startsWith(ThumbStore.identityPrefix("movie")))
        assertTrue(b.startsWith(ThumbStore.identityPrefix("movie")))
        assertEquals(a, ThumbStore.keyFor("movie", 1000, "head-a"))
    }
    @Test fun copyImagesKeepBothTheirFingerprintAndFittedGeometry() {
        val a = ThumbStore.copyKeyFor("episode", 1000, 480, 270, "head-a")
        assertNotEquals(a, ThumbStore.copyKeyFor("episode", 1000, 480, 270, "head-b"))
        assertNotEquals(a, ThumbStore.copyKeyFor("episode", 1000, 480, 360, "head-a"))
        assertNotEquals(a, ThumbStore.keyFor("episode", 1000, "head-a"))
    }
}
