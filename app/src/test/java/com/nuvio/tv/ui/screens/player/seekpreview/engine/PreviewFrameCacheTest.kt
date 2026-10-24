package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.*
import org.junit.Test

class PreviewFrameCacheTest {
    @Test fun aliasesShareOneByteChargeAndEvictTogether() {
        val cache = PreviewFrameCache<Int, Long, String>(8) { it.length }
        cache.put(listOf(0, 1, 2, 3), 10L, "frame")
        assertEquals(5, cache.size())
        assertEquals("frame", cache.get(3))
        cache.put(listOf(4), 20L, "next")
        assertEquals(4, cache.size())
        assertNull(cache.get(0))
        assertNull(cache.get(3))
        assertEquals(setOf(4), cache.slots())
    }

    @Test fun replacingAnAliasDoesNotEvictTheOtherAliases() {
        val cache = PreviewFrameCache<Int, Int, String>(20) { it.length }
        cache.put(listOf(0, 1), 1, "old")
        cache.put(listOf(0), 2, "new")
        assertEquals("old", cache.get(1))
        assertEquals("new", cache.get(0))
        cache.remove(0)
        assertEquals(3, cache.size())
        cache.trimToSize(0)
        assertTrue(cache.slots().isEmpty())
        assertEquals(0, cache.size())
    }

    @Test fun reusingAFrameKeepsItsAliasesAndUpdatesItsValue() {
        val cache = PreviewFrameCache<Int, Int, String>(20) { it.length }
        cache.put(listOf(0, 1), 1, "old")
        cache.put(listOf(2), 1, "updated")
        assertEquals(7, cache.size())
        assertEquals("updated", cache.get(0))
        assertEquals("updated", cache.get(2))
    }

    @Test fun oversizedImagesCannotDisplaceAnExistingFrame() {
        val cache = PreviewFrameCache<Int, Int, String>(3) { it.length }
        cache.put(listOf(0), 1, "ok")
        cache.put(listOf(1), 2, "large")
        assertEquals("ok", cache.get(0))
        assertNull(cache.get(1))
    }
}
