package com.nuvio.tv.reshaped.livetv

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request

/** Live TV's own small HTTP client: playlists, provider APIs and guides, never playback. */
internal object LiveTvHttp {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Opens [url] and hands [block] the body as a stream, un-gzipped when the server sent a
     * gzip file (common for guides) rather than gzip transfer encoding. Runs on the IO pool; cancelling interrupts the read.
     */
    suspend fun <T> stream(url: String, headers: Map<String, String>, block: (InputStream) -> T): T =
        runInterruptible(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")
                BufferedInputStream(body.byteStream(), BUFFER_BYTES).use { buffered ->
                    block(if (buffered.startsWithGzipMagic()) GZIPInputStream(buffered, BUFFER_BYTES) else buffered)
                }
            }
        }

    /** A small response (provider API calls) as text. */
    suspend fun text(url: String, headers: Map<String, String>): String =
        stream(url, headers) { it.bufferedReader().readText() }

    private fun BufferedInputStream.startsWithGzipMagic(): Boolean {
        mark(2)
        val first = read()
        val second = read()
        reset()
        return first == 0x1f && second == 0x8b
    }

    private const val BUFFER_BYTES = 64 * 1024
}
