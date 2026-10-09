package com.nuvio.tv.ui.reshaped.netflix

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Density
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.Disposable
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/**
 * Keeps the wide art of the titles either side of a focused row's focus in Coil's memory cache,
 * so the next card opens onto its art. Fetching starts only once focus rests for
 * [NetflixTokens.PRELOAD_DWELL_MS], so quick moves decode nothing extra. What this put in memory
 * is removed again once focus is [NetflixTokens.PRELOAD_KEEP] titles away, or when the row loses
 * focus or leaves the page, so it never crowds the posters out. [center] null: nothing to keep
 * (row not focused, or a held key gliding).
 */
internal suspend fun netflixPreloadRowArt(
    context: Context,
    density: Density,
    items: () -> List<MetaPreview>,
    center: () -> Int?,
) {
    val loader = context.imageLoader
    val artSize = netflixArtworkSizePx(density)
    // Cache key -> the title index it belongs to and its request (null: it was already in memory).
    val held = HashMap<String, Pair<Int, Disposable?>>()

    fun drop(key: String) {
        held.remove(key)?.second?.dispose()
        loader.memoryCache?.remove(MemoryCache.Key(key))
    }

    try {
        snapshotFlow { center() }.collectLatest { focus ->
            if (focus == null) {
                held.keys.toList().forEach(::drop)
                return@collectLatest
            }
            held.keys.toList().forEach { key ->
                val index = held[key]?.first ?: return@forEach
                if (abs(index - focus) >= NetflixTokens.PRELOAD_KEEP) drop(key)
            }
            delay(NetflixTokens.PRELOAD_DWELL_MS)
            val list = items()
            for (index in intArrayOf(focus + 1, focus - 1)) {
                val item = list.getOrNull(index) ?: continue
                if (item.id.startsWith("__placeholder_")) continue
                val url = item.netflixArtworkUrl() ?: continue
                val key = netflixArtworkCacheKey(url, artSize)
                if (key in held) continue
                held[key] = index to if (loader.memoryCache?.get(MemoryCache.Key(key)) != null) null
                else loader.enqueue(netflixArtworkRequest(context, url, artSize))
            }
        }
    } finally {
        held.keys.toList().forEach(::drop)
    }
}
