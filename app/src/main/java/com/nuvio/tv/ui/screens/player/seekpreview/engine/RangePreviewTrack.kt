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
    private val singleFile: Boolean = true,
) : SeekPreviewTrack {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val readers = CopyOnWriteArraySet<PreviewRangeReader>()
    private val planner = PreviewPlanner(durationMs)
    private val playbackHealth = PreviewPlaybackHealth()
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
    @Volatile private var requested = false
    @Volatile private var metadataBusy = false
    @Volatile private var verifyingCopy = false
    private val revisionState = MutableStateFlow(0)
    override val revision: StateFlow<Int> = revisionState
    override var offsetMs = 0L
    override val isLocal = true
    init {
        images = PreviewLru(resources.budget.bitmapBytes) { it.allocationByteCount }
        scope.launch { runWorker() }
    }
    fun updatePlayback(playing: Boolean, stalled: Boolean, positionMs: Long, bufferedMs: Long, droppedFrames: Int = -1) {
        val resumed = playing && !active
        val newlyStalled = stalled && !buffering
        active = playing
        buffering = stalled
        bufferAheadMs = (bufferedMs - positionMs).coerceAtLeast(0)
        playbackHealth.observe(SystemClock.elapsedRealtime(), playing, stalled, droppedFrames)
        if (resumed || newlyStalled) readers.forEach { it.cancel() }
    }
    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? {
        if (closed.get()) return null
        requested = true
        val previous = planner.latest
        val request = planner.request(positionMs)
        if (previous?.generation != request.generation) {
            requestAt = SystemClock.elapsedRealtime()
            if (!metadataBusy && !verifyingCopy) readers.forEach { it.cancel() }
            server?.cancel()
        }
        return lookup(positionMs)
    }
    override fun externalPreviewAvailable(positionMs: Long) {
        requested = false
        planner.request(positionMs)
        readers.forEach { it.cancel() }
        server?.cancel()
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
        return SeekPreviewThumbnail(bitmap, entry.timeMs, minOf(entry.endMs, durationMs),
            origin = entry.origin.name, actualFrameMs = entry.timeMs)
    }
    override fun keyframeNear(positionMs: Long, toleranceMs: Long): Long? = original?.nearestTime(positionMs)
        ?.takeIf { PreviewTiming.near(it, positionMs, toleranceMs) }

    private suspend fun runWorker() {
        var failures = 0
        var coverageBytes = 0L
        val decodeFailures = mutableMapOf<Long, Int>()
        try {
            while (!closed.get()) {
                val request = planner.latest
                val waiting = SystemClock.elapsedRealtime() - requestAt < 150
                val canWork = mayWorkNow() && !waiting
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
                                    saved.put(request.positionMs, picture.timeMs, PreviewOrigin.SERVER, encoded, endMs = picture.endMs)
                                    revisionState.value++
                                }
                            } finally { picture.bitmap.recycle() }
                            delay(100)
                            continue
                        }
                    }
                    if (serverStore?.has(request.positionMs) == true) { delay(250); continue }
                    if (!singleFile) break // Server thumbnails may cover HLS; raw-file range extraction never does.
                    if (!ThumbNative.available) break
                    if (original == null && !resources.allowsIndex()) { delay(500); continue }
                    val own = original ?: openFile(url, headers).also {
                        original = it
                        store = PreviewStore(directory, it.identity, resources.budget.diskBytes)
                        revisionState.value++
                    }
                    if (!planner.isCurrent(request)) continue
                    if (!triedCopies && !active && own.index.video.width.toLong() * own.index.video.height > 1920L * 1088) {
                        verifyingCopy = true
                        try { tryCopy(own) } finally { verifyingCopy = false }
                    }
                    if (!planner.isCurrent(request) || !requested) continue
                    val source = copy ?: own
                    if (!resources.allows(source.index.video)) { delay(500); continue }
                    val saved = store ?: continue
                    val coverage = !mediaServer && coverageBytes < resources.budget.coverageBytes && !active
                    val position = planner.next(saved::has, coverage)
                    if (position == null) { delay(250); continue }
                    val before = source.reader.bytesRead
                    val mapped = (position * copyRatio).toLong()
                    val attempt = PreviewDecodeGate.tryRun {
                        if (!mayWorkNow()) null else source.frame(mapped, resources.budget.outputWidth) to true
                    }
                    val made = attempt?.first
                    if (made != null) playbackHealth.decoded(SystemClock.elapsedRealtime())
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
                        // A busy tap is different from an undecodable frame. Never re-download a bad frame forever.
                        if (attempt != null) {
                            val attempts = (decodeFailures[position] ?: 0) + 1
                            decodeFailures[position] = attempts
                            if (attempts >= 2) planner.completed(position)
                        }
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
                    if (!requested || !planner.isCurrent(request) || buffering || active || !foreground) continue
                    val coolingUntil = readers.maxOfOrNull { it.retryAtMs } ?: 0L
                    if (coolingUntil > System.currentTimeMillis()) {
                        delay(minOf(30_000, coolingUntil - System.currentTimeMillis()).coerceAtLeast(1))
                        continue
                    }
                    if (copy != null) {
                        copy?.close(); copy = null; copyRatio = 1.0
                        failures = 0
                        continue // A failed optional copy never terminates the original-file provider.
                    }
                    if (++failures >= 5) break
                    delay((1_000L shl failures).coerceAtMost(30_000))
                }
            }
        } finally {
            readers.forEach { it.close() }
            original?.close(); copy?.close(); server?.close(); resources.close()
            // Stop generation on failure, but keep saved images readable until the session itself closes.
            if (closed.get()) { store?.close(); serverStore?.close() }
        }
    }

    private fun mayWorkNow(): Boolean = requested && !closed.get() && foreground && !resources.hidden && !buffering &&
        playbackHealth.allows(SystemClock.elapsedRealtime(), active) &&
        (!active || (!resources.budget.small && bufferAheadMs >= 20_000))

    private fun openFile(address: String, requestHeaders: Map<String, String>): IndexedPreviewFile {
        metadataBusy = true
        return try {
            IndexedPreviewFile.open(directory, identity, address, requestHeaders, register = { readers.add(it) },
                permitted = { requested && !closed.get() && !buffering && !resources.hidden })
        } finally { metadataBusy = false }
    }

    private fun tryCopy(own: IndexedPreviewFile) {
        val offers = PreviewCopies.candidates(copies(), playingLabel, durationMs)
        // Source lists arrive asynchronously. Do not permanently close discovery while they are still empty.
        if (offers.isEmpty()) return
        // Verifying 4K still needs a full frame's working memory. Test real headroom, even on a small TV.
        if (!resources.allowsVerification(own.index.video)) return
        for (offer in offers) {
            if (!requested || closed.get() || resources.hidden || active) return
            if (!resources.allowsIndex()) return
            var candidate: IndexedPreviewFile? = null
            try {
                val other = openFile(offer.url, offer.headers)
                candidate = other
                val ratio = ReleaseMatch.ratio(own.index.durationUs / 1000, other.index.durationUs / 1000) ?: continue
                if (!resources.allows(other.index.video)) continue
                val scores = mutableListOf<Float>()
                for (fraction in doubleArrayOf(1.0 / 6, 0.5, 5.0 / 6)) {
                    if (!requested || resources.hidden || active || closed.get()) return
                    if (!resources.allowsVerification(own.index.video)) return
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
                    copy = other; copyRatio = ratio; candidate = null; triedCopies = true
                    return
                }
            } catch (_: Exception) { /* An unsuitable copy does not fail the playing file. */ }
            finally { candidate?.close() }
        }
        triedCopies = true
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resources.close()
        readers.forEach { it.close() }
        server?.close()
        scope.cancel()
        images?.clear()
        // Flush manifests off the main thread; overlapping handles share one store state.
        CoroutineScope(Dispatchers.IO).launch { store?.close(); serverStore?.close() }
    }
}
