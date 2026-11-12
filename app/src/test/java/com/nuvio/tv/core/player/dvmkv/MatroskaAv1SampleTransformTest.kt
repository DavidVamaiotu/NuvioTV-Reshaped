package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.DataReader
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class MatroskaAv1SampleTransformTest {
    @Test
    fun `transformed AV1 writes its actual size before the payload`() {
        val sample = byteArrayOf(1, 2, 3)
        val result = extract(sample)
        assertEquals(1, result.transformCalls)
        assertArrayEquals(ByteBuffer.allocate(4).putInt(2).array(), result.prefix)
        assertArrayEquals(byteArrayOf(1, 2), result.payload)
    }

    @Test
    fun `header stripped AV1 retains both the restored header and its size prefix`() {
        val result = extract(byteArrayOf(1, 2, 3), header = byteArrayOf(9))
        assertEquals(0, result.transformCalls)
        assertArrayEquals(ByteBuffer.allocate(4).putInt(4).array(), result.prefix)
        assertArrayEquals(byteArrayOf(9, 1, 2, 3), result.payload)
    }

    @Test
    fun `encrypted tracks never send AV1 sample bytes to the transformer`() {
        // A clear sample inside an encrypted track still carries its signal byte.
        val result = extract(byteArrayOf(0, 1, 2, 3), encryptedTrack = true)
        assertEquals(0, result.transformCalls)
        assertArrayEquals(ByteBuffer.allocate(4).putInt(3).array(), result.prefix)
        assertArrayEquals(byteArrayOf(1, 2, 3), result.payload)
    }

    private data class Result(val transformCalls: Int, val prefix: ByteArray, val payload: ByteArray)

    private fun extract(sample: ByteArray, header: ByteArray? = null, encryptedTrack: Boolean = false): Result {
        var calls = 0
        val transformer = object : MatroskaExtractor.DolbyVisionSampleTransformer {
            override fun transformAv1Sample(sample: ByteArray, sampleLength: Int): ByteArray {
                calls++
                return sample.copyOf(2)
            }
            override fun lastTransformedSampleLength() = 2
        }
        val extractor = MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, 0, transformer)
        val trackClass = Class.forName("com.nuvio.tv.core.player.dvmkv.MatroskaExtractor\$Track")
        val track = trackClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        fun set(name: String, value: Any?) = trackClass.getDeclaredField(name).apply { isAccessible = true }.set(track, value)
        val prefix = ByteArrayOutputStream()
        val payload = ByteArrayOutputStream()
        val output = mockk<TrackOutput>(relaxed = true)
        fun capture(data: ParsableByteArray, count: Int, target: ByteArrayOutputStream) {
            val bytes = ByteArray(count)
            data.readBytes(bytes, 0, count)
            target.write(bytes)
        }
        every { output.sampleData(any<ParsableByteArray>(), any<Int>()) } answers {
            capture(firstArg(), secondArg(), payload)
        }
        every { output.sampleData(any<ParsableByteArray>(), any<Int>(), any<Int>()) } answers {
            capture(firstArg(), secondArg(), if (thirdArg<Int>() == TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL) prefix else payload)
        }
        var position = 0
        val input = mockk<ExtractorInput>(relaxed = true)
        every { input.readFully(any<ByteArray>(), any<Int>(), any<Int>()) } answers {
            val count = thirdArg<Int>()
            sample.copyInto(firstArg(), secondArg(), position, position + count)
            position += count
        }
        every { output.sampleData(any<DataReader>(), any<Int>(), any<Boolean>()) } answers {
            val count = secondArg<Int>()
            val bytes = ByteArray(count)
            input.readFully(bytes, 0, count)
            payload.write(bytes)
            count
        }
        set("codecId", "V_AV1")
        set("output", output)
        set("maxBlockAdditionId", 1)
        set("requiresDolbyVisionTransform", true)
        set("sampleStrippedBytes", header)
        set("hasContentEncryption", encryptedTrack)
        val method = MatroskaExtractor::class.java.getDeclaredMethod(
            "writeSampleData", ExtractorInput::class.java, trackClass, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        method.invoke(extractor, input, track, sample.size, true)
        assertEquals(sample.size, position)
        return Result(calls, prefix.toByteArray(), payload.toByteArray())
    }
}
