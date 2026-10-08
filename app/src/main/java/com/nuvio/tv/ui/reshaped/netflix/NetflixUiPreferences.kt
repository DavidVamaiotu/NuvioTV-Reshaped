package com.nuvio.tv.ui.reshaped.netflix

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether Home uses the Netflix-style look. Purely visual; off by default. */
internal object NetflixUiPreferences {
    private const val PREFS = "nuvio_netflix_ui_settings"
    private const val KEY_ENABLED = "netflix_ui_enabled"

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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The Netflix-style setting as Compose state, loaded on first use. */
@Composable
internal fun rememberNetflixUiEnabled(): Boolean {
    NetflixUiPreferences.ensureLoaded(LocalContext.current)
    val enabled by NetflixUiPreferences.enabled.collectAsState()
    return enabled
}
