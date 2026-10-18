package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.Closeable
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Optional server-owned provider. Only discovers metadata for the exact playing item/file version. */
internal class ServerPreviewProvider private constructor(
    private val base: HttpUrl,
    private val item: String,
    private val mediaSource: String,
    private val headers: Map<String, String>,
) : Closeable {
    data class Picture(val bitmap: Bitmap, val timeMs: Long, val endMs: Long)
    private data class Grid(val width: Int, val height: Int, val columns: Int, val rows: Int, val count: Int, val interval: Long, val source: String)
    private var grid: Grid? = null
    private var discovered = false
    @Volatile private var closed = false
    @Volatile private var call: Call? = null
    private val sheets = PreviewLru<Int, ByteArray>(4 * 1024 * 1024) { it.size }
    private var bifIndex: List<Pair<Long, Long>>? = null

    fun picture(positionMs: Long): Picture? {
        if (!discovered) {
            discovered = true
            val json = runCatching { JSONObject(String(get(endpoint("Items/$item", "Fields" to "Trickplay"), 512 * 1024))) }.getOrNull()
            val manifest = json?.optJSONObject("Trickplay")
            val listed = manifest?.keys()?.asSequence()?.firstOrNull { it == mediaSource || it == item }
            val levels = listed?.let { manifest?.optJSONObject(it) }
            grid = levels?.keys()?.asSequence()?.mapNotNull { key ->
                val value = levels.optJSONObject(key) ?: return@mapNotNull null
                val candidate = Grid(value.optInt("Width"), value.optInt("Height"), value.optInt("TileWidth"),
                    value.optInt("TileHeight"), value.optInt("ThumbnailCount"), value.optLong("Interval"), listed ?: return@mapNotNull null)
                candidate.takeIf { it.width in 1..640 && it.height in 1..640 && it.columns in 1..50 && it.rows in 1..50 &&
                    it.count in 1..100000 && it.interval in 100..300000 }
            }?.minByOrNull { kotlin.math.abs(it.width - 320) }
        }
        val layout = grid ?: return bifPicture(positionMs)
        val index = (positionMs / layout.interval).toInt().coerceIn(0, layout.count - 1)
        val cells = layout.columns * layout.rows
        val sheet = index / cells
        val bytes = sheets[sheet] ?: get(endpoint("Videos/$item/Trickplay/${layout.width}/$sheet.jpg",
            "MediaSourceId" to layout.source), 4 * 1024 * 1024).also { sheets.put(sheet, it) }
        val cell = index % cells
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return null
        try {
            val left = cell % layout.columns * layout.width
            val top = cell / layout.columns * layout.height
            if (left + layout.width > decoder.width || top + layout.height > decoder.height) return null
            val bitmap = decoder.decodeRegion(Rect(left, top, left + layout.width, top + layout.height), BitmapFactory.Options()) ?: return null
            return Picture(bitmap, index * layout.interval, (index + 1) * layout.interval)
        } finally { decoder.recycle() }
    }

    /** Emby BIF: bounded index first, then only the selected JPEG's byte range. */
    private fun bifPicture(positionMs: Long): Picture? {
        val url = endpoint("Videos/$item/index.bif", "MediaSourceId" to mediaSource, "Width" to "320")
        val entries = bifIndex ?: run {
            val head = get(url, 64, 0L..63L)
            if (head.size != 64 || !head.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a))) return null
            val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.getInt(8) != 0) return null
            val count = buffer.getInt(12)
            if (count !in 1..20000) return null
            val multiplier = buffer.getInt(16).toLong().takeIf { it > 0 } ?: 1000
            val table = ByteBuffer.wrap(get(url, (count + 1) * 8, 64L until 64L + (count + 1) * 8)).order(ByteOrder.LITTLE_ENDIAN)
            val parsed = (0..count).map { (table.int.toLong() and 0xffffffffL) * multiplier to (table.int.toLong() and 0xffffffffL) }
            if (parsed.zipWithNext().any { (a, b) -> b.second <= a.second || b.second - a.second > 512 * 1024 }) return null
            bifIndex = parsed
            parsed
        }
        val index = (0 until entries.lastIndex).lastOrNull { entries[it].first <= positionMs } ?: 0
        val first = entries[index]
        val next = entries[index + 1]
        val bytes = get(url, (next.second - first.second).toInt(), first.second until next.second)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return Picture(bitmap, first.first, if (index + 1 < entries.lastIndex) next.first else first.first + 10_000)
    }
    private fun endpoint(path: String, vararg params: Pair<String, String>): HttpUrl = base.newBuilder().apply {
        addPathSegments(path)
        params.forEach { (name, value) -> setQueryParameter(name, value) }
    }.build()
    private fun get(url: HttpUrl, cap: Int, range: LongRange? = null): ByteArray {
        if (closed) throw IOException("server preview closed")
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> if (!name.equals("Range", true)) header(name, value) }
            range?.let { header("Range", "bytes=${it.first}-${it.last}") }
            header("Accept-Encoding", "identity")
        }.build()
        val current = http.newCall(request)
        call = current
        if (closed) current.cancel()
        try {
            current.execute().use { response ->
                if (!response.isSuccessful || (range != null && response.code != 206)) throw IOException("server previews unavailable")
                val body = response.body ?: throw IOException("empty server preview")
                if (body.contentLength() > cap) throw IOException("server preview above limit")
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val chunk = ByteArray(8192)
                    while (output.size() <= cap) {
                        val n = input.read(chunk, 0, minOf(chunk.size, cap + 1 - output.size()))
                        if (n < 0) break
                        output.write(chunk, 0, n)
                    }
                }
                val bytes = output.toByteArray()
                if (bytes.size > cap) throw IOException("server preview above limit")
                if (range != null) {
                    val contentRange = response.header("Content-Range").orEmpty()
                    if (!contentRange.startsWith("bytes ${range.first}-${range.last}/", true) || bytes.size != cap) throw IOException("invalid BIF range")
                }
                return bytes
            }
        } finally { if (call === current) call = null }
    }
    fun cancel() { call?.cancel() }
    override fun close() { closed = true; cancel(); sheets.clear() }
    companion object {
        private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
        fun forStream(url: String, headers: Map<String, String>, sourceId: String?): ServerPreviewProvider? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            val videos = parsed.pathSegments.indexOfFirst { it.equals("Videos", true) }
            val item = parsed.pathSegments.getOrNull(videos + 1)?.takeIf { videos >= 0 && it.matches(Regex("[A-Za-z0-9_-]+")) } ?: return null
            val source = sourceId ?: parsed.queryParameter("MediaSourceId") ?: parsed.queryParameter("mediaSourceId") ?: return null
            if (!source.matches(Regex("[A-Za-z0-9_-]+"))) return null
            val builder = parsed.newBuilder().encodedPath("/" + parsed.encodedPathSegments.take(videos).joinToString("/")).query(null)
            for (name in listOf("api_key", "X-Emby-Token")) parsed.queryParameter(name)?.let { builder.setQueryParameter(name, it) }
            return ServerPreviewProvider(builder.build(), item, source, headers)
        }
    }
}
