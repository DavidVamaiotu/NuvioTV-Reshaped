package com.nuvio.tv.ui.screens.player.seekpreview

import com.nuvio.tv.domain.model.Stream

/** Player panels can still hold a different episode's results after playback changes. */
internal fun previewCopyPanelStreams(
    currentRequestKey: String,
    sourceRequestKey: String?,
    sourceStreams: List<Stream>,
    episodeRequestKey: String?,
    episodeStreams: List<Stream>,
): List<Stream> = buildList {
    if (sourceRequestKey == currentRequestKey) addAll(sourceStreams)
    if (episodeRequestKey == currentRequestKey) addAll(episodeStreams)
}

/** Read only: the callback consults the existing search cache, never starts an addon search. */
internal suspend fun previewCopySourceStreams(
    panelStreams: List<Stream>,
    cachedStreams: suspend () -> List<Stream>?,
): List<Stream>? = (panelStreams + cachedStreams().orEmpty()).distinct().takeIf { it.isNotEmpty() }
