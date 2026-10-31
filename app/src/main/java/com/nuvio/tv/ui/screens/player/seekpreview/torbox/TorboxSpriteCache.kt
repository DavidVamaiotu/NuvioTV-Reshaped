package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Content-addressed compressed bytes only. No account keys, CDN URLs or decoded images on disk. */
internal class TorboxSpriteDiskCache(
    private val directory: File,
    private val maxBytes: Long = 128L * 1024 * 1024
) {
    init { require(maxBytes > 0) }

    fun read(sheet: TorboxSpriteSheet): ByteArray? = synchronized(diskLock) {
        if (!validHash(sheet.sha256)) return@synchronized null
        val file = File(directory, sheet.sha256)
        try {
            if (!file.isFile) return@synchronized null
            if (file.length() != sheet.bytes.toLong()) {
                file.delete()
                return@synchronized null
            }
            val bytes = file.readBytes()
            if (!validSpriteBytes(sheet, bytes)) {
                file.delete()
                return@synchronized null
            }
            file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (_: IOException) { null } catch (_: SecurityException) { null }
    }

    fun write(sheet: TorboxSpriteSheet, bytes: ByteArray) = synchronized(diskLock) {
        require(validSpriteBytes(sheet, bytes))
        if (bytes.size > maxBytes) return@synchronized
        var temporary: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized
            temporary = File.createTempFile("sprite-", ".tmp", directory)
            temporary.writeBytes(bytes)
            val target = File(directory, sheet.sha256)
            if (!temporary.renameTo(target)) return@synchronized
            target.setLastModified(System.currentTimeMillis())
            val files = directory.listFiles()?.filter { validHash(it.name) && it.isFile }.orEmpty()
            var total = files.sumOf { it.length() }
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                val size = file.length()
                if (file.delete()) total -= size
            }
        } catch (_: IOException) { /* Disk full or evicted: the RAM copy remains usable. */ }
        catch (_: SecurityException) { }
        finally { temporary?.delete() }
    }

    companion object { private val diskLock = Any() }
}

/** Network waits are per sheet; prefetching never holds the tile-decoder lock. */
internal class TorboxSpriteByteCache(
    private val disk: TorboxSpriteDiskCache,
    private val maxBytes: Int = 8 * 1024 * 1024
) {
    private val lock = Any()
    private val memory = LinkedHashMap<String, ByteArray>(4, .75f, true)
    private val downloads = ConcurrentHashMap<String, Mutex>()
    private var bytes = 0
    @Volatile private var closed = false
    init { require(maxBytes > 0) }

    val memoryBytes: Int get() = synchronized(lock) { bytes }

    suspend fun get(sheet: TorboxSpriteSheet, fetch: suspend () -> ByteArray): ByteArray {
        require(sheet.bytes in 1..2 * 1024 * 1024 && validHash(sheet.sha256))
        check(!closed)
        synchronized(lock) { memory[sheet.sha256] }?.let { return it }
        return downloads.getOrPut(sheet.sha256) { Mutex() }.withLock {
            coroutineContext.ensureActive()
            check(!closed)
            synchronized(lock) { memory[sheet.sha256] }?.let { return@withLock it }
            val cached = disk.read(sheet)
            val data = cached ?: fetch().also {
                require(validSpriteBytes(sheet, it))
                coroutineContext.ensureActive()
                if (!closed) disk.write(sheet, it)
            }
            coroutineContext.ensureActive()
            synchronized(lock) {
                check(!closed)
                if (data.size <= maxBytes) {
                    memory.put(sheet.sha256, data)?.let { bytes -= it.size }
                    bytes += data.size
                    val oldest = memory.entries.iterator()
                    while (bytes > maxBytes && oldest.hasNext()) {
                        bytes -= oldest.next().value.size
                        oldest.remove()
                    }
                }
            }
            data
        }
    }

    /**
     * The sheet if it is already in RAM or verified on disk, without waiting on the network or
     * on another caller's download. Used while a remote key is held.
     */
    fun cached(sheet: TorboxSpriteSheet): ByteArray? {
        if (closed || sheet.bytes !in 1..2 * 1024 * 1024 || !validHash(sheet.sha256)) return null
        synchronized(lock) { memory[sheet.sha256] }?.let { return it }
        val data = disk.read(sheet) ?: return null
        synchronized(lock) {
            if (closed) return null
            if (data.size <= maxBytes && memory[sheet.sha256] == null) {
                memory[sheet.sha256] = data
                bytes += data.size
                val oldest = memory.entries.iterator()
                while (bytes > maxBytes && oldest.hasNext()) {
                    val entry = oldest.next()
                    if (entry.key == sheet.sha256) continue
                    bytes -= entry.value.size
                    oldest.remove()
                }
            }
        }
        return data
    }

    fun close() {
        closed = true
        synchronized(lock) { memory.clear(); bytes = 0 }
    }
}

/** Start at the resume point, then nearby sheets, then the rest of the movie. */
internal class TorboxPrefetchQueue(
    frames: List<TorboxSpriteFrame>,
    sheets: Map<String, TorboxSpriteSheet>,
    initialPositionMs: Long,
    maxBytes: Long = 48L * 1024 * 1024,
    maxSheets: Int = 240
) {
    private val ordered = frames.map { it.sheet }.distinct()
    private val pending: MutableList<TorboxSpriteSheet>
    init {
        require(maxBytes >= 0 && maxSheets >= 0)
        val current = frames.lastOrNull { it.startMs <= initialPositionMs }?.sheet
        val pivot = ordered.indexOf(current).coerceAtLeast(0)
        var remaining = maxBytes
        pending = ordered.indices.sortedBy { kotlin.math.abs(it - pivot) }.map { sheets.getValue(ordered[it]) }
            .filter { sheet ->
                if (sheet.bytes > remaining) false else { remaining -= sheet.bytes; true }
            }.take(maxSheets).toMutableList()
    }

    @Synchronized fun next(): TorboxSpriteSheet? =
        if (pending.isEmpty()) null else pending.removeAt(0)

    private val retried = HashSet<String>()

    /** A sheet whose download failed is tried once more after the rest of the movie. */
    @Synchronized fun retryLater(sheet: TorboxSpriteSheet) {
        if (retried.add(sheet.path) && pending.none { it.path == sheet.path }) pending.add(sheet)
    }

    @Synchronized fun prioritize(path: String) {
        val index = pending.indexOfFirst { it.path == path }
        if (index > 0) pending.add(0, pending.removeAt(index))
    }
}

private fun validHash(value: String) = value.matches(Regex("[0-9a-f]{64}"))
private fun validSpriteBytes(sheet: TorboxSpriteSheet, bytes: ByteArray): Boolean =
    validHash(sheet.sha256) && bytes.size == sheet.bytes &&
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } == sheet.sha256
