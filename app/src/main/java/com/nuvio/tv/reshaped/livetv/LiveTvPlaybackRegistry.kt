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
    /** The registered URLs that are a past programme (catch-up), not the live channel. */
    @Volatile private var catchups: Set<String> = emptySet()

    @Synchronized
    fun register(playbackUrl: String, listUrl: String = playbackUrl, catchup: Boolean = false) {
        if (playbackUrl.isBlank()) return
        entries = (entries.filterNot { it.first == playbackUrl } + (playbackUrl to listUrl)).takeLast(MAX_URLS)
        val kept = entries.mapTo(HashSet()) { it.first }
        catchups = catchups.filterTo(HashSet()) { it in kept && it != playbackUrl }.apply { if (catchup) add(playbackUrl) }
    }

    /** Whether [url] plays a past programme: it can be paused and sought like a film. */
    fun isCatchup(url: String?): Boolean = url != null && url in catchups

    fun isLiveTv(url: String?): Boolean = url != null && entries.any { it.first == url }

    /** The list entry's URL for a URL the player is playing (itself when unknown). */
    fun listUrlFor(playbackUrl: String?): String? =
        playbackUrl?.let { url -> entries.lastOrNull { it.first == url }?.second ?: url }
}
