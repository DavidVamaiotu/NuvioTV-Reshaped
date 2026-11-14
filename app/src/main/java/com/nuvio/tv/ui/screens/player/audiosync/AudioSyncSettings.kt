package com.nuvio.tv.ui.screens.player.audiosync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How much of the film is read ahead of playback for dialogue samples, on any network. */
internal enum class AudioSamplingMode {
    /** Listen during playback only. */
    OFF,

    /** Fewer spots over one connection, with a small data cap. The most a metered network gets. */
    LOW,

    /** More spots over several connections, for the fastest sync. */
    HIGH,
}

/**
 * Settings of the audio subtitle sync fallback (see AutoSync). Persistence is injected by the
 * platform, so Nuvio's own player settings are left untouched.
 */
internal object AudioSyncSettings {
    // Declared first: object properties initialize in order.
    // Tester build: high by default for the fastest sync; metered networks still get at most low.
    private val DEFAULT_SAMPLING_MODE = AudioSamplingMode.HIGH

    // Tester build: on by default.
    private val _fallbackEnabled = MutableStateFlow(true)

    /** Sync to the audio when AutoSync keeps a subtitle's original timing. */
    val fallbackEnabled: StateFlow<Boolean> = _fallbackEnabled.asStateFlow()

    private val _samplingOnMobileData = MutableStateFlow(false)

    /** Retains the existing opt-in for extra sampling traffic on metered connections. */
    val samplingOnMobileData: StateFlow<Boolean> = _samplingOnMobileData.asStateFlow()

    private val _samplingMode = MutableStateFlow(DEFAULT_SAMPLING_MODE)

    /** Reading short parts of the film ahead of playback; see [AudioSamplingMode]. */
    val samplingMode: StateFlow<AudioSamplingMode> = _samplingMode.asStateFlow()

    private val _showStatistics = MutableStateFlow(false)

    /** Show the live sync statistics panel in the player's top-left corner. Off by default. */
    val showStatistics: StateFlow<Boolean> = _showStatistics.asStateFlow()

    private var save: (key: String, value: Boolean) -> Unit = { _, _ -> }
    private var saveString: (key: String, value: String) -> Unit = { _, _ -> }

    fun installPersistence(
        load: (key: String) -> Boolean?,
        save: (key: String, value: Boolean) -> Unit,
        loadString: (key: String) -> String?,
        saveString: (key: String, value: String) -> Unit,
    ) {
        this.save = save
        this.saveString = saveString
        _fallbackEnabled.value = load(FALLBACK_ENABLED) ?: true
        _showStatistics.value = load(SHOW_STATISTICS) ?: false
        _samplingOnMobileData.value = load(SAMPLING_ON_MOBILE_DATA) ?: false
        _samplingMode.value = loadString(SAMPLING_MODE)
            ?.let { stored -> AudioSamplingMode.entries.firstOrNull { it.name == stored } }
            ?: DEFAULT_SAMPLING_MODE
    }

    fun setFallbackEnabled(enabled: Boolean) {
        _fallbackEnabled.value = enabled
        save(FALLBACK_ENABLED, enabled)
    }

    fun setSamplingOnMobileData(enabled: Boolean) {
        _samplingOnMobileData.value = enabled
        save(SAMPLING_ON_MOBILE_DATA, enabled)
    }

    fun setSamplingMode(mode: AudioSamplingMode) {
        _samplingMode.value = mode
        saveString(SAMPLING_MODE, mode.name)
    }

    private const val FALLBACK_ENABLED = "audio_sync_fallback_enabled"
    fun setShowStatistics(enabled: Boolean) {
        _showStatistics.value = enabled
        save(SHOW_STATISTICS, enabled)
    }

    private const val SAMPLING_ON_MOBILE_DATA = "audio_sync_sampling_on_mobile_data"
    private const val SAMPLING_MODE = "audio_sync_sampling_mode"
    private const val SHOW_STATISTICS = "audio_sync_show_statistics"
}

/** Sampling intensity and permission to use metered data are independent preferences. */
internal fun audioSamplingAllowed(mode: AudioSamplingMode, onMobileData: Boolean, unmetered: Boolean): Boolean =
    mode != AudioSamplingMode.OFF && (unmetered || onMobileData)
