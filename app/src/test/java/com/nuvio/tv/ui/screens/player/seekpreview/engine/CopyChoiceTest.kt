// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class CopyChoiceTest {
    private val ownMs = 7_200_000L
    private val playing = "Film.2019.2160p.UHD.BluRay.REMUX.HDR.HEVC.TrueHD-GRP"

    private fun offer(
        id: String,
        kind: CopyKind = CopyKind.LINK,
        height: Int = 1080,
        codec: CopyCodec = CopyCodec.H264,
        hdr: Boolean = false,
        web: Boolean = true,
        size: Long? = 4_000_000_000L,
        container: String? = "mkv",
        durationMs: Long? = null,
        text: String = "Film.2019.1080p.WEB-DL.H264-GRP",
    ) = CopyOffer(id, id, kind, height, codec, hdr, web, size, container, durationMs, text)

    @Test
    fun editionWordsMustMatch() {
        assertEquals(setOf(CopyChoice.Edition.EXTENDED), CopyChoice.editions("Film.Extended.Cut.1080p"))
        assertEquals(setOf(CopyChoice.Edition.DIRECTORS), CopyChoice.editions("Film Director’s Cut 1080p"))
        assertEquals(setOf(CopyChoice.Edition.DIRECTORS), CopyChoice.editions("Film.Directors.Cut"))
        assertTrue(CopyChoice.editions("Film.2019.1080p.WEB-DL").isEmpty())
        assertNotNull(CopyChoice.rejection(offer("a", text = "Film.Extended.1080p"), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("b", text = "Film.IMAX.1080p"), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("c"), "Film.2019.2160p.Unrated.REMUX", ownMs))
        assertNull(CopyChoice.rejection(offer("d", text = "Film.Extended.1080p"), "Film.EXTENDED.2160p.REMUX", ownMs))
        assertNull(CopyChoice.rejection(offer("e"), playing, ownMs))
    }

    @Test
    fun resolutionAndContainerRules() {
        assertNull(CopyChoice.rejection(offer("a", height = 720), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("b", height = 2160), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("c", height = 480), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("d", height = 0), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("e", container = "avi"), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("f", container = "m3u8"), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("g", container = "mp4"), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("h", container = null), playing, ownMs))
        assertEquals("mkv", CopyChoice.containerOf("Film.2019.1080p.WEB.mkv"))
        assertEquals("mp4", CopyChoice.containerOf("https://h/x/Film.MP4?token=1"))
        assertNull(CopyChoice.containerOf("https://h/Videos/abc/stream"))
    }

    @Test
    fun lengthWithinTwoSecondsOrPalRatio() {
        assertNull(CopyChoice.rejection(offer("a", durationMs = ownMs + 1_900), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("b", durationMs = ownMs + 5_000), playing, ownMs))
        assertNull(CopyChoice.rejection(offer("c", durationMs = (ownMs * 0.959).toLong()), playing, ownMs))
        assertNotNull(CopyChoice.rejection(offer("d", durationMs = (ownMs * 0.9).toLong()), playing, ownMs))
        assertEquals(7_200_000L, CopyChoice.parsedDurationMs(7_200L))
        assertEquals(7_200_000L, CopyChoice.parsedDurationMs(7_200_000L))
        assertNull(CopyChoice.parsedDurationMs(0L))
        assertNull(CopyChoice.parsedDurationMs(null))
    }

    @Test
    fun orderPrefersNoSideEffectsThenSdrH264Then1080pWebSmaller() {
        val ordered = CopyChoice.order(listOf(
            offer("debrid", kind = CopyKind.DEBRID, size = 1_000_000_000L),
            offer("720", height = 720, size = 1_000_000_000L),
            offer("hdr", hdr = true, size = 1_000_000_000L),
            offer("hevc", codec = CopyCodec.HEVC, size = 1_000_000_000L),
            offer("bluray", web = false, size = 1_000_000_000L),
            offer("big", size = 9_000_000_000L),
            offer("small", size = 2_000_000_000L),
            offer("server", kind = CopyKind.SERVER, size = 3_000_000_000L),
            offer("nosize", size = null),
        )).map { it.id }
        assertEquals(listOf("debrid", "small", "server", "big", "nosize", "bluray", "720", "hevc", "hdr"), ordered)
    }

    @Test
    fun a720pH264CopyComesBeforeA1080pHevcOneButNotBeforeAnAccountChange() {
        val ordered = CopyChoice.order(listOf(
            offer("hevc1080", codec = CopyCodec.HEVC),
            offer("added720", kind = CopyKind.TORBOX_ADD, height = 720),
            offer("h264720", height = 720),
            offer("h2641080"),
        )).map { it.id }
        assertEquals(listOf("h2641080", "h264720", "hevc1080", "added720"), ordered)
    }

    @Test
    fun accountSideEffectsLastAndSourceCostBreaksTies() {
        val ordered = CopyChoice.order(listOf(
            offer("usenet", kind = CopyKind.USENET, size = 1_000_000_000L),
            offer("premiumize", kind = CopyKind.PREMIUMIZE, size = 1_000_000_000L),
            offer("added", kind = CopyKind.TORBOX_ADD, size = 1_000_000_000L),
            offer("link720", height = 720),
            offer("link", size = 2_000_000_000L),
            offer("list", kind = CopyKind.DEBRID, size = 2_000_000_000L),
        )).map { it.id }
        assertEquals(listOf("list", "link", "link720", "added", "premiumize", "usenet"), ordered)
    }

    @Test
    fun aUsenetTitleTakesAnyCopyUpToItsOwnResolutionButNoUsenetLink() {
        val hd = "Film.2019.1080p.WEB-DL.DDP5.1.H264-GRP"
        assertNull(CopyChoice.rejection(offer("same"), hd, ownMs, maxHeight = 1080, usenet = true))
        assertNull(CopyChoice.rejection(offer("720", height = 720), hd, ownMs, maxHeight = 1080, usenet = true))
        assertEquals("2160p is above the playing 1080p",
            CopyChoice.rejection(offer("4k", height = 2160), hd, ownMs, maxHeight = 1080, usenet = true))
        assertEquals("1080p is above the playing 720p",
            CopyChoice.rejection(offer("up"), hd, ownMs, maxHeight = 720, usenet = true))
        assertEquals("another Usenet link",
            CopyChoice.rejection(offer("nzb", kind = CopyKind.USENET), hd, ownMs, maxHeight = 1080, usenet = true))
        assertNotNull(CopyChoice.rejection(offer("cut", text = "Film.Extended.1080p"), hd, ownMs, maxHeight = 1080, usenet = true))
        assertNotNull(CopyChoice.rejection(offer("long", durationMs = ownMs + 5_000), hd, ownMs, maxHeight = 1080, usenet = true))
        // A 4K title from Usenet keeps the smaller-copy rule.
        assertNotNull(CopyChoice.rejection(offer("4k", height = 2160), playing, ownMs, maxHeight = 2160, usenet = true))
        assertNull(CopyChoice.rejection(offer("hd"), playing, ownMs, maxHeight = 2160, usenet = true))
        // A 4K title not from Usenet may still use a Usenet copy.
        assertNull(CopyChoice.rejection(offer("nzb", kind = CopyKind.USENET), playing, ownMs))
    }

    @Test
    fun aUsenetTitleOrdersBySourceFirst() {
        val ordered = CopyChoice.order(listOf(
            offer("added", kind = CopyKind.TORBOX_ADD, size = 1_000_000_000L),
            offer("link", size = 1_000_000_000L),
            offer("premiumize", kind = CopyKind.PREMIUMIZE, size = 1_000_000_000L),
            offer("list720", kind = CopyKind.DEBRID, height = 720),
            offer("list", kind = CopyKind.DEBRID, size = 9_000_000_000L),
            offer("server", kind = CopyKind.SERVER, codec = CopyCodec.HEVC),
        ), usenet = true).map { it.id }
        assertEquals(listOf("server", "list", "list720", "premiumize", "link", "added"), ordered)
    }

    @Test
    fun heightClassesFollowTheWidthForWideFilms() {
        assertEquals(2160, CopyChoice.heightClass(3840, 2160))
        assertEquals(2160, CopyChoice.heightClass(3840, 1600))
        assertEquals(1080, CopyChoice.heightClass(1920, 1080))
        assertEquals(1080, CopyChoice.heightClass(1920, 800))
        assertEquals(1080, CopyChoice.heightClass(1440, 1080))
        assertEquals(720, CopyChoice.heightClass(1280, 720))
        assertEquals(720, CopyChoice.heightClass(1280, 536))
        assertEquals(480, CopyChoice.heightClass(720, 480))
    }

    @Test
    fun usenetMarkingsAreKnown() {
        assertTrue(CopyChoice.usenetMarked("usenet", null))
        assertTrue(CopyChoice.usenetMarked(null, "Usenet"))
        assertTrue(CopyChoice.usenetMarked("nzb", "torbox"))
        assertFalse(CopyChoice.usenetMarked("debrid", "torbox"))
        assertFalse(CopyChoice.usenetMarked(null, null))
    }

    @Test
    fun usenetLinksAreKnown() {
        assertTrue(CopyChoice.isUsenet("http://192.168.1.10:3000/api/v1/usenet/stream/abc/Film.mkv"))
        assertTrue(CopyChoice.isUsenet("http://nas.local/USENET/stream/abc"))
        assertFalse(CopyChoice.isUsenet("https://cdn.example/Film.1080p.mkv"))
        assertFalse(CopyChoice.isUsenet("https://cdn.example/files/usenet-stream.mkv"))
        assertFalse(CopyChoice.isUsenet("https://cdn.example/Film.mkv?x=/usenet/stream/"))
    }

    @Test
    fun copySlotsServeTheSameMoments() {
        val same = CopyChoice.ownSlotsByCopySlot(10, 1.0, 10)
        assertEquals(10, same.size)
        assertArrayEquals(intArrayOf(4), same[4])
        // A 25 fps copy runs 4 % shorter: some copy slots serve two own slots.
        val pal = CopyChoice.ownSlotsByCopySlot(720, 0.959, ReleaseMatch.slotCount((7_200_000 * 0.959).toLong(), 10_000))
        assertEquals(720, pal.values.sumOf { it.size })
        assertTrue(pal.values.any { it.size == 2 })
        assertTrue(pal.keys.all { it in 0 until 691 })
    }

    @Test
    fun fitPadsOrCutsToTheOwnPicture() {
        val same = CopyFit.plan(480, 270, 480, 270)!!
        assertEquals(0, same.top)
        // A scope copy without bars into a letterboxed 16:9 4K picture: bars added.
        val pad = CopyFit.plan(480, 200, 480, 270)!!
        assertEquals(480, pad.renderW)
        assertEquals(200, pad.renderH)
        assertEquals(35, pad.top)
        // A letterboxed copy into a cropped 4K picture: the copy's bars are cut.
        val cut = CopyFit.plan(480, 270, 480, 200)!!
        assertEquals(-35, cut.top)
        assertNull(CopyFit.plan(480, 360, 480, 200))
    }

    @Test
    fun anotherReleaseWithItsBarsKeptFitsOneWithoutThem() {
        // The Crown: the remux keeps its bars (480x270), the RARBG encode has them cut (480x240).
        val cut = CopyFit.plan(480, 270, 480, 240)!!
        assertEquals(480, cut.renderW)
        assertEquals(270, cut.renderH)
        assertEquals(-15, cut.top)
        val pad = CopyFit.plan(480, 240, 480, 270)!!
        assertEquals(15, pad.top)
    }

    @Test
    fun serversLeftAloneAfterTrouble() {
        val b = CopyHostBlocks()
        val now = 1_000_000_000_000L
        b.refused("h", now)
        assertNotNull(b.blocked("h", now + 29 * 60_000L))
        assertNull(b.blocked("h", now + 31 * 60_000L))
        b.trouble("t", now)
        assertNotNull(b.blocked("t", now + 60_000L))
        assertTrue(b.blames(1_000L, 1_400L, 15_000L, 20_000L))
        assertFalse(b.blames(1_000L, 1_400L, 30_000L, 20_000L))
        assertFalse(b.blames(1_000L, 1_400L, 500L, 20_000L))
        assertFalse(b.blames(0L, 0L, 500L, 20_000L))
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(86_400_000L, CopyHostBlocks.endOfDayMs(3_600_000L, utc))
    }

    @Test
    fun moreEditionWords() {
        assertEquals(setOf(CopyChoice.Edition.UNCUT), CopyChoice.editions("Film.UNCUT.1080p"))
        assertEquals(setOf(CopyChoice.Edition.SPECIAL), CopyChoice.editions("Film Special Edition 1080p"))
        assertEquals(setOf(CopyChoice.Edition.FINAL_CUT), CopyChoice.editions("Film.Final.Cut.1080p"))
        assertEquals(setOf(CopyChoice.Edition.REMASTERED), CopyChoice.editions("Film.REMASTERED.1080p"))
        assertNotNull(CopyChoice.rejection(offer("a", text = "Film.Final.Cut.1080p"), playing, ownMs))
    }

    @Test
    fun onlyPlainFileLinksAndLocalUsenetStreamsAreRead() {
        assertNull(CopyChoice.linkRejection("https://cdn.example.org/files/Film.2019.1080p.WEB.mkv", "Film 1080p"))
        assertNull(CopyChoice.linkRejection("http://192.168.1.10:3000/api/v1/usenet/stream/abc/Film.mkv", "[TB+] Film"))
        assertNotNull(CopyChoice.linkRejection("https://torrentio.strem.fun/resolve/realdebrid/key/hash/1/Film.mkv", "Film"))
        assertNotNull(CopyChoice.linkRejection("https://aio.example.org/api/v1/debrid/playback/abc", "Film"))
        assertNotNull(CopyChoice.linkRejection("https://x.example.org/torbox/abc/Film.mkv", "Film"))
        assertNotNull(CopyChoice.linkRejection("https://x.example.org/Film.mkv", "[RD+] Film 1080p"))
        assertNotNull(CopyChoice.linkRejection("https://x.example.org/Film.mkv", "TB+ cached"))
        assertNotNull(CopyChoice.linkRejection("https://x.example.org/Film.mkv", "[PM] Film"))
        assertNotNull(CopyChoice.linkRejection("https://x.example.org/Film.mkv", "\u2b07 Film"))
        assertNotNull(CopyChoice.linkRejection("https://x.example.org/Film.mkv", "Film (download)"))
    }
}
