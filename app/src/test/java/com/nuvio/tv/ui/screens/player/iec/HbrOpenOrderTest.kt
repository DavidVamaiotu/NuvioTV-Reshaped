package com.nuvio.tv.ui.screens.player.iec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HbrOpenOrderTest {

    @Test
    fun trueHd_triesIecBeforeDolbyMat() {
        assertEquals(
            listOf(HbrOpenStep.IEC61937, HbrOpenStep.DOLBY_MAT),
            hbrOpenSteps(trueHd = true, iecUsable = true, iecSettled = true, matSupported = true)
        )
    }

    @Test
    fun trueHd_usesDolbyMatOnlyWhenIecIsOut() {
        assertEquals(
            listOf(HbrOpenStep.DOLBY_MAT),
            hbrOpenSteps(trueHd = true, iecUsable = false, iecSettled = true, matSupported = true)
        )
    }

    @Test
    fun trueHd_waitsForTheProbeInsteadOfJumpingToDolbyMat() {
        assertEquals(
            emptyList<HbrOpenStep>(),
            hbrOpenSteps(trueHd = true, iecUsable = false, iecSettled = false, matSupported = true)
        )
        assertFalse(hbrCanOpen(iecUsable = false, iecSettled = false, matSupported = true))
    }

    @Test
    fun trueHd_withoutDolbyMat_isIecOnly() {
        assertEquals(
            listOf(HbrOpenStep.IEC61937),
            hbrOpenSteps(trueHd = true, iecUsable = true, iecSettled = true, matSupported = false)
        )
        assertEquals(
            emptyList<HbrOpenStep>(),
            hbrOpenSteps(trueHd = true, iecUsable = false, iecSettled = true, matSupported = false)
        )
    }

    @Test
    fun dtsHd_neverUsesDolbyMat() {
        assertEquals(
            listOf(HbrOpenStep.IEC61937),
            hbrOpenSteps(trueHd = false, iecUsable = true, iecSettled = true, matSupported = true)
        )
        assertEquals(
            emptyList<HbrOpenStep>(),
            hbrOpenSteps(trueHd = false, iecUsable = false, iecSettled = true, matSupported = true)
        )
    }

    @Test
    fun canOpen_followsIecFirst() {
        assertTrue(hbrCanOpen(iecUsable = true, iecSettled = true, matSupported = false))
        assertTrue(hbrCanOpen(iecUsable = false, iecSettled = true, matSupported = true))
        assertFalse(hbrCanOpen(iecUsable = false, iecSettled = true, matSupported = false))
    }

    @Test
    fun pathNames() {
        assertEquals("IEC 61937", iecPathName(HbrPayload.IEC_BURST))
        assertEquals("Dolby MAT", iecPathName(HbrPayload.MAT))
        assertEquals("IEC", iecPathName(null))
    }
}
