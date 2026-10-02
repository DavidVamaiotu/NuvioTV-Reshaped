package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry
import kotlinx.coroutines.flow.update

/**
 * A replay (catch-up, or a programme started over) plays on instead of stopping at the guide's
 * end time: it follows the player's own position updates, see [LiveTvPlayerState.followReplay].
 * Should the stream still end first (a provider that has less than was asked for), the end is
 * taken as the cue for what follows rather than as the end of a film, which would close the player.
 */
@Composable
internal fun LiveTvReplayFollow(state: LiveTvPlayerState) {
    LaunchedEffect(state) {
        val controller = state.player
        var watched: ExoPlayer? = null
        val endGuard = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                val window = LiveTvPlaybackRegistry.replayWindow(controller.currentStreamUrl) ?: return
                // Nuvio's listener, added first, has just marked the end; it is cleared before
                // the player screen reads it.
                controller._uiState.update { it.copy(playbackEnded = false) }
                state.continueReplay(window)
            }
        }
        try {
            controller.playbackTimeline.collect { timeline ->
                if (LiveTvPlaybackRegistry.replayWindow(controller.currentStreamUrl) == null) return@collect
                val player = controller._exoPlayer
                if (player !== watched) {
                    watched?.removeListener(endGuard)
                    player?.addListener(endGuard)
                    watched = player
                }
                state.followReplay(timeline.currentPosition, timeline.duration)
            }
        } finally {
            watched?.removeListener(endGuard)
        }
    }
}
