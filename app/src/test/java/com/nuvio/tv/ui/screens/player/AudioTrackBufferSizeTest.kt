package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTrackBufferSizeTest {

    @Test
    fun decodedPcm_getsFiveHundredMilliseconds() {
        assertEquals(96_000, pcmBytes(minBuffer = 7_680))
        assertEquals(96_000, pcmBytes(minBuffer = 3_840))
        assertEquals(96_000, pcmBytes(minBuffer = 46_080))
    }

    @Test
    fun decodedPcm_scalesWithPlaybackSpeed() {
        assertEquals(192_000, pcmBytes(minBuffer = 7_680, maxSpeed = 2.0))
    }

    @Test
    fun decodedPcm_neverGoesBelowThePlatformMinimum() {
        assertEquals(120_000, pcmBytes(minBuffer = 120_000))
    }

    @Test
    fun decodedPcm_multichannelFloat() {
        val bytes = cappedPassthroughBufferSizeProvider.getBufferSizeInBytes(
            15_360,
            C.ENCODING_PCM_FLOAT,
            DefaultAudioSink.OUTPUT_MODE_PCM,
            32,
            48_000,
            Format.NO_VALUE,
            1.0
        )
        assertEquals(768_000, bytes)
    }

    @Test
    fun passthrough_keepsItsCaps() {
        assertEquals(1_536 * 8, passthroughBytes(C.ENCODING_AC3))
        assertEquals(2 * 10_752, passthroughBytes(C.ENCODING_E_AC3))
        assertEquals(16 * 2_012, passthroughBytes(C.ENCODING_DTS))
        assertEquals(2 * 61_440, passthroughBytes(C.ENCODING_DOLBY_TRUEHD))
    }

    private fun pcmBytes(minBuffer: Int, maxSpeed: Double = 1.0): Int {
        return cappedPassthroughBufferSizeProvider.getBufferSizeInBytes(
            minBuffer,
            C.ENCODING_PCM_16BIT,
            DefaultAudioSink.OUTPUT_MODE_PCM,
            4,
            48_000,
            Format.NO_VALUE,
            maxSpeed
        )
    }

    private fun passthroughBytes(encoding: Int): Int {
        return cappedPassthroughBufferSizeProvider.getBufferSizeInBytes(
            1_024,
            encoding,
            DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH,
            1,
            48_000,
            Format.NO_VALUE,
            1.0
        )
    }
}
