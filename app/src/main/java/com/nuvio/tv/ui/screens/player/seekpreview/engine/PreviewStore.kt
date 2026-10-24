package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal enum class PreviewOrigin { ORIGINAL, PLAYBACK, VERIFIED_COPY, SERVER }
internal data class StoredPreview(val slot: Long, val timeMs: Long, val origin: PreviewOrigin, val offset: Long, val size: Int, val endMs: Long = timeMs + 10_000)

/** Append-only images, small atomic manifest, lazy image reads. No image array for a whole title. */
internal class PreviewStore(
    directory: File,
    identity: String,
    budgetBytes: Long,
    private val slotMs: Long = 10_000,
) : Closeable {
    private val storeIdentity = if (slotMs == 10_000L) identity else "$identity|slot:$slotMs"
    private val registryKey = File(directory, digest(storeIdentity.toByteArray())).absolutePath
    fun imageKey(entry: StoredPreview) = PreviewImageKey(registryKey, entry.slot, entry.offset, entry.size)
    @Volatile private var closed = false
    private val state = synchronized(active) {
        active[registryKey]?.also { it.references++ } ?: State(directory, digest(storeIdentity.toByteArray()), budgetBytes, slotMs).also {
            active[registryKey] = it
        }
    }
    fun nearest(positionMs: Long, toleranceMs: Long = 5_000): StoredPreview? = state.nearest(positionMs, toleranceMs)
    fun has(positionMs: Long): Boolean = state.has(positionMs)
    fun all(): List<StoredPreview> = state.all()
    fun atSlot(slot: Long): StoredPreview? = state.atSlot(slot)
    fun read(entry: StoredPreview): ByteArray? = if (closed) null else state.read(entry)
    @Synchronized fun put(positionMs: Long, actualTimeMs: Long, origin: PreviewOrigin, picture: ByteArray, replace: Boolean = false, endMs: Long = actualTimeMs + 10_000) {
        if (!closed) {
            state.put(positionMs, actualTimeMs, origin, picture, replace, endMs)
            prune(directory = state.directory, budget = state.budgetBytes)
        }
    }
    @Synchronized override fun close() {
        synchronized(active) {
            if (closed) return
            closed = true
            if (--state.references == 0) { state.flush(); active.remove(registryKey) }
        }
    }
    private class State(val directory: File, val key: String, val budgetBytes: Long, private val slotMs: Long) {
        var references = 1
        private val dataFile = File(directory, "$key.frames")
        private val indexFile = File(directory, "$key.index")
        private val entries = linkedMapOf<Long, StoredPreview>()
        private val byTime = java.util.TreeMap<Long, StoredPreview>()
        private var dirty = 0
        private val ioLock = Any()
        init { directory.mkdirs(); load() }

        @Synchronized fun nearest(positionMs: Long, toleranceMs: Long = 5_000): StoredPreview? {
            val before = byTime.floorEntry(positionMs)?.value
            if (before != null && positionMs < before.endMs) return before
            val after = byTime.ceilingEntry(positionMs)?.value
            return listOfNotNull(before, after).minByOrNull { kotlin.math.abs(it.timeMs - positionMs) }
                ?.takeIf { PreviewTiming.near(it.timeMs, positionMs, toleranceMs) }
        }
        @Synchronized fun has(positionMs: Long): Boolean = entries.containsKey(positionMs / slotMs)
        @Synchronized fun all(): List<StoredPreview> = entries.values.toList()
        @Synchronized fun atSlot(slot: Long): StoredPreview? = entries[slot]
        fun read(entry: StoredPreview): ByteArray? {
            if (entry.size !in 1..MAX_PICTURE_BYTES || entry.offset < 0 || entry.offset + entry.size > dataFile.length()) return null
            return runCatching {
                RandomAccessFile(dataFile, "r").use { file ->
                    file.seek(entry.offset)
                    ByteArray(entry.size).also(file::readFully)
                }
            }.getOrNull()
        }
        fun put(positionMs: Long, actualTimeMs: Long, origin: PreviewOrigin, picture: ByteArray, replace: Boolean = false, endMs: Long = actualTimeMs + 10_000) {
            if (actualTimeMs < 0 || endMs <= actualTimeMs || picture.size !in 1..MAX_PICTURE_BYTES) return
            val slot = positionMs / slotMs
            synchronized(ioLock) {
                val accepted = synchronized(this) { (replace || slot !in entries) && entries.size < MAX_ENTRIES }
                if (!accepted || dataFile.length() + picture.size > budgetBytes) return
                if (directory.usableSpace < 128L * 1024 * 1024 + picture.size) return
                runCatching {
                    val offset = dataFile.length()
                    dataFile.appendBytes(picture)
                    val shouldFlush = synchronized(this) {
                        entries.remove(slot)?.let { byTime.remove(it.timeMs) }
                        val entry = StoredPreview(slot, actualTimeMs, origin, offset, picture.size, endMs)
                        entries[slot] = entry
                        byTime[actualTimeMs] = entry
                        ++dirty >= 12
                    }
                    if (shouldFlush) flush()
                }
            }
        }
        private fun load() {
            if (!indexFile.isFile || indexFile.length() > 2 * 1024 * 1024) return
            runCatching {
                DataInputStream(indexFile.inputStream().buffered()).use { input ->
                    if (input.readInt() != MAGIC) return
                    val count = input.readInt()
                    require(count in 0..MAX_ENTRIES)
                    repeat(count) {
                        val item = StoredPreview(input.readLong(), input.readLong(), PreviewOrigin.entries[input.readInt()], input.readLong(), input.readInt(), input.readLong())
                        require(item.slot >= 0 && item.timeMs >= 0 && item.endMs > item.timeMs && item.size in 1..MAX_PICTURE_BYTES && item.offset >= 0)
                        if (item.offset + item.size <= dataFile.length()) { entries[item.slot] = item; byTime[item.timeMs] = item }
                    }
                }
                indexFile.setLastModified(System.currentTimeMillis())
            }.onFailure { entries.clear(); byTime.clear() }
        }
        fun flush() = synchronized(ioLock) {
            val snapshot = synchronized(this) metadata@ {
                if (dirty == 0) return@metadata emptyList<StoredPreview>()
                entries.values.toList()
            }
            if (snapshot.isEmpty()) return@synchronized
            val temp = File(indexFile.path + ".tmp")
            runCatching {
                DataOutputStream(temp.outputStream().buffered()).use { output ->
                    output.writeInt(MAGIC); output.writeInt(snapshot.size)
                    for (entry in snapshot) {
                        output.writeLong(entry.slot); output.writeLong(entry.timeMs); output.writeInt(entry.origin.ordinal)
                        output.writeLong(entry.offset); output.writeInt(entry.size); output.writeLong(entry.endMs)
                    }
                }
                check(temp.renameTo(indexFile))
                synchronized(this) { dirty = 0 }
            }
            Unit
        }
    }

    companion object {
        private const val MAGIC = 0x52535034
        private const val MAX_ENTRIES = 20_000
        private const val MAX_PICTURE_BYTES = 512 * 1024
        private val active = mutableMapOf<String, State>()
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun prune(directory: File, budget: Long) = synchronized(active) {
            var bytes = directory.listFiles()?.sumOf { it.length() } ?: return@synchronized
            for (file in directory.listFiles().orEmpty().filter { it.extension == "index" }.sortedBy { it.lastModified() }) {
                if (bytes <= budget) break
                val key = file.nameWithoutExtension
                if (active.values.any { it.key == key }) continue
                for (part in listOf(file, File(directory, "$key.frames"), File(directory, "$key.media"))) {
                    val length = part.length()
                    if (part.delete()) bytes -= length
                }
            }
        }
    }
}
