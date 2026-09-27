package com.nuvio.tv.ui.reshaped.livetv

import com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController

/**
 * A channel change from one Live TV channel to another keeps the ExoPlayer it already has, so a
 * zap skips rebuilding renderers, codecs and the audio sink, which takes seconds on weak TVs.
 * The new channel is then set on that player by Nuvio's own switch. Returns false (rebuild as
 * usual) for anything else, including the mpv engine.
 */
internal fun PlayerRuntimeController.keepPlayerForLiveTvZap(newUrl: String): Boolean {
    val player = _exoPlayer ?: return false
    if (!LiveTvPlaybackRegistry.isLiveTv(currentStreamUrl) || !LiveTvPlaybackRegistry.isLiveTv(newUrl)) return false
    errorRetryJob?.cancel()
    errorRetryJob = null
    playbackPreparationJob?.cancel()
    playbackPreparationJob = null
    runCatching {
        player.stop()
        player.clearMediaItems()
    }
    return true
}
