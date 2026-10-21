package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class IecReplayFallbackTest {

    @Before
    fun setUp() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @After
    fun tearDown() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @Test
    fun trueHd_writeError_replaysLittleEndianThroughAStallingSink() {
        val inner = StrictSink(room = { call -> if (call % 2 == 0) 7 else 0 })
        var failures = 0
        val sink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = OneTrackFactory(FailingTrack()),
            onIecFailed = { failures++ }
        )
        sink.configure(trueHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()

        val sent = ByteArrayOutputStream()
        for (i in 0 until 80) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
            sent.write(au)
            feed(sink, au, i * 833L)
        }
        finish(sink)

        assertFalse(sink.isIecActive)
        assertEquals(1, failures)
        assertTrue("the wrapped sink should have taken buffers in part", inner.partialAccepts > 0)
        assertArrayEquals(sent.toByteArray(), inner.received())
        assertTrue(inner.endOfStreamRequested)
        assertFalse(sink.hasPendingData())
    }

    @Test
    fun dtsHd_writeStall_replaysQueuedUnitsBeforeTheNextOne() {
        val clock = longArrayOf(0L)
        val inner = StrictSink(room = { call -> if (call % 3 == 0) 0 else 5 })
        val sink = IecPassthroughAudioSink(
            sink = inner,
            trackFactory = OneTrackFactory(StalledTrack(), iecReady = true),
            nanoTime = { clock[0] }
        )
        sink.configure(dtsHdFormat(), 0, null)
        assertTrue(sink.isIecActive)
        sink.play()

        val first = ByteArray(64) { 0x21 }
        val second = ByteArray(64) { 0x22 }
        val third = ByteArray(64) { 0x23 }
        assertTrue(sink.handleBuffer(le(first), 0L, 1))

        val secondBuffer = le(second)
        assertFalse(sink.handleBuffer(secondBuffer, 10_000L, 1))
        assertTrue(sink.isIecActive)
        clock[0] = IecPassthroughAudioSink.WRITE_STALL_BUDGET_NANOS
        var calls = 0
        while (!sink.handleBuffer(secondBuffer, 10_000L, 1)) {
            assertTrue("second unit never accepted", ++calls < 1_000)
        }
        assertFalse(sink.isIecActive)
        feed(sink, third, 20_000L)
        finish(sink)

        assertArrayEquals(first + second + third, inner.received())
        assertEquals(listOf(0L, 10_000L, 20_000L), inner.firstPtsOfEachBuffer())
    }

    @Test
    fun flush_whileAReplayIsPending_dropsItAndTakesNewBuffers() {
        var open = false
        val inner = StrictSink(room = { if (open) Int.MAX_VALUE else 3 })
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = OneTrackFactory(FailingTrack()))
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        for (i in 0 until 80) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
            assertTrue(sink.handleBuffer(le(au), i * 833L, 1))
            if (!sink.isIecActive) break
        }
        assertFalse(sink.isIecActive)
        assertTrue(sink.hasPendingData())

        sink.flush()
        inner.clearReceived()
        open = true
        val after = TrueHdMatPackerTest.trueHdAu(frameTime = 0, major = true)
        assertTrue(sink.handleBuffer(le(after), 5_000_000L, 1))
        assertArrayEquals(after, inner.received())
        assertFalse(sink.hasPendingData())
    }

    @Test
    fun endOfStream_waitsForThePendingReplay() {
        var open = false
        val inner = StrictSink(room = { if (open) Int.MAX_VALUE else 0 })
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = OneTrackFactory(FailingTrack()))
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        val sent = ByteArrayOutputStream()
        for (i in 0 until 80) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
            sent.write(au)
            assertTrue(sink.handleBuffer(le(au), i * 833L, 1))
            if (!sink.isIecActive) break
        }
        assertFalse(sink.isIecActive)

        sink.playToEndOfStream()
        assertFalse(inner.endOfStreamRequested)
        assertFalse(sink.isEnded())
        assertTrue(sink.hasPendingData())

        open = true
        assertFalse("isEnded only reports, the next end-of-stream call hands the rest over", sink.isEnded())
        sink.playToEndOfStream()
        assertTrue(sink.isEnded())
        assertTrue(inner.endOfStreamRequested)
        assertArrayEquals(sent.toByteArray(), inner.received())
    }

    @Test
    fun configure_whileAReplayIsPending_flushesTheWrappedSinkFirst() {
        val inner = StrictSink(room = { 0 })
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = OneTrackFactory(FailingTrack()))
        pendingReplayAfterIecFailure(sink)
        val flushesBefore = inner.flushes

        sink.configure(eac3Format(), 0, null)
        assertEquals(flushesBefore + 1, inner.flushes)
        assertFalse(sink.hasPendingData())
        val next = ByteArray(32) { 0x44 }
        inner.clearReceived()
        inner.room = { Int.MAX_VALUE }
        assertTrue(sink.handleBuffer(le(next), 9_000_000L, 1))
        assertArrayEquals(next, inner.received())
    }

    @Test
    fun resetAndRelease_whileAReplayIsPending_dropIt() {
        val inner = StrictSink(room = { 0 })
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = OneTrackFactory(FailingTrack()))
        pendingReplayAfterIecFailure(sink)
        sink.reset()
        assertFalse(sink.hasPendingData())

        val other = IecPassthroughAudioSink(sink = StrictSink(room = { 0 }), trackFactory = OneTrackFactory(FailingTrack()))
        pendingReplayAfterIecFailure(other)
        other.release()
        assertFalse(other.hasPendingData())
    }

    @Test
    fun endOfStream_wrappedSinkInitFailure_isRaisedAsAWriteException() {
        val inner = StrictSink(room = { 0 })
        val sink = IecPassthroughAudioSink(sink = inner, trackFactory = OneTrackFactory(FailingTrack()))
        pendingReplayAfterIecFailure(sink)
        inner.failInit = true

        assertFalse(sink.isEnded())
        var thrown: Throwable? = null
        try {
            sink.playToEndOfStream()
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected a WriteException, got $thrown", thrown is AudioSink.WriteException)
        assertTrue(thrown!!.cause is AudioSink.InitializationException)
    }

    @Test
    fun iecInvolved_followsTheCurrentFormat_andSurvivesReset() {
        val sink = IecPassthroughAudioSink(sink = StrictSink(), trackFactory = OneTrackFactory(FailingTrack()))
        assertFalse(sink.iecInvolved)
        sink.configure(trueHdFormat(), 0, null)
        assertTrue(sink.iecInvolved)
        sink.reset()
        assertTrue("the player resets the sink before the error reaches recovery", sink.iecInvolved)

        sink.configure(eac3Format(), 0, null)
        assertFalse("an E-AC-3 track after a TrueHD one is not IEC's", sink.iecInvolved)

        val pcm = IecPassthroughAudioSink(sink = StrictSink(), trackFactory = OneTrackFactory(FailingTrack()))
        pcm.configure(pcmFormat(), 0, null)
        assertFalse(pcm.iecInvolved)
    }

    @Test
    fun iecInvolved_staysTrueAfterTheOwnTrackFailed() {
        val sink = IecPassthroughAudioSink(sink = StrictSink(room = { 0 }), trackFactory = OneTrackFactory(FailingTrack()))
        pendingReplayAfterIecFailure(sink)
        assertFalse(sink.isIecActive)
        assertTrue(sink.iecInvolved)
    }

    @Test
    fun iecOffForTheStream_neverOpensMatOrIecForTrueHd() {
        val factory = OneTrackFactory(FailingTrack(), iecReady = true)
        val sink = IecPassthroughAudioSink(
            sink = StrictSink(),
            trackFactory = factory,
            hbrIecEnabled = false
        )
        assertFalse(sink.claimsHbr(trueHdFormat()))
        sink.configure(trueHdFormat(), 0, null)
        sink.configure(trueHdFormat(), 0, null)
        assertFalse(sink.isIecActive)
        assertFalse(sink.iecInvolved)
        assertEquals(0, factory.openCount)
    }

    private fun pendingReplayAfterIecFailure(sink: IecPassthroughAudioSink) {
        sink.configure(trueHdFormat(), 0, null)
        sink.play()
        for (i in 0 until 80) {
            val au = TrueHdMatPackerTest.trueHdAu(frameTime = i * 40, major = i == 0)
            assertTrue(sink.handleBuffer(le(au), i * 833L, 1))
            if (!sink.isIecActive) break
        }
        assertFalse(sink.isIecActive)
        assertTrue(sink.hasPendingData())
    }

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    // Hands the same buffer back until it is taken, as the audio renderer does.
    private fun feed(sink: AudioSink, bytes: ByteArray, ptsUs: Long) {
        val buffer = le(bytes)
        var calls = 0
        while (!sink.handleBuffer(buffer, ptsUs, 1)) {
            assertTrue("buffer at $ptsUs never accepted", ++calls < 10_000)
        }
    }

    private fun finish(sink: AudioSink) {
        var calls = 0
        while (true) {
            sink.playToEndOfStream()
            if (sink.isEnded()) return
            assertTrue("end of stream never reached", ++calls < 10_000)
        }
    }

    private fun trueHdFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_TRUEHD)
        .setChannelCount(8)
        .setSampleRate(48_000)
        .build()

    private fun dtsHdFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_DTS_HD)
        .setChannelCount(8)
        .setSampleRate(48_000)
        .build()

    private fun eac3Format(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
        .setChannelCount(6)
        .setSampleRate(48_000)
        .build()

    private fun pcmFormat(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setChannelCount(2)
        .setSampleRate(48_000)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .build()

    private class OneTrackFactory(
        private val track: IecAudioTrack,
        private val iecReady: Boolean = false
    ) : IecAudioTrackFactory {
        var openCount = 0

        override fun open(sampleRate: Int, channelCount: Int, bufferSizeBytes: Int, sessionId: Int): IecAudioTrack {
            openCount++
            return track
        }

        override fun canOpen(sampleRate: Int, channelCount: Int): Boolean = true
        override fun iec61937Ready(): Boolean = iecReady
    }

    private open class FailingTrack : IecAudioTrack {
        override val sampleRate: Int = 192_000
        override val frameSizeBytes: Int = 16
        override val payload: HbrPayload = HbrPayload.IEC_BURST
        override val bufferSizeBytes: Int = 192_000 * 16
        override fun write(data: ByteArray, offset: Int, size: Int): Int = -2
        override fun play() = Unit
        override fun pause() = Unit
        override fun flush() = Unit
        override fun release() = Unit
        override fun playbackHeadFrames(): Long = 0L
        override fun setVolume(volume: Float) = Unit
        override fun underrunCount(): Int = 0
    }

    private class StalledTrack : FailingTrack() {
        override fun write(data: ByteArray, offset: Int, size: Int): Int = 0
    }

    // Keeps the media3 DefaultAudioSink input rules: little-endian buffers only, and a buffer
    // taken in part must come back, unchanged, before any other.
    private class StrictSink(var room: (Int) -> Int = { Int.MAX_VALUE }) : AudioSink {
        private var inputBuffer: ByteBuffer? = null
        var flushes = 0
            private set
        var failInit = false
        private var calls = 0
        private val out = ByteArrayOutputStream()
        private val pts = mutableListOf<Long>()
        var partialAccepts = 0
            private set
        var endOfStreamRequested = false
            private set

        fun received(): ByteArray = out.toByteArray()
        fun firstPtsOfEachBuffer(): List<Long> = pts.toList()
        fun clearReceived() {
            out.reset()
            pts.clear()
        }

        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean {
            require(inputBuffer == null || buffer === inputBuffer) { "different buffer while one is pending" }
            if (failInit) throw AudioSink.InitializationException(
                0, 48_000, 0, C.ENCODING_AC3, 0, Format.Builder().build(), true, null
            )
            if (inputBuffer == null) {
                require(buffer.order() == ByteOrder.LITTLE_ENDIAN) { "big-endian buffer" }
                if (!buffer.hasRemaining()) return true
                inputBuffer = buffer
                pts.add(presentationTimeUs)
            }
            val take = minOf(buffer.remaining(), room(calls++).coerceAtLeast(0))
            val chunk = ByteArray(take)
            buffer.get(chunk)
            out.write(chunk)
            if (buffer.hasRemaining()) {
                partialAccepts++
                return false
            }
            inputBuffer = null
            return true
        }

        override fun playToEndOfStream() {
            endOfStreamRequested = true
        }

        override fun isEnded(): Boolean = endOfStreamRequested && inputBuffer == null
        override fun hasPendingData(): Boolean = inputBuffer != null
        override fun flush() {
            flushes++
            inputBuffer = null
        }
        override fun reset() {
            inputBuffer = null
        }
        override fun release() {
            inputBuffer = null
        }
        override fun setListener(listener: AudioSink.Listener) = Unit
        override fun supportsFormat(format: Format): Boolean = true
        override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
            AudioOffloadSupport.DEFAULT_UNSUPPORTED
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L
        override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = Unit
        override fun play() = Unit
        override fun handleDiscontinuity() = Unit
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) = Unit
        override fun getSkipSilenceEnabled(): Boolean = false
        override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) = Unit
        override fun getAudioAttributes(): androidx.media3.common.AudioAttributes? = null
        override fun setAudioSessionId(audioSessionId: Int) = Unit
        override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = Unit
        override fun enableTunnelingV21() = Unit
        override fun disableTunneling() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun pause() = Unit
    }
}
