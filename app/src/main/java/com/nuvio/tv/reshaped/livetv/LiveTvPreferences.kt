package com.nuvio.tv.reshaped.livetv

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether Live TV shows in the menu (off by default), and whether its list previews channels, with sound (both on). */
object LiveTvPreferences {
    private const val KEY_ENABLED = "live_tv_enabled"
    private const val KEY_PREVIEWS = "live_tv_previews"
    private const val KEY_PREVIEW_SOUND = "live_tv_preview_sound"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _previews = MutableStateFlow(true)
    /** A small live picture of the focused channel in the list. */
    val previews: StateFlow<Boolean> = _previews.asStateFlow()

    private val _previewSound = MutableStateFlow(true)
    /** Whether the preview plays the channel's sound. */
    val previewSound: StateFlow<Boolean> = _previewSound.asStateFlow()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = prefs(context)
            _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
            _previews.value = prefs.getBoolean(KEY_PREVIEWS, true)
            _previewSound.value = prefs.getBoolean(KEY_PREVIEW_SOUND, true)
            loaded = true
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        _enabled.value = enabled
        loaded = true
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setPreviews(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _previews.value = enabled
        prefs(context).edit().putBoolean(KEY_PREVIEWS, enabled).apply()
    }

    fun setPreviewSound(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _previewSound.value = enabled
        prefs(context).edit().putBoolean(KEY_PREVIEW_SOUND, enabled).apply()
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

/** The channel preview setting as Compose state. */
@Composable
fun rememberLiveTvPreviewsEnabled(): Boolean {
    LiveTvPreferences.ensureLoaded(LocalContext.current)
    val enabled by LiveTvPreferences.previews.collectAsState()
    return enabled
}

/** The preview sound setting as Compose state. */
@Composable
fun rememberLiveTvPreviewSoundEnabled(): Boolean {
    LiveTvPreferences.ensureLoaded(LocalContext.current)
    val enabled by LiveTvPreferences.previewSound.collectAsState()
    return enabled
}

/** The Live TV screen's navigation route. */
const val LIVE_TV_ROUTE = "reshaped_live_tv"
