package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionConversionConfigTest {

    @Test
    fun `auto keeps mode 1 without fallback`() {
        val config = DolbyVisionConversionConfig(active = true)
        assertEquals(1, config.conversionMode(7))
        assertFalse(config.allowMode2Fallback)
    }

    @Test
    fun `auto with autoMode2 converts profile 7 like manual Convert to DV8_1`() {
        val auto = DolbyVisionConversionConfig(active = true, autoMode2 = true)
        val manual = DolbyVisionConversionConfig(active = true, manualDv81 = true)
        assertEquals(manual.conversionMode(7), auto.conversionMode(7))
        assertEquals(2, auto.conversionMode(7))
        assertTrue(auto.allowMode2Fallback)
        assertTrue(auto.shouldConvert(7))
    }

    @Test
    fun `autoMode2 leaves DV5 alone`() {
        val auto = DolbyVisionConversionConfig(active = true, dv5Enabled = true, autoMode2 = true)
        assertFalse(auto.shouldConvert(5))
        assertFalse(auto.convertDv5Rpu)
    }

    @Test
    fun `forced mode wins over autoMode2`() {
        val forced = DolbyVisionConversionConfig(active = true, forcedMode = 1, autoMode2 = true)
        assertEquals(1, forced.conversionMode(7))
        assertFalse(forced.allowMode2Fallback)
    }
}
