// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.Allocation
import androidx.media3.exoplayer.upstream.DefaultAllocatorNative
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Size limits of the raw file reads behind thumbnails. */
internal object ThumbReads {
    /**
     * Largest single range read, and largest keyframe taken. 4K remux keyframes are about 0.5-5 MB (HEVC at
     * 80-100 Mb/s), so this leaves room for the heaviest ones; a bigger keyframe is skipped, never read whole.
     */
    const val MAX_READ = 8 * 1024 * 1024
    /** Parameter sets and block headers in front of a keyframe in the same buffer. */
    const val MAX_PREFIX = 64 * 1024
    const val BUFFER_MAX = MAX_READ + MAX_PREFIX
    /** Index elements (Cues, moov, OpenDML indexes) are read in steps of at most this size. */
    const val INDEX_STEP = 4 * 1024 * 1024

    /** [length] bytes at [offset] through reads of at most [step] bytes; shorter only at end of file. */
    fun readStepped(reader: RangeReader, offset: Long, length: Int, step: Int = INDEX_STEP): ByteArray =
        read(reader, offset, length, step, exact = false)

    /** As [readStepped], but a short read throws instead of copying what arrived. */
    fun readExact(reader: RangeReader, offset: Long, length: Int, step: Int = INDEX_STEP): ByteArray =
        read(reader, offset, length, step, exact = true)

    private fun read(reader: RangeReader, offset: Long, length: Int, step: Int, exact: Boolean): ByteArray {
        val out = ByteArray(length)
        val dst = ByteBuffer.wrap(out)
        var n = 0
        while (n < length) {
            val want = minOf(step, length - n)
            val got = reader.readInto(offset + n, want, dst)
            if (got <= 0) break
            n += got
            if (got < want) break
        }
        if (n == length) return out
        if (exact) throw UnsupportedMediaException("truncated read: $n of $length bytes")
        return out.copyOf(n)
    }
}

/** The box ran out of memory while a keyframe was read or decoded. */
internal class MemoryShortException(message: String) : Exception(message)

/** Memory for keyframe buffers, outside the Java heap where it can be had. */
internal interface ThumbMemory {
    class Block(val buffer: ByteBuffer, val handle: Any? = null)

    /** A direct buffer of [size] bytes at position 0, or null when none can be had. */
    fun allocate(size: Int): Block?

    fun free(block: Block)
}

/** media3's native allocator: malloc'd memory, not counted against the Java heap (allocateDirect is, on Android). */
@OptIn(UnstableApi::class)
internal object NativeThumbMemory : ThumbMemory {
    override fun allocate(size: Int): ThumbMemory.Block? {
        val a = try {
            DefaultAllocatorNative.createAllocation(size)
        } catch (t: Throwable) {
            null
        } ?: return null
        val b = a.buffer
        if (b == null || !b.isDirect || a.offset != 0 || b.capacity() < size) {
            runCatching { DefaultAllocatorNative.freeAllocation(a) }
            return null
        }
        b.clear()
        return ThumbMemory.Block(b, a)
    }

    override fun free(block: ThumbMemory.Block) {
        val a = block.handle as? Allocation ?: return
        runCatching { DefaultAllocatorNative.freeAllocation(a) }
    }
}

/**
 * One reusable keyframe buffer. It grows to the largest keyframe it has held, at most [ThumbReads.BUFFER_MAX]. When
 * native memory is refused it falls back to a Java array of the size asked for, never above that cap.
 */
