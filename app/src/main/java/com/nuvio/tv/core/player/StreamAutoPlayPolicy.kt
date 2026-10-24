package com.nuvio.tv.core.player

import com.nuvio.tv.core.streams.ranking.RankedRegexRules
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode

object StreamAutoPlayPolicy {
    fun isEffectivelyEnabled(playerSettings: PlayerSettings): Boolean {
        if (playerSettings.streamReuseLastLinkEnabled) return true
        if (playerSettings.streamAutoPlayReuseBingeGroup &&
            playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) return true

        return when (playerSettings.streamAutoPlayMode) {
            StreamAutoPlayMode.MANUAL -> false
            StreamAutoPlayMode.FIRST_STREAM -> true
            StreamAutoPlayMode.REGEX_MATCH -> isRegexSelectionConfigured(playerSettings.streamAutoPlayRegex, playerSettings.streamAutoPlayRankedRegex)
        }
    }

    fun isRegexSelectionConfigured(regexPattern: String, rankedPatterns: List<String> = emptyList()): Boolean {
        if (rankedPatterns.isNotEmpty()) {
            return RankedRegexRules.create(rankedPatterns) != null
        }
        val pattern = regexPattern.trim()
        if (pattern.isEmpty() || !pattern.any { it.isLetterOrDigit() }) return false
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.isSuccess
    }
}
