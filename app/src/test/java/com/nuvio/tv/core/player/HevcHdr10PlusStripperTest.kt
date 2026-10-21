package com.nuvio.tv.core.player

import android.util.Log
import androidx.media3.common.util.UnstableApi
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

@UnstableApi
class HevcHdr10PlusStripperTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `length delimited output matches the allocating version byte for byte`() {
        val random = Random(7)
        val out = ExposedByteArrayOutputStream(16)
        var stripped = 0
        repeat(3000) {
            val nlf = 1 + random.nextInt(4)
            val sample = randomSample(random, nlf, corrupt = random.nextInt(20) == 0)
            val expected = ReferenceStripper.lengthDelimited(sample, sample.size, nlf)
            val changed = HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(sample, sample.size, nlf, out)
            assertEquals(expected != null, changed)
            if (expected != null) {
                stripped++
                assertArrayEquals(expected, out.backingArray().copyOf(out.size()))
            }
        }
        assertTrue("too few samples carried HDR10+: $stripped", stripped > 500)
    }

    @Test
    fun `annex b output matches the allocating version byte for byte`() {
        val random = Random(11)
        val out = ExposedByteArrayOutputStream(16)
        var stripped = 0
        repeat(2000) {
            val sample = annexB(random, randomNals(random, maxNalSize = 2000))
            val expected = ReferenceStripper.annexB(sample, sample.size)
            val changed = HevcHdr10PlusStripper.stripHdr10PlusAnnexB(sample, sample.size, out)
            assertEquals(expected != null, changed)
            if (expected != null) {
                stripped++
                assertArrayEquals(expected, out.backingArray().copyOf(out.size()))
            }
        }
        assertTrue("too few samples carried HDR10+: $stripped", stripped > 300)
    }

    @Test
    fun `only the valid length counts when the sample array is larger than the sample`() {
        val random = Random(3)
        val out = ExposedByteArrayOutputStream(16)
        repeat(500) {
            val sample = randomSample(random, 4, corrupt = false)
            val padded = sample.copyOf(sample.size + 1 + random.nextInt(64)).also { random.nextBytes(it, sample.size, it.size) }
            val expected = ReferenceStripper.lengthDelimited(sample, sample.size, 4)
            val changed = HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(padded, sample.size, 4, out)
            assertEquals(expected != null, changed)
            if (expected != null) assertArrayEquals(expected, out.backingArray().copyOf(out.size()))
        }
    }

    @Test
    fun `mkv transformer hands back each stripped sample intact while reusing its buffer`() {
        val transformer = DolbyVisionMatroskaTransformer(
            config = DolbyVisionConversionConfig(active = false),
            stripHdr10PlusSei = true
        )
        val random = Random(5)
        repeat(400) {
            // Large and small samples in turn, so a reused buffer would show stale bytes.
            val big = it % 2 == 0
            val sample = randomSample(random, 4, corrupt = false, maxNalSize = if (big) 60_000 else 300)
            val expected = ReferenceStripper.lengthDelimited(sample, sample.size, 4)
            val result = transformer.transformHevcSample(sample, sample.size, 4, null, null)
            assertNotNull(result)
            val length = transformer.lastTransformedSampleLength()
            if (expected == null) {
                assertTrue(result === sample)
                assertEquals(sample.size, length)
            } else {
                assertArrayEquals(expected, result!!.copyOf(length))
            }
        }
    }

    @Test
    fun `sample without hdr10 plus is left alone`() {
        val out = ExposedByteArrayOutputStream(16)
        val sei = seiNal(39, listOf(5 to ByteArray(40) { 7 }, 4 to byteArrayOf(0xB5.toByte(), 0x00, 0x31, 0x47, 0x41)))
        val sample = lengthDelimited(4, listOf(sei, sliceNal(500)))
        assertNull(ReferenceStripper.lengthDelimited(sample, sample.size, 4))
        assertFalse(HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(sample, sample.size, 4, out))
        assertEquals(0, out.size())
    }

    private fun randomSample(random: Random, nlf: Int, corrupt: Boolean, maxNalSize: Int = 3000): ByteArray {
        val limit = when (nlf) {
            1 -> 255
            2 -> 65_535
            else -> maxNalSize
        }
        val sample = lengthDelimited(nlf, randomNals(random, minOf(limit, maxNalSize)))
        if (random.nextInt(10) == 0) {
            // Trailing bytes shorter than a length field.
            return sample + ByteArray(random.nextInt(nlf)) { 0x55 }
        }
        if (corrupt && sample.size > nlf) {
            sample[0] = 0x7F
        }
        return sample
    }

    private fun randomNals(random: Random, maxNalSize: Int): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        repeat(1 + random.nextInt(6)) {
            val nal = when (random.nextInt(6)) {
                0, 1 -> seiNal(if (random.nextBoolean()) 39 else 40, randomMessages(random))
                2 -> parameterSetNal(32 + random.nextInt(3), random, 5 + random.nextInt(30))
                3 -> parameterSetNal(62, random, 3 + random.nextInt(60))
                else -> sliceNal(3 + random.nextInt(maxOf(1, maxNalSize / 2)), random)
            }
            if (nal.size <= maxNalSize) nals += nal
        }
        if (nals.isEmpty()) nals += sliceNal(10, random)
        return nals
    }

    private fun randomMessages(random: Random): List<Pair<Int, ByteArray>> {
        val messages = mutableListOf<Pair<Int, ByteArray>>()
        repeat(1 + random.nextInt(3)) {
            messages += when (random.nextInt(5)) {
                0, 1 -> 4 to hdr10PlusPayload(random, random.nextInt(300))
                2 -> 4 to byteArrayOf(0xB5.toByte(), 0x00, 0x31) + randomBytes(random, random.nextInt(40))
                3 -> 5 to randomBytes(random, 16 + random.nextInt(300))
                else -> 137 to randomBytes(random, 24)
            }
        }
        return messages
    }

    private fun hdr10PlusPayload(random: Random, extra: Int): ByteArray =
        byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04, 0x01) + randomBytes(random, extra)

    // Plenty of zeros, so emulation prevention bytes appear.
    private fun randomBytes(random: Random, size: Int): ByteArray =
        ByteArray(size) { if (random.nextInt(3) == 0) 0 else random.nextInt(256).toByte() }

    private fun seiNal(type: Int, messages: List<Pair<Int, ByteArray>>): ByteArray {
        val rbsp = ByteArrayOutputStream()
        for ((payloadType, payload) in messages) {
            var t = payloadType
            while (t >= 255) { rbsp.write(0xFF); t -= 255 }
            rbsp.write(t)
            var s = payload.size
            while (s >= 255) { rbsp.write(0xFF); s -= 255 }
            rbsp.write(s)
            rbsp.write(payload)
        }
        rbsp.write(0x80)
        return nalHeader(type) + escape(rbsp.toByteArray())
    }

    private fun parameterSetNal(type: Int, random: Random, size: Int): ByteArray =
        nalHeader(type) + escape(randomBytes(random, size))

    private fun sliceNal(size: Int, random: Random? = null): ByteArray =
        nalHeader(1) + escape(ByteArray(size) { i -> random?.nextInt(256)?.toByte() ?: (i * 31 + 1).toByte() })

    private fun nalHeader(type: Int) = byteArrayOf(((type and 0x3F) shl 1).toByte(), 0x01)

    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 16)
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun lengthDelimited(nlf: Int, nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            for (i in nlf - 1 downTo 0) out.write((nal.size ushr (i * 8)) and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun annexB(random: Random, nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nals) {
            if (random.nextBoolean()) out.write(0)
            out.write(byteArrayOf(0, 0, 1))
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** The allocating implementation this replaced, kept here as the reference for the output bytes. */
    private object ReferenceStripper {
        private const val NAL_TYPE_PREFIX_SEI = 39
        private const val NAL_TYPE_SUFFIX_SEI = 40
        private const val SEI_PAYLOAD_TYPE_USER_DATA_REGISTERED = 4

        private val HDR10_PLUS_T35_SIGNATURE = byteArrayOf(
            0xB5.toByte(), 0x00, 0x3C.toByte(), 0x00, 0x01
        )

        fun lengthDelimited(
            sample: ByteArray,
            sampleLen: Int,
            nalLengthFieldLength: Int
        ): ByteArray? {
            if (sampleLen < nalLengthFieldLength) return null
            val out = ByteArrayOutputStream(sampleLen)
            var pos = 0
            var changed = false
            while (pos + nalLengthFieldLength <= sampleLen) {
                var nalSize = 0
                for (i in 0 until nalLengthFieldLength) {
                    nalSize = (nalSize shl 8) or (sample[pos + i].toInt() and 0xFF)
                }
                val nalStart = pos + nalLengthFieldLength
                if (nalSize <= 0 || nalStart + nalSize > sampleLen) {
                    return annexB(sample, sampleLen)
                }
                val nalType = (sample[nalStart].toInt() ushr 1) and 0x3F
                if (nalType == NAL_TYPE_PREFIX_SEI || nalType == NAL_TYPE_SUFFIX_SEI) {
                    val filtered = filterSeiNal(sample, nalStart, nalSize)
                    when {
                        filtered == null -> {
                            for (i in nalLengthFieldLength - 1 downTo 0) {
                                out.write((nalSize ushr (i * 8)) and 0xFF)
                            }
                            out.write(sample, nalStart, nalSize)
                        }
                        filtered.isNotEmpty() -> {
                            changed = true
                            for (i in nalLengthFieldLength - 1 downTo 0) {
                                out.write((filtered.size ushr (i * 8)) and 0xFF)
                            }
                            out.write(filtered)
                        }
                        else -> changed = true
                    }
                } else {
                    for (i in nalLengthFieldLength - 1 downTo 0) {
                        out.write((nalSize ushr (i * 8)) and 0xFF)
                    }
                    out.write(sample, nalStart, nalSize)
                }
                pos = nalStart + nalSize
            }
            return if (changed) out.toByteArray() else null
        }

        fun annexB(sample: ByteArray, sampleLen: Int): ByteArray? {
            val out = ByteArrayOutputStream(sampleLen)
            var scan = 0
            var changed = false
            while (scan < sampleLen) {
                val startCode = findStartCode(sample, scan, sampleLen)
                if (startCode < 0) {
                    out.write(sample, scan, sampleLen - scan)
                    break
                }
                val scLen = startCodeLength(sample, startCode, sampleLen)
                val nalBegin = startCode + scLen
                val nextStartCode = findStartCode(sample, nalBegin + 2, sampleLen)
                val nalEnd = if (nextStartCode < 0) sampleLen else nextStartCode

                if (startCode > scan) out.write(sample, scan, startCode - scan)

                if (nalBegin < nalEnd) {
                    val nalSize = nalEnd - nalBegin
                    val nalType = (sample[nalBegin].toInt() ushr 1) and 0x3F
                    if (nalType == NAL_TYPE_PREFIX_SEI || nalType == NAL_TYPE_SUFFIX_SEI) {
                        val filtered = filterSeiNal(sample, nalBegin, nalSize)
                        when {
                            filtered == null -> out.write(sample, startCode, nalEnd - startCode)
                            filtered.isNotEmpty() -> {
                                changed = true
                                out.write(sample, startCode, scLen)
                                out.write(filtered)
                            }
                            else -> changed = true
                        }
                    } else {
                        out.write(sample, startCode, nalEnd - startCode)
                    }
                }
                scan = nalEnd
            }
            return if (changed) out.toByteArray() else null
        }

        private fun filterSeiNal(data: ByteArray, nalOffset: Int, nalSize: Int): ByteArray? {
            if (nalSize < 3) return null

            val rbsp = ByteArrayOutputStream(nalSize)
            rbsp.write(data[nalOffset].toInt() and 0xFF)
            rbsp.write(data[nalOffset + 1].toInt() and 0xFF)

            var i = nalOffset + 2
            val end = nalOffset + nalSize
            while (i < end) {
                if (i + 2 < end && data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 3) {
                    rbsp.write(0)
                    rbsp.write(0)
                    i += 3
                } else {
                    rbsp.write(data[i].toInt() and 0xFF)
                    i++
                }
            }

            val rbspData = rbsp.toByteArray()
            val rbspEnd = rbspData.size

            val outRbsp = ByteArrayOutputStream(rbspEnd)
            outRbsp.write(rbspData[0].toInt() and 0xFF)
            outRbsp.write(rbspData[1].toInt() and 0xFF)

            var pos = 2
            var hasHdr10Plus = false

            while (pos < rbspEnd) {
                if (rbspEnd - pos == 1 && (rbspData[pos].toInt() and 0xFF) == 0x80) break

                val msgStart = pos

                var payloadType = 0
                while (pos < rbspEnd) {
                    val b = rbspData[pos++].toInt() and 0xFF
                    payloadType += b
                    if (b != 0xFF) break
                }

                var payloadSize = 0
                while (pos < rbspEnd) {
                    val b = rbspData[pos++].toInt() and 0xFF
                    payloadSize += b
                    if (b != 0xFF) break
                }

                if (pos + payloadSize > rbspEnd) return null
                val payloadStart = pos
                pos += payloadSize
                val msgEnd = pos

                if (payloadType == SEI_PAYLOAD_TYPE_USER_DATA_REGISTERED &&
                    payloadSize >= HDR10_PLUS_T35_SIGNATURE.size &&
                    matchesSignature(rbspData, payloadStart, HDR10_PLUS_T35_SIGNATURE)
                ) {
                    hasHdr10Plus = true
                } else {
                    outRbsp.write(rbspData, msgStart, msgEnd - msgStart)
                }
            }

            if (!hasHdr10Plus) return null
            if (outRbsp.size() <= 2) return ByteArray(0)

            outRbsp.write(0x80)
            val filteredRbsp = outRbsp.toByteArray()

            val finalNal = ByteArrayOutputStream(filteredRbsp.size)
            finalNal.write(filteredRbsp[0].toInt() and 0xFF)
            finalNal.write(filteredRbsp[1].toInt() and 0xFF)

            var consecutiveZeros = 0
            if (filteredRbsp[0] == 0.toByte()) consecutiveZeros++
            if (filteredRbsp[1] == 0.toByte()) {
                consecutiveZeros = if (consecutiveZeros == 1) 2 else 1
            } else {
                consecutiveZeros = 0
            }

            for (j in 2 until filteredRbsp.size) {
                val b = filteredRbsp[j].toInt() and 0xFF
                if (consecutiveZeros == 2 && (b == 0 || b == 1 || b == 2 || b == 3)) {
                    finalNal.write(0x03)
                    consecutiveZeros = 0
                }
                finalNal.write(b)
                if (b == 0) {
                    consecutiveZeros++
                } else {
                    consecutiveZeros = 0
                }
            }

            return finalNal.toByteArray()
        }

        private fun findStartCode(data: ByteArray, from: Int, limit: Int): Int {
            var i = from
            while (i + 2 < limit) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                    if (data[i + 2].toInt() == 1) return i
                    if (i + 3 < limit && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) return i
                }
                i++
            }
            return -1
        }

        private fun matchesSignature(data: ByteArray, offset: Int, sig: ByteArray): Boolean {
            if (offset + sig.size > data.size) return false
            for (i in sig.indices) {
                if (data[offset + i] != sig[i]) return false
            }
            return true
        }

        private fun startCodeLength(data: ByteArray, offset: Int, limit: Int): Int {
            return if (offset + 3 < limit &&
                data[offset].toInt() == 0 && data[offset + 1].toInt() == 0 &&
                data[offset + 2].toInt() == 0 && data[offset + 3].toInt() == 1
            ) 4 else 3
        }
    }
}
