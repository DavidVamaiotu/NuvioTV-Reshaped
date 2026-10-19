package com.nuvio.tv.core.player.recovery

import android.content.Context
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One opt-in; source order and filters remain owned by the existing autoplay settings. */
internal object AutoSourceRecoverySettings {
    private const val PREFS = "nuvio_auto_source_recovery"
    private val state = MutableStateFlow(false)
    @Volatile private var loaded = false

    fun enabled(context: Context): StateFlow<Boolean> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                state.value = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean("enabled", false)
                loaded = true
            }
        }
        return state.asStateFlow()
    }

    @Synchronized fun setEnabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", value).apply()
        state.value = value
        loaded = true
    }

    fun applies(context: Context, settings: PlayerSettings): Boolean =
        shouldRecoverAutoSource(enabled(context).value, settings.streamAutoPlayMode, settings.playerPreference)
}

internal fun shouldRecoverAutoSource(enabled: Boolean, mode: StreamAutoPlayMode, player: PlayerPreference): Boolean =
    enabled && mode != StreamAutoPlayMode.MANUAL && player != PlayerPreference.EXTERNAL
