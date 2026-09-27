package com.nuvio.tv.reshaped.livetv

/**
 * Stream URLs Live TV has sent to the player, so the fork's playback extras (disk read-ahead,
 * connection speed learning) leave live channels alone, and the player overlay knows it is
 * showing a channel. Also remembers which list entry each one came from: a Stalker link is
 * created per play and differs from the list's URL. Reads are memory lookups.
 */
object LiveTvPlaybackRegistry {
    private const val MAX_URLS = 16

    /** Playback URL to the list entry's URL, most recent last. */
    @Volatile private var entries: List<Pair<String, String>> = emptyList()

    @Synchronized
    fun register(playbackUrl: String, listUrl: String = playbackUrl) {
        if (playbackUrl.isBlank()) return
        entries = (entries.filterNot { it.first == playbackUrl } + (playbackUrl to listUrl)).takeLast(MAX_URLS)
    }

    fun isLiveTv(url: String?): Boolean = url != null && entries.any { it.first == url }

    /** The list entry's URL for a URL the player is playing (itself when unknown). */
    fun listUrlFor(playbackUrl: String?): String? =
        playbackUrl?.let { url -> entries.lastOrNull { it.first == url }?.second ?: url }
}
