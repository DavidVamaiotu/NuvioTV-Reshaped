// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

private val ADAPTIVE_MIME_TYPES = setOf(
    "application/x-mpegurl",
    "application/vnd.apple.mpegurl",
    "audio/mpegurl",
    "audio/x-mpegurl",
    "application/dash+xml",
    "application/vnd.ms-sstr+xml"
)

private val ADAPTIVE_PATH_SUFFIXES = listOf(".m3u8", ".m3u", ".mpd", ".ism", ".ism/manifest")

/**
 * Thumbnails read keyframes out of one file by range request. A segmented stream (HLS, DASH,
 * Smooth Streaming) has no such file, so it is left alone before anything is shown or fetched.
 */
internal fun isThumbnailSource(url: String?, mimeType: String? = null): Boolean {
    if (url.isNullOrBlank()) return false
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return false
    if (mimeType != null && mimeType.trim().lowercase() in ADAPTIVE_MIME_TYPES) return false
    val path = url.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
    return ADAPTIVE_PATH_SUFFIXES.none { path.endsWith(it) }
}

/**
 * Whether a restarted session gives back the player its generating screen paused. A new stream or
 * engine starts with its own autoplay, and the player there may still be opening its first frame.
 */
internal fun handsPlayerBackOnRestart(previous: Pair<String?, Boolean>?, url: String?, onMpv: Boolean): Boolean =
    previous == null || (previous.first == url && previous.second == onMpv)
