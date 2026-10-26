package com.nuvio.tv.data.local

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReshapedFormatSettingsTest {
    @Test
    fun formatHandlingAndIecStartDisabled() {
        val defaults = ReshapedFormatSettings()
        val restored = ReshapedFormatSettingsStore.read(emptyPreferences())

        assertFalse(defaults.enabled)
        assertFalse(defaults.iecEngineEnabled)
        assertFalse(restored.enabled)
        assertFalse(restored.iecEngineEnabled)
    }

    @Test
    fun anExplicitOptInIsRestored() {
        val preferences = emptyPreferences()
        every { preferences.getBoolean("enabled", any()) } returns true

        val restored = ReshapedFormatSettingsStore.read(preferences)

        assertTrue(restored.enabled)
        assertTrue(restored.iecEngineEnabled)
    }

    @Test
    fun systemPassthroughKeepsTheCustomIecEngineDisabled() {
        val preferences = emptyPreferences()
        every { preferences.getBoolean("enabled", any()) } returns true
        every { preferences.getBoolean("use_system_passthrough", any()) } returns true

        val restored = ReshapedFormatSettingsStore.read(preferences)

        assertTrue(restored.enabled)
        assertFalse(restored.iecEngineEnabled)
    }

    private fun emptyPreferences(): SharedPreferences = mockk {
        every { contains(any()) } returns false
        every { getBoolean(any(), any()) } answers { secondArg<Boolean>() }
        every { getString(any(), any()) } answers { secondArg<String?>() }
        every { getStringSet(any(), any()) } answers { secondArg<Set<String>?>() }
    }
}
