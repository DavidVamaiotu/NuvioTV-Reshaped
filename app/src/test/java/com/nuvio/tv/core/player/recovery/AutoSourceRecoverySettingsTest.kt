package com.nuvio.tv.core.player.recovery

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.StreamAutoPlayMode
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSourceRecoverySettingsTest {
    @Test fun disabledRecoveryDoesNotAffectAnyAutoplayMode() {
        StreamAutoPlayMode.entries.forEach { mode ->
            assertFalse(shouldRecoverAutoSource(false, mode, PlayerPreference.INTERNAL))
        }
    }

    @Test fun enabledRecoveryUsesFirstSourceOrRegexButLeavesManualSelectionAlone() {
        assertTrue(shouldRecoverAutoSource(true, StreamAutoPlayMode.FIRST_STREAM, PlayerPreference.INTERNAL))
        assertTrue(shouldRecoverAutoSource(true, StreamAutoPlayMode.REGEX_MATCH, PlayerPreference.INTERNAL))
        assertFalse(shouldRecoverAutoSource(true, StreamAutoPlayMode.MANUAL, PlayerPreference.INTERNAL))
    }

    @Test fun externalPlayersDoNotActivateRecovery() {
        StreamAutoPlayMode.entries.forEach { mode ->
            assertFalse(shouldRecoverAutoSource(true, mode, PlayerPreference.EXTERNAL))
        }
    }

    @Test fun choosingAnInternalPlayerCanUseRecovery() {
        assertTrue(shouldRecoverAutoSource(true, StreamAutoPlayMode.FIRST_STREAM, PlayerPreference.ASK_EVERY_TIME))
    }

    @Test fun settingStartsOffAndPersistsEnableAndDisable() {
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("nuvio_auto_source_recovery", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getBoolean("enabled", false) } returns false
        every { preferences.edit() } returns editor
        every { editor.putBoolean("enabled", any()) } returns editor
        justRun { editor.apply() }

        val enabled = AutoSourceRecoverySettings.enabled(context)
        assertFalse(enabled.value)
        AutoSourceRecoverySettings.setEnabled(context, true)
        assertTrue(enabled.value)
        verify { editor.putBoolean("enabled", true) }
        AutoSourceRecoverySettings.setEnabled(context, false)
        assertFalse(enabled.value)
        verify { editor.putBoolean("enabled", false) }
    }
}
