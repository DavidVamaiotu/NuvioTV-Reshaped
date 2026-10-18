package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.nio.ByteBuffer

/** Feature-owned bounded buffers. Never borrows or changes ExoPlayer's allocator. */
internal object ThumbReads {
    const val MAX_READ = 8 * 1024 * 1024
    const val MAX_PREFIX = 64 * 1024
    const val BUFFER_MAX = MAX_READ + MAX_PREFIX
    const val INDEX_STEP = 1024 * 1024
    fun readStepped(reader: RangeReader, offset: Long, length: Int, step: Int = INDEX_STEP): ByteArray =
        read(reader, offset, length, step, false)
    fun readExact(reader: RangeReader, offset: Long, length: Int, step: Int = INDEX_STEP): ByteArray =
        read(reader, offset, length, step, true)
    private fun read(reader: RangeReader, offset: Long, length: Int, step: Int, exact: Boolean): ByteArray {
        require(length in 0..(16 * 1024 * 1024) && step in 1..MAX_READ)
        val bytes = ByteArray(length)
        val dst = ByteBuffer.wrap(bytes)
        var n = 0
        while (n < length) {
            val want = minOf(step, length - n)
            val got = reader.readInto(offset + n, want, dst)
            if (got <= 0) break
            n += got
            if (got < want) break
        }
        if (n == length) return bytes
        if (exact) throw UnsupportedMediaException("truncated index")
        return bytes.copyOf(n)
    }
}

internal interface ThumbMemory {
    class Block(val buffer: ByteBuffer)
    fun allocate(size: Int): Block?
    fun free(block: Block)
}

internal object NativeThumbMemory : ThumbMemory {
    // Heap-backed deliberately: one worker, one buffer, included in the Java heap budget.
    override fun allocate(size: Int): ThumbMemory.Block = ThumbMemory.Block(ByteBuffer.allocate(size))
    override fun free(block: ThumbMemory.Block) = Unit
}

internal class ThumbBuffer private constructor(private val memory: ThumbMemory) {
    private var block: ThumbMemory.Block? = null
    var data: ByteBuffer = ByteBuffer.allocate(0)
        private set
    fun ensure(size: Int, keep: Int = 0): ByteBuffer? {
        if (size !in 0..ThumbReads.BUFFER_MAX) return null
        if (data.capacity() >= size) return data
        val allocated = memory.allocate(size) ?: return null
        val next = allocated.buffer
        if (keep > 0) {
            val old = data.duplicate().apply { clear(); limit(minOf(keep, capacity())) }
            next.put(old)
        }
        block?.let(memory::free)
        block = allocated
        data = next.apply { clear() }
        return data
    }
    fun release() { block?.let(memory::free); block = null; data = ByteBuffer.allocate(0) }
    companion object { fun single(memory: ThumbMemory = NativeThumbMemory) = ThumbBuffer(memory) }
}

internal class ThumbAu(val data: ByteBuffer, val length: Int, private val buffer: ThumbBuffer? = null) {
    fun release() { buffer?.release() }
    fun toByteArray(): ByteArray = ByteArray(length).also {
        data.duplicate().apply { clear(); limit(length) }.get(it)
    }
    companion object { fun of(bytes: ByteArray) = ThumbAu(ByteBuffer.wrap(bytes), bytes.size) }
}
