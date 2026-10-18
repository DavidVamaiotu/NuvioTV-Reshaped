@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player.seekpreview.local

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class VideoKeyframeTapTest {
    private class Playback : TrackOutput {
        val received = ByteArrayOutputStream()
        var samples = 0
        override fun format(format: Format) = Unit
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            val count = input.read(bytes, 0, length)
            if (count > 0) received.write(bytes, 0, count)
            return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            received.write(data.data, data.position, length)
            data.skipBytes(length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) { samples++ }
    }
    private class Sink : KeyframeSink {
        var accepts = true
        var frames = 0
        override fun wantsKeyframes() = true
        override fun acceptsFormat(format: Format) = accepts
        override fun wantsKeyframe(timeUs: Long) = true
        override fun onKeyframe(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) { frames++ }
    }
    private fun feed(tap: VideoTapTrackOutput, bytes: ByteArray, timeUs: Long = 0) {
        tap.sampleData(ParsableByteArray(bytes), bytes.size, 0)
        tap.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, null)
    }
    private fun format() = Format.Builder().setSampleMimeType("video/avc").setWidth(3840).setHeight(2160).build()

    @Test fun rejectedFormatsNeverAllocateOrCopySamplesButPlaybackStillReceivesEveryByte() {
        val playback = Playback()
        val sink = Sink().apply { accepts = false }
        var allocations = 0
        val tap = VideoTapTrackOutput(playback, sink) { _, _ -> allocations++; error("must not allocate") }
        tap.format(format())
        val bytes = byteArrayOf(1, 2, 3, 4)
        feed(tap, bytes)
        assertEquals(0, allocations)
        assertEquals(0, sink.frames)
        assertEquals(1, playback.samples)
        assertArrayEquals(bytes, playback.received.toByteArray())
    }

    @Test fun allocationFailureDisablesCaptureWithoutFailingOrChangingPlayback() {
        val playback = Playback()
        val sink = Sink()
        var allocations = 0
        val tap = VideoTapTrackOutput(playback, sink) { _, _ -> allocations++; throw OutOfMemoryError("preview buffer") }
        tap.format(format())
        val input = DataReader { target, offset, _ ->
            target[offset] = 1; target[offset + 1] = 2
            2
        }
        assertEquals(2, tap.sampleData(input, 2, false, 0))
        tap.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME, 2, 0, null)
        feed(tap, byteArrayOf(3, 4), 10_000_000)
        assertEquals(1, allocations)
        assertEquals(0, sink.frames)
        assertEquals(2, playback.samples)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), playback.received.toByteArray())
    }

    @Test fun formatEligibilityCanChangeWithoutLosingPlaybackOrFutureKeyframes() {
        val playback = Playback()
        val sink = Sink().apply { accepts = false }
        val tap = VideoTapTrackOutput(playback, sink)
        tap.format(format())
        feed(tap, byteArrayOf(1, 2))
        sink.accepts = true
        feed(tap, byteArrayOf(3, 4), 10_000_000)
        assertEquals(1, sink.frames)
        assertEquals(2, playback.samples)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), playback.received.toByteArray())
    }
}
