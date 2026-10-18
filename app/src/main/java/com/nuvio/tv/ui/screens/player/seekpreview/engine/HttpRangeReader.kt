// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.util.Log
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The server asked us to slow down (429/503). [retryAfterMs] is the server's Retry-After, null when it sent none;
 * [url] is the address that answered, after redirects.
 */
internal class RateLimitedException(
    val retryAfterMs: Long?,
    val status: Int = TOO_MANY_REQUESTS,
    val url: String? = null,
) : IOException("rate limited") {
    companion object {
        const val TOO_MANY_REQUESTS = 429
    }
}

/**
 * Bounded Range reads over the playback OkHttp client, so a title's thumbnail fetches reuse keep-alive connections.
 * Safe for reads from several threads at once. Never open-ended and never above [ThumbReads.MAX_READ]; a 200 (Range
 * ignored) is refused without reading the body. Never log URLs or headers. Each read takes a slot in the
 * [ConnectionLedger] first.
 */
internal class HttpRangeReader(
    private val url: String,
    private val headers: Map<String, String>,
    private val client: OkHttpClient,
    private val ledger: ConnectionLedger = ConnectionLedger.app,
    /** How long a read waits for a free connection in the [ConnectionLedger]. */
    @Volatile var slotWaitMs: Long = ConnectionLedger.SLOT_WAIT_MS,
) : RangeReader {
    private companion object {
        const val TAG = "ThumbWorker"
        val RESOLVED_URL_FAILURES = setOf(401, 403, 404, 410)
        const val TOO_MANY_PEEK_BYTES = 512L
    }

    @Volatile
    override var totalLength: Long = -1L
        private set

    private val inFlight: MutableSet<Call> = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

    @Volatile
    private var closed = false

    /** URL after redirects, so later requests skip the redirect round trip (0.25-0.7 s each on debrid links). */
    @Volatile
    private var effectiveUrl: String = url

    val resolvedUrl: String get() = effectiveUrl

    private val requestCount = AtomicInteger()
    private val byteCount = AtomicLong()

    val requests: Int get() = requestCount.get()
    val bytes: Long get() = byteCount.get()

    /** Reads waiting for a ledger slot when [cancel] runs give up too. */
    private val cancelEpoch = AtomicInteger()
    private val transport = if (client.followRedirects || client.followSslRedirects)
        client.newBuilder().followRedirects(false).followSslRedirects(false).build() else client

    fun cancel() {
        cancelEpoch.incrementAndGet()
        for (call in inFlight) call.cancel()
    }

    /** Cancels every read under way and refuses new ones. */
    fun close() {
        closed = true
        cancel()
    }

    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(capped(length))
        val n = readInto(offset, out.size, ByteBuffer.wrap(out))
        return if (n == out.size) out else out.copyOf(n)
    }

    override fun readInto(offset: Long, length: Int, dst: ByteBuffer): Int {
        require(offset >= 0 && length > 0)
        val n = capped(minOf(length, dst.remaining()))
        if (n <= 0) return 0
        val target = effectiveUrl
        return try {
            readFrom(target, offset, n, dst)
        } catch (e: HttpStatusException) {
            // The redirect target may be short-lived: retry once from the original.
            if (target != url && e.code in RESOLVED_URL_FAILURES) {
                effectiveUrl = url
                readFrom(url, offset, n, dst)
            } else {
                throw e
            }
        }
    }

    /** A longer read is cut to the cap (callers treat it like a short read at end of file). */
    private fun capped(length: Int): Int {
        if (length <= ThumbReads.MAX_READ) return length
        Log.w(TAG, "range read of ${length / 1024} KB cut to ${ThumbReads.MAX_READ / 1024} KB")
        return ThumbReads.MAX_READ
    }

    class HttpStatusException(val code: Int, val tooMany: Boolean) : IOException("HTTP $code")

    private fun readFrom(target: String, offset: Long, length: Int, dst: ByteBuffer): Int {
        val builder = Request.Builder().url(target)
        for ((k, v) in headers) {
            if (!k.equals("Range", ignoreCase = true) && !k.equals("Accept-Encoding", true)) builder.header(k, v)
        }
        val request = builder
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .header("Accept-Encoding", "identity")
            .build()
        if (closed) throw IOException("closed")
        val cancels0 = cancelEpoch.get()
        var slot: ConnectionLedger.Slot? = null
        var call: Call? = null
        try {
            val held = ledger.acquire(target, slotWaitMs) { closed || cancelEpoch.get() != cancels0 }
            slot = held
            execute(request, cancels0) { next -> call = next }.use { resp ->
                held.moveTo(resp.request.url.toString())
                when (resp.code) {
                    206 -> Unit
                    416 -> return 0
                    429, 503 -> throw RateLimitedException(
                        ThumbRateLimit.retryAfterMs(resp.header("Retry-After"), System.currentTimeMillis()),
                        resp.code,
                        resp.request.url.toString(),
                    )
                    200 -> throw UnsupportedMediaException("server ignored Range")
                    else -> throw HttpStatusException(resp.code, ConnectionLedger.saysTooMany(
                        runCatching { resp.peekBody(TOO_MANY_PEEK_BYTES).string() }.getOrNull()))
                }
                val range = resp.header("Content-Range")?.let { Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE).matchEntire(it) }
                    ?: throw UnsupportedMediaException("missing content range")
                val first = range.groupValues[1].toLongOrNull()
                val last = range.groupValues[2].toLongOrNull() ?: -1L
                val total = range.groupValues[3].toLongOrNull() ?: -1L
                if (first != offset || last < offset || last - offset >= length || total <= last)
                    throw UnsupportedMediaException("invalid content range")
                synchronized(this) {
                    if (totalLength > 0 && totalLength != total) throw UnsupportedMediaException("file changed")
                    totalLength = total
                }
                val expected = (last - offset + 1).toInt()
                val finalUrl = resp.request.url.toString()
                if (finalUrl != target) effectiveUrl = finalUrl
                val body = resp.body ?: throw IOException("empty body")
                // Straight from OkHttp's segments into dst: no array of the whole range.
                val start = dst.position()
                val limit = dst.limit()
                dst.limit(start + expected)
                try {
                    val source = body.source()
                    while (dst.hasRemaining()) {
                        if (closed || cancelEpoch.get() != cancels0) throw IOException("preview request cancelled")
                        if (source.read(dst) < 0) throw IOException("truncated range")
                    }
                } finally {
                    dst.limit(limit)
                }
                val n = dst.position() - start
                byteCount.addAndGet(n.toLong())
                held.succeeded()
                return n
            }
        } catch (e: IOException) {
            val refusal = ConnectionLedger.refusalOf(e) ?: (e as? HttpStatusException)?.let {
                when {
                    it.tooMany -> Refusal.TOO_MANY
                    // A 403 from a short-lived redirect target is an expired link, retried from the original.
                    it.code == 403 && target == url -> Refusal.CONNECTION
                    else -> null
                }
            }
            refusal?.let { slot?.refused(it) }
            throw e
        } finally {
            slot?.close()
            call?.let { inFlight.remove(it) }
        }
    }
    /** Explicit redirects prevent origin credentials leaking to a redirected or cached CDN address. */
    private fun execute(initial: Request, epoch: Int, onCall: (Call) -> Unit): Response {
        var request = initial
        val origin = url.toHttpUrl()
        repeat(6) {
            if (closed || cancelEpoch.get() != epoch) throw IOException("preview request cancelled")
            if (request.url.scheme != origin.scheme || request.url.host != origin.host || request.url.port != origin.port) {
                request = request.newBuilder().apply {
                    headers.keys.filterNot { it.equals("User-Agent", true) }.forEach { removeHeader(it) }
                    header("Range", initial.header("Range").orEmpty())
                    header("Accept-Encoding", "identity")
                }.build()
            }
            val c = transport.newCall(request)
            onCall(c)
            inFlight.add(c)
            if (closed || cancelEpoch.get() != epoch) c.cancel()
            requestCount.incrementAndGet()
            val response = try { c.execute() } catch (error: IOException) { inFlight.remove(c); throw error }
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val next = response.header("Location")?.let { response.request.url.resolve(it) }
            response.close()
            inFlight.remove(c)
            if (next == null || request.url.isHttps && !next.isHttps) throw IOException("invalid preview redirect")
            request = request.newBuilder().url(next).build()
        }
        throw IOException("too many preview redirects")
    }

}
