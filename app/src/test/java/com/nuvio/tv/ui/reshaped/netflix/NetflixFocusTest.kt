package com.nuvio.tv.ui.reshaped.netflix

import org.junit.Assert.assertEquals
import org.junit.Test

class NetflixFocusTest {
    @Test
    fun `saved title follows insertions and reordering while details is open`() {
        assertEquals(2, netflixRestoreIndex(listOf("new", "a", "b"), "b", 1))
        assertEquals(0, netflixRestoreIndex(listOf("b", "a"), "b", 1))
    }

    @Test
    fun `removed title or old index only saves fall back within the new row`() {
        assertEquals(1, netflixRestoreIndex(listOf("a", "b"), "removed", 20))
        assertEquals(0, netflixRestoreIndex(listOf("a", "b"), null, -1))
        assertEquals(0, netflixRestoreIndex(emptyList(), "b", 1))
    }
}
