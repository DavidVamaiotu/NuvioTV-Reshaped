package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

/** One session's cancellable, bounded reader. It never touches Nuvio's playback connection settings. */
internal class PreviewRangeReader(
    private val url: String,
    private val headers: Map<String, String>,
    private val permitted: () -> Boolean = { true },
) : RangeReader, Closeable {
    @Volatile private var closed = false
    @Volatile private var call: Call? = null
    private val epoch = AtomicLong()
    @Volatile private var lease = 0L
    @Volatile override var totalLength = -1L
        private set
    @Volatile var bytesRead = 0L
        private set
    @Volatile var retryAtMs = 0L
        private set
    private var effectiveUrl = url

    override fun read(offset: Long, length: Int): ByteArray {
        require(length in 1..ThumbReads.MAX_READ)
        val out = ByteBuffer.allocate(length)
        val n = readInto(offset, length, out)
        return out.array().let { if (n == length) it else it.copyOf(n) }
    }

    @Synchronized override fun readInto(offset: Long, length: Int, dst: ByteBuffer): Int {
        require(offset >= 0 && length in 1..ThumbReads.MAX_READ && dst.remaining() >= length)
        if (closed || !permitted()) throw IOException("preview session suspended")
        val started = lease
        if (started != epoch.get()) throw IOException("obsolete preview operation")
        if (System.currentTimeMillis() < retryAtMs) throw IOException("preview source cooling down")
        val request = Request.Builder().url(effectiveUrl).apply {
            headers.forEach { (key, value) ->
                if (!key.equals("Range", true) && !key.equals("Accept-Encoding", true)) header(key, value)
            }
            header("Range", "bytes=$offset-${offset + length - 1}")
            header("Accept-Encoding", "identity")
        }.build()
        try {
            execute(request, started).use { response ->
                if (response.code == 429 || response.code == 503) {
                    val wait = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 300) ?: 30
                    retryAtMs = System.currentTimeMillis() + wait * 1000
                    throw IOException("preview source throttled")
                }
                if (response.code != 206) throw UnsupportedMediaException("range response ${response.code}")
                val range = response.header("Content-Range")?.let { CONTENT_RANGE.matchEntire(it) }
                    ?: throw UnsupportedMediaException("missing content range")
                val start = range.groupValues[1].toLongOrNull()
                val end = range.groupValues[2].toLongOrNull() ?: -1
                val total = range.groupValues[3].toLongOrNull() ?: -1
                if (start != offset || end < offset || end - offset >= length || total <= end) {
                    throw UnsupportedMediaException("invalid content range")
                }
                if (totalLength > 0 && totalLength != total) throw UnsupportedMediaException("file changed")
                totalLength = total
                val expected = (end - offset + 1).toInt()
                val body = response.body ?: throw IOException("empty range")
                val oldLimit = dst.limit()
                val initial = dst.position()
                dst.limit(initial + expected)
                try {
                    while (dst.hasRemaining()) {
                        if (closed || started != epoch.get()) throw IOException("obsolete preview request")
                        if (body.source().read(dst) < 0) throw IOException("truncated range")
                    }
                } finally { dst.limit(oldLimit) }
                bytesRead += expected
                effectiveUrl = response.request.url.toString()
                return expected
            }
        } finally { call = null }
    }

    private fun execute(initial: Request, started: Long): Response {
        var request = initial
        val origin = url.toHttpUrl()
        val begin = System.nanoTime()
        repeat(6) {
            if (closed || !permitted() || started != epoch.get() || System.nanoTime() - begin > TimeUnit.SECONDS.toNanos(10)) throw IOException("preview request cancelled")
            // A cached CDN address must not receive the origin's credentials on subsequent requests either.
            if (request.url.host != origin.host || request.url.port != origin.port || request.url.scheme != origin.scheme) {
                request = request.newBuilder().apply {
                    for ((name, _) in headers) if (!name.equals("User-Agent", true)) removeHeader(name)
                    header("Range", initial.header("Range").orEmpty())
                    header("Accept-Encoding", "identity")
                }.build()
            }
            val current = client.newCall(request)
            call = current
            if (closed || started != epoch.get()) current.cancel()
            val response = current.execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val next = response.header("Location")?.let { response.request.url.resolve(it) }
            response.close()
            if (next == null || (request.url.isHttps && !next.isHttps)) throw IOException("invalid preview redirect")
            request = request.newBuilder().url(next).build()
        }
        throw IOException("too many preview redirects")
    }

    fun cancel() { epoch.incrementAndGet(); call?.cancel() }
    fun beginOperation() { lease = epoch.get() }
    override fun close() { closed = true; cancel() }
    companion object {
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)
        // No redirects with credentials. Resolve links through playback first; direct CDN URLs work as-is.
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    }
}
