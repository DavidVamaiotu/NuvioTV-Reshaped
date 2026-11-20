package com.nuvio.tv.data.local

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ImagePerformancePreferencesReshapedTest {
    private val stored = mutableMapOf<String, Boolean>()
    private val pending = mutableMapOf<String, Boolean>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val context = mockk<Context>()

    @Before fun configureStorage() {
        every { context.getSharedPreferences("image_performance", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getBoolean(any(), any()) } answers { stored[firstArg<String>()] ?: secondArg<Boolean>() }
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            pending[firstArg<String>()] = secondArg<Boolean>()
            editor
        }
        every { editor.commit() } answers { stored.putAll(pending); pending.clear(); true }
    }

    @Test fun existingInstallKeepsSoftwareDecodingAndRgb565ByDefault() {
        val settings = ImagePerformancePreferences(context)
        assertFalse(settings.hardwareBitmapsEnabled)
        assertTrue(settings.rgb565Enabled)
        assertTrue(stored.isEmpty())
    }

    @Test fun anExistingHardwareOptInAndRgb565OptOutAreRespected() {
        stored["hardware_bitmaps_enabled"] = true
        stored["rgb565_enabled"] = false
        val settings = ImagePerformancePreferences(context)
        assertTrue(settings.hardwareBitmapsEnabled)
        assertFalse(settings.rgb565Enabled)
    }

    @Test fun hardwareTogglePersistsWithoutChangingTheRgb565Preference() {
        stored["rgb565_enabled"] = true
        val settings = ImagePerformancePreferences(context)
        assertTrue(settings.setHardwareBitmapsEnabled(true))
        assertTrue(settings.hardwareBitmapsEnabled)
        assertTrue(settings.rgb565Enabled)
        assertTrue(settings.setHardwareBitmapsEnabled(false))
        assertFalse(settings.hardwareBitmapsEnabled)
        assertTrue(settings.rgb565Enabled)
        assertEquals(setOf("rgb565_enabled", "hardware_bitmaps_enabled"), stored.keys)
    }

    @Test fun failedHardwarePreferenceWriteIsReportedAndDoesNotChangeStoredSettings() {
        every { editor.commit() } returns false
        val settings = ImagePerformancePreferences(context)
        assertFalse(settings.setHardwareBitmapsEnabled(true))
        assertFalse(settings.hardwareBitmapsEnabled)
        assertTrue(settings.rgb565Enabled)
        assertTrue(stored.isEmpty())
    }
}
