package com.nuvio.tv.ui.screens.player.seekpreview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.nuvio.tv.ui.screens.player.seekpreview.engine.PreviewLru
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
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

/** Seekr adapter with asynchronous latest-target fetching and region-only atlas decoding for small TVs. */
internal object BoundedSeekr {
    /** One client for every title, so connections are pooled rather than rebuilt per film. */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS).build()
    }

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
        track.tileAt(positionMs)?.let { sheets.request(it) }
    }

    suspend fun thumbnailAt(positionMs: Long): Bitmap? {
        val tile = track.tileAt(positionMs) ?: return null
        return cropTile(tile)
    }

    suspend fun thumbnailFor(positionMs: Long): SeekrThumbnail? {
        val cue = track.cueAt(positionMs) ?: return null
        val interval = (cue.endMs - cue.startMs).coerceAtLeast(1)
        val neighbours = listOfNotNull(track.tileAt(cue.endMs), track.tileAt((cue.startMs - 1).coerceAtLeast(0)),
            track.tileAt(cue.endMs + interval), track.tileAt((cue.startMs - interval - 1).coerceAtLeast(0)))
        val bitmap = sheets.tile(cue.tile, request = true, neighbours = neighbours) ?: return null
        return SeekrThumbnail(bitmap = bitmap, cueStartMs = cue.startMs, cueEndMs = cue.endMs)
    }

    suspend fun sideThumbnailFor(positionMs: Long): SeekrThumbnail? {
        val cue = track.cueAt(positionMs) ?: return null
        val bitmap = sheets.tile(cue.tile, request = false) ?: return null
        return SeekrThumbnail(bitmap = bitmap, cueStartMs = cue.startMs, cueEndMs = cue.endMs)
    }

    private suspend fun cropTile(tile: SeekrTile): Bitmap? = sheets.tile(tile, request = true)
    val revision: StateFlow<Int> get() = sheets.revision
    fun close() = sheets.close()
}

/** One conflated target, byte-bounded compressed sheets and individual decoded tiles. No full atlas bitmap. */
internal class BoundedSheetCache(private val http: OkHttpClient, private val urls: List<String>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val encoded = PreviewLru<String, ByteArray>(4 * 1024 * 1024) { it.size }
    private val decoded = PreviewLru<String, Bitmap>(2 * 1024 * 1024) { it.allocationByteCount }
    private data class Wanted(val tile: SeekrTile, val neighbours: List<SeekrTile>)
    private val requests = Channel<Wanted>(Channel.CONFLATED)
    @Volatile private var latestTarget = ""
    @Volatile private var runningTarget: String? = null
    @Volatile private var pendingTarget: String? = null
    private val changes = MutableStateFlow(0)
    val revision: StateFlow<Int> = changes
    @Volatile private var closed = false
    @Volatile private var call: Call? = null
    private val failed = mutableMapOf<String, Long>()

    init {
        scope.launch {
            for (wanted in requests) {
                if (closed) break
                val target = key(wanted.tile)
                runningTarget = target
                if (pendingTarget == target) pendingTarget = null
                for (tile in listOf(wanted.tile) + wanted.neighbours) {
                    if (closed || latestTarget != target) break
                    try {
                        val key = key(tile)
                        if (decoded[key] != null) continue
                        if ((failed[tile.sheetUrl] ?: 0L) > System.currentTimeMillis()) continue
                        val bytes = encoded[tile.sheetUrl] ?: download(tile.sheetUrl)?.also { encoded.put(tile.sheetUrl, it) }
                        if (bytes == null) { failed[tile.sheetUrl] = System.currentTimeMillis() + 30_000; continue }
                        @Suppress("DEPRECATION")
                        val region = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: continue
                        try {
                            val x = tile.x.coerceAtLeast(0)
                            val y = tile.y.coerceAtLeast(0)
                            val width = tile.w.takeIf { it in 1..640 } ?: 320
                            val height = tile.h.takeIf { it in 1..640 } ?: 180
                            if (x.toLong() + width > region.width || y.toLong() + height > region.height) continue
                            val options = BitmapFactory.Options().apply { inSampleSize = if (maxOf(width, height) > 320) 2 else 1 }
                            val bitmap = region.decodeRegion(Rect(x, y, x + width, y + height), options) ?: continue
                            if (!closed) { decoded.put(key, bitmap); changes.value++ }
                        } finally { region.recycle() }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: OutOfMemoryError) { decoded.clear(); encoded.clear(); break }
                    catch (_: Exception) { if (latestTarget == target) failed[tile.sheetUrl] = System.currentTimeMillis() + 30_000 }
                }
                runningTarget = null
            }
        }
    }
    private fun key(tile: SeekrTile) = "${tile.sheetUrl}|${tile.x}|${tile.y}|${tile.w}|${tile.h}"
    suspend fun tile(tile: SeekrTile, request: Boolean, neighbours: List<SeekrTile> = emptyList()): Bitmap? {
        if (closed) return null
        if (request) enqueue(tile, neighbours)
        return decoded[key(tile)]
    }
    private fun enqueue(tile: SeekrTile, neighbours: List<SeekrTile>) {
        val target = key(tile)
        latestTarget = target
        if (runningTarget == target || pendingTarget == target) return
        if ((listOf(tile) + neighbours).all { decoded[key(it)] != null }) return
        if (call?.request()?.url?.toString()?.let { it != tile.sheetUrl } == true) call?.cancel()
        pendingTarget = target
        requests.trySend(Wanted(tile, neighbours))
    }
    // Kept for source compatibility; startup no longer downloads an entire title's sheets.
    suspend fun downloadAll() = Unit
    fun warm(url: String) = Unit
    fun request(tile: SeekrTile) { if (!closed) enqueue(tile, emptyList()) }
    private fun download(url: String): ByteArray? {
        val current = http.newCall(Request.Builder().url(url).get().build())
        call = current
        if (closed) current.cancel()
        try {
            current.execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > 4 * 1024 * 1024) return null
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val chunk = ByteArray(8192)
                    while (output.size() <= 4 * 1024 * 1024) {
                        val n = input.read(chunk, 0, minOf(chunk.size, 4 * 1024 * 1024 + 1 - output.size()))
                        if (n < 0) break
                        output.write(chunk, 0, n)
                    }
                }
                return output.toByteArray().takeIf { it.size <= 4 * 1024 * 1024 }
            }
        } finally { if (call === current) call = null }
    }
    fun close() {
        closed = true; call?.cancel(); requests.close(); scope.cancel(); encoded.clear(); decoded.clear()
    }
}
