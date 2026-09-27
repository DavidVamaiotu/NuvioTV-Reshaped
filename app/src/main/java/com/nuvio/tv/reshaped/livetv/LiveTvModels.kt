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

@Immutable
data class LiveTvUiState(
    val sourceType: LiveTvSourceType = LiveTvSourceType.M3u,
    /** The M3U link, the portal or server URL, or an imported file's name. */
    val sourceUrl: String = "",
    val stalkerSettings: LiveTvStalkerSettings = LiveTvStalkerSettings(),
    val xtreamSettings: LiveTvXtreamSettings = LiveTvXtreamSettings(),
    val channels: List<LiveTvChannel> = emptyList(),
    /** Sorted category names of [channels], computed once per load rather than per frame. */
    val groups: List<String> = emptyList(),
    /** Channel tvg-id (as the playlist spells it) to the programme on air now. */
    val currentProgrammes: Map<String, LiveTvProgramme> = emptyMap(),
    val recentChannel: LiveTvRecentChannel? = null,
    val favoriteUrls: Set<String> = emptySet(),
    val isEpgLoading: Boolean = false,
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    val error: LiveTvError? = null,
) {
    val hasSource: Boolean
        get() = when (sourceType) {
            LiveTvSourceType.M3u -> sourceUrl.isNotBlank()
            LiveTvSourceType.Stalker -> stalkerSettings.isConfigured
            LiveTvSourceType.Xtream -> xtreamSettings.isConfigured
        }
}
