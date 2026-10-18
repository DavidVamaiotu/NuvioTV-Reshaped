package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal enum class PreviewOrigin { ORIGINAL, PLAYBACK, VERIFIED_COPY, SERVER }
internal data class StoredPreview(val slot: Long, val timeMs: Long, val origin: PreviewOrigin, val offset: Long, val size: Int)

/** Append-only images, small atomic manifest, lazy image reads. No image array for a whole title. */
internal class PreviewStore(
    private val directory: File,
    identity: String,
    private val budgetBytes: Long,
) : Closeable {
    private val key = digest(identity.toByteArray())
    private val dataFile = File(directory, "$key.frames")
    private val indexFile = File(directory, "$key.index")
    private val entries = linkedMapOf<Long, StoredPreview>()
    private var dirty = 0
    private var closed = false
    init {
        directory.mkdirs()
        synchronized(active) { active[key] = (active[key] ?: 0) + 1 }
        load()
    }

    @Synchronized fun nearest(positionMs: Long, toleranceMs: Long = 5_000): StoredPreview? = entries.values
        .minByOrNull { kotlin.math.abs(it.timeMs - positionMs) }
        ?.takeIf { PreviewTiming.near(it.timeMs, positionMs, toleranceMs) }
    @Synchronized fun has(positionMs: Long): Boolean = entries.containsKey(positionMs / 10_000)
    @Synchronized fun all(): List<StoredPreview> = entries.values.toList()
    @Synchronized fun atSlot(slot: Long): StoredPreview? = entries[slot]
    @Synchronized fun read(entry: StoredPreview): ByteArray? {
        if (closed || entry.size !in 1..MAX_PICTURE_BYTES || entry.offset < 0 || entry.offset + entry.size > dataFile.length()) return null
        return runCatching {
            RandomAccessFile(dataFile, "r").use { file ->
                file.seek(entry.offset)
                ByteArray(entry.size).also(file::readFully)
            }
        }.getOrNull()
    }
    @Synchronized fun put(positionMs: Long, actualTimeMs: Long, origin: PreviewOrigin, picture: ByteArray, replace: Boolean = false) {
        if (closed || actualTimeMs < 0 || picture.size !in 1..MAX_PICTURE_BYTES) return
        val slot = positionMs / 10_000
        if ((!replace && slot in entries) || entries.size >= MAX_ENTRIES || dataFile.length() + picture.size > budgetBytes) return
        if (directory.usableSpace < 128L * 1024 * 1024 + picture.size) return
        runCatching {
            val offset = dataFile.length()
            dataFile.appendBytes(picture)
            entries[slot] = StoredPreview(slot, actualTimeMs, origin, offset, picture.size)
            if (++dirty >= 12) flush()
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
                    val item = StoredPreview(input.readLong(), input.readLong(), PreviewOrigin.entries[input.readInt()], input.readLong(), input.readInt())
                    require(item.slot >= 0 && item.timeMs >= 0 && item.size in 1..MAX_PICTURE_BYTES && item.offset >= 0)
                    if (item.offset + item.size <= dataFile.length()) entries[item.slot] = item
                }
            }
            indexFile.setLastModified(System.currentTimeMillis())
        }.onFailure { entries.clear() }
    }
    @Synchronized private fun flush() {
        if (dirty == 0) return
        val temp = File(indexFile.path + ".tmp")
        runCatching {
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC); output.writeInt(entries.size)
                for (entry in entries.values) {
                    output.writeLong(entry.slot); output.writeLong(entry.timeMs); output.writeInt(entry.origin.ordinal)
                    output.writeLong(entry.offset); output.writeInt(entry.size)
                }
            }
            check(temp.renameTo(indexFile))
            dirty = 0
            prune(directory, budgetBytes)
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        flush(); closed = true
        synchronized(active) {
            val remaining = (active[key] ?: 1) - 1
            if (remaining <= 0) active.remove(key) else active[key] = remaining
        }
    }
    companion object {
        private const val MAGIC = 0x52535033
        private const val MAX_ENTRIES = 20_000
        private const val MAX_PICTURE_BYTES = 512 * 1024
        private val active = mutableMapOf<String, Int>()
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun prune(directory: File, budget: Long) {
            var bytes = directory.listFiles()?.sumOf { it.length() } ?: return
            for (file in directory.listFiles().orEmpty().filter { it.extension == "index" }.sortedBy { it.lastModified() }) {
                if (bytes <= budget) break
                val key = file.nameWithoutExtension
                if (synchronized(active) { key in active }) continue
                for (part in listOf(file, File(directory, "$key.frames"), File(directory, "$key.media"))) {
                    val length = part.length()
                    if (part.delete()) bytes -= length
                }
            }
        }
    }
}
