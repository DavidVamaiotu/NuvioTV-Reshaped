package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Real muxer fixtures: 3 s of 160x90 H.264 at 24 fps, B frames, IDRs at 0/1/2 s. MP4 moov is at the end. */
class RealContainerTest {
    private class Bytes(private val bytes: ByteArray) : RangeReader {
        override val totalLength = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray {
            if (offset >= bytes.size) return ByteArray(0)
            return bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
        }
    }
    private fun fixture(name: String): Bytes {
        val text = javaClass.getResourceAsStream("/seekpreview/$name.b64")!!.bufferedReader().use { it.readText() }
        return Bytes(Base64.getMimeDecoder().decode(text))
    }
    @Test fun indexesAndExtractsARealTrailingMoovMp4() {
        val reader = fixture("gop.mp4")
        verify(Mp4IndexReader.read(reader, reader.read(0, 64)), reader)
    }
    @Test fun indexesAndExtractsTheSameBFramesInMatroska() {
        val reader = fixture("gop.mkv")
        verify(MkvIndexReader.read(reader, reader.read(0, 1024)), reader)
    }
    private fun verify(index: MediaIndex, reader: RangeReader) {
        assertEquals(VideoCodec.H264, index.video.codec)
        assertEquals(160, index.video.width)
        assertEquals(90, index.video.height)
        assertEquals(3, index.keyframes.count)
        assertEquals(1, index.keyframes.nearest(1_400_000))
        for (i in 0..2) {
            assertTrue(kotlin.math.abs(index.keyframes.ptsUs[i] - i * 1_000_000L) <= 2_000)
            val sample = KeyframeExtractor(index, reader).extract(i)!!
            assertTrue(sample.size > index.video.parameterSetsAnnexB.size)
            assertArrayEquals(byteArrayOf(0, 0, 0, 1), sample.copyOfRange(0, 4))
        }
        val restored = KeyframeIndexCodec.decode(KeyframeIndexCodec.encode(index))!!
        assertArrayEquals(index.keyframes.ptsUs, restored.keyframes.ptsUs)
        assertEquals(index.fileLength, restored.fileLength)
    }
}
