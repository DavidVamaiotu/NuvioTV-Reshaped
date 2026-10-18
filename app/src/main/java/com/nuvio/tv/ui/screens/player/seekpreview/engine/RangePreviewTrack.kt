package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewThumbnail
import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Feature-owned session. Network and native work are never run from thumbnailFor or the main thread. */
internal class RangePreviewTrack(
    context: Context,
    private val identity: String,
    private val durationMs: Long,
    private val url: String,
    private val headers: Map<String, String>,
    private val playingLabel: String,
    private val copies: () -> List<PreviewCopy>,
    private val mediaServer: Boolean = false,
    serverSourceId: String? = null,
) : SeekPreviewTrack {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val readers = CopyOnWriteArraySet<PreviewRangeReader>()
    private val planner = PreviewPlanner(durationMs)
    private var images: PreviewLru<Long, Bitmap>? = null
    private val resources = PreviewResources(context) { images?.clear(); readers.forEach { it.cancel() } }
    private val directory = File(context.applicationContext.cacheDir, "seek_preview_engine_v1")
    @Volatile private var original: IndexedPreviewFile? = null
    private var copy: IndexedPreviewFile? = null
    private var copyRatio = 1.0
    private var triedCopies = false
    @Volatile private var store: PreviewStore? = null
    private var serverStore: PreviewStore? = null
    private val server = if (mediaServer) ServerPreviewProvider.forStream(url, headers, serverSourceId) else null
    private var serverUnavailable = false
    @Volatile private var active = true
    @Volatile private var buffering = false
    @Volatile private var foreground = true
    @Volatile private var bufferAheadMs = 0L
    @Volatile private var requestAt = 0L
    private val revisionState = MutableStateFlow(0)
    override val revision: StateFlow<Int> = revisionState
    override var offsetMs = 0L
    override val isLocal = true
    init {
        images = PreviewLru(resources.budget.bitmapBytes) { it.allocationByteCount }
        scope.launch { runWorker() }
    }
    fun updatePlayback(playing: Boolean, stalled: Boolean, positionMs: Long, bufferedMs: Long) {
        active = playing
        buffering = stalled
        bufferAheadMs = (bufferedMs - positionMs).coerceAtLeast(0)
        if (playing || stalled) readers.forEach { it.cancel() }
    }
    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? {
        if (closed.get()) return null
        val previous = planner.latest
        val request = planner.request(positionMs)
        if (previous?.generation != request.generation) {
            requestAt = SystemClock.elapsedRealtime()
            readers.forEach { it.cancel() }
            server?.cancel()
        }
        return lookup(positionMs)
    }
    override suspend fun sideThumbnailFor(positionMs: Long): SeekPreviewThumbnail? = lookup(positionMs)
    private suspend fun lookup(positionMs: Long): SeekPreviewThumbnail? {
        val saved = store?.takeIf { it.nearest(positionMs) != null } ?: serverStore ?: return null
        val entry = saved.nearest(positionMs) ?: return null
        val bitmap = images?.get(entry.slot) ?: withContext(Dispatchers.IO) {
            if (closed.get()) return@withContext null
            val bytes = saved.read(entry) ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { images?.put(entry.slot, it) }
        } ?: return null
        return SeekPreviewThumbnail(bitmap, entry.timeMs, PreviewTiming.frameCueEnd(entry.timeMs, durationMs),
            origin = entry.origin.name, actualFrameMs = entry.timeMs)
    }
    override fun keyframeNear(positionMs: Long, toleranceMs: Long): Long? = original?.nearestTime(positionMs)
        ?.takeIf { PreviewTiming.near(it, positionMs, toleranceMs) }

    private suspend fun runWorker() {
        var failures = 0
        var coverageBytes = 0L
        try {
            while (!closed.get()) {
                val request = planner.latest
                val waiting = SystemClock.elapsedRealtime() - requestAt < 100
                val canWork = foreground && !resources.hidden && !buffering && !waiting &&
                    (!active || (!resources.budget.small && bufferAheadMs >= 20_000))
                if (!canWork || request == null) { delay(100); continue }
                try {
                    if (server != null && !serverUnavailable && (serverStore?.has(request.positionMs) != true)) {
                        val picture = try { server.picture(request.positionMs) } catch (_: Exception) {
                            if (planner.isCurrent(request)) serverUnavailable = true
                            null
                        }
                        if (picture != null) {
                            try {
                                if (planner.isCurrent(request) && !closed.get()) {
                                    val saved = serverStore ?: PreviewStore(directory, "$identity|server|${PreviewStore.digest(url.toByteArray())}", resources.budget.diskBytes)
                                        .also { serverStore = it }
                                    val encoded = ByteArrayOutputStream().also { picture.bitmap.compress(Bitmap.CompressFormat.WEBP, 80, it) }.toByteArray()
                                    saved.put(request.positionMs, picture.timeMs, PreviewOrigin.SERVER, encoded)
                                    revisionState.value++
                                }
                            } finally { picture.bitmap.recycle() }
                            delay(100)
                            continue
                        }
                    }
                    if (serverStore?.has(request.positionMs) == true) { delay(250); continue }
                    if (!ThumbNative.available) break
                    val own = original ?: IndexedPreviewFile.open(directory, identity, url, headers, readers::add).also {
                        original = it
                        store = PreviewStore(directory, it.identity, resources.budget.diskBytes)
                        revisionState.value++
                    }
                    if (!planner.isCurrent(request)) continue
                    if (!triedCopies && !active && own.index.video.width.toLong() * own.index.video.height > 1920L * 1088) tryCopy(own, request)
                    val source = copy ?: own
                    if (!resources.allows(source.index.video)) { delay(500); continue }
                    val saved = store ?: continue
                    val coverage = !mediaServer && coverageBytes < resources.budget.coverageBytes && !active
                    val position = planner.next(saved::has, coverage)
                    if (position == null) { delay(250); continue }
                    val before = source.reader.bytesRead
                    val mapped = (position * copyRatio).toLong()
                    val made = PreviewDecodeGate.tryRun { source.frame(mapped, resources.budget.outputWidth) }
                    if (made != null) {
                        try {
                            if (!closed.get() && foreground && planner.isCurrent(request)) {
                                val actual = ((source.nearestTime(mapped) ?: mapped) / copyRatio).toLong()
                                // A very sparse index may point to another scene: do not claim it covers this slot.
                                if (PreviewTiming.near(actual, position)) {
                                    val bytes = ByteArrayOutputStream().also { made.compress(Bitmap.CompressFormat.WEBP, 80, it) }.toByteArray()
                                    saved.put(position, actual, if (copy != null) PreviewOrigin.VERIFIED_COPY else PreviewOrigin.ORIGINAL, bytes)
                                    images?.put(position / 10_000, made)
                                    revisionState.value++
                                }
                                planner.completed(position)
                            }
                        } finally {
                            // A displayed bitmap may still be referenced by Compose; eviction uses GC, never recycle.
                        }
                    } else {
                        // Native failure and a busy local-tap decoder both remain bounded; do not spin.
                        delay(250)
                    }
                    if (position != request.positionMs / 10_000 * 10_000) coverageBytes += source.reader.bytesRead - before
                    failures = 0
                    delay(if (active) 2_000 else 25)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: OutOfMemoryError) {
                    images?.clear()
                    break // No retry loop after memory exhaustion.
                } catch (_: Exception) {
                    if (!planner.isCurrent(request) || buffering || active || !foreground) continue
                    if (++failures >= 5) break
                    delay((1_000L shl failures).coerceAtMost(30_000))
                }
            }
        } finally {
            readers.forEach { it.close() }
            original?.close(); copy?.close(); store?.close(); serverStore?.close(); server?.close(); resources.close()
        }
    }

    private fun tryCopy(own: IndexedPreviewFile, request: PreviewPlanner.Request) {
        val offers = PreviewCopies.candidates(copies(), playingLabel, durationMs)
        // Source lists arrive asynchronously. Do not permanently close discovery while they are still empty.
        if (offers.isEmpty()) return
        // Verifying 4K still needs a full frame's working memory. Test real headroom, even on a small TV.
        if (!resources.allowsVerification(own.index.video)) return
        triedCopies = true
        for (offer in offers) {
            if (closed.get() || !planner.isCurrent(request) || active) return
            var candidate: IndexedPreviewFile? = null
            try {
                val other = IndexedPreviewFile.open(directory, identity, offer.url, offer.headers, readers::add)
                candidate = other
                val ratio = ReleaseMatch.ratio(own.index.durationUs / 1000, other.index.durationUs / 1000) ?: continue
                if (!resources.allows(other.index.video)) continue
                val scores = mutableListOf<Float>()
                for (fraction in doubleArrayOf(1.0 / 6, 0.5, 5.0 / 6)) {
                    if (!planner.isCurrent(request) || active || closed.get()) return
                    val at = (durationMs * fraction).toLong()
                    val ownAt = own.nearestTime(at) ?: break
                    val copyAt = other.nearestTime((ownAt * ratio).toLong()) ?: break
                    if (kotlin.math.abs(copyAt / ratio - ownAt) > 1_000) break
                    val score = PreviewDecodeGate.tryRun {
                        val a = own.frame(ownAt, 320) ?: return@tryRun null
                        try {
                            val aSig = PreviewCopies.signature(a) ?: return@tryRun null
                            val b = other.frame(copyAt, 320) ?: return@tryRun null
                            try { PreviewCopies.signature(b)?.let { ReleaseMatch.correlation(aSig, it) } }
                            finally { b.recycle() }
                        } finally { a.recycle() }
                    } ?: break
                    scores += score
                }
                if (PreviewCopies.verified(scores)) {
                    copy = other; copyRatio = ratio; candidate = null
                    return
                }
            } catch (_: Exception) { /* An unsuitable copy does not fail the playing file. */ }
            finally { candidate?.close() }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resources.close()
        readers.forEach { it.close() }
        server?.close()
        scope.cancel()
        images?.clear()
    }
}
