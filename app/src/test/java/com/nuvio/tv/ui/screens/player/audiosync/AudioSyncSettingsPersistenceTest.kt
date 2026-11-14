package com.nuvio.tv.ui.screens.player.audiosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After

class AudioSyncSettingsPersistenceTest {
    @After
    fun reset() {
        AudioSyncSettings.installPersistence({ null }, { _, _ -> }, { null }, { _, _ -> })
    }

    @Test
    fun legacyMeteredOptOutSurvivesNewSamplingModes() {
        val booleans = mutableMapOf("audio_sync_sampling_on_mobile_data" to false)
        val strings = mutableMapOf<String, String>()
        AudioSyncSettings.installPersistence(booleans::get, { k, v -> booleans[k] = v }, strings::get, { k, v -> strings[k] = v })
        assertEquals(AudioSamplingMode.HIGH, AudioSyncSettings.samplingMode.value)
        assertFalse(AudioSyncSettings.samplingOnMobileData.value)
        AudioSyncSettings.setSamplingMode(AudioSamplingMode.LOW)
        assertEquals("LOW", strings["audio_sync_sampling_mode"])
        assertEquals(false, booleans["audio_sync_sampling_on_mobile_data"])
        AudioSyncSettings.installPersistence(booleans::get, { k, v -> booleans[k] = v }, strings::get, { k, v -> strings[k] = v })
        assertEquals(AudioSamplingMode.LOW, AudioSyncSettings.samplingMode.value)
        assertFalse(AudioSyncSettings.samplingOnMobileData.value)
    }

    @Test
    fun legacyMeteredOptInAndAllOtherSettingsSurvive() {
        val booleans = mutableMapOf(
            "audio_sync_sampling_on_mobile_data" to true,
            "audio_sync_fallback_enabled" to false,
            "audio_sync_show_statistics" to true,
        )
        AudioSyncSettings.installPersistence(booleans::get, { k, v -> booleans[k] = v }, { "HIGH" }, { _, _ -> })
        assertTrue(AudioSyncSettings.samplingOnMobileData.value)
        assertFalse(AudioSyncSettings.fallbackEnabled.value)
        assertTrue(AudioSyncSettings.showStatistics.value)
        AudioSyncSettings.setSamplingOnMobileData(false)
        assertEquals(false, booleans["audio_sync_sampling_on_mobile_data"])
        assertEquals(AudioSamplingMode.HIGH, AudioSyncSettings.samplingMode.value)
    }

    @Test
    fun samplingPermissionRequiresTheIndependentMeteredOptIn() {
        for (mode in AudioSamplingMode.entries) {
            for (unmetered in listOf(false, true)) {
                for (optIn in listOf(false, true)) {
                    assertEquals(
                        mode != AudioSamplingMode.OFF && (unmetered || optIn),
                        audioSamplingAllowed(mode, optIn, unmetered),
                        "$mode unmetered=$unmetered optIn=$optIn",
                    )
                }
            }
        }
    }
}
