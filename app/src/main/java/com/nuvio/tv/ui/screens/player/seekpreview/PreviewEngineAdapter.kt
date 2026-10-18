@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.player.seekpreview

import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.seekpreview.engine.ServerPreviewTrack
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalPreviewSource

internal fun PlayerRuntimeController.serverPreview(source: LocalPreviewSource, durationMs: Long, onUnavailable: () -> Unit): ServerPreviewTrack? =
    ServerPreviewTrack.open(context, listOf(contentId, currentSeason, currentEpisode).joinToString("|"), durationMs,
        source.sourceKey, source.headers, previewServerSource(source.sourceKey)?.mediaSourceId, onUnavailable)

internal fun previewSingleFileUrl(url: String): Boolean {
    if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return false
    val path = url.substringBefore('?').lowercase()
    return listOf(".m3u8", ".m3u", ".mpd", ".ism", "/manifest").none(path::endsWith) &&
        listOf("/resolve/", "/torrent/", "/transcode/").none { it in path }
}

internal fun PlayerRuntimeController.previewDroppedFrames(): Int =
    _exoPlayer?.videoDecoderCounters?.droppedBufferCount ?: -1
