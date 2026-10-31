package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.JsonReader
import android.util.JsonToken
import com.nuvio.tv.ui.screens.player.seekpreview.colourmatch.PreviewColourAdjustment
import com.nuvio.tv.ui.screens.player.seekpreview.colourmatch.PreviewColourTarget
import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewThumbnail
import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MultipartBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong

/**
 * Reads an already-added preview torrent using the viewer's own TorBox account.
 * Compressed sheets are prefetched and cached; nearby requested tiles share a bounded decode.
 */
internal class TorboxSpriteTrack private constructor(
    private val http: PreviewTorboxHttp,
    private val batch: PreviewBatch,
    private val match: TorboxPackMatch,
    private val timeline: TorboxSpriteTimeline,
    cacheDirectory: File,
    private val fingerprintsPath: String?,
    private val candidateScale: Double,
    private val timing: TorboxPreviewTiming,
) : SeekPreviewTrack {
    override var offsetMs: Long = 0L
    private val visualReady = MutableStateFlow(match.exactSource)
    val displayReady: StateFlow<Boolean> get() = visualReady
    private var visualMatcher: TorboxVisualMatcher? = null
    @Volatile private var visualMapping = TorboxVisualMapping()

    private fun verifiedMapping(): TorboxVisualMapping? = visualMapping.takeIf { visualReady.value }
    private fun sourcePosition(positionMs: Long): Long = timing.sourcePosition(positionMs, verifiedMapping())

    fun observeFingerprint(positionMs: Long, hash: PreviewHash128): Boolean {
        if (closed || offsetMs != 0L) return visualReady.value
        val matcher = visualMatcher ?: return false
        matcher.observe(positionMs, hash)
        val accepted = matcher.mapping() ?: return false
        visualMapping = accepted
        visualReady.value = true
        colourAdjustment = null
        colourRevision.value++
        return true
    }

    suspend fun prepareFingerprintMatcher(): Boolean = withContext(Dispatchers.IO) {
        if (match.exactSource || closed) return@withContext false
        val anchors = try {

            val fingerprintPath = requireNotNull(fingerprintsPath)
            require(safePreviewPath(fingerprintPath))
            val root = JSONObject(String(http.file(batch,
                match.pack.indexPath.substringBeforeLast('/') + "/" + fingerprintPath, 2 * 1024 * 1024), Charsets.UTF_8))
            require(root.getInt("version") == 1 && root.getString("algorithm") == "center-dhash128-v1" &&
                root.getInt("bits") == 128 && root.getString("imdb_id") == match.pack.imdbId &&
                kotlin.math.abs(milliseconds(root.getDouble("duration_seconds")) - match.pack.durationMs) <= 1)
            val hashes = root.getJSONArray("frames")
            require(hashes.length() == timeline.frames.size)
            (0 until hashes.length()).map { i ->
                val row = hashes.getJSONObject(i)
                val frame = row.getInt("frame_index")
                require(frame == i)
                val at = milliseconds(row.getDouble("seconds"))
                require(kotlin.math.abs(at - timeline.frames[frame].actualMs) <= 1)
                TorboxVisualAnchor(frame, at, PreviewHash128.parse(row.getString("hash"), row.getBoolean("usable")))
            }.also { require(it.count { a -> a.hash.usable } >= 3) }

        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return@withContext false }
        catch (_: OutOfMemoryError) { return@withContext false }
        visualMatcher = TorboxVisualMatcher(anchors, candidateScale)
        true
    }

    private val colourRevision = MutableStateFlow(0)
    override val revision: StateFlow<Int> get() = colourRevision
    @Volatile private var colourAdjustment: PreviewColourAdjustment? = null

    fun acceptColourAdjustment(adjustment: PreviewColourAdjustment) {
        if (closed || adjustment.isIdentity) return
        colourAdjustment = adjustment
        colourRevision.value++
    }

    fun nextColourTarget(positionMs: Long): PreviewColourTarget? {
        if (closed || offsetMs != 0L) return null
        return timeline.frames.firstOrNull { it.actualMs >= sourcePosition(positionMs) }
            ?.let { PreviewColourTarget(timing.playbackPosition(it.startMs, verifiedMapping()), timing.playbackPosition(it.actualMs, verifiedMapping())) }
    }

    suspend fun colourReference(target: PreviewColourTarget): Bitmap? =
        rawThumbnail(target.lookupMs, markUnavailable = false)?.bitmap

    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? = displayed(positionMs, steer = true)

    /** Filmstrip neighbours never steer the held-scrub prefetch direction. */
    override suspend fun sideThumbnailFor(positionMs: Long): SeekPreviewThumbnail? = displayed(positionMs, steer = false)

    private suspend fun displayed(positionMs: Long, steer: Boolean): SeekPreviewThumbnail? =
        rawThumbnail(positionMs, markUnavailable = true, steer = steer)?.let { thumbnail ->
            val mapping = verifiedMapping()
            thumbnail.copy(
                cueStartMs = timing.cueStart(thumbnail.cueStartMs, mapping),
                cueEndMs = timing.cueEnd(thumbnail.cueStartMs, thumbnail.cueEndMs, mapping),
                colourAdjustment = colourAdjustment,
            )
        }

    private val mutex = Mutex()
    private val bytes = TorboxSpriteByteCache(TorboxSpriteDiskCache(cacheDirectory))
    private val warming = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmStarted = AtomicBoolean(false)
    @Volatile private var prefetchQueue: TorboxPrefetchQueue? = null
    private val tiles = LinkedHashMap<Long, SeekPreviewThumbnail>(8, .75f, true)
    private var bitmapBytes = 0
    @Volatile private var closed = false
    private val healthy = MutableStateFlow(true)
    val available: StateFlow<Boolean> get() = healthy

    /** Called after the player owns this track, so cancellation always closes the workers. */
    fun prefetch(positionMs: Long) {
        if (closed || !warmStarted.compareAndSet(false, true)) return
        val queue = TorboxPrefetchQueue(timeline.frames, timeline.sheets, sourcePosition(positionMs))
        prefetchQueue = queue
        repeat(2) {
            warming.launch {
                var failures = 0
                while (!closed) {
                    // Playback comes first: no preview download competes with a stalled buffer.
                    while (playbackStalled && !closed) delay(PREFETCH_STALL_POLL_MS)
                    val sheet = queue.next() ?: break
                    try { sheetBytes(sheet); failures = 0 }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: OutOfMemoryError) { break }
                    catch (_: Exception) {
                        // One failed sheet no longer ends the movie's prefetch: it goes to the back
                        // once, the worker waits out a rate-limit cooldown, and only a run of
                        // failures stops it. Cached previews stay usable either way.
                        if (++failures >= PREFETCH_MAX_FAILURES) break
                        queue.retryLater(sheet)
                        delay(maxOf(PREFETCH_RETRY_DELAY_MS, PreviewTorboxGate.remainingMs()))
                    }
                }
            }
        }
    }

    /** Set by the player while it rebuffers; background sheet downloads wait until it plays again. */
    @Volatile var playbackStalled = false

    /** While a remote key is held, lookups answer from RAM or disk only and never wait on the network. */
    @Volatile private var heldScrub = false
    @Volatile private var lastHeldSourceMs = -1L

    override fun noteHeldScrub(held: Boolean) {
        heldScrub = held
        if (!held) {
            lastHeldSourceMs = -1L
            // Shown crops may still reference these bitmaps; dropping them leaves release to GC.
            synchronized(heldSheets) { heldSheets.clear(); heldSheetBytes = 0 }
        }
    }

    /**
     * Whole decoded sheets kept only while a key is held. A held step jumps several cues, so
     * keeping the sheet (instead of the two tiles either side) answers every cue on it, and the
     * filmstrip neighbours, as crops without decoding the same sheet again. Bounded to about
     * two sheets and cleared as soon as the hold ends.
     */
    private val heldSheets = LinkedHashMap<String, Bitmap>(4, .75f, true)
    private var heldSheetBytes = 0
    @Volatile private var heldDecodeAhead: kotlinx.coroutines.Job? = null

    private fun heldCrop(frame: TorboxSpriteFrame): SeekPreviewThumbnail? {
        val sheet = synchronized(heldSheets) { heldSheets[frame.sheet] } ?: return null
        return SeekPreviewThumbnail(sheet, frame.startMs, frame.endMs,
            approximate = !match.exactSource, origin = "TORBOX",
            actualFrameMs = if (match.exactSource) frame.actualMs else null,
            crop = Rect(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height))
    }

    /** Decodes [sheet] from RAM/disk only (caller holds [mutex]); null when it is not on the device. */
    private fun decodeHeldSheet(sheet: TorboxSpriteSheet): Bitmap? {
        synchronized(heldSheets) { heldSheets[sheet.path] }?.let { return it }
        if (!heldScrub || sheet.width.toLong() * sheet.height > HELD_SHEET_MAX_PIXELS) return null
        val data = bytes.cached(sheet) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        if (bitmap.width != sheet.width || bitmap.height != sheet.height) return null
        synchronized(heldSheets) {
            if (!heldScrub) return null
            heldSheets[sheet.path] = bitmap
            heldSheetBytes += bitmap.byteCount
            val oldest = heldSheets.entries.iterator()
            while (heldSheetBytes > HELD_SHEETS_MAX_BYTES && heldSheets.size > 1 && oldest.hasNext()) {
                val entry = oldest.next()
                if (entry.key == sheet.path) continue
                heldSheetBytes -= entry.value.byteCount
                oldest.remove()
            }
        }
        return bitmap
    }

    /** While held, the next sheet in the scrub direction is decoded before the cursor reaches it. */
    private fun decodeAheadWhileHeld(next: TorboxSpriteSheet) {
        if (heldDecodeAhead?.isActive == true || synchronized(heldSheets) { heldSheets.containsKey(next.path) }) return
        heldDecodeAhead = warming.launch {
            try { mutex.withLock { if (!closed) decodeHeldSheet(next) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { }
            catch (_: OutOfMemoryError) { }
        }
    }

    /** Fetch [sheet] next, then the sheet after it in the direction the held key moves. */
    private fun prioritizeHeld(frame: TorboxSpriteFrame, sourceMs: Long) {
        val queue = prefetchQueue ?: return
        val previous = lastHeldSourceMs
        lastHeldSourceMs = sourceMs
        val direction = if (previous < 0L || previous == sourceMs) 0 else if (sourceMs > previous) 1 else -1
        if (direction != 0) {
            var index = timeline.frames.binarySearchBy(frame.startMs) { it.startMs }
            while (index in timeline.frames.indices && timeline.frames[index].sheet == frame.sheet) index += direction
            timeline.frames.getOrNull(index)?.let { ahead ->
                queue.prioritize(ahead.sheet)
                timeline.sheets[ahead.sheet]?.let(::decodeAheadWhileHeld)
            }
        }
        queue.prioritize(frame.sheet)
    }

    private suspend fun sheetBytes(sheet: TorboxSpriteSheet): ByteArray = bytes.get(sheet) {
        val directory = match.pack.indexPath.substringBeforeLast('/')
        http.file(batch, "$directory/${sheet.path}", sheet.bytes)
    }

    /** Sheets whose last download failed: elapsed-realtime until which only cached bytes are used. */
    private val sheetRetryAt = ConcurrentHashMap<String, Long>()

    /**
     * Only a failure that cannot heal during this playback turns TorBox off for the movie: the
     * account key was refused, or bytes that passed their hash do not decode as the index says.
     * A timeout, rate limit, server error, missing sheet or low memory skips that lookup and the
     * sheet is tried again later, so one network hiccup no longer removes every preview.
     */
    private fun noteLookupFailure(sheet: TorboxSpriteSheet?, error: Throwable, mayDisable: Boolean) {
        val permanent = mayDisable && (error is PreviewLinkExpired || (error !is IOException && error !is OutOfMemoryError))
        if (permanent) healthy.value = false
        else if (sheet != null) sheetRetryAt[sheet.path] = android.os.SystemClock.elapsedRealtime() +
            if (error is PreviewLocationUnavailable) SHEET_MISSING_RETRY_MS else SHEET_RETRY_MS
        com.nuvio.tv.ui.screens.player.seekpreview.engine.PreviewDiagnostics.note(
            "torbox lookup failed: ${error.javaClass.simpleName} ${error.message.orEmpty()}" + if (permanent) " (disabled)" else "")
    }

    private suspend fun rawThumbnail(positionMs: Long, markUnavailable: Boolean, steer: Boolean = false): SeekPreviewThumbnail? = withContext(Dispatchers.IO) {
        if (closed || !healthy.value) return@withContext null
        val sourceMs = sourcePosition(positionMs + offsetMs)
        val frame = timeline.frameAt(sourceMs) ?: return@withContext null
        val sheet = timeline.sheets[frame.sheet] ?: return@withContext null
        val now = android.os.SystemClock.elapsedRealtime()
        // A held scrub, a sheet that just failed, or a rate-limit cooldown: answer from the device only.
        val cacheOnly = (heldScrub && markUnavailable) || (sheetRetryAt[sheet.path] ?: 0L) > now ||
            PreviewTorboxGate.blocked()
        if (heldScrub && markUnavailable && steer) prioritizeHeld(frame, sourceMs)
        mutex.withLock { tiles[frame.startMs] }?.let { return@withContext it }
        if (heldScrub && markUnavailable) {
            heldCrop(frame)?.let { return@withContext it }
            val decoded = try { mutex.withLock { if (closed) null else decodeHeldSheet(sheet) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
                catch (_: OutOfMemoryError) { synchronized(heldSheets) { heldSheets.clear(); heldSheetBytes = 0 }; null }
            if (decoded != null) return@withContext heldCrop(frame)
            // Oversized sheets fall through to the region decode below, from cached bytes only.
        }
        try {
            if (!cacheOnly) prefetchQueue?.prioritize(sheet.path)
            // A held scrub outruns any download: a sheet not on the device yet shows nothing new
            // now, and the frame for the release position is looked up again with network.
            val data = if (cacheOnly) bytes.cached(sheet) ?: return@withContext null else sheetBytes(sheet)
            coroutineContext.ensureActive()
            mutex.withLock {
                if (closed || !healthy.value) return@withLock null
                tiles[frame.startMs]?.let { return@withLock it }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                require(bounds.outWidth == sheet.width && bounds.outHeight == sheet.height)
                val center = timeline.frames.binarySearchBy(frame.startMs) { it.startMs }
                val nearby = (center - 2..center + 2).mapNotNull { timeline.frames.getOrNull(it) }
                    .filter { it.sheet == sheet.path && tiles[it.startMs] == null }
                    .sortedByDescending { kotlin.math.abs(it.startMs - frame.startMs) } // Keep the center most recent in the LRU.

                fun keep(tile: com.nuvio.tv.ui.screens.player.seekpreview.torbox.TorboxSpriteFrame, bitmap: Bitmap) {
                    val picture = SeekPreviewThumbnail(bitmap, tile.startMs, tile.endMs,
                        approximate = !match.exactSource, origin = "TORBOX",
                        actualFrameMs = if (match.exactSource) tile.actualMs else null)
                    tiles[tile.startMs] = picture
                    bitmapBytes += bitmap.byteCount
                    while (bitmapBytes > 3 * 1024 * 1024 && tiles.size > 1) {
                        val first = tiles.entries.iterator()
                        bitmapBytes -= first.next().value.bitmap.byteCount
                        first.remove() // A displayed tile may still be referenced by Compose.
                    }
                }
                if (sheet.width.toLong() * sheet.height <= 2_000_000L) {
                    // A small 5x5 WebP is cheaper to decode once than to decode the same
                    // compressed image again for every neighboring region. The full image
                    // is transient (at most 8 MiB), never retained in the tile cache.
                    val full = BitmapFactory.decodeByteArray(data, 0, data.size,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                        ?: throw IOException("No sprite decoder")
                    try {
                        for (tile in nearby) {
                            coroutineContext.ensureActive()
                            if (closed) break
                            val extracted = Bitmap.createBitmap(full, tile.x, tile.y, tile.width, tile.height)
                            val independent = if (extracted === full)
                                full.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("No sprite tile") else extracted
                            keep(tile, independent)
                        }
                    } finally { full.recycle() }
                } else {
                    @Suppress("DEPRECATION")
                    val decoder = BitmapRegionDecoder.newInstance(data, 0, data.size, false)
                        ?: throw IOException("No sprite decoder")
                    try {
                        val bitmap = decoder.decodeRegion(Rect(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height),
                            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                            ?: throw IOException("No sprite tile")
                        if (closed) bitmap.recycle() else keep(frame, bitmap)
                    } finally { decoder.recycle() }
                }
                if (closed) null else tiles[frame.startMs]
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { noteLookupFailure(sheet, error, mayDisable = markUnavailable && steer); null }
        catch (error: OutOfMemoryError) { noteLookupFailure(sheet, error, mayDisable = false); null }
    }

    override fun close() {
        closed = true
        healthy.value = false
        warming.cancel()
        http.close()
        bytes.close()
        // All cache mutations, including release, share the lookup lock.
        if (mutex.tryLock()) {
            try { tiles.clear(); bitmapBytes = 0 }
            finally { mutex.unlock() }
        }
    }

    companion object {
        private const val PREFETCH_MAX_FAILURES = 3
        private const val PREFETCH_RETRY_DELAY_MS = 2_000L
        private const val SHEET_RETRY_MS = 5_000L
        private const val PREFETCH_STALL_POLL_MS = 500L
        private const val HELD_SHEET_MAX_PIXELS = 2_000_000L
        private const val HELD_SHEETS_MAX_BYTES = 12 * 1024 * 1024
        private const val SHEET_MISSING_RETRY_MS = 60_000L

        /** Waits between opening attempts after a network failure; a missing pack is never retried. */
        private val OPEN_RETRY_DELAYS_MS = longArrayOf(5_000L, 20_000L, 60_000L)

        /**
         * Opens the movie's previews, retrying a network failure, timeout or rate-limit cooldown a
         * few times instead of leaving the whole playback without TorBox previews. Other providers
         * stay usable meanwhile, and a source change cancels the wait.
         */
        suspend fun open(key: String, request: TorboxMovieRequest, cacheDirectory: File): TorboxSpriteTrack? {
            var attempt = 0
            while (true) {
                val outcome = openOnce(key, request, cacheDirectory)
                if (outcome.track != null || !outcome.retry || attempt >= OPEN_RETRY_DELAYS_MS.size) {
                    if (outcome.track == null && outcome.retry) com.nuvio.tv.ui.screens.player.seekpreview.engine
                        .PreviewDiagnostics.note("torbox open gave up after ${attempt + 1} attempts")
                    return outcome.track
                }
                val wait = maxOf(OPEN_RETRY_DELAYS_MS[attempt++], PreviewTorboxGate.remainingMs() + 1_000L)
                com.nuvio.tv.ui.screens.player.seekpreview.engine.PreviewDiagnostics.note(
                    "torbox open retry $attempt in ${wait / 1000}s")
                delay(wait)
            }
        }

        private class OpenOutcome(val track: TorboxSpriteTrack?, val retry: Boolean)

        private suspend fun openOnce(key: String, request: TorboxMovieRequest, cacheDirectory: File): OpenOutcome = withContext(Dispatchers.IO) {
            if (key.isBlank() || request.durationMs <= 0L ||
                request.contentType?.lowercase() in setOf("series", "tv", "show", "channel", "live")) return@withContext OpenOutcome(null, false)
            if (PreviewTorboxGate.blocked()) return@withContext OpenOutcome(null, true)
            val setupResult = TorboxPreviewLibrary.ensure(key, cacheDirectory)
            val http = PreviewTorboxHttp(key, cacheDirectory)
            var transferred = false
            try {
                val choices = mutableListOf<Pair<PreviewBatch, TorboxPackMatch>>()
                val movieId = request.contentId?.removePrefix("movie:")?.substringBefore(':')
                    ?.takeIf { it.matches(Regex("tt[0-9]{7,10}")) }
                var batches = http.batches(movieId, refresh = setupResult == TorboxLibrarySetup.Result.ADDED)
                var polls = 0
                while (batches.isEmpty() && !PreviewTorboxGate.blocked() && polls < 20 &&
                    (http.pendingBatch || setupResult == TorboxLibrarySetup.Result.ADDED)) {
                    delay(if (polls++ == 0) 2_000L else 15_000L)
                    batches = http.batches(movieId, refresh = true)
                }
                for (batch in batches) {
                    val bytes = http.file(batch, "catalog.json", 2 * 1024 * 1024)
                    val catalog = JSONObject(String(bytes, Charsets.UTF_8))
                    require(catalog.getInt("version") == 1)
                    val packs = catalog.getJSONArray("packs")
                    require(packs.length() in 1..5000)
                    for (i in 0 until packs.length()) {
                        val pack = parsePack(packs.getJSONObject(i))
                        pack.match(request)?.let { choices += batch to it }
                    }
                }
                val order = torboxPreviewMatchOrder(request)
                val selected = choices.minWithOrNull { a, b -> order.compare(a.second, b.second) }
                    ?: return@withContext OpenOutcome(null, batches.isEmpty() && http.pendingBatch)
                val (batch, match) = selected
                val indexBytes = http.file(batch, match.pack.indexPath, 4 * 1024 * 1024, match.pack.indexSha256)
                val index = JSONObject(String(indexBytes, Charsets.UTF_8))
                require(index.getInt("version") == 1 && index.getString("pack_id") == match.pack.id)
                val source = index.getJSONObject("source_identity")
                require(source.getString("info_hash").equals(match.pack.sourceHash, true))
                require(source.getInt("file_id") == match.pack.sourceFileIndex)
                require(kotlin.math.abs(milliseconds(index.getDouble("duration_seconds")) - match.pack.durationMs) <= 1)
                val sheets = index.getJSONArray("sheets").let { values ->
                    require(values.length() in 1..4000)
                    (0 until values.length()).map { i ->
                        val s = values.getJSONObject(i)
                        TorboxSpriteSheet(s.getString("file"), s.getInt("width"), s.getInt("height"),
                            s.getInt("size_bytes"), s.getString("sha256")).also {
                            require(it.sha256.matches(Regex("[0-9a-f]{64}")))
                        }
                    }.associateBy { it.path }.also { require(it.size == values.length()) }
                }
                val frames = index.getJSONArray("frames").let { values ->
                    require(values.length() in 1..100_000)
                    (0 until values.length()).map { i ->
                        val f = values.getJSONObject(i)
                        val rect = f.getJSONArray("xywh")
                        require(rect.length() == 4)
                        TorboxSpriteFrame(milliseconds(f.getDouble("requested_seconds")), milliseconds(f.getDouble("end_seconds")),
                            milliseconds(f.getDouble("actual_seconds")), f.getString("sheet"),
                            rect.getInt(0), rect.getInt(1), rect.getInt(2), rect.getInt(3))
                    }
                }
                val timeline = TorboxSpriteTimeline(frames, sheets)
                require(kotlin.math.abs(timeline.frames.last().endMs - match.pack.durationMs) <= 1)
                transferred = true
                OpenOutcome(TorboxSpriteTrack(http, batch, match, timeline, cacheDirectory,
                    index.optString("fingerprints").takeIf { it.isNotBlank() },
                    listOf(1.0, 24.0 / 25, 25.0 / 24).minBy {
                        kotlin.math.abs(request.durationMs * it - match.pack.durationMs)
                    }.takeIf { kotlin.math.abs(request.durationMs * it - match.pack.durationMs) / match.pack.durationMs < .002 } ?: 1.0,
                    TorboxPreviewTiming(request.durationMs, timeline.frames.last().endMs, match.exactSource)), false)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                com.nuvio.tv.ui.screens.player.seekpreview.engine.PreviewDiagnostics.note(
                    "torbox open failed: ${error.javaClass.simpleName} ${error.message.orEmpty()}")
                OpenOutcome(null, error is IOException)
            }
            catch (_: OutOfMemoryError) { OpenOutcome(null, true) }
            finally { if (!transferred) http.close() }
        }

        private fun parsePack(value: JSONObject): TorboxPreviewPack {
            val id = value.getString("pack_id")
            val sourceHash = value.getString("source_info_hash").lowercase(Locale.ROOT)
            val file = Regex(":([0-9a-f]{40})-f([0-9]+):v([0-9]+)$").find(id) ?: error("Pack identity")
            require(file.groupValues[1] == sourceHash)
            val path = value.getString("index_path")
            val hash = value.getString("index_sha256")
            require(safePreviewPath(path) && hash.matches(Regex("[0-9a-f]{64}")))
            return TorboxPreviewPack(id, value.getString("imdb_id"),
                value.optInt("tmdb_id").takeIf { it > 0 }, value.getInt("revision"),
                sourceHash, file.groupValues[2].toInt(), value.getLong("video_duration_ms"), path, hash)
                .also { require(it.durationMs in 1..86_400_000 && it.revision > 0) }
        }

        private fun milliseconds(seconds: Double): Long {
            require(seconds.isFinite() && seconds >= 0 && seconds <= 86400)
            return (seconds * 1000).roundToLong()
        }
    }
}

private data class PreviewFile(val id: String, val name: String, val size: Long)
private data class PreviewBatch(val id: String, val name: String, val hash: String, val files: List<PreviewFile>)
private data class PreviewListedTorrent(val id: String, val name: String, val hash: String,
    val finished: Boolean, val present: Boolean, val files: List<PreviewFile>)

/** One isolated client: API authorization is never attached to CDN requests or logs. */
private class PreviewTorboxHttp(
    private val key: String,
    cacheDirectory: File,
    private val httpClient: OkHttpClient = client,
) : Closeable {
    @Volatile private var closed = false
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    /**
     * The account listing holds every file of the preview library (tens of MB of JSON), and the
     * catalog/index arrive while a large source fills its buffer at line speed. Sprite sheets
     * keep the short limits of the shared client.
     */
    private val metadataClient = httpClient.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
    private val links = ConcurrentHashMap<String, String>()
    private val locations = ConcurrentHashMap<String, PreviewBatch>()
    private val metadata = TorboxPreviewMetadataCache(File(cacheDirectory, "metadata-v1"))
    private val account = sha256(key.toByteArray(Charsets.UTF_8))

    var pendingBatch: Boolean = false
        private set

    suspend fun batches(imdbId: String?, refresh: Boolean = false): List<PreviewBatch> {
        pendingBatch = false
        val published = publishedBatches().toMap()
        val found = mutableListOf<PreviewBatch>()
        for (page in 0 until 20) {
            val rows = listing(page, keepFiles = true, imdbId = imdbId, refresh = refresh)
            for (row in rows) {
                if (row.hash !in published) continue
                if (!row.finished || !row.present) { pendingBatch = true; continue }
                val batch = PreviewBatch(row.id, published.getValue(row.hash), row.hash, row.files)
                if (row.files.count { previewFileMatches(it.name, "catalog.json", batch.name) } == 1) {
                    found += batch
                    locations[batch.hash] = batch
                }
            }
            if (found.size >= published.size || rows.size < 100) break
        }
        return found
    }

    // Parse the 40,000-file library without allocating a JSONObject for every file.
    // Retain only the requested movie's paths plus the catalog; never download an archive.
    private suspend fun listing(page: Int, keepFiles: Boolean, imdbId: String? = null, refresh: Boolean = false): List<PreviewListedTorrent> {
        val url = "https://api.torbox.app/v1/api/torrents/mylist".toHttpUrl().newBuilder()
            .addQueryParameter("offset", (page * 100).toString()).addQueryParameter("limit", "100")
            .addQueryParameter("bypass_cache", "true").build()
        return metadata.get("account/$account/list/$page", 32 * 1024 * 1024, 600_000L,
            refresh = refresh, fetch = { consume ->
                readPayload(Request.Builder().url(url).header("Authorization", "Bearer $key").build(),
                    32 * 1024 * 1024, metadataClient, consume)
            }, decode = { input ->
            JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                val rows = mutableListOf<PreviewListedTorrent>()
                var success = false
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "success" -> success = reader.nextBoolean()
                    "data" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(rows.size < 1000)
                            var id = ""; var name = ""; var hash = ""; var finished = false; var present = false
                            val files = mutableListOf<PreviewFile>()
                            reader.beginObject()
                            while (reader.hasNext()) when (reader.nextName()) {
                                "id" -> id = reader.nextString()
                                "name" -> name = reader.nextString()
                                "hash" -> hash = reader.nextString().lowercase(Locale.ROOT)
                                "download_finished" -> finished = reader.nextBoolean()
                                "download_present" -> present = reader.nextBoolean()
                                "files" -> if (!keepFiles || reader.peek() == JsonToken.NULL) reader.skipValue() else {
                                    reader.beginArray()
                                    var seen = 0
                                    while (reader.hasNext()) {
                                        require(++seen <= 100_000)
                                        var fileId = ""; var fileName = ""; var size = 0L
                                        reader.beginObject()
                                        while (reader.hasNext()) when (reader.nextName()) {
                                            "id" -> fileId = reader.nextString()
                                            "name" -> fileName = reader.nextString()
                                            "size" -> size = reader.nextLong()
                                            else -> reader.skipValue()
                                        }
                                        reader.endObject()
                                        val normalized = fileName.replace('\\', '/')
                                        if (imdbId == null || normalized == "catalog.json" || normalized.endsWith("/catalog.json") ||
                                            normalized.startsWith("movies/$imdbId/") || normalized.contains("/movies/$imdbId/"))
                                            files += PreviewFile(fileId, fileName, size)
                                    }
                                    reader.endArray()
                                }
                                else -> reader.skipValue()
                            }
                            reader.endObject()
                            rows += PreviewListedTorrent(id, name, hash, finished, present, files)
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
                reader.endObject()
                require(success)
                rows
            }
        })
    }

    suspend fun file(batch: PreviewBatch, path: String, cap: Int, expectedSha256: String? = null): ByteArray {
        require(safePreviewPath(path))
        if (!path.endsWith(".json")) return downloadFile(batch, path, cap)
        // Catalog, index and fingerprints are larger and fetched while playback buffers hardest.
        return metadata.get("batch/${batch.hash}/$path", cap, Long.MAX_VALUE,
            decode = { input -> input.readBytes().also {
                require(JSONObject(String(it, Charsets.UTF_8)).getInt("version") == 1)
                if (expectedSha256 != null) require(sha256(it) == expectedSha256)
            } },
            fetch = { consume -> consume(downloadFile(batch, path, cap, client = metadataClient).inputStream()) })
    }

    private suspend fun downloadFile(batch: PreviewBatch, path: String, cap: Int, refreshed: Boolean = false,
                                     client: OkHttpClient = httpClient): ByteArray = try {
        require(safePreviewPath(path))
        val current = locations[batch.hash] ?: batch
        val file = current.files.singleOrNull { previewFileMatches(it.name, path, current.name) }
            ?: throw PreviewLocationUnavailable()
        require(file.size in 1..cap.toLong())
        val cacheKey = current.id + ":" + file.id
        suspend fun resolve(): String {
            val link = api("requestdl", mapOf("token" to key, "torrent_id" to current.id,
                "file_id" to file.id, "redirect" to "false", "zip_link" to "false", "append_name" to "false")).getString("data")
            require(link.toHttpUrlOrNull()?.isHttps == true)
            links[cacheKey] = link
            return link
        }
        val link = links[cacheKey] ?: resolve()
        try { get(Request.Builder().url(link).build(), cap, client) }
        catch (expired: PreviewLinkExpired) {
            links.remove(cacheKey)
            get(Request.Builder().url(resolve()).build(), cap, client) // Refresh one expired URL; no general retry loop.
        }
    } catch (stale: PreviewLocationUnavailable) {
        if (refreshed || PreviewTorboxGate.blocked()) throw stale
        val movieId = path.split('/').getOrNull(1)?.takeIf { path.startsWith("movies/") }
        val current = batches(movieId, refresh = true).firstOrNull { it.hash == batch.hash } ?: throw stale
        downloadFile(current, path, cap, refreshed = true, client = client)
    }


    suspend fun publishedBatches(): List<Pair<String, String>> = publishedMutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        publishedCache?.takeIf { now < publishedUntil }?.let { return@withLock it }
        val batches = try {
            val url = "https://raw.githubusercontent.com/DavidVamaiotu/NuvioTV-Reshaped/subtitle-autosync-tester/tools/torbox-preview/published-batches.json"
            metadata.get("published-batches", 64 * 1024, 300_000L,
                fetch = { consume -> consume(get(Request.Builder().url(url).build(), 64 * 1024).inputStream()) },
                decode = { input ->
                    val root = JSONObject(String(input.readBytes(), Charsets.UTF_8))
                    require(root.getInt("version") == 1)
                    val values = root.getJSONArray("batches")
                    require(values.length() in 1..32)
                    (0 until values.length()).map { i ->
                        val b = values.getJSONObject(i)
                        val hash = b.getString("info_hash").lowercase(Locale.ROOT)
                        val name = b.getString("name")
                        require(hash.matches(Regex("[0-9a-f]{40}")) && name.startsWith("NuvioPreviews-") && name.length <= 100)
                        hash to name
                    }.distinctBy { it.first }
            })
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { listOf("82681a29c5ff6849dd168594acde29eaeb890c69" to "NuvioPreviews-comet-1085-20261027-v1") }
        publishedCache = batches
        publishedUntil = now + 300_000L
        batches
    }

    suspend fun existingHashes(wanted: Set<String>): Set<String> {
        suspend fun scan(refresh: Boolean): Set<String> {
            val found = mutableSetOf<String>()
            for (page in 0 until 100) {
                val rows = listing(page, keepFiles = false, refresh = refresh)
                found += rows.map { it.hash }
                if (found.containsAll(wanted) || rows.size < 100) return found
            }
            // Do not create potential duplicates when discovery was truncated.
            throw IOException("Preview account list exceeded discovery limit")
        }
        val snapshot = scan(refresh = false)
        // Reuse a recent positive snapshot, including after restart. Missing hashes must be
        // checked against a fresh listing before setup is allowed to add anything.
        return if (snapshot.containsAll(wanted)) snapshot else scan(refresh = true)
    }

    suspend fun addLibrary(hash: String, name: String): Boolean {
        val magnet = "magnet:?xt=urn:btih:$hash&dn=" + java.net.URLEncoder.encode(name, "UTF-8") +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&tr=udp%3A%2F%2Fopen.demonii.com%3A1337%2Fannounce"
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("magnet", magnet).addFormDataPart("add_only_if_cached", "false")
            .addFormDataPart("allow_zip", "false").addFormDataPart("seed", "2").build()
        val request = Request.Builder().url("https://api.torbox.app/v1/api/torrents/createtorrent")
            .header("Authorization", "Bearer $key").post(form).build()
        val response = JSONObject(String(get(request, 64 * 1024), Charsets.UTF_8))
        return response.optBoolean("success")
    }

    private suspend fun api(endpoint: String, params: Map<String, String>): JSONObject {
        val url = "https://api.torbox.app/v1/api/torrents/$endpoint".toHttpUrl().newBuilder()
        params.forEach { (name, value) -> url.addQueryParameter(name, value) }
        val bytes = get(Request.Builder().url(url.build()).header("Authorization", "Bearer $key").build(), 8 * 1024 * 1024)
        val result = JSONObject(String(bytes, Charsets.UTF_8))
        if (!result.optBoolean("success")) {
            if (endpoint == "requestdl") throw PreviewLocationUnavailable()
            throw IOException("Preview API unavailable")
        }
        return result
    }

    private suspend fun get(request: Request, cap: Int, client: OkHttpClient = httpClient): ByteArray = readPayload(request, cap, client) { input ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            output.write(chunk, 0, n)
        }
        output.toByteArray()
    }

    private suspend fun <T> readPayload(request: Request, cap: Int, client: OkHttpClient = httpClient,
                                        consume: (InputStream) -> T): T {
        coroutineContext.ensureActive()
        PreviewTorboxGate.enter()
        if (closed) throw IOException("Preview closed")
        val call = client.newCall(request)
        calls.add(call)
        if (closed) call.cancel()
        try {
            call.execute().use { response ->
                if (response.code == 429) {
                    PreviewTorboxGate.limit(response.header("Retry-After"))
                    throw IOException("Preview rate limited")
                }
                if (response.code == 401 || response.code == 403) throw PreviewLinkExpired()
                if (response.code == 404) throw PreviewLocationUnavailable()
                if (!response.isSuccessful) throw IOException("Preview HTTP unavailable")
                val body = response.body ?: throw IOException("Empty preview")
                if (body.contentLength() > cap) throw IOException("Preview above limit")
                val context = coroutineContext
                var size = 0
                body.byteStream().use { input ->
                    val limited = object : InputStream() {
                        override fun read(): Int {
                            context.ensureActive()
                            val value = input.read()
                            if (value >= 0 && ++size > cap) throw IOException("Preview above limit")
                            return value
                        }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            context.ensureActive()
                            val n = input.read(buffer, offset, minOf(length, cap + 1 - size))
                            if (n > 0) {
                                size += n
                                if (size > cap) throw IOException("Preview above limit")
                            }
                            return n
                        }
                    }
                    return consume(limited)
                }
            }
        } finally { calls.remove(call) }
    }

    override fun close() { closed = true; calls.forEach { it.cancel() }; links.clear(); locations.clear() }

    companion object {
        private val publishedMutex = Mutex()
        private var publishedCache: List<Pair<String, String>>? = null
        private var publishedUntil = 0L
        private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    }
}

private class PreviewLinkExpired : IOException()
private class PreviewLocationUnavailable : IOException("Preview file unavailable")

/** Shared pacing across tracks, with a Retry-After cooldown that survives switching movies. */
private object PreviewTorboxGate {
    private val mutex = Mutex()
    private var next = 0L
    private val until = AtomicLong(0L)
    fun blocked(): Boolean = android.os.SystemClock.elapsedRealtime() < until.get()
    fun remainingMs(): Long = (until.get() - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    suspend fun enter() = mutex.withLock {
        if (blocked()) throw IOException("Preview cooldown")
        val now = android.os.SystemClock.elapsedRealtime()
        if (next > now) delay(next - now)
        if (blocked()) throw IOException("Preview cooldown")
        next = android.os.SystemClock.elapsedRealtime() + 250
    }
    fun limit(header: String?) {
        val seconds = header?.toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceAtLeast(0.0)
        val date = if (seconds == null && header != null) runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(header)?.time
        }.getOrNull() else null
        val milliseconds = seconds?.let { (it * 1000).coerceAtMost(Long.MAX_VALUE.toDouble() / 4).toLong() }
            ?: date?.let { (it - System.currentTimeMillis()).coerceAtLeast(0L) } ?: 60_000L
        until.set(maxOf(until.get(), android.os.SystemClock.elapsedRealtime() + milliseconds))
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

/** Enabled only by the preview switch or a movie session with previews enabled. */
internal object TorboxPreviewLibrary {
    private val setup = TorboxLibrarySetup { android.os.SystemClock.elapsedRealtime() }
    suspend fun ensure(key: String, cacheDirectory: File): TorboxLibrarySetup.Result = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext TorboxLibrarySetup.Result.FAILED
        val http = PreviewTorboxHttp(key, cacheDirectory)
        try {
            setup.ensure(sha256(key.toByteArray(Charsets.UTF_8)), PreviewTorboxGate::blocked,
                http::publishedBatches, http::existingHashes, http::addLibrary)
        } finally { http.close() }
    }
}
