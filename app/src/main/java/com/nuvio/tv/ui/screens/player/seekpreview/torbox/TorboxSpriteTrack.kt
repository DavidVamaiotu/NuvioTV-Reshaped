package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
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
    cacheDirectory: File
) : SeekPreviewTrack {
    override var offsetMs: Long = 0L
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
        return timeline.frames.firstOrNull { it.actualMs >= positionMs }
            ?.let { PreviewColourTarget(it.startMs, it.actualMs) }
    }

    suspend fun colourReference(target: PreviewColourTarget): Bitmap? =
        rawThumbnail(target.lookupMs, markUnavailable = false)?.bitmap

    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? =
        rawThumbnail(positionMs, markUnavailable = true)?.let { thumbnail ->
            colourAdjustment?.let { thumbnail.copy(colourAdjustment = it) } ?: thumbnail
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
        val queue = TorboxPrefetchQueue(timeline.frames, timeline.sheets, positionMs)
        prefetchQueue = queue
        repeat(2) {
            warming.launch {
                while (!closed) {
                    val sheet = queue.next() ?: break
                    try { sheetBytes(sheet) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { break } // Keep already cached previews on a background failure.
                    catch (_: OutOfMemoryError) { break }
                }
            }
        }
    }

    private suspend fun sheetBytes(sheet: TorboxSpriteSheet): ByteArray = bytes.get(sheet) {
        val directory = match.pack.indexPath.substringBeforeLast('/')
        http.file(batch, "$directory/${sheet.path}", sheet.bytes)
    }

    private suspend fun rawThumbnail(positionMs: Long, markUnavailable: Boolean): SeekPreviewThumbnail? = withContext(Dispatchers.IO) {
        if (closed || !healthy.value) return@withContext null
        val frame = timeline.frameAt(positionMs + offsetMs) ?: return@withContext null
        mutex.withLock { tiles[frame.startMs] }?.let { return@withContext it }
        try {
            val sheet = requireNotNull(timeline.sheets[frame.sheet])
            prefetchQueue?.prioritize(sheet.path)
            val data = sheetBytes(sheet)
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
        catch (_: Exception) { if (markUnavailable) healthy.value = false; null }
        catch (_: OutOfMemoryError) { if (markUnavailable) healthy.value = false; null }
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
        suspend fun open(key: String, request: TorboxMovieRequest, cacheDirectory: File): TorboxSpriteTrack? = withContext(Dispatchers.IO) {
            if (key.isBlank() || request.durationMs <= 0L || PreviewTorboxGate.blocked()) return@withContext null
            val http = PreviewTorboxHttp(key)
            var transferred = false
            try {
                val choices = mutableListOf<Pair<PreviewBatch, TorboxPackMatch>>()
                for (batch in http.batches()) {
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
                val selected = choices.sortedWith(
                    compareByDescending<Pair<PreviewBatch, TorboxPackMatch>> { it.second.exactSource }
                        .thenByDescending { it.second.pack.revision }
                        .thenBy { kotlin.math.abs(it.second.pack.durationMs - request.durationMs) }
                ).firstOrNull() ?: return@withContext null
                val (batch, match) = selected
                val indexBytes = http.file(batch, match.pack.indexPath, 4 * 1024 * 1024)
                require(sha256(indexBytes) == match.pack.indexSha256)
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
                require(timeline.frames.last().endMs == match.pack.durationMs)
                transferred = true
                TorboxSpriteTrack(http, batch, match, timeline, cacheDirectory)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
            catch (_: OutOfMemoryError) { null }
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
private data class PreviewBatch(val id: String, val name: String, val files: List<PreviewFile>)

/** One isolated client: API authorization is never attached to CDN requests or logs. */
private class PreviewTorboxHttp(private val key: String) : Closeable {
    @Volatile private var closed = false
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val links = ConcurrentHashMap<String, String>()

    suspend fun batches(): List<PreviewBatch> {
        // Recently added batches normally occur on the first page. Bound discovery work.
        val found = mutableListOf<PreviewBatch>()
        for (page in 0 until 20) {
            val values = api("mylist", mapOf("offset" to (page * 100).toString(), "limit" to "100", "bypass_cache" to "true")).getJSONArray("data")
            for (i in 0 until values.length()) {
                val row = values.getJSONObject(i)
                if (!row.optString("name").startsWith("NuvioPreviews-") ||
                    !row.optBoolean("download_finished") || !row.optBoolean("download_present")) continue
                val listed = row.optJSONArray("files") ?: continue
                if (listed.length() !in 1..100_000) continue
                val files = (0 until listed.length()).map { j ->
                    val f = listed.getJSONObject(j)
                    PreviewFile(f.get("id").toString(), f.getString("name"), f.getLong("size"))
                }
                val batch = PreviewBatch(row.get("id").toString(), row.getString("name"), files)
                if (files.count { previewFileMatches(it.name, "catalog.json", batch.name) } == 1) found += batch
                if (found.size >= 8) return found
            }
            if (values.length() < 100) break
        }
        return found
    }

    suspend fun file(batch: PreviewBatch, path: String, cap: Int): ByteArray {
        require(safePreviewPath(path))
        val file = batch.files.singleOrNull { previewFileMatches(it.name, path, batch.name) }
            ?: throw IOException("Preview file unavailable")
        require(file.size in 1..cap.toLong())
        val cacheKey = batch.id + ":" + file.id
        suspend fun resolve(): String {
            val link = api("requestdl", mapOf("token" to key, "torrent_id" to batch.id,
                "file_id" to file.id, "redirect" to "false", "zip_link" to "false", "append_name" to "false")).getString("data")
            require(link.toHttpUrlOrNull()?.isHttps == true)
            links[cacheKey] = link
            return link
        }
        val link = links[cacheKey] ?: resolve()
        return try { get(Request.Builder().url(link).build(), cap) }
        catch (expired: PreviewLinkExpired) {
            links.remove(cacheKey)
            get(Request.Builder().url(resolve()).build(), cap) // Refresh one expired URL; no general retry loop.
        }
    }


    suspend fun publishedBatches(): List<Pair<String, String>> {
        val url = "https://raw.githubusercontent.com/DavidVamaiotu/NuvioTV-Reshaped/subtitle-autosync-tester/tools/torbox-preview/published-batches.json"
        val root = JSONObject(String(get(Request.Builder().url(url).build(), 64 * 1024), Charsets.UTF_8))
        require(root.getInt("version") == 1)
        val batches = root.getJSONArray("batches")
        require(batches.length() in 1..32)
        return (0 until batches.length()).map { i ->
            val b = batches.getJSONObject(i)
            val hash = b.getString("info_hash").lowercase(Locale.ROOT)
            val name = b.getString("name")
            require(hash.matches(Regex("[0-9a-f]{40}")) && name.startsWith("NuvioPreviews-") && name.length <= 100)
            hash to name
        }.distinctBy { it.first }
    }

    suspend fun existingHashes(wanted: Set<String>): Set<String> {
        val found = mutableSetOf<String>()
        for (page in 0 until 10) {
            val rows = api("mylist", mapOf("offset" to (page * 1000).toString(),
                "limit" to "1000", "bypass_cache" to "true")).getJSONArray("data")
            for (i in 0 until rows.length()) found += rows.getJSONObject(i).optString("hash").lowercase(Locale.ROOT)
            if (found.containsAll(wanted) || rows.length() < 1000) break
        }
        return found
    }

    suspend fun addCached(hash: String, name: String): Boolean {
        val magnet = "magnet:?xt=urn:btih:$hash&dn=" + java.net.URLEncoder.encode(name, "UTF-8") +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&tr=udp%3A%2F%2Fopen.demonii.com%3A1337%2Fannounce"
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("magnet", magnet).addFormDataPart("add_only_if_cached", "true")
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
        if (!result.optBoolean("success")) throw IOException("Preview API unavailable")
        return result
    }

    private suspend fun get(request: Request, cap: Int): ByteArray {
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
                if (!response.isSuccessful) throw IOException("Preview HTTP unavailable")
                val body = response.body ?: throw IOException("Empty preview")
                if (body.contentLength() > cap) throw IOException("Preview above limit")
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(chunk, 0, minOf(chunk.size, cap + 1 - output.size()))
                        if (n < 0) break
                        output.write(chunk, 0, n)
                        if (output.size() > cap) throw IOException("Preview above limit")
                    }
                }
                return output.toByteArray()
            }
        } finally { calls.remove(call) }
    }

    override fun close() { closed = true; calls.forEach { it.cancel() }; links.clear() }

    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    }
}

private class PreviewLinkExpired : IOException()

/** Shared pacing across tracks, with a Retry-After cooldown that survives switching movies. */
private object PreviewTorboxGate {
    private val mutex = Mutex()
    private var next = 0L
    private val until = AtomicLong(0L)
    fun blocked(): Boolean = android.os.SystemClock.elapsedRealtime() < until.get()
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

/** Called exclusively by the settings button; nothing is added during playback. */
internal object TorboxPreviewLibrary {
    enum class Result { READY, UNAVAILABLE, FAILED, RATE_LIMITED }
    private val installLock = Mutex()
    suspend fun add(key: String): Result = withContext(Dispatchers.IO) {
        installLock.withLock {
            if (key.isBlank()) return@withLock Result.FAILED
            if (PreviewTorboxGate.blocked()) return@withLock Result.RATE_LIMITED
            val http = PreviewTorboxHttp(key)
            try {
                val published = http.publishedBatches()
                val existing = http.existingHashes(published.map { it.first }.toSet())
                var unavailable = false
                for ((hash, name) in published) {
                    if (hash in existing) continue
                    if (!http.addCached(hash, name)) unavailable = true
                }
                if (unavailable) Result.UNAVAILABLE else Result.READY
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (PreviewTorboxGate.blocked()) Result.RATE_LIMITED else Result.FAILED }
            finally { http.close() }
        }
    }
}
