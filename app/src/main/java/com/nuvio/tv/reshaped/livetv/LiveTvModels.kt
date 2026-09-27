package com.nuvio.tv.reshaped.livetv

import androidx.compose.runtime.Immutable

/** A channel as the list shows it. [streamUrl] is also its identity (favorites, last watched). */
@Immutable
data class LiveTvChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val tvgId: String? = null,
    val logoUrl: String? = null,
    val group: String = "",
    val headers: Map<String, String> = emptyMap(),
    /** Stalker only: the command a playable link is created from, per play. */
    val stalkerCommand: String? = null,
    /** The [LiveTvSource.id] this channel was listed by. */
    val sourceId: String = "",
)

@Immutable
data class LiveTvRecentChannel(
    val streamUrl: String,
    val name: String,
    val logoUrl: String? = null,
    val group: String = "",
    val tvgId: String? = null,
)

@Immutable
data class LiveTvProgramme(
    val title: String,
    val startEpochMs: Long,
    val stopEpochMs: Long,
    val timeLabel: String,
)

enum class LiveTvSourceType { M3u, Stalker, Xtream }

@Immutable
data class LiveTvStalkerSettings(
    val portalUrl: String = "",
    val macAddress: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean get() = portalUrl.isNotBlank() && macAddress.isNotBlank()
}

@Immutable
data class LiveTvXtreamSettings(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

/** One saved channel source. Several can be added; their channels show as one list. */
@Immutable
data class LiveTvSource(
    val id: String,
    val type: LiveTvSourceType,
    /** The M3U link or an imported file's name; the server or portal URL for the others. */
    val url: String = "",
    val stalker: LiveTvStalkerSettings = LiveTvStalkerSettings(),
    val xtream: LiveTvXtreamSettings = LiveTvXtreamSettings(),
) {
    /** A short name for lists: the host of a link, or the imported file's name. */
    val label: String
        get() = url.substringAfter("://", url).substringBefore('/').substringBefore('?')
            .substringAfterLast('@').ifBlank { url }

    /** Two sources with the same identity are one: adding it again replaces it. */
    internal val identity: String
        get() = when (type) {
            LiveTvSourceType.M3u -> "m3u|${url.lowercase()}"
            LiveTvSourceType.Xtream -> "xtream|${xtream.serverUrl.lowercase()}|${xtream.username}"
            LiveTvSourceType.Stalker -> "stalker|${stalker.portalUrl.lowercase()}|${stalker.macAddress}"
        }
}

@Immutable
data class LiveTvUiState(
    val sources: List<LiveTvSource> = emptyList(),
    val channels: List<LiveTvChannel> = emptyList(),
    /** Sorted category names of [channels], hidden ones included, computed once per load rather than per frame. */
    val groups: List<String> = emptyList(),
    /** How many channels each category has. */
    val groupCounts: Map<String, Int> = emptyMap(),
    /** Categories the viewer chose not to see: their channels leave the list, search and zapping. */
    val hiddenGroups: Set<String> = emptySet(),
    /** Single channels the viewer chose not to see (by stream URL), inside categories that stay. */
    val hiddenChannelUrls: Set<String> = emptySet(),
    /** How many channels each source listed. */
    val sourceCounts: Map<String, Int> = emptyMap(),
    /** Sources whose last load failed (their earlier channels, if any, stay listed). */
    val sourceErrors: Map<String, LiveTvError> = emptyMap(),
    /** Channel tvg-id (as the playlist spells it) to the programme on air now. */
    val currentProgrammes: Map<String, LiveTvProgramme> = emptyMap(),
    val recentChannel: LiveTvRecentChannel? = null,
    val favoriteUrls: Set<String> = emptySet(),
    val isEpgLoading: Boolean = false,
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    /** The last failed attempt to add a source. */
    val error: LiveTvError? = null,
) {
    val hasSource: Boolean get() = sources.isNotEmpty()

    /** Categories the list shows. */
    val visibleGroups: List<String>
        get() = if (hiddenGroups.isEmpty()) groups else groups.filterNot(hiddenGroups::contains)
}
