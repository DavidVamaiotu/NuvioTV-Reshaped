package com.nuvio.tv.reshaped.livetv

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request

/** Live TV's own small HTTP client: playlists, provider APIs and guides, never playback. */
internal object LiveTvHttp {
    /** Also carries the list's channel previews, so they stay out of Nuvio's playback networking and speed learning. */
    internal val client: OkHttpClient by lazy {
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

    /**
     * Saves [url] to [target] gzip-compressed (as sent when the server already gzipped it, else
     * compressed quickly on the way), so a 100+ MB guide takes a few MB on the TV's storage.
     * The old file stays until the new one is complete.
     */
    suspend fun download(url: String, headers: Map<String, String>, target: File) {
        runInterruptible(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            target.parentFile?.mkdirs()
            val temp = File(target.path + ".part")
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty response")
                    BufferedInputStream(body.byteStream(), BUFFER_BYTES).use { buffered ->
                        if (buffered.startsWithGzipMagic()) {
                            temp.outputStream().use { buffered.copyTo(it, BUFFER_BYTES) }
                        } else {
                            FastGzipOutputStream(temp.outputStream()).use { buffered.copyTo(it, BUFFER_BYTES) }
                        }
                    }
                }
                if (!temp.renameTo(target)) throw IOException("Could not save ${target.name}")
            } finally {
                temp.delete()
            }
        }
    }

    /** Reads a file saved by [download]. Runs on the IO pool; cancelling interrupts the read. */
    suspend fun <T> readFile(file: File, block: (InputStream) -> T): T =
        runInterruptible(Dispatchers.IO) {
            BufferedInputStream(file.inputStream(), BUFFER_BYTES).use { buffered ->
                block(if (buffered.startsWithGzipMagic()) GZIPInputStream(buffered, BUFFER_BYTES) else buffered)
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

    /** Lowest compression: XML still shrinks about tenfold, at little CPU on a weak TV. */
    private class FastGzipOutputStream(out: java.io.OutputStream) : GZIPOutputStream(out, BUFFER_BYTES) {
        init {
            def.setLevel(Deflater.BEST_SPEED)
        }
    }
}
