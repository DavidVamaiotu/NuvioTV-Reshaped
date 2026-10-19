// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.util.Log
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Per-title thumbnail store. `<key>.dat` holds appended WebP frames, `<key>.idx` the slot table, retention metadata,
 * the video duration, the cached keyframe index and the verdicts on other releases, rewritten atomically (temp +
 * rename). Keyed by content identity + file byte length, so URL rotation does not matter; the key starts with the
 * identity's own prefix, so every release of a title can be found.
 */
internal class ThumbStore private constructor(
    private val dir: File,
    val key: String,
    private val rgb565: Boolean,
    memFrames: Int = memFrames(),
) {
    companion object {
        private const val TAG = "ThumbStore"
        const val DIR = "reshaped_seek_thumbs_v1"
        /** Older stores (smaller pictures), deleted at the next housekeeping. */
        private val LEGACY_DIRS = listOf("seek_thumbs", "seek_thumbs_v3")
        private const val MAGIC = 0x4E545333            // "NTS3"
        private const val FORMAT = 2
        private const val WEBP_QUALITY = 80
        private const val PREFIX_CHARS = 12
        /** Memory LRU size in frames. */
        const val MEM_FRAMES_STRONG = 36
        const val MEM_FRAMES_SMALL = 24

        fun memFrames(): Int = if (MemoryGovernor.isStrongBox) MEM_FRAMES_STRONG else MEM_FRAMES_SMALL
        /** Another release's store only stands in until it is confirmed. */
        private const val OTHER_MEM_FRAMES = 12
        private const val MAX_BUDGET_STRONG_BYTES = 256L * 1024 * 1024
        private const val MAX_BUDGET_SMALL_BYTES = 128L * 1024 * 1024
        private const val MEMORY_ONLY_BELOW_FREE_BYTES = 1024L * 1024 * 1024
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val FLUSH_EVERY_FRAMES = 10
        private const val FLUSH_EVERY_MS = 5_000L

        private fun sha1Hex(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Shared by every release of [identity]. */
        fun identityPrefix(identity: String): String = sha1Hex("$identity|$FORMAT").take(PREFIX_CHARS)

        fun keyFor(identity: String, fileLength: Long, fingerprint: String? = null): String =
            identityPrefix(identity) + sha1Hex("$identity|$fileLength|$FORMAT${fingerprint?.let { "|$it" }.orEmpty()}").take(PREFIX_CHARS)

        /**
         * A smaller copy's pictures made for a 4K title of [identity]: found as another release of the title, but never
         * the copy file's own store, since they are fitted to the 4K title's [pictureW] x [pictureH].
         */
        fun copyKeyFor(identity: String, fileLength: Long, pictureW: Int, pictureH: Int, fingerprint: String? = null): String =
            identityPrefix(identity) + sha1Hex("$identity|$fileLength|copy ${pictureW}x$pictureH|$FORMAT${fingerprint?.let { "|$it" }.orEmpty()}").take(PREFIX_CHARS)

        fun open(context: Context, identity: String, fileLength: Long, rgb565: Boolean, fingerprint: String? = null): ThumbStore =
            openKey(context, keyFor(identity, fileLength, fingerprint), rgb565)

        fun openKey(context: Context, key: String, rgb565: Boolean): ThumbStore {
            val dir = File(context.applicationContext.cacheDir, DIR).apply { mkdirs() }
            return ThumbStore(dir, key, rgb565).also { it.load() }
        }

        /** Another release's store, read only: its cached keyframe index is not loaded. */
        fun openOther(context: Context, key: String, rgb565: Boolean): ThumbStore =
            ThumbStore(File(context.applicationContext.cacheDir, DIR), key, rgb565, OTHER_MEM_FRAMES)
                .also { it.load(keepIndex = false) }

        /** [memoryOnly] null = decided by the free space, as [open] does. */
        internal fun openIn(dir: File, key: String, memoryOnly: Boolean? = null): ThumbStore =
            ThumbStore(dir.apply { mkdirs() }, key, rgb565 = true).also { s ->
                s.load()
                if (memoryOnly != null) synchronized(s) { s.memoryOnly = memoryOnly }
            }

        fun siblings(context: Context, identity: String, exceptKey: String): List<Sibling> =
            siblingsIn(File(context.applicationContext.cacheDir, DIR), identity, exceptKey)

        internal fun siblingsIn(dir: File, identity: String, exceptKey: String): List<Sibling> {
            val prefix = identityPrefix(identity)
            return dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".idx") }?.mapNotNull { idx ->
                val key = idx.name.removeSuffix(".idx")
                if (key == exceptKey) return@mapNotNull null
                runCatching { readMeta(idx) }.getOrNull()?.let { Sibling(key, it.durationMs, it.pictures) }
            } ?: emptyList()
        }

        /** Deletes every stored thumbnail, including the legacy JPEG cache. */
        fun clearAll(context: Context): Boolean {
            val cache = context.applicationContext.cacheDir
            val a = File(cache, DIR).let { !it.exists() || it.deleteRecursively() }
            val b = LEGACY_DIRS.all { name -> File(cache, name).let { !it.exists() || it.deleteRecursively() } }
            return a && b
        }

        /** Stores being written outside a session, housekeeping skips them. Reference-counted per key. */
        private val busy = java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun markBusy(key: String) {
            busy.merge(key, 1, Int::plus)
        }

        fun unmarkBusy(key: String) {
            busy.computeIfPresent(key) { _, n -> if (n <= 1) null else n - 1 }
        }

        fun isBusy(key: String): Boolean = busy.containsKey(key)

        /** Drops the legacy cache, expired titles and the least-recently-used titles above the budget. */
        fun housekeeping(context: Context, activeKey: String? = null) {
            val cache = context.applicationContext.cacheDir
            for (name in LEGACY_DIRS) File(cache, name).takeIf { it.exists() }?.deleteRecursively()
            val dir = File(cache, DIR)
            val titles = dir.listFiles { f -> f.name.endsWith(".idx") }?.mapNotNull { idx ->
                val key = idx.name.removeSuffix(".idx")
                val dat = File(dir, "$key.dat")
                val meta = runCatching { readMeta(idx) }.getOrNull()
                Triple(key, meta, idx.length() + dat.length())
            } ?: return
            val now = System.currentTimeMillis()
            var total = titles.sumOf { it.third }
            val budget = budgetBytes(context)
            for ((key, meta, size) in titles) {
                if (key == activeKey || isBusy(key)) continue
                val expired = meta == null || now - meta.lastAccessMs > retentionMs(meta.watchedFraction)
                if (expired) {
                    delete(dir, key)
                    total -= size
                }
            }
            if (total > budget) {
                for ((key, meta, size) in titles.sortedBy { it.second?.lastAccessMs ?: 0L }) {
                    if (total <= budget) break
                    if (key == activeKey || isBusy(key) || !File(dir, "$key.idx").exists()) continue
                    delete(dir, key)
                    total -= size
                }
            }
            // orphaned data and temp files
            dir.listFiles()?.forEach { f ->
                val k = f.name.substringBefore('.')
                if (isBusy(k) || k == activeKey) return@forEach
                if (f.name.endsWith(".tmp") ||
                    ((f.name.endsWith(".dat") || f.name.endsWith(".done")) && !File(dir, "$k.idx").exists())) f.delete()
            }
        }

        fun totalBytes(context: Context): Long =
            File(context.applicationContext.cacheDir, DIR).listFiles()?.sumOf { it.length() } ?: 0L

        /** Watched 7 days, in progress 30 days, abandoned 3 days. */
        internal fun retentionMs(watchedFraction: Float): Long = when {
            watchedFraction >= 0.9f -> 7 * DAY_MS
            watchedFraction < 0.05f -> 3 * DAY_MS
            else -> 30 * DAY_MS
        }

        /** min(256 MB on a strong box or 128 MB on a small one, 5 % of free space, 1/2 cache quota). */
        fun budgetBytes(context: Context): Long {
            MemoryGovernor.init(context)
            val cache = context.applicationContext.cacheDir
            val free = runCatching { StatFs(cache.absolutePath).availableBytes }.getOrDefault(0L)
            val cap = if (MemoryGovernor.isStrongHardware) MAX_BUDGET_STRONG_BYTES else MAX_BUDGET_SMALL_BYTES
            var b = minOf(cap, free / 20)
            if (Build.VERSION.SDK_INT >= 26) {
                runCatching {
                    val sm = context.getSystemService(StorageManager::class.java)
                    val quota = sm.getCacheQuotaBytes(sm.getUuidForPath(cache))
                    if (quota > 0) b = minOf(b, quota / 2)
                }
            }
            return b.coerceAtLeast(0L)
        }

        private fun delete(dir: File, key: String) {
            File(dir, "$key.idx").delete()
            File(dir, "$key.dat").delete()
            File(dir, "$key.done").delete()
        }

        /** Every coverage thumbnail of this release is stored. Checked without opening the store or the network. */
        fun isMarkedComplete(context: Context, identity: String, fileLength: Long): Boolean {
            val dir = File(context.applicationContext.cacheDir, DIR)
            val key = keyFor(identity, fileLength)
            return File(dir, "$key.done").exists() && File(dir, "$key.idx").exists()
        }

        fun exists(context: Context, identity: String, fileLength: Long): Boolean =
            File(File(context.applicationContext.cacheDir, DIR), "${keyFor(identity, fileLength)}.idx").exists()

        private class Meta(val lastAccessMs: Long, val watchedFraction: Float, val durationMs: Long, val pictures: Int)

        private fun readMeta(idx: File): Meta = DataInputStream(idx.inputStream().buffered()).use { s ->
            if (s.readInt() != MAGIC || s.readInt() != FORMAT) throw IllegalStateException("bad idx")
            Meta(lastAccessMs = s.readLong(), watchedFraction = s.readFloat(), durationMs = s.readLong(), pictures = s.readInt())
        }

        private fun DataInputStream.skipFully(n: Int) {
            var left = n
            while (left > 0) {
                val k = skipBytes(left)
                if (k <= 0) throw java.io.EOFException()
                left -= k
            }
        }
    }

    /** A saved store of another release of the same title. */
    class Sibling(val key: String, val durationMs: Long, val pictures: Int)

    /** Frame location in .dat. */
    private class Entry(val offset: Long, val length: Int, val keyframePtsUs: Long)

    private val entries = HashMap<Long, Entry>()
    private fun bytesForFrames(frames: Int): Int {
        val requested = frames.coerceAtLeast(0) * ThumbGeometry.MAX_WIDTH *
            (ThumbGeometry.MAX_WIDTH * 3 / 4) * if (rgb565) 2 else 4
        return if (MemoryGovernor.isLowRam) minOf(requested, 4 * 1024 * 1024) else requested
    }
    private val mem = object : LruCache<Long, Bitmap>(bytesForFrames(memFrames)) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }
    private var lastAccessMs = System.currentTimeMillis()
    @Volatile var watchedFraction = 0f
    private var cachedIndex: ByteArray? = null
    /** Video duration in ms, 0 = not known yet. */
    private var durationMs = 0L
    /** Other release's key -> same picture timing (true) or different (false). */
    private val verdicts = HashMap<String, Boolean>()
    private var dirty = 0
    private var lastFlushMs = 0L
    @Volatile private var memoryOnly = false
    /** Serialises the .dat appends and .idx rewrites. Taken before the store's own lock, never under it. */
    internal val diskLock = Any()
    /** Storage is nearly full, frames stay in memory only. */
    val isMemoryOnly: Boolean get() = synchronized(this) { memoryOnly }
    private val idxFile get() = File(dir, "$key.idx")
    private val datFile get() = File(dir, "$key.dat")

    @Synchronized
    private fun load(keepIndex: Boolean = true) {
        memoryOnly = runCatching { StatFs(dir.absolutePath).availableBytes < MEMORY_ONLY_BELOW_FREE_BYTES }.getOrDefault(true)
        if (!idxFile.exists() || idxFile.length() > 16L * 1024 * 1024) return
        runCatching {
            val datLen = datFile.length()
            DataInputStream(idxFile.inputStream().buffered()).use { s ->
                if (s.readInt() != MAGIC || s.readInt() != FORMAT) return@use
                s.readLong()                       // last access
                watchedFraction = s.readFloat()
                durationMs = s.readLong()
                val n = s.readInt(); require(n in 0..17_280)
                repeat(n) {
                    val slot = s.readLong()
                    val e = Entry(s.readLong(), s.readInt(), s.readLong())
                    require(slot in 0 until PreviewLimits.MAX_DURATION_MS * 1000L && e.offset >= 0 && e.length in 1..512 * 1024 && e.keyframePtsUs >= 0)
                    if (e.offset <= datLen && e.length <= datLen - e.offset) entries[slot] = e   // drop entries beyond a torn append
                }
                val idxLen = s.readInt(); require(idxLen in 0..8 * 1024 * 1024)
                if (keepIndex && idxLen in 1..(8 * 1024 * 1024)) cachedIndex = ByteArray(idxLen).also { s.readFully(it) }
                else if (idxLen > 0) s.skipFully(idxLen)
                val verdictCount = s.readInt(); require(verdictCount in 0..2000)
                repeat(verdictCount) { verdicts[s.readUTF()] = s.readBoolean() }
            }
        }.onFailure {
            Log.w(TAG, "discarding unreadable store $key: ${it.javaClass.simpleName}")
            entries.clear()
            cachedIndex = null
            verdicts.clear()
            if (keepIndex) {
                idxFile.delete()
                datFile.delete()
            }
        }
    }

    @Synchronized fun has(slotUs: Long): Boolean = entries.containsKey(slotUs) || mem.get(slotUs) != null
    @Synchronized fun memGet(slotUs: Long): Bitmap? = mem.get(slotUs)
    @Synchronized fun slots(): Set<Long> = entries.keys.toSet()
    @Synchronized fun isEmpty(): Boolean = entries.isEmpty() && mem.size() == 0
    @Synchronized fun keyframePtsFor(slotUs: Long): Long? = entries[slotUs]?.keyframePtsUs

    /** Serialised with [KeyframeIndexCodec]. */
    @Synchronized fun cachedKeyframeIndex(): ByteArray? = cachedIndex
    @Synchronized fun setKeyframeIndex(bytes: ByteArray) { cachedIndex = bytes; dirty++ }

    @Synchronized fun durationMs(): Long = durationMs

    @Synchronized fun noteDuration(ms: Long) {
        if (ms <= 0L || ms == durationMs) return
        durationMs = ms
        dirty++
    }

    /** True = same picture timing as release [otherKey], false = different, null = not checked. */
    @Synchronized fun verdictOn(otherKey: String): Boolean? = verdicts[otherKey]

    @Synchronized fun noteVerdict(otherKey: String, same: Boolean) {
        if (verdicts.put(otherKey, same) != same) dirty++
    }

    /** The stored WebP bytes of [slotUs]. IO thread. */
    fun compressed(slotUs: Long): ByteArray? = readBytes(slotUs)

    class Compressed(val slotsUs: LongArray, val keyframePtsUs: Long, val webp: ByteArray)

    /** Appends pictures that are already compressed (no decode). [indexNow] false leaves the index to [flush]. IO thread. */
    fun putCompressed(items: List<Compressed>, indexNow: Boolean = true): Boolean {
        if (memoryOnly || items.isEmpty()) return false
        synchronized(diskLock) {
            var offset = datFile.length()
            val added = ArrayList<Pair<LongArray, Entry>>(items.size)
            val ok = runCatching {
                java.io.FileOutputStream(datFile, true).buffered().use { out ->
                    for (c in items) {
                        out.write(c.webp)
                        added += c.slotsUs to Entry(offset, c.webp.size, c.keyframePtsUs)
                        offset += c.webp.size
                    }
                }
            }.isSuccess
            if (!ok) return false
            val snapshot = synchronized(this) {
                for ((slots, e) in added) for (s in slots) entries[s] = e
                lastAccessMs = System.currentTimeMillis()
                dirty++
                if (indexNow) snapshotLocked() else null
            }
            snapshot?.let { writeIndex(it) }
        }
        return true
    }

    private fun validPicture(bytes: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth in 1..640 && bounds.outHeight in 1..640
    }

    /** The latest full-colour picture (one kept) when memory frames are RGB565: the centre of the strip uses it. */
    @Volatile private var fullColour: Pair<Long, Bitmap>? = null

    @Volatile private var fullColourWanted = -1L

    /** The full-colour picture of [slotUs] if it is at hand: memory frames when they are full colour, else the one kept. */
    fun fullColourGet(slotUs: Long): Bitmap? {
        if (!rgb565) return memGet(slotUs)
        fullColourWanted = slotUs
        return fullColour?.takeIf { it.first == slotUs }?.second
    }

    /** Disk to the full-colour slot. IO thread. */
    fun loadFullColour(slotUs: Long): Bitmap? {
        if (!rgb565) return load(slotUs)
        fullColour?.takeIf { it.first == slotUs }?.let { return it.second }
        val bytes = readBytes(slotUs) ?: return discardPicture(slotUs)
        if (!validPicture(bytes)) return discardPicture(slotUs)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return discardPicture(slotUs)
        fullColour = slotUs to bmp
        return bmp
    }

    /** The saved pictures' compressed bytes, read once so a scrub decodes without waiting for the disk. */
    @Volatile private var datBytes: ByteArray? = null

    /** Reads the whole .dat into memory when it is at most [maxBytes]. IO thread. */
    fun preloadCompressed(maxBytes: Long): Boolean {
        if (datBytes != null) return true
        if (memoryOnly) return false
        val bytes = synchronized(diskLock) {
            val length = datFile.length()
            if (length <= 0 || length > maxBytes || length > Int.MAX_VALUE) return false
            try {
                RandomAccessFile(datFile, "r").use { file -> ByteArray(length.toInt()).also(file::readFully) }
            } catch (_: java.io.IOException) { null }
        } ?: return false
        datBytes = bytes
        return true
    }

    private fun readBytes(slotUs: Long): ByteArray? {
        val e = synchronized(this) { entries[slotUs] } ?: return null
        datBytes?.let { b -> if (e.offset + e.length <= b.size) return b.copyOfRange(e.offset.toInt(), (e.offset + e.length).toInt()) }
        return runCatching {
            RandomAccessFile(datFile, "r").use { f ->
                ByteArray(e.length).also { f.seek(e.offset); f.readFully(it) }
            }
        }.getOrNull()
    }

    /** A damaged or deleted image must become missing so generation can replace it. */
    @Synchronized private fun discardPicture(slotUs: Long): Bitmap? {
        val bad = entries[slotUs] ?: return null
        val slots = entries.filterValues { it.offset == bad.offset && it.length == bad.length }.keys.toList()
        for (slot in slots) { entries.remove(slot); mem.remove(slot) }
        fullColour = null
        dirty++
        return null
    }

    /** Disk to memory. IO thread. */
    fun load(slotUs: Long): Bitmap? {
        synchronized(this) { mem.get(slotUs)?.let { return it } }
        val bytes = readBytes(slotUs) ?: return discardPicture(slotUs)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = if (rgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        if (!validPicture(bytes)) return discardPicture(slotUs)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return discardPicture(slotUs)
        synchronized(this) {
            mem.put(slotUs, bmp)
            lastAccessMs = System.currentTimeMillis()
        }
        return bmp
    }

    /**
     * Stores one thumbnail for every slot it serves: one WebP append shared by all [slotsUs], written from the full-colour
     * [bmp]. Memory keeps an RGB565 copy when the app's image setting asks for it. IO thread.
     */
    fun put(slotsUs: LongArray, keyframePtsUs: Long, bmp: Bitmap) {
        val kept = if (rgb565 && bmp.config != Bitmap.Config.RGB_565) bmp.copy(Bitmap.Config.RGB_565, false) ?: bmp else bmp
        if (rgb565) fullColourWanted.takeIf { it in slotsUs }?.let { fullColour = it to bmp }
        synchronized(this) { for (s in slotsUs) mem.put(s, kept) }
        if (memoryOnly) return
        val webp = ByteArrayOutputStream(8 * 1024).also {
            @Suppress("DEPRECATION")
            val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bmp.compress(fmt, WEBP_QUALITY, it)
        }.toByteArray()
        synchronized(diskLock) {
            val offset = datFile.length()
            runCatching { datFile.appendBytes(webp) }.onFailure { return }
            val entry = Entry(offset, webp.size, keyframePtsUs)
            val snapshot = synchronized(this) {
                for (s in slotsUs) entries[s] = entry
                val now = System.currentTimeMillis()
                lastAccessMs = now
                dirty++
                if (dirty >= FLUSH_EVERY_FRAMES || now - lastFlushMs >= FLUSH_EVERY_MS) snapshotLocked() else null
            }
            snapshot?.let { writeIndex(it) }
        }
    }

    /** See [isMarkedComplete]. */
    fun markComplete() {
        if (memoryOnly) return
        runCatching { File(dir, "$key.done").createNewFile() }
    }

    fun flush() {
        synchronized(diskLock) {
            synchronized(this) { if (dirty > 0) snapshotLocked() else null }?.let { writeIndex(it) }
        }
    }

    @Synchronized fun trimMemory(keep: Int) {
        mem.trimToSize(bytesForFrames(keep))
        datBytes = null
        fullColour = null
    }

    /** Index contents at one moment, written to disk without holding the store's lock. */
    private class IndexSnapshot(
        val lastAccessMs: Long,
        val watchedFraction: Float,
        val durationMs: Long,
        val entries: List<Pair<Long, Entry>>,
        val cachedIndex: ByteArray?,
        val verdicts: List<Pair<String, Boolean>>,
        val dirty: Int,
    )

    private fun snapshotLocked(): IndexSnapshot? {
        if (memoryOnly) return null
        return IndexSnapshot(lastAccessMs, watchedFraction, durationMs, entries.toList(), cachedIndex, verdicts.toList(), dirty)
    }

    /** Under [diskLock] only: one writer per file, while [has] and [memGet] (main thread) never wait for the disk. */
    private fun writeIndex(snap: IndexSnapshot) {
        val tmp = File(dir, "$key.idx.tmp")
        runCatching {
            DataOutputStream(tmp.outputStream().buffered()).use { s ->
                s.writeInt(MAGIC)
                s.writeInt(FORMAT)
                s.writeLong(snap.lastAccessMs)
                s.writeFloat(snap.watchedFraction)
                s.writeLong(snap.durationMs)
                s.writeInt(snap.entries.size)
                for ((slot, e) in snap.entries) {
                    s.writeLong(slot)
                    s.writeLong(e.offset)
                    s.writeInt(e.length)
                    s.writeLong(e.keyframePtsUs)
                }
                val idx = snap.cachedIndex
                s.writeInt(idx?.size ?: 0)
                if (idx != null) s.write(idx)
                s.writeInt(snap.verdicts.size)
                for ((k, same) in snap.verdicts) {
                    s.writeUTF(k)
                    s.writeBoolean(same)
                }
            }
            if (!tmp.renameTo(idxFile)) {
                idxFile.delete()
                tmp.renameTo(idxFile)
            }
            synchronized(this) {
                dirty = (dirty - snap.dirty).coerceAtLeast(0)
                lastFlushMs = System.currentTimeMillis()
            }
        }.onFailure { Log.w(TAG, "index flush failed: ${it.javaClass.simpleName}") }
    }
}
