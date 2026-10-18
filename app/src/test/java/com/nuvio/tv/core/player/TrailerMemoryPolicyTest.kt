package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerMemoryPolicyTest {
    private val mb = 1024L * 1024

    @Test
    fun `two GB TV is constrained even when OEM low RAM flag is false`() {
        assertTrue(TrailerMemoryPolicy.isLowMemory(1900 * mb, 768 * mb, false))
    }

    @Test
    fun `small heap and OEM low RAM flag independently enable protection`() {
        assertTrue(TrailerMemoryPolicy.isLowMemory(4000 * mb, 256 * mb, false))
        assertTrue(TrailerMemoryPolicy.isLowMemory(4000 * mb, 768 * mb, true))
        assertTrue(TrailerMemoryPolicy.isLowMemory(0, 768 * mb, false))
    }

    @Test
    fun `three GB TV with sufficient heap retains full resolution`() {
        assertFalse(TrailerMemoryPolicy.isLowMemory(2800 * mb, 512 * mb, false))
    }
}
