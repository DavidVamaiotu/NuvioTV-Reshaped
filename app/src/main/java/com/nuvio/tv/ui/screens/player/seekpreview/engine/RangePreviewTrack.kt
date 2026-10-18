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
    private var images: PreviewLru<PreviewImageKey, Bitmap>? = null
    private val resources = PreviewResources(context) { images?.clear(); readers.forEach { it.cancel() } }
    private val directory = File(context.applicationContext.cacheDir, "seek_preview_engine_v1")
    @Volatile private var original: IndexedPreviewFile? = null
    private var copy: IndexedPreviewFile? = null
    private var copyRatio = 1.0
    private var triedCopies = false
    private var copyRetryAt = 0L
    private val copyScores = mutableMapOf<String, MutableList<Float>>()
    private var smallFourKFailed = false
    @Volatile private var store: PreviewStore? = null
    private var serverStore: PreviewStore? = null
    private val server = if (mediaServer) ServerPreviewProvider.forStream(url, headers, serverSourceId) else null
    private var serverUnavailable = false
    private var serverRetryAt = 0L
    @Volatile private var active = true
    @Volatile private var buffering = false
    @Volatile private var foreground = true
    @Volatile private var bufferAheadMs = 0L
    @Volatile private var requestAt = 0L
    @Volatile private var requested = false
    @Volatile private var metadataBusy = false
    @Volatile private var verifyingCopy = false
    @Volatile private var imageRetryAt = 0L
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
        if (newlyStalled) server?.cancel()
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
        val key = saved.imageKey(entry)
        val bitmap = images?.get(key) ?: withContext(Dispatchers.IO) {
            if (closed.get() || SystemClock.elapsedRealtime() < imageRetryAt || !resources.allowsImages()) return@withContext null
            val bytes = saved.read(entry) ?: return@withContext null
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth !in 1..640 || bounds.outHeight !in 1..640) return@withContext null
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { images?.put(key, it) }
            } catch (_: OutOfMemoryError) {
                imageRetryAt = SystemClock.elapsedRealtime() + 30_000
                images?.clear()
                null
            }
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
                val canWork = mayFetchNow() && !waiting
                if (!canWork || request == null) { delay(100); continue }
                try {
                    if (server != null && !serverUnavailable && resources.allowsImages() &&
                        store?.nearest(request.positionMs) == null && SystemClock.elapsedRealtime() >= serverRetryAt &&
                        (serverStore?.nearest(request.positionMs, 0) == null)) {
                        val picture = try { server.picture(request.positionMs) } catch (_: Exception) {
                            if (planner.isCurrent(request)) serverRetryAt = SystemClock.elapsedRealtime() + 30_000
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
                        if (serverRetryAt <= SystemClock.elapsedRealtime() && planner.isCurrent(request)) serverUnavailable = true
                    }
                    if (serverStore?.nearest(request.positionMs, 0) != null) { delay(250); continue }
                    if (!singleFile) {
                        if (serverUnavailable) break
                        delay(500)
                        continue // A temporary server failure may retry; segmented media is never range-extracted.
                    }
                    if (!mayWorkNow()) { delay(250); continue }
                    if (!ThumbNative.available) break
                    if (original == null && !resources.allowsIndex()) { delay(500); continue }
                    val own = original ?: openFile(url, headers).also {
                        original = it
                        store = PreviewStore(directory, it.identity, resources.budget.diskBytes)
                        revisionState.value++
                    }
                    if (!planner.isCurrent(request)) continue
                    if (!triedCopies && !active && SystemClock.elapsedRealtime() >= copyRetryAt &&
                        (!resources.budget.small || store?.has(request.positionMs) == true) &&
                        own.index.video.width.toLong() * own.index.video.height > 1920L * 1088) {
                        verifyingCopy = true
                        try { tryCopy(own) } finally { verifyingCopy = false }
                    }
                    if (!planner.isCurrent(request) || !requested) continue
                    val source = copy ?: own
                    val saved = store ?: continue
                    val smallFourK = resources.budget.small && source.index.video.width.toLong() * source.index.video.height > 1920L * 1088
                    val coverage = !smallFourK && !mediaServer && coverageBytes < resources.budget.coverageBytes && !active
                    val position = if (smallFourK) (request.positionMs / 10_000 * 10_000)
                        .takeIf { !smallFourKFailed && !saved.has(it) && !planner.wasCompleted(it) }
                        else planner.next(saved::has, coverage)
                    if (position == null) { delay(250); continue }
                    val explicit = position == request.positionMs / 10_000 * 10_000
                    if (!resources.allows(source.index.video, paused = !active, explicit = explicit)) { delay(1_000); continue }
                    val before = source.reader.bytesRead
                    val mapped = (position * copyRatio).toLong()
                    val priorityLease = if (explicit) PreviewDecodeGate.prioritizeTarget() else 0L
                    val attempt = PreviewDecodeGate.tryRun(foreground = explicit) {
                        if (!mayWorkNow() || !planner.isCurrent(request)) null
                        else source.frame(mapped, resources.budget.outputWidth) to true
                    }
                    if (attempt != null) PreviewDecodeGate.releaseTarget(priorityLease)
                    val made = attempt?.first
                    if (made != null) {
                        try {
                            if (!closed.get() && foreground && planner.isCurrent(request)) {
                                val actual = ((source.nearestTime(mapped) ?: mapped) / copyRatio).toLong()
                                // A very sparse index may point to another scene: do not claim it covers this slot.
                                if (PreviewTiming.near(actual, position)) {
                                    val bytes = ByteArrayOutputStream().also { made.compress(Bitmap.CompressFormat.WEBP, 80, it) }.toByteArray()
                                    saved.put(position, actual, if (copy != null) PreviewOrigin.VERIFIED_COPY else PreviewOrigin.ORIGINAL, bytes)
                                    saved.atSlot(position / 10_000)?.takeIf { it.timeMs == actual }
                                        ?.let { images?.put(saved.imageKey(it), made) }
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
                            if (smallFourK) smallFourKFailed = true // Do not stress a small TV with repeated 4K failures.
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
                    // A permitted background attempt needs the same backoff as a paused attempt.
                    if (!mayWorkNow() || !planner.isCurrent(request)) continue
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

    private fun mayFetchNow(): Boolean = requested && !closed.get() && foreground && !resources.hidden && !buffering &&
        playbackHealth.allows(SystemClock.elapsedRealtime(), active) && (!active || bufferAheadMs >= 10_000)

    private fun mayWorkNow(): Boolean = mayFetchNow() &&
        (!active || (!resources.budget.small && bufferAheadMs >= 20_000))

    private fun openFile(address: String, requestHeaders: Map<String, String>): IndexedPreviewFile {
        metadataBusy = true
        var reader: PreviewRangeReader? = null
        return try {
            IndexedPreviewFile.open(directory, identity, address, requestHeaders, register = { reader = it; readers.add(it) },
                permitted = { requested && !closed.get() && !buffering && !resources.hidden })
        } catch (error: Throwable) {
            reader?.let { readers.remove(it) }
            throw error
        } finally { metadataBusy = false }
    }

    private fun tryCopy(own: IndexedPreviewFile) {
        val offers = PreviewCopies.candidates(copies(), playingLabel, durationMs)
        // Source lists arrive asynchronously. Do not permanently close discovery while they are still empty.
        copyRetryAt = SystemClock.elapsedRealtime() + 5_000
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
                // Keep completed anchors across transient decoder contention or a pause/resume.
                if (other.identity !in copyScores && copyScores.size >= 2) copyScores.clear()
                val scores = copyScores.getOrPut(other.identity) { mutableListOf() }
                for (fraction in doubleArrayOf(1.0 / 6, 0.5, 5.0 / 6).drop(scores.size)) {
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
                    } ?: return // Busy/unclear samples are inconclusive, never a permanent rejection.
                    scores += score
                }
                if (PreviewCopies.verified(scores)) {
                    copy = other; copyRatio = ratio; candidate = null; triedCopies = true
                    return
                }
            } catch (_: Exception) { /* An unsuitable copy does not fail the playing file. */ }
            finally { candidate?.let { readers.remove(it.reader); it.close() } }
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
