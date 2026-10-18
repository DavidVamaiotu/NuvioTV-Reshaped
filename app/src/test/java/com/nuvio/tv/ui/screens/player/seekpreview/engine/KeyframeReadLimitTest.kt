// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, ae6212ef. See engine/NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Every raw read asks only for the keyframe's bytes and never more than [ThumbReads.MAX_READ]. */
class KeyframeReadLimitTest {

    private class RecordingReader(private val b: ByteArray) : RangeReader {
        val lengths = ArrayList<Int>()
        var bytes = 0L
        override val totalLength: Long get() = b.size.toLong()

        override fun read(offset: Long, length: Int): ByteArray {
            val out = ByteBuffer.allocate(length)
            val n = readInto(offset, length, out)
            return out.array().copyOf(n)
        }

        override fun readInto(offset: Long, length: Int, dst: ByteBuffer): Int {
            lengths += length
            if (offset >= b.size) return 0
            val n = minOf(b.size.toLong() - offset, length.toLong()).toInt()
            dst.put(b, offset.toInt(), n)
            bytes += n
            return n
        }
    }

    private val params = byteArrayOf(0, 0, 0, 1, 0x42, 0x01, 0x11, 0x22)

    private fun video(nalLength: Int = 4) = VideoTrack(
        codec = VideoCodec.HEVC, width = 3840, height = 2160, displayAspect = 0.0, nalLengthSize = nalLength,
        parameterSetsAnnexB = params, bitDepth = 10, colour = ContainerColour(), dolbyVision = null, trackNumber = 1,
    )

    /** A length-prefixed HEVC sample of [size] bytes: an IDR NAL whose payload fills the rest. */
    private fun sample(size: Int, fill: Byte): ByteArray {
        val body = size - 4
        val s = ByteArray(size) { fill }
        s[0] = (body ushr 24).toByte(); s[1] = (body ushr 16).toByte(); s[2] = (body ushr 8).toByte(); s[3] = body.toByte()
        s[4] = (19 shl 1).toByte()
        s[5] = 0x01
        return s
    }

    private fun annexB(sample: ByteArray): ByteArray = params + byteArrayOf(0, 0, 0, 1) + sample.copyOfRange(4, sample.size)

    @Test
    fun mp4ReadsExactlyTheSampleAndSkipsOneAboveTheCap() {
        val small = sample(300_000, 5)
        val file = ByteArray(1_000) + small + ByteArray(64)
        val r = RecordingReader(file)
        val idx = MediaIndex(
            ContainerKind.MP4, file.size.toLong(), 20_000_000, video(),
            KeyframeIndex(longArrayOf(0, 10_000_000), longArrayOf(1_000, 0), longArrayOf(-1, -1),
                intArrayOf(small.size, ThumbReads.MAX_READ + 1)),
        )
        val ex = KeyframeExtractor(idx, r)
        assertArrayEquals(annexB(small), ex.extract(0))
        assertEquals(listOf(small.size), r.lengths)
        assertNull(ex.extract(1))
        assertEquals("a keyframe above the cap is not read at all", 1, r.lengths.size)
    }

    // --- Matroska ---

    private fun id(v: Long): ByteArray {
        val n = when { v > 0xFFFFFF -> 4; v > 0xFFFF -> 3; v > 0xFF -> 2; else -> 1 }
        return ByteArray(n) { ((v shr (8 * (n - 1 - it))) and 0xFF).toByte() }
    }

    private fun size8(v: Long) = ByteArray(8) { if (it == 0) 0x01 else ((v shr (8 * (7 - it))) and 0xFF).toByte() }
    private fun size4(v: Long) = byteArrayOf((0x10 or ((v shr 24) and 0x0F).toInt()).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun cat(vararg parts: ByteArray) = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()

    private fun simpleBlock(track: Int, relTc: Int, key: Boolean, frame: ByteArray): ByteArray {
        val body = cat(byteArrayOf((0x80 or track).toByte(), (relTc shr 8).toByte(), relTc.toByte(), if (key) 0x80.toByte() else 0), frame)
        return cat(id(0xA3), size8(body.size.toLong()), body)
    }

    private fun timecode(tc: Long) = cat(id(0xE7), size8(4), byteArrayOf((tc shr 24).toByte(), (tc shr 16).toByte(), (tc shr 8).toByte(), tc.toByte()))

    private class Mkv(val file: ByteArray, val clusterAbs: LongArray, val relPos: LongArray, val keyframes: List<ByteArray>)

    /**
     * Clusters at 0 s, 10 s, ...: Timecode, a [fillerSize] block of the audio track, [pFrames] video frames that are no
     * keyframe, then the video keyframe. The cluster size field alternates between 4 and 8 bytes, so a jump with the
     * last cluster's header length lands 4 bytes off, on bytes that parse as some other element.
     */
    private fun mkv(count: Int, fillerSize: Int, keyframeSize: Int, pFrames: Int = 0): Mkv {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(4_096))
        val abs = LongArray(count)
        val rel = LongArray(count)
        val kfs = ArrayList<ByteArray>()
        for (k in 0 until count) {
            val kf = sample(keyframeSize, (k + 1).toByte())
            kfs += kf
            val before = cat(
                timecode(k * 10_000L),
                simpleBlock(2, 0, true, ByteArray(fillerSize) { 0x81.toByte() }),
                *Array(pFrames) { simpleBlock(1, 40 * (it + 1), false, ByteArray(1_000_000) { 9 }) },
            )
            val body = cat(before, simpleBlock(1, 0, true, kf))
            abs[k] = out.size().toLong()
            rel[k] = before.size.toLong()
            out.write(cat(id(0x1F43B675), if (k % 2 == 0) size4(body.size.toLong()) else size8(body.size.toLong()), body))
        }
        return Mkv(out.toByteArray(), abs, rel, kfs)
    }

