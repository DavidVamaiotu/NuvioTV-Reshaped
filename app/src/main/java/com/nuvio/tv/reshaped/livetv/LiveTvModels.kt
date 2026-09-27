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
    /** What hiding this channel stores: see [liveTvHideKey]. */
    val hideKey: Long = 0L,
)

/** The category key of channels the playlist gives no category; the screens call it "Uncategorised". */
const val LIVE_TV_UNGROUPED = ""

/**
 * A hidden channel is kept as a 64-bit hash of its source, category and name, not its link: the
 * link can be long, carries account details, and changes when a provider rotates tokens.
 */
fun liveTvHideKey(sourceId: String, group: String, name: String): Long {
    var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
    fun mix(text: String) {
        text.forEach { char ->
            hash = (hash xor char.code.toLong()) * 0x100000001b3L
        }
        hash = (hash xor 0x1fL) * 0x100000001b3L
    }
    mix(sourceId)
    mix(group)
    mix(name)
    return hash
}

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
    /** Single channels the viewer chose not to see ([LiveTvChannel.hideKey]), inside categories that stay. */
    val hiddenChannelKeys: Set<Long> = emptySet(),
    /** [channels] without hidden categories and channels: what All channels and zapping go through. */
    val shownChannels: List<LiveTvChannel> = emptyList(),
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
    /** Goes up each time a source is added, so the Sources dialog can tell an add went through. */
    val addedCount: Int = 0,
) {
    val hasSource: Boolean get() = sources.isNotEmpty()

    /** Categories the list shows. */
    val visibleGroups: List<String>
        get() = if (hiddenGroups.isEmpty()) groups else groups.filterNot(hiddenGroups::contains)
}
