package com.nuvio.tv.core.streams.ranking

import com.nuvio.tv.domain.model.Stream

/** The sole model adapter for the standalone ranking engine. No URLs are resolved or fetched. */
object StreamRegexRanking {
    fun rank(streams: List<Stream>, patterns: List<String>): List<Stream>? =
        RankedRegexRules.create(patterns)?.rank(streams, ::searchableText)

    fun searchableText(stream: Stream): String = buildString {
        append(stream.addonName).append(' ')
        append(stream.name.orEmpty()).append(' ')
        append(stream.title.orEmpty()).append(' ')
        append(stream.description.orEmpty()).append(' ')
        append(stream.quality.orEmpty()).append(' ')
        append(stream.behaviorHints?.filename.orEmpty()).append(' ')
        append(stream.clientResolve?.filename.orEmpty()).append(' ')
        append(stream.clientResolve?.torrentName.orEmpty()).append(' ')
        append(stream.getStreamUrl().orEmpty())
        if (stream.isTorrent()) append(' ').append(stream.infoHash.orEmpty())
    }
}
