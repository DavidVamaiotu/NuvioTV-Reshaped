package com.nuvio.tv.ui.reshaped.netflix

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Density
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.Disposable
import coil3.request.ImageRequest
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/** Where a row's focus is: [center] is the title it rests on (or will open on, when not focused). */
internal data class NetflixPreloadWindow(val center: Int, val focused: Boolean)

/**
 * Keeps the wide art (and its logo) of the titles around a row's focus in Coil's memory cache.
 *
 * In the focused row that is [NetflixTokens.PRELOAD_AHEAD] titles ahead and
 * [NetflixTokens.PRELOAD_BEHIND] behind; any other row on screen keeps just the title it would
 * open on. What this put in memory is removed again once focus is more than
 * [NetflixTokens.PRELOAD_KEEP] titles away, when the row loses focus, or when it leaves the
 * page, so preloading never crowds the posters out of the cache. Requests still in flight are
 * cancelled the same way. Runs until cancelled; [window] null pauses it (a held key).
 */
internal suspend fun netflixPreloadRowArt(
    context: Context,
    density: Density,
    items: () -> List<MetaPreview>,
    window: () -> NetflixPreloadWindow?,
) {
    val loader = context.imageLoader
    val artSize = netflixArtworkSizePx(density)
    val logoSize = netflixLogoSizePx(density)
    // Cache key -> the title index it belongs to and its request (null: it was already in memory).
    val held = HashMap<String, Pair<Int, Disposable?>>()

    fun drop(key: String) {
        held.remove(key)?.second?.dispose()
        loader.memoryCache?.remove(MemoryCache.Key(key))
    }

    fun requestsFor(item: MetaPreview): List<Pair<String, ImageRequest>> {
        if (item.id.startsWith("__placeholder_")) return emptyList()
        val art = item.netflixArtworkUrl() ?: return emptyList()
        return buildList {
            add(netflixArtworkCacheKey(art, artSize) to netflixArtworkRequest(context, art, artSize))
            // The tile draws the logo only over a plain backdrop (landscape posters carry the title).
            val logo = item.logo?.takeIf { it.isNotBlank() }
            if (logo != null && item.landscapePoster.isNullOrBlank()) {
                add(netflixLogoCacheKey(logo, logoSize) to netflixLogoRequest(context, logo, logoSize))
            }
        }
    }

    try {
        snapshotFlow { window() }.collectLatest { current ->
            current ?: return@collectLatest
            // Rows scrolled past quickly fetch nothing.
            if (!current.focused) delay(UNFOCUSED_PRELOAD_DELAY_MS)
            val list = items()
            val range = if (current.focused) {
                (current.center - NetflixTokens.PRELOAD_BEHIND)..(current.center + NetflixTokens.PRELOAD_AHEAD)
            } else {
                current.center..current.center
            }
            val wanted = HashMap<String, Pair<Int, ImageRequest>>()
            for (index in range) {
                // The focused title loads itself; preloading it too would fetch it twice.
                if (current.focused && index == current.center) continue
                val item = list.getOrNull(index) ?: continue
                requestsFor(item).forEach { (key, request) -> wanted[key] = index to request }
            }
            held.keys.toList().forEach { key ->
                if (key in wanted) return@forEach
                val index = held[key]?.first ?: return@forEach
                val near = current.focused && abs(index - current.center) <= NetflixTokens.PRELOAD_KEEP
                if (!near) drop(key)
            }
            wanted.forEach { (key, value) ->
                if (key in held) return@forEach
                val (index, request) = value
                held[key] = index to if (loader.memoryCache?.get(MemoryCache.Key(key)) != null) null else loader.enqueue(request)
            }
        }
    } finally {
        held.keys.toList().forEach(::drop)
    }
}

private const val UNFOCUSED_PRELOAD_DELAY_MS = 200L
