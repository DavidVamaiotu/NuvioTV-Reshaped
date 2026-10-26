package com.nuvio.tv.ui.screens.player.iec

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IecKnownUnusableTest {

    @After
    fun clear() {
        PlatformIecAudioTrackFactory.invalidateIec61937ProbeMemo(routeChanged = true)
    }

    @Test
    fun `a new player does not hold Dolby MAT back once IEC 61937 failed on this output`() {
        PlatformIecAudioTrackFactory.invalidateIec61937ProbeMemo(routeChanged = true)
        PlatformIecAudioTrackFactory().markIecUnusable()
        assertTrue(PlatformIecAudioTrackFactory.iecSettledAt(192_000))
        PlatformIecAudioTrackFactory.invalidateIec61937ProbeMemo()
        assertTrue(PlatformIecAudioTrackFactory.iecSettledAt(192_000))
        assertTrue(hbrCanOpen(iecUsable = false, iecSettled = PlatformIecAudioTrackFactory.iecSettledAt(192_000), matSupported = true))
    }

    @Test
    fun `an output change probes IEC 61937 first again`() {
        PlatformIecAudioTrackFactory().markIecUnusable()
        PlatformIecAudioTrackFactory.invalidateIec61937ProbeMemo(routeChanged = true)
        assertFalse(PlatformIecAudioTrackFactory.iecSettledAt(192_000))
    }
}
