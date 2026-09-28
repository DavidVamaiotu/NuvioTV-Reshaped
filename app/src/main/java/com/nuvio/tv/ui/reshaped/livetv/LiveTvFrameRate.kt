package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry
import com.nuvio.tv.ui.screens.player.FrameRateSource
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.PlayerUiState
import com.nuvio.tv.ui.screens.player.currentHostActivity
import com.nuvio.tv.ui.screens.player.isUsingMpvEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Frame rate matching for Live TV. Nuvio's preflight downloads the start of a file over extra
 * connections before playback starts (for up to 18 s). A live channel has no start, and many IPTV
 * accounts allow a single connection, so the probe could get the channel itself refused. Live TV
 * skips it (ExoPlayer only) and [LiveTvFrameRateMatch] switches the display to the frame rate the
 * playing stream reports, as IPTV players do. Returns true when the preflight is skipped.
 */
internal fun PlayerRuntimeController.skipAfrPreflightForLiveTv(url: String): Boolean {
    if (isUsingMpvEngine() || !LiveTvPlaybackRegistry.isLiveTv(url)) return false
    // The last channel's rate must not be matched again while the new one loads.
    _uiState.update {
        it.copy(detectedFrameRateRaw = 0f, detectedFrameRate = 0f, detectedFrameRateSource = null, afrProbeRunning = false)
    }
    return true
}

/** Matches the display once the playing channel's video track reports its frame rate. */
@Composable
internal fun LiveTvFrameRateMatch(state: LiveTvPlayerState, uiState: PlayerUiState) {
    val fps = if (uiState.detectedFrameRateSource == FrameRateSource.TRACK) uiState.detectedFrameRate else 0f
    val raw = uiState.detectedFrameRateRaw
    val mode = uiState.frameRateMatchingMode
    LaunchedEffect(fps, mode) {
        if (fps <= 0f || mode == FrameRateMatchingMode.OFF) return@LaunchedEffect
        state.matchDisplay(fps, raw)
    }
}

internal suspend fun PlayerRuntimeController.matchDisplayToLiveTrack(fps: Float, raw: Float) {
    if (isUsingMpvEngine()) return
    val activity = currentHostActivity() ?: return
    val settings = playerSettingsDataStore.playerSettings.first()
    val target = FrameRateUtils.refineFrameRateForDisplay(
        activity = activity,
        detectedFps = fps,
        prefer23976Near24 = raw in 23.95f..23.999f,
    )
    FrameRateUtils.matchFrameRateAndWait(
        activity = activity,
        frameRate = target,
        videoWidth = currentVideoWidth,
        videoHeight = currentVideoHeight,
        resolutionMatchingEnabled = settings.resolutionMatchingEnabled,
    )
}
