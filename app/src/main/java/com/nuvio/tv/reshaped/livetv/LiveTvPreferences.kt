package com.nuvio.tv.reshaped.livetv

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether Live TV shows in the menu. Off by default. */
object LiveTvPreferences {
    private const val KEY_ENABLED = "live_tv_enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
            loaded = true
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        _enabled.value = enabled
        loaded = true
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // Its own tiny file: this is read on the main thread when the menu is built.
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("nuvio_live_tv_menu", Context.MODE_PRIVATE)
}

/** The Live TV menu setting as Compose state, loaded on first use. */
@Composable
fun rememberLiveTvEnabled(): Boolean {
    LiveTvPreferences.ensureLoaded(LocalContext.current)
    val enabled by LiveTvPreferences.enabled.collectAsState()
    return enabled
}

/** The Live TV screen's navigation route. */
const val LIVE_TV_ROUTE = "reshaped_live_tv"