    private fun mkvIndex(m: Mkv, withRelPos: Boolean) = MediaIndex(
        ContainerKind.MATROSKA, m.file.size.toLong(), m.clusterAbs.size * 10_000_000L, video(),
        KeyframeIndex(LongArray(m.clusterAbs.size) { it * 10_000_000L }, m.clusterAbs,
            if (withRelPos) m.relPos else LongArray(m.clusterAbs.size) { -1 }, IntArray(m.clusterAbs.size) { -1 }),
    )

    @Test
    fun mkvJumpReadsTheBlockNotTheClusterBeforeIt() {
        // A 2 MB keyframe 6 MB into its cluster; cluster headers of different lengths make every other jump miss.
        val m = mkv(count = 4, fillerSize = 6 * 1024 * 1024, keyframeSize = 2 * 1024 * 1024)
        val r = RecordingReader(m.file)
        val ex = KeyframeExtractor(mkvIndex(m, withRelPos = true), r)
        for (k in 0 until 4) {
            val before = r.bytes
            assertArrayEquals("keyframe $k", annexB(m.keyframes[k]), ex.extract(k))
            val read = r.bytes - before
            assertTrue("keyframe $k: $read bytes read, the cluster before it is 6 MB", read < 5L * 1024 * 1024)
        }
        assertTrue("largest read ${r.lengths.max()}", r.lengths.all { it <= ThumbReads.MAX_READ })
    }

    @Test
    fun mkvKeyframeAboveTheCapIsSkippedWithoutAHugeRead() {
        val m = mkv(count = 2, fillerSize = 1_000, keyframeSize = ThumbReads.MAX_READ + 1024 * 1024)
        val r = RecordingReader(m.file)
        val ex = KeyframeExtractor(mkvIndex(m, withRelPos = true), r)
        assertNull(ex.extract(0))
        assertNull(ex.extract(1))
        assertTrue("largest read ${r.lengths.max()}", r.lengths.all { it <= ThumbReads.MAX_READ })
        assertTrue("${r.bytes} bytes read", r.bytes < 3L * ThumbReads.MAX_READ)
    }

    @Test
    fun mkvScanWithoutRelPosStepsOverWhatItDoesNotNeed() {
        // No CueRelativePosition: the cluster is walked. The 10 MB audio block is stepped over unread.
        val m = mkv(count = 2, fillerSize = 10 * 1024 * 1024, keyframeSize = 1024 * 1024, pFrames = 3)
        val r = RecordingReader(m.file)
        val ex = KeyframeExtractor(mkvIndex(m, withRelPos = false), r)
        for (k in 0 until 2) assertArrayEquals("keyframe $k", annexB(m.keyframes[k]), ex.extract(k))
        assertTrue("largest read ${r.lengths.max()}", r.lengths.all { it <= ThumbReads.MAX_READ })
        assertTrue("${r.bytes} bytes read for two 14 MB clusters", r.bytes < 2L * 8 * 1024 * 1024)
    }

    @Test
    fun standaloneBufferHoldsTheSameAccessUnitAndReleasesMemory() {
        val m = mkv(count = 2, fillerSize = 1_000, keyframeSize = 500_000)
        val idx = mkvIndex(m, withRelPos = true)
        val memory = DirectMemory()
        val ex = KeyframeExtractor(idx, RecordingReader(m.file), memory)
        val buf = ThumbBuffer.single(memory)
        for (k in 0 until 2) {
            val au = ex.extractAu(k, buf)!!
            assertTrue(au.data.isDirect)
            assertArrayEquals(annexB(m.keyframes[k]), au.toByteArray())
            au.release()
        }
        assertEquals(2, memory.allocations)
        assertEquals(2, memory.frees)
    }

    @Test
    fun indexElementsAreReadInSteps() {
        val data = ByteArray(9 * 1024 * 1024 + 5) { (it % 251).toByte() }
        val r = RecordingReader(data)
        val got = ThumbReads.readStepped(r, 3, data.size - 3)
        assertArrayEquals(data.copyOfRange(3, data.size), got)
        assertTrue(r.lengths.all { it <= ThumbReads.INDEX_STEP })
        assertEquals((data.size - 3 + ThumbReads.INDEX_STEP - 1) / ThumbReads.INDEX_STEP, r.lengths.size)
    }

    @Test
    fun exactIndexReadFailsOnAShortFileInsteadOfCopying() {
        val data = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
        val r = RecordingReader(data)
        val e = runCatching { ThumbReads.readExact(r, 1024, data.size) }.exceptionOrNull()
        assertTrue(e is UnsupportedMediaException)
        assertEquals(data.size - 1024, ThumbReads.readStepped(RecordingReader(data), 1024, data.size).size)
        assertArrayEquals(data.copyOfRange(7, 7 + 4096), ThumbReads.readExact(RecordingReader(data), 7, 4096))
    }

    private class DirectMemory : ThumbMemory {
        var allocations = 0
        var frees = 0
        override fun allocate(size: Int): ThumbMemory.Block {
            allocations++
            return ThumbMemory.Block(ByteBuffer.allocateDirect(size))
        }
        override fun free(block: ThumbMemory.Block) {
            frees++
        }
    }
}
