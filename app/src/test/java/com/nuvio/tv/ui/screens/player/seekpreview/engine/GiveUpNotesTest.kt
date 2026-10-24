// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The summary line written when thumbnails give up for a title. */
class GiveUpNotesTest {
    private fun line(n: GiveUpNotes) = n.line(
        event = "generating screen stalled at 0/40",
        made = 0,
        stored = 0,
        memory = MemoryGovernor.memoryNote(270L, 512L, 300L, lowRam = false),
        connections = "in use playback 4, thumbnails 0",
        decoder = "ok mpv",
        decoderReady = true,
        source = "debrid direct (TorBox)",
        video = GiveUpNotes.videoNote(3840, 2160, MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06", C.COLOR_TRANSFER_ST2084),
        settings = SETTINGS,
    )

    @Test fun nothingSeen() {
        val n = GiveUpNotes()
        assertEquals("nothing seen", n.reason(decoderReady = true))
        assertFalse(n.anything)
        n.noConnectionFree()
        assertTrue(n.anything)
    }

    @Test fun theDecoderComesFirstThenMemoryThenTheNetwork() {
        val n = GiveUpNotes()
        n.decodeFailed()
        assertEquals("decodes failed", n.reason(true))
        n.noConnectionFree()
        assertEquals("no connection free", n.reason(true))
        n.rateLimited()
        assertEquals("rate limited", n.reason(true))
        n.fetchFailed(IOException("reset"))
        assertEquals("fetches failed", n.reason(true))
        n.memoryRefused("MemAvailable 270 MB - 35 < floor 300")
        assertEquals("memory floor", n.reason(true))
        assertEquals("decoder unavailable", n.reason(false))
    }

    @Test fun aSessionErrorAloneIsNamed() {
        val n = GiveUpNotes()
        n.ended(UnsupportedMediaException("container not supported"))
        assertEquals("session error", n.reason(true))
        assertTrue(line(n).endsWith("; ended by UnsupportedMediaException: container not supported"))
    }

    @Test fun theLineCarriesTheNumbers() {
        val n = GiveUpNotes()
        repeat(3) { n.memoryRefused("MemAvailable 270 MB - 35 < floor 300") }
        n.fetchFailed(SocketTimeoutException())
        repeat(2) { n.noConnectionFree() }
        assertEquals(
            "gave up (generating screen stalled at 0/40): reason=memory floor made=0 stored=0; " +
                "memory: MemAvailable 270 MB (floor 300, funded 150), SwapFree 300 MB, decodes refused 3 " +
                "(last: MemAvailable 270 MB - 35 < floor 300); connections: in use playback 4, thumbnails 0, " +
                "none free x2; fetches failed 1 (last: SocketTimeoutException), rate limited x0; decodes failed 0; " +
                "decoder: ok mpv; playback: source debrid direct (TorBox), video 3840x2160 HEVC DV P8 HDR10, " +
                "before play shown (Faster), " + SETTINGS,
            line(n.also { it.beforePlay = "shown (Faster)" }),
        )
    }

    @Test fun theMemoryNoteNamesTheBoxClass() {
        assertEquals(
            "MemAvailable unknown (floor 200, funded 100), SwapFree no swap, low-RAM box",
            MemoryGovernor.memoryNote(-1L, 0L, 0L, lowRam = true),
        )
    }

    @Test fun theVideoIsNamedByCodecAndRange() {
        assertEquals("1920x1080 H.264", GiveUpNotes.videoNote(1920, 1080, MimeTypes.VIDEO_H264, "avc1.640028", Format.NO_VALUE))
        assertEquals("3840x2160 HEVC HDR10", GiveUpNotes.videoNote(3840, 2160, MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", C.COLOR_TRANSFER_ST2084))
        assertEquals("3840x2160 HEVC DV P7", GiveUpNotes.videoNote(3840, 2160, MimeTypes.VIDEO_DOLBY_VISION, "dvhe.07.06", Format.NO_VALUE))
        assertEquals("3840x2160 AV1 DV P10", GiveUpNotes.videoNote(3840, 2160, MimeTypes.VIDEO_DOLBY_VISION, "dav1.10.06", Format.NO_VALUE))
        assertEquals("1920x1080 HEVC HLG", GiveUpNotes.videoNote(1920, 1080, MimeTypes.VIDEO_H265, null, C.COLOR_TRANSFER_HLG))
        assertEquals("1280x720 unknown", GiveUpNotes.videoNote(1280, 720, null, null, Format.NO_VALUE))
    }

    @Test fun noLinkHostOrNameReachesTheLine() {
        val n = GiveUpNotes()
        n.fetchFailed(UnknownHostException("store-042.weur.tb-cdn.st"))
        n.ended(IOException(
            "Response code 403 for https://user:pw@cdn.example.com:8443/dl/abc/The.Film.2024.2160p.mkv?token=" +
                "eyJhbGciOiJIUzI1NiJ9abcdefabcdef from 192.168.1.10:3100 and [2001:db8::1] key 0123456789abcdefABCDEF0123",
        ))
        val text = line(n)
        for (leak in listOf("://", "example", "tb-cdn", "weur", "Film", "mkv", "token", "eyJ", "192.168", "2001:db8",
                "0123456789abcdef", "user", "pw@")) {
            assertFalse("leaked $leak: $text", text.contains(leak))
        }
        assertTrue(text, text.endsWith("; ended by IOException: Response code 403"))
        assertFalse(text.contains("UnknownHostException:"))
    }

    @Test fun anErrorIsNamedByItsTypeAndStatusOnly() {
        assertEquals("HttpStatusException: HTTP 429", GiveUpNotes.errorNote(HttpRangeReader.HttpStatusException(429, true)))
        assertEquals("UnknownHostException", GiveUpNotes.errorNote(UnknownHostException("nas")))
        assertEquals("IOException", GiveUpNotes.errorNote(IOException("open failed: /storage/The Film 2024.mkv")))
        assertEquals("IOException", GiveUpNotes.errorNote(IOException()))
    }

    private companion object {
        const val SETTINGS = "engine ExoPlayer, native memory on, managed budget on, target buffer auto, " +
            "custom buffers off, box 3072 MB lowRam no"
    }
}
