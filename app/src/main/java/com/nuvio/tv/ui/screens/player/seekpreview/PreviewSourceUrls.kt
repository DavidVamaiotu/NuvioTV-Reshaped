package com.nuvio.tv.ui.screens.player.seekpreview

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Optional protocol metadata; independent of Nuvio's version-specific server models. */
internal data class PreviewServerSource(val mediaSourceId: String?)

internal fun previewServerSource(url: String): PreviewServerSource? {
    val parsed = url.toHttpUrlOrNull() ?: return null
    val parts = parsed.pathSegments
    val videos = parts.indexOfFirst { it.equals("Videos", ignoreCase = true) }
    if (videos < 0) return null
    val id = parts.getOrNull(videos + 1) ?: return null
    if (!id.matches(Regex("[A-Za-z0-9_-]+"))) return null
    val endpoint = parts.getOrNull(videos + 2)?.substringBefore('.')?.lowercase()
    if (endpoint !in setOf("stream", "master", "main", "static", "universal", "download")) return null
    val source = parsed.queryParameter("MediaSourceId") ?: parsed.queryParameter("mediaSourceId")
    return PreviewServerSource(source?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) })
}
