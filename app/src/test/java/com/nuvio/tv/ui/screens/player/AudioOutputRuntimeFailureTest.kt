package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputRuntimeFailureTest {

    private fun failure(vararg frames: Pair<String, String>): Throwable =
        IllegalArgumentException().apply {
            stackTrace = frames.map { (cls, method) -> StackTraceElement(cls, method, "SourceFile", 1) }
                .toTypedArray()
        }

    // Minified release stack (app classes renamed).
    private val replayFailure = failure(
        "androidx.media3.common.util.Assertions" to "checkArgument",
        "androidx.media3.exoplayer.audio.DefaultAudioSink" to "handleBuffer",
        "androidx.media3.exoplayer.audio.ForwardingAudioSink" to "handleBuffer",
        "ua.l" to "c",
        "ua.l" to "handleBuffer",
        "androidx.media3.exoplayer.audio.ForwardingAudioSink" to "handleBuffer",
        "sa.g5" to "handleBuffer",
        "androidx.media3.exoplayer.audio.MediaCodecAudioRenderer" to "processOutputBuffer",
        "androidx.media3.exoplayer.mediacodec.MediaCodecRenderer" to "bypassRender",
        "androidx.media3.exoplayer.mediacodec.MediaCodecRenderer" to "render",
        "androidx.media3.exoplayer.ExoPlayerImplInternal" to "doSomeWork"
    )

    private val videoFailure = failure(
        "androidx.media3.common.util.Assertions" to "checkState",
        "androidx.media3.exoplayer.video.MediaCodecVideoRenderer" to "processOutputBuffer",
        "androidx.media3.exoplayer.mediacodec.MediaCodecRenderer" to "drainOutputBuffer",
        "androidx.media3.exoplayer.mediacodec.MediaCodecRenderer" to "render",
        "androidx.media3.exoplayer.ExoPlayerImplInternal" to "doSomeWork"
    )

    private val conversionFailure = failure(
        "androidx.media3.common.util.Assertions" to "checkArgument",
        "com.nuvio.tv.core.player.dvmkv.MatroskaExtractor" to "commitSampleToOutput",
        "androidx.media3.exoplayer.source.ProgressiveMediaPeriod\$ExtractingLoadable" to "load"
    )

    private val runtimeCheck = PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK

    @Test
    fun replayAssertionInTheAudioSink_isAnAudioOutputFailure() {
        assertTrue(isAudioOutputRuntimeFailure(runtimeCheck, null, replayFailure))
    }

    @Test
    fun wrappedCause_isFollowed() {
        assertTrue(isAudioOutputRuntimeFailure(runtimeCheck, null, RuntimeException(replayFailure)))
    }

    @Test
    fun iecEngineFrame_countsAsAudioOutput() {
        val debugStack = failure(
            "com.nuvio.tv.ui.screens.player.iec.IecPassthroughAudioSink" to "drainReplays",
            "androidx.media3.exoplayer.ExoPlayerImplInternal" to "doSomeWork"
        )
        assertTrue(isAudioOutputRuntimeFailure(runtimeCheck, null, debugStack))
    }

    @Test
    fun videoRendererAssertion_isNotAnAudioOutputFailure() {
        assertFalse(isAudioOutputRuntimeFailure(runtimeCheck, null, videoFailure))
    }

    @Test
    fun extractorAssertion_isNotAnAudioOutputFailure() {
        assertFalse(isAudioOutputRuntimeFailure(runtimeCheck, null, conversionFailure))
    }

    @Test
    fun rendererFormatDecides_whenPresent() {
        assertTrue(isAudioOutputRuntimeFailure(runtimeCheck, MimeTypes.AUDIO_DTS_HD, videoFailure))
        assertFalse(isAudioOutputRuntimeFailure(runtimeCheck, MimeTypes.VIDEO_DOLBY_VISION, replayFailure))
    }

    @Test
    fun otherErrorCodes_areNotRuntimeChecks() {
        assertFalse(
            isAudioOutputRuntimeFailure(PlaybackException.ERROR_CODE_DECODING_FAILED, null, replayFailure)
        )
        assertFalse(
            isAudioOutputRuntimeFailure(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED, null, replayFailure)
        )
    }

    @Test
    fun noCause_isNotAnAudioOutputFailure() {
        assertFalse(isAudioOutputRuntimeFailure(runtimeCheck, null, null))
    }

    @Test
    fun iecOffRung_onlyForAnAudioFailureWhileIecCarriesTheTrack() {
        assertTrue(iecOffRungApplies(audioPathFailure = true, iecInvolved = true, failingMimeType = null))
        assertFalse(iecOffRungApplies(audioPathFailure = true, iecInvolved = false, failingMimeType = null))
        assertFalse(iecOffRungApplies(audioPathFailure = false, iecInvolved = true, failingMimeType = null))
        assertFalse(iecOffRungApplies(audioPathFailure = false, iecInvolved = false, failingMimeType = null))
    }

    @Test
    fun iecOffRung_needsAnHdFormatWhenTheFailingFormatIsKnown() {
        assertTrue(iecOffRungApplies(true, true, MimeTypes.AUDIO_TRUEHD))
        assertTrue(iecOffRungApplies(true, true, MimeTypes.AUDIO_DTS_HD))
        assertFalse(iecOffRungApplies(true, true, MimeTypes.AUDIO_E_AC3))
        assertFalse(iecOffRungApplies(true, true, MimeTypes.AUDIO_AC3))
    }

    @Test
    fun firstIecFailure_onlyCounts_soTheNextRebuildTriesIecAgain() {
        val strikes = mutableMapOf<String, Int>()
        val off = mutableSetOf<String>()
        assertEquals(1, noteIecFailure(strikes, off, "https://a/film.mkv"))
        assertFalse("https://a/film.mkv" in off)
    }

    @Test
    fun secondIecFailure_onTheSameStream_keepsItOff() {
        val strikes = mutableMapOf<String, Int>()
        val off = mutableSetOf<String>()
        noteIecFailure(strikes, off, "https://a/film.mkv")
        noteIecFailure(strikes, off, "https://b/other.mkv")
        assertFalse("https://a/film.mkv" in off)
        assertEquals(2, noteIecFailure(strikes, off, "https://a/film.mkv"))
        assertTrue("https://a/film.mkv" in off)
        assertFalse("https://b/other.mkv" in off)
        assertEquals(3, noteIecFailure(strikes, off, "https://a/film.mkv"))
        assertEquals(setOf("https://a/film.mkv"), off)
    }
}
