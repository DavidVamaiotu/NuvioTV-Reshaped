package com.nuvio.tv.ui.screens.player.seekpreview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.seekr.previews.android.SeekrThumbnail
import tv.seekr.previews.core.PreviewTrack
import tv.seekr.previews.core.SeekrContent
import tv.seekr.previews.core.SeekrPreviews
import tv.seekr.previews.core.SeekrTile

/**
 * Stands in for the Seekr library's `Seekr`/`SeekrTrack` pair with the same API, but with memory
 * bounded for 2 GB TVs.
 *
 * The library decodes every sprite sheet of a title at playback start and keeps all of them as
 * full-size bitmaps for the whole session (its `maxCachedSheets` argument is ignored), which is
 * over 100 MB on a long film. Here every sheet is still downloaded at the same moment, but kept
 * as its compressed bytes; only the sheet in use and its neighbours stay decoded, and the
 * neighbours are decoded ahead so stepping across a sheet boundary finds them ready. Tiles are
 * cropped from the same ARGB_8888 sheets as before, so thumbnails look the same.
 */
internal object BoundedSeekr {
    /** One client for every title, so connections are pooled rather than rebuilt per film. */
    private val http: OkHttpClient by lazy { OkHttpClient() }

    suspend fun loadTrack(apiKey: String, content: SeekrContent, durationMs: Long): BoundedSeekrTrack? {
        val track = SeekrPreviews.create(apiKey, httpClient = http).loadTrack(content, durationMs) ?: return null
        return BoundedSeekrTrack(track, BoundedSheetCache(http, track.sheetUrls.toList()))
    }
}

class BoundedSeekrTrack internal constructor(
    private val track: PreviewTrack,
    private val sheets: BoundedSheetCache,
) {
    val isEmpty: Boolean get() = track.isEmpty
    val sourceDurationMs: Long get() = track.sourceDurationMs
    val scale: Double get() = track.scale

    var offsetMs: Long
        get() = track.offsetMs
        set(value) {
            track.offsetMs = value
        }

    /** Downloads every sheet in parallel, as the library does, keeping them compressed. */
    suspend fun prefetchSheets() = sheets.downloadAll()

    /** Decodes the sheet around [positionMs] and its neighbours ahead of the first scrub. */
    fun warm(positionMs: Long) {
        track.tileAt(positionMs)?.let { sheets.warm(it.sheetUrl) }
    }

    suspend fun thumbnailAt(positionMs: Long): Bitmap? {
        val tile = track.tileAt(positionMs) ?: return null
        return cropTile(tile)
    }

    suspend fun thumbnailFor(positionMs: Long): SeekrThumbnail? {
        val cue = track.cueAt(positionMs) ?: return null
        val bitmap = cropTile(cue.tile) ?: return null
        return SeekrThumbnail(bitmap = bitmap, cueStartMs = cue.startMs, cueEndMs = cue.endMs)
    }

    // Same crop as the library's SeekrTrack.
    private suspend fun cropTile(tile: SeekrTile): Bitmap? {
        val sheet = sheets.get(tile.sheetUrl) ?: return null
        val tileW = tile.w.takeIf { it > 0 } ?: 320
        val tileH = tile.h.takeIf { it > 0 } ?: 180
        val x = tile.x.coerceIn(0, (sheet.width - 1).coerceAtLeast(0))
        val y = tile.y.coerceIn(0, (sheet.height - 1).coerceAtLeast(0))
        val w = tileW.coerceAtMost((sheet.width - x).coerceAtLeast(1))
        val h = tileH.coerceAtMost((sheet.height - y).coerceAtLeast(1))
        return runCatching { Bitmap.createBitmap(sheet, x, y, w, h) }.getOrNull()
    }
}

/**
 * Compressed bytes for every sheet, decoded bitmaps for at most [MAX_DECODED] of them. A cropped
 * tile is its own copy, so evicting a sheet never affects a thumbnail on screen; evicted sheets
 * are left to the garbage collector rather than recycled.
 */
internal class BoundedSheetCache(
    private val http: OkHttpClient,
    /** Sheets in timeline order, so the ones either side of a sheet are its neighbours. */
    private val urls: List<String>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val encoded = ConcurrentHashMap<String, ByteArray>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val decoded = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > MAX_DECODED
    }

    private fun mutexFor(url: String): Mutex = mutexes.computeIfAbsent(url) { Mutex() }

    private fun cached(url: String): Bitmap? = synchronized(decoded) { decoded[url] }

    suspend fun downloadAll() {
        coroutineScope {
            urls.map { url -> async { bytes(url) } }.awaitAll()
        }
    }

    /** The decoded sheet; its neighbours are decoded in the background for the next step. */
    suspend fun get(url: String): Bitmap? {
        val sheet = decode(url)
        if (sheet != null) warmNeighbours(url)
        return sheet
    }

    fun warm(url: String) {
        scope.launch { decode(url) }
        warmNeighbours(url)
    }

    private fun warmNeighbours(url: String) {
        val index = urls.indexOf(url)
        if (index < 0) return
        for (neighbour in listOf(index + 1, index - 1)) {
            val next = urls.getOrNull(neighbour) ?: continue
            if (cached(next) == null) scope.launch { decode(next) }
        }
    }

    private suspend fun decode(url: String): Bitmap? {
        cached(url)?.let { return it }
        return mutexFor(url).withLock {
            cached(url)?.let { return@withLock it }
            val data = bytes(url) ?: return@withLock null
            val bitmap = withContext(Dispatchers.Default) {
                runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
            } ?: return@withLock null
            synchronized(decoded) { decoded[url] = bitmap }
            bitmap
        }
    }

    private suspend fun bytes(url: String): ByteArray? {
        encoded[url]?.let { return it }
        return downloadMutexFor(url).withLock {
            encoded[url]?.let { return@withLock it }
            download(url)?.also { encoded[url] = it }
        }
    }

    private val downloadMutexes = ConcurrentHashMap<String, Mutex>()

    private fun downloadMutexFor(url: String): Mutex = downloadMutexes.computeIfAbsent(url) { Mutex() }

    private suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()
            }
        }.getOrNull()
    }

    private companion object {
        /** The sheet in use, the ones either side, and the one just left. */
        const val MAX_DECODED = 4
    }
}
