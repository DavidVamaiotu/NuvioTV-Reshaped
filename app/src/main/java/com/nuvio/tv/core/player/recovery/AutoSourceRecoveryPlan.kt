package com.nuvio.tv.core.player.recovery

import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Immutable selection snapshot. Retry order is owned by the existing autoplay selector. */
data class AutoSourceRecoveryPlan(
    val streams: List<Stream>,
    val mode: StreamAutoPlayMode,
    val regex: String,
    val rankedRegex: List<String>,
    val source: StreamAutoPlaySource,
    val installedAddonNames: Set<String>,
    val selectedAddons: Set<String>,
    val selectedPlugins: Set<String>,
    val preferredBingeGroup: String?,
    val bingeGroupOnly: Boolean,
    val initialSource: Stream?,
    val profileId: Int,
    val videoId: String?,
    val attempted: Set<String> = emptySet(),
    val startupState: StartupSourceRecovery.State? = null
) {
    suspend fun next(attempted: Set<String>): Stream? = withContext(Dispatchers.Default) {
        StreamAutoPlaySelector.selectAutoPlayStreamAsync(
            streams = streams.filter { recoverySourceKey(it) !in attempted && "url:${it.getStreamUrl()}" !in attempted },
            mode = mode,
            regexPattern = regex,
            rankedRegexPatterns = rankedRegex,
            source = source,
            installedAddonNames = installedAddonNames,
            selectedAddons = selectedAddons,
            selectedPlugins = selectedPlugins,
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = preferredBingeGroup != null,
            bingeGroupOnly = bingeGroupOnly
        )
    }

    companion object {
        fun create(
            streams: List<Stream>, settings: PlayerSettings, installedAddonNames: Set<String>,
            preferredBingeGroup: String?, initialSource: Stream?, profileId: Int, videoId: String?,
            bingeGroupOnly: Boolean = settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL
        ) = AutoSourceRecoveryPlan(
            streams.toList(), settings.streamAutoPlayMode, settings.streamAutoPlayRegex,
            settings.streamAutoPlayRankedRegex.toList(), settings.streamAutoPlaySource,
            installedAddonNames.toSet(), settings.streamAutoPlaySelectedAddons.toSet(),
            settings.streamAutoPlaySelectedPlugins.toSet(), preferredBingeGroup, bingeGroupOnly,
            initialSource, profileId, videoId
        )
    }
}

/** Keep signed-URL refreshes of one provider/file from repeatedly consuming the retry queue. */
fun recoverySourceKey(stream: Stream): String {
    val hash = stream.getEffectiveInfoHash()
    return if (hash != null) {
        val file = stream.getEffectiveFileIdx()?.toString()
            ?: stream.clientResolve?.filename ?: stream.behaviorHints?.filename.orEmpty()
        "file:${stream.addonName}:${hash.lowercase()}:$file"
    } else {
        "url:${stream.getStreamUrl() ?: stream.youTubeIdToResolve() ?: stream.toString()}"
    }
}
