package com.nuvio.tv.ui.reshaped.netflix

import com.nuvio.tv.domain.model.MetaPreview

/** Consume Nuvio's existing enrichment without replacing a row's identity or custom poster art. */
internal fun netflixEnrichedPreview(item: MetaPreview, previews: Map<String, MetaPreview>): MetaPreview {
    val enriched = previews[item.id]?.takeIf { it.apiType == item.apiType } ?: return item
    if (enriched == item) return item
    return enriched.copy(
        id = item.id,
        type = item.type,
        rawType = item.rawType,
        poster = item.poster,
        posterShape = item.posterShape,
        landscapePoster = item.landscapePoster,
        rawPosterUrl = item.rawPosterUrl,
        sourceAddonBaseUrl = item.sourceAddonBaseUrl
    )
}
