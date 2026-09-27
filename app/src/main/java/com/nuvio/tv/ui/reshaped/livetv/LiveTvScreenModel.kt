package com.nuvio.tv.ui.reshaped.livetv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvError
import com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps [LiveTvRepository] on the active profile. The list itself lives in the repository. */
@HiltViewModel
class LiveTvScreenModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager,
) : ViewModel() {
    init {
        viewModelScope.launch {
            profileManager.activeProfileId.collectLatest { profileId ->
                LiveTvRepository.ensureLoaded(context, profileId)
            }
        }
    }

    val profileId: Int get() = profileManager.activeProfileId.value
}

/**
 * The player route for a Live TV channel: resolves its playable link, and plays it as Stremio
 * type "channel" with no content id, so Nuvio's player treats it as live and saves no progress.
 */
internal suspend fun liveTvPlayerRoute(channel: LiveTvChannel, profileId: Int): String {
    val playback = LiveTvRepository.prepareForPlayback(channel)
    // The player keys on the URL it plays, which drops a user:password@ part into a header.
    val playerUrl = PlayerMediaSourceFactory.normalizePlaybackRequest(playback.streamUrl, playback.headers).url
    LiveTvPlaybackRegistry.register(playerUrl, listUrl = channel.streamUrl)
    return Screen.Player.createRoute(
        streamUrl = playback.streamUrl,
        title = channel.name,
        streamName = channel.name,
        headers = playback.headers,
        contentType = LIVE_TV_CONTENT_TYPE,
        logo = channel.logoUrl,
        addonName = LIVE_TV_ADDON_NAME,
        streamDescription = channel.group.takeIf(String::isNotBlank),
        profileId = profileId,
    )
}

internal const val LIVE_TV_CONTENT_TYPE = "channel"
internal const val LIVE_TV_ADDON_NAME = "Live TV"

internal fun LiveTvError.message(context: Context): String = context.getString(
    when (this) {
        LiveTvError.InvalidUrl -> R.string.live_tv_error_invalid_url
        LiveTvError.NoChannels -> R.string.live_tv_error_no_channels
        LiveTvError.LoadFailed -> R.string.live_tv_error_load_failed
        LiveTvError.FileEmpty -> R.string.live_tv_error_file_empty
        LiveTvError.FileNoChannels -> R.string.live_tv_error_file_no_channels
        LiveTvError.StalkerRequired -> R.string.live_tv_error_stalker_required
        LiveTvError.StalkerInvalidUrl -> R.string.live_tv_error_stalker_invalid_url
        LiveTvError.StalkerNoChannels -> R.string.live_tv_error_stalker_no_channels
        LiveTvError.StalkerFailed -> R.string.live_tv_error_stalker_failed
        LiveTvError.StalkerToken -> R.string.live_tv_error_stalker_token
        LiveTvError.XtreamRequired -> R.string.live_tv_error_xtream_required
        LiveTvError.XtreamInvalidUrl -> R.string.live_tv_error_xtream_invalid_url
        LiveTvError.XtreamNoChannels -> R.string.live_tv_error_xtream_no_channels
        LiveTvError.XtreamFailed -> R.string.live_tv_error_xtream_failed
    },
)
