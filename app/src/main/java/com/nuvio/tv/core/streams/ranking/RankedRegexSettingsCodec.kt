package com.nuvio.tv.core.streams.ranking

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Ordered JSON storage preserves backslashes, pipes, inline flags, and embedded newlines. */
object RankedRegexSettingsCodec {
    fun encode(patterns: List<String>): String {
        require(patterns.isEmpty() || RankedRegexRules.create(patterns) != null)
        return Json.encodeToString(patterns.map { it.trim() })
    }

    fun decode(value: String?): List<String> {
        if (value == null) return emptyList()
        // A corrupt saved configuration must not silently switch to legacy auto-selection.
        return runCatching { Json.decodeFromString<List<String>>(value) }.getOrDefault(listOf("["))
    }
}