internal class ThumbBuffer internal constructor(
    private val memory: ThumbMemory,
    private val pool: ThumbBufferPool?,
) {
    private var block: ThumbMemory.Block? = null

    /** Storage indexed from 0; position and limit are not used. */
    var data: ByteBuffer = EMPTY
        private set

    internal var lent = false

    val isNative: Boolean get() = block != null

    /** Room for [size] bytes, keeping the first [keep]. Null above [ThumbReads.BUFFER_MAX]. */
    fun ensure(size: Int, keep: Int = 0): ByteBuffer? {
        if (size > ThumbReads.BUFFER_MAX) return null
        if (data.capacity() >= size) return data
        val cap = roundUp(size)
        val next = memory.allocate(cap)
        val nextData = next?.buffer ?: ByteBuffer.allocate(cap)
        if (keep > 0) {
            val src = data.duplicate()
            src.clear()
            src.limit(minOf(keep, src.capacity()))
            nextData.clear()
            nextData.put(src)
        }
        nextData.clear()
        freeMemory()
        block = next
        data = nextData
        return data
    }

    /** Back to the pool, or freed when the buffer has none. */
    fun release() {
        if (pool != null) pool.giveBack(this) else freeMemory()
    }

    internal fun freeMemory() {
        block?.let { memory.free(it) }
        block = null
        data = EMPTY
    }

    companion object {
        fun single() = ThumbBuffer(NativeThumbMemory, null)

        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
        private const val GRAIN = 1024 * 1024

        fun roundUp(size: Int): Int = ((size + GRAIN - 1) / GRAIN * GRAIN).coerceIn(GRAIN, ThumbReads.BUFFER_MAX)

        /** A buffer of its own, freed by [release]. */
        fun single(memory: ThumbMemory = NativeThumbMemory): ThumbBuffer = ThumbBuffer(memory, null)
    }
}

/** One access unit for the decoder: the first [length] bytes of [data]. [release] gives its buffer back, once. */
internal class ThumbAu(val data: ByteBuffer, val length: Int, private val buffer: ThumbBuffer? = null) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) buffer?.release()
    }

    fun toByteArray(): ByteArray {
        val out = ByteArray(length)
        val src = data.duplicate()
        src.clear()
        src.limit(length)
        src.get(out)
        return out
    }

    companion object {
        fun of(bytes: ByteArray) = ThumbAu(ByteBuffer.wrap(bytes), bytes.size)
    }
}

/**
 * Keyframe buffers of one session: at most [limit] exist, lent or idle, and a lent one is freed when it comes back
 * after [close]. Thread-safe.
 */
internal class ThumbBufferPool(limit: Int, private val memory: ThumbMemory = NativeThumbMemory) {
    private val idle = ArrayDeque<ThumbBuffer>()
    private var lent = 0
    private var closed = false
    private var max = limit.coerceAtLeast(1)

    var limit: Int
        get() = synchronized(this) { max }
        set(value) {
            val drop = synchronized(this) {
                max = value.coerceAtLeast(1)
                trimLocked()
            }
            drop.forEach { it.freeMemory() }
        }

    /** Buffers that can still be lent now. */
    val available: Int get() = synchronized(this) { if (closed) 0 else (max - lent).coerceAtLeast(0) }

    val lentCount: Int get() = synchronized(this) { lent }

    val idleCount: Int get() = synchronized(this) { idle.size }

    fun tryAcquire(): ThumbBuffer? {
        synchronized(this) {
            if (closed || lent >= max) return null
            lent++
            return (idle.removeFirstOrNull() ?: ThumbBuffer(memory, this)).also { it.lent = true }
        }
    }

    internal fun giveBack(b: ThumbBuffer) {
        synchronized(this) {
            if (!b.lent) return
            b.lent = false
            lent--
            if (!closed && idle.size + lent < max) {
                idle.addLast(b)
                return
            }
        }
        b.freeMemory()
    }

    fun close() {
        val drop = synchronized(this) {
            closed = true
            idle.toList().also { idle.clear() }
        }
        drop.forEach { it.freeMemory() }
    }

    /** Frees the buffers not lent out now; the pool makes new ones when they are needed again. */
    fun releaseIdle() {
        val drop = synchronized(this) { idle.toList().also { idle.clear() } }
        drop.forEach { it.freeMemory() }
    }

    private fun trimLocked(): List<ThumbBuffer> {
        val out = ArrayList<ThumbBuffer>()
        while (idle.isNotEmpty() && idle.size + lent > max) out += idle.removeLast()
        return out
    }

    companion object {
        /**
         * 4K on a protected box: two buffers per decode lane, one decoding and one fetched ahead. A strong box keeps
         * four fetches going as well, so the generating screen is not held up by the time to first byte. Smaller titles
         * keep a buffer for each fetch lane: their keyframes are small and the buffers grow only to what they hold.
         */
        fun limitFor(lanes: Int, fourK: Boolean, strong: Boolean): Int {
            val l = lanes.coerceAtLeast(1)
            return when {
                !fourK -> l + ThumbFetchLanes.MAX
                strong -> l + minOf(ThumbFetchLanes.MAX, STRONG_4K_FETCHES)
                else -> 2 * l
            }
        }

        private const val STRONG_4K_FETCHES = 4
    }
}
