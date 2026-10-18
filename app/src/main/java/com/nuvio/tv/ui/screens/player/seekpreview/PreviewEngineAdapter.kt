package com.nuvio.tv.ui.screens.player.seekpreview

import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.seekpreview.engine.PreviewCopy
import com.nuvio.tv.ui.screens.player.seekpreview.engine.RangePreviewTrack
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalPreviewSource

/** The only boundary that knows Nuvio's controller or Stream model. The engine takes immutable values. */
internal fun PlayerRuntimeController.rangePreview(source: LocalPreviewSource, durationMs: Long): RangePreviewTrack? {
    val url = source.sourceKey
    if (!previewSingleFileUrl(url) || playbackTimeline.value.isLive) return null
    return RangePreviewTrack(context, listOf(contentId, currentSeason, currentEpisode).joinToString("|"), durationMs,
        url, source.headers, currentStreamDescription.orEmpty(), copies = {
            // Read the latest already-fetched list. Never searches addons, resolves torrents, or changes an account.
            (uiState.value.sourceAllStreams + uiState.value.episodeAllStreams).mapNotNull { stream ->
                val link = stream.getStreamUrl()?.takeIf { it != url && previewSingleFileUrl(it) } ?: return@mapNotNull null
                if (stream.clientResolve != null || stream.isTorrent() || stream.serverTarget != null) return@mapNotNull null
                val path = link.substringBefore('?').lowercase()
                // Only plain file links; addon resolve endpoints can mutate debrid accounts just by opening them.
                if (!listOf(".mkv", ".mp4", ".m4v").any(path::endsWith)) return@mapNotNull null
                val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename).joinToString(" ")
                val height = when {
                    Regex("(?i)1080[pil]?").containsMatchIn(text) -> 1080
                    Regex("(?i)720[pil]?").containsMatchIn(text) -> 720
                    else -> return@mapNotNull null
                }
                PreviewCopy(link, stream.behaviorHints?.proxyHeaders?.request.orEmpty(), text, height,
                    Regex("(?i)(h[ ._-]?264|x264|avc)").containsMatchIn(text),
                    Regex("(?i)(hdr|dolby[ ._-]?vision|\\bDV\\b)").containsMatchIn(text), null)
            }
        }, mediaServer = serverPlayback.isServerSource(url), serverSourceId = serverPlayback.session(url)?.mediaSourceId)
}

internal fun previewSingleFileUrl(url: String): Boolean {
    if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return false
    val path = url.substringBefore('?').lowercase()
    return listOf(".m3u8", ".m3u", ".mpd", ".ism", "/manifest").none(path::endsWith) &&
        listOf("/resolve/", "/usenet/stream/", "/torrent/", "/transcode/").none { it in path }
}
