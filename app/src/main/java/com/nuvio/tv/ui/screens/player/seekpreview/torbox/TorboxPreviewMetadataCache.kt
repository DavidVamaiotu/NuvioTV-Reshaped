package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Stream large account listings to disk; retain only the requested movie when parsing them.
 * Keys contain account digests or immutable torrent identities, never credentials or CDN URLs.
 */
internal class TorboxPreviewMetadataCache(
    private val directory: File,
    private val maxBytes: Long = 128L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init { require(maxBytes > 0) }
    suspend fun <T> get(
        key: String,
        cap: Int,
        maxAgeMs: Long,
        refresh: Boolean = false,
        decode: (InputStream) -> T,
        fetch: suspend ((InputStream) -> T) -> T,
    ): T {
        require(cap > 0 && maxAgeMs >= 0)
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return locked(File(directory, name).absolutePath) {
            coroutineContext.ensureActive()
            val target = File(directory, name)
            if (!refresh && target.isFile && target.length() in 1..cap.toLong() &&
                now() - target.lastModified() in 0..maxAgeMs) {
                try { return@locked target.inputStream().buffered().use { decode(limited(it, cap)) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Evicted or corrupt metadata gets one fresh fetch. */ }
            }
            val temporary = try {
                if (!directory.isDirectory && !directory.mkdirs()) null
                else File.createTempFile("metadata-", ".tmp", directory)
            } catch (_: IOException) { null } catch (_: SecurityException) { null }
            if (temporary == null) return@locked fetch { decode(limited(it, cap)) }
            try {
                var cacheable = true
                val output = try { temporary.outputStream().buffered() }
                catch (_: IOException) { return@locked fetch { decode(limited(it, cap)) } }
                val context = coroutineContext
                val result = try {
                    fetch { input ->
                        val bounded = limited(input, cap)
                        val tee = object : InputStream() {
                            private fun save(buffer: ByteArray, offset: Int, count: Int) {
                                if (cacheable && count > 0) try { output.write(buffer, offset, count) }
                                catch (_: IOException) { cacheable = false }
                            }
                            override fun read(): Int = bounded.read().also {
                                if (it >= 0) save(byteArrayOf(it.toByte()), 0, 1)
                            }
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                                bounded.read(buffer, offset, length).also { save(buffer, offset, it) }
                            // The HTTP response owns the source; JsonReader may close this wrapper.
                            override fun close() = Unit
                        }
                        val decoded = decode(tee)
                        val buffer = ByteArray(8192)
                        while (tee.read(buffer) >= 0) context.ensureActive()
                        decoded
                    }
                } finally { try { output.close() } catch (_: IOException) { cacheable = false } }
                coroutineContext.ensureActive()
                if (cacheable) try {
                    if ((!target.exists() || target.delete()) && temporary.renameTo(target)) {
                        target.setLastModified(now())
                        val files = directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}")) && it.isFile }.orEmpty()
                        var total = files.sumOf { it.length() }
                        for (file in files.sortedBy { it.lastModified() }) {
                            if (total <= maxBytes) break
                            val size = file.length()
                            if (file.delete()) total -= size
                        }
                    }
                } catch (_: SecurityException) { }
                result
            } finally { temporary.delete() }
        }
    }

    private fun limited(input: InputStream, cap: Int): InputStream = object : InputStream() {
        private var count = 0
        override fun read(): Int = input.read().also {
            if (it >= 0 && ++count > cap) throw IOException("Preview metadata above limit")
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = input.read(buffer, offset, minOf(length, cap + 1 - count))
            if (n > 0) {
                count += n
                if (count > cap) throw IOException("Preview metadata above limit")
            }
            return n
        }
        override fun close() = Unit
    }

    companion object {
        private class Lock(val mutex: Mutex = Mutex(), var users: Int = 0)
        private val locks = mutableMapOf<String, Lock>()
        private suspend fun <T> locked(key: String, block: suspend () -> T): T {
            val lock = synchronized(locks) { locks.getOrPut(key) { Lock() }.also { it.users++ } }
            try { return lock.mutex.withLock { block() } }
            finally { synchronized(locks) { if (--lock.users == 0) locks.remove(key) } }
        }
    }
}
