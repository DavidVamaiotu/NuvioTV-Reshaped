package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewThumbnail
import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Exact server-owned images stay independent of video generation and use bounded tile decoding. */
internal class ServerPreviewTrack private constructor(
    context: Context, identity: String, url: String, private val durationMs: Long,
    private val provider: ServerPreviewProvider, private val onUnavailable: () -> Unit,
) : SeekPreviewTrack {
    private data class Request(val position: Long, val generation: Long, val at: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val images = PreviewLru<PreviewImageKey, Bitmap>(4 * 1024 * 1024) { it.allocationByteCount }
    private val resources = PreviewResources(context) { images.clear(); provider.cancel() }
    private val health = PreviewPlaybackHealth()
    private val store = PreviewStore(File(context.applicationContext.cacheDir, "seek_preview_engine_v1"),
        "$identity|server|${PreviewStore.digest(url.toByteArray())}", resources.budget.diskBytes, slotMs = 1)
    @Volatile private var request: Request? = null
    @Volatile private var playing = true
    @Volatile private var stalled = false
    @Volatile private var ahead = 0L
    override val revision = MutableStateFlow(0)
    override var offsetMs = 0L
    override val isLocal = true
    init { scope.launch { work() } }
    fun updatePlayback(playing: Boolean, stalled: Boolean, positionMs: Long, bufferedMs: Long, droppedFrames: Int) {
        this.playing = playing; this.stalled = stalled; ahead = (bufferedMs - positionMs).coerceAtLeast(0)
        health.observe(SystemClock.elapsedRealtime(), playing, stalled, droppedFrames)
        if (stalled) provider.cancel()
    }
    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? {
        if (closed.get()) return null
        if (request?.position != positionMs) {
            request = Request(positionMs, generation.incrementAndGet(), SystemClock.elapsedRealtime())
            provider.cancel()
        }
        return lookup(positionMs)
    }
    override suspend fun sideThumbnailFor(positionMs: Long) = lookup(positionMs)
    override fun externalPreviewAvailable(positionMs: Long) { request = null; generation.incrementAndGet(); provider.cancel() }
    private suspend fun lookup(positionMs: Long): SeekPreviewThumbnail? {
        if (closed.get()) return null
        val entry = store.nearest(positionMs, 0) ?: return null
        val key = store.imageKey(entry)
        val bitmap = images[key] ?: withContext(Dispatchers.IO) {
            if (!resources.allowsImages()) return@withContext null
            val bytes = store.read(entry) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..640 || bounds.outHeight !in 1..640) return@withContext null
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })?.also { images.put(key, it) }
            } catch (_: OutOfMemoryError) { images.clear(); null }
        } ?: return null
        return SeekPreviewThumbnail(bitmap, entry.timeMs, minOf(durationMs, entry.endMs), origin = "SERVER", actualFrameMs = entry.timeMs)
    }
    private suspend fun work() {
        var failures = 0
        var observedGeneration = -1L
        val attempted = hashSetOf<Long>()
        try {
            while (!closed.get()) {
                val target = request
                if (target == null || SystemClock.elapsedRealtime() - target.at < 150 || resources.hidden || stalled ||
                    !resources.allowsImages() || !health.allows(SystemClock.elapsedRealtime(), playing) || playing && ahead < 10_000) {
                    delay(100); continue
                }
                if (observedGeneration != target.generation) { attempted.clear(); observedGeneration = target.generation }
                val center = store.nearest(target.position, 0)
                val interval = center?.let { it.endMs - it.timeMs } ?: 10_000
                val wanted = intArrayOf(0, 1, -1, 2, -2).map { target.position + it * interval }
                    .firstOrNull { it in 0 until durationMs && it !in attempted && store.nearest(it, 0) == null }
                if (wanted == null) { delay(250); continue }
                try {
                    val picture = provider.picture(wanted)
                    if (generation.get() != target.generation) { picture?.bitmap?.recycle(); continue }
                    attempted += wanted
                    if (picture == null) {
                        if (wanted == target.position) { onUnavailable(); break }
                        continue
                    }
                    try {
                        if (!closed.get() && generation.get() == target.generation) {
                            val bytes = ByteArrayOutputStream().also { picture.bitmap.compress(Bitmap.CompressFormat.WEBP, 80, it) }.toByteArray()
                            store.put(picture.timeMs, picture.timeMs, PreviewOrigin.SERVER, bytes, endMs = picture.endMs)
                            revision.value++
                        }
                    } finally { picture.bitmap.recycle() }
                    failures = 0
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: OutOfMemoryError) { images.clear(); onUnavailable(); break }
                catch (_: Exception) {
                    if (generation.get() != target.generation || resources.hidden || stalled) continue
                    if (++failures >= 3) { onUnavailable(); break }
                    delay((1000L shl failures).coerceAtMost(30_000))
                }
            }
        } finally { provider.close() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        provider.close(); resources.close(); scope.cancel(); images.clear()
        CoroutineScope(Dispatchers.IO).launch { store.close() }
    }
    companion object {
        fun open(context: Context, identity: String, durationMs: Long, url: String, headers: Map<String, String>, sourceId: String?,
                 onUnavailable: () -> Unit): ServerPreviewTrack? = ServerPreviewProvider.forStream(url, headers, sourceId)?.let {
            ServerPreviewTrack(context, identity, url, durationMs, it, onUnavailable)
        }
    }
}
