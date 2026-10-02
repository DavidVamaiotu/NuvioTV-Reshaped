package com.nuvio.tv.reshaped.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvPlaylistParserTest {

    private fun parse(text: String) = parseM3uPlaylist(text.lineSequence())

    @Test
    fun readsChannelsGroupsLogosAndGuide() {
        val playlist = parse(
            """
            #EXTM3U url-tvg="https://guide.example/epg.xml.gz"
            #EXTINF:-1 tvg-id="one.uk" tvg-logo="https://logo/1.png" group-title="News",Channel One
            https://stream.example/1.m3u8
            #EXTINF:-1 group-title="Sports",Sport, HD
            #EXTVLCOPT:http-user-agent=Custom
            https://stream.example/2.ts|Referer=https://ref.example
            """.trimIndent(),
        )
        assertEquals(listOf("https://guide.example/epg.xml.gz"), playlist.epgUrls)
        assertEquals(2, playlist.channels.size)
        val one = playlist.channels[0]
        assertEquals("Channel One", one.name)
        assertEquals("one.uk", one.tvgId)
        assertEquals("https://logo/1.png", one.logoUrl)
        assertEquals("News", one.group)
        val two = playlist.channels[1]
        assertEquals("Sport, HD", two.name)
        assertEquals("https://stream.example/2.ts", two.streamUrl)
        assertEquals("Custom", two.headers["User-Agent"])
        assertEquals("https://ref.example", two.headers["Referer"])
    }

    @Test
    fun dropsDuplicatesAndCategorySeparators() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1,##### SPORTS #####
            https://stream.example/separator
            #EXTINF:-1,A
            https://stream.example/a
            #EXTINF:-1,A again
            https://stream.example/a
            """.trimIndent(),
        )
        assertEquals(listOf("A"), playlist.channels.map { it.name })
        assertEquals(listOf("m0"), playlist.channels.map { it.id })
    }

    @Test
    fun aWebPageIsNotAChannelList() {
        val playlist = parse(
            """
            <!DOCTYPE html>
            <html><body>Login</body></html>
            {"user_info":{"auth":0}}
            """.trimIndent(),
        )
        assertTrue(playlist.channels.isEmpty())
    }

    @Test
    fun readsExtGrpRefererSpellingAndRelativeLinks() {
        val playlist = parseM3uPlaylist(
            """
            #EXTM3U
            #EXTINF:-1,One
            #EXTGRP:Sports
            #EXTVLCOPT:http-referer=https://ref.example
            one.m3u8
            #EXTINF:-1,Two
            #EXT-X-STRAY
            https://stream.example/two.ts
            """.trimIndent().lineSequence(),
            baseUrl = "https://lists.example/live/list.m3u",
        )
        assertEquals(listOf("One", "Two"), playlist.channels.map { it.name })
        assertEquals("https://lists.example/live/one.m3u8", playlist.channels[0].streamUrl)
        assertEquals("Sports", playlist.channels[0].group)
        assertEquals("https://ref.example", playlist.channels[0].headers["Referer"])
    }

    @Test
    fun recognisesAnHlsStreamItself() {
        val playlist = parse(
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:6.0,
            segment1.ts
            """.trimIndent(),
        )
        assertTrue(playlist.isHlsStream)
        assertTrue(playlist.channels.isEmpty())
    }

    @Test
    fun neighbourWrapsAround() {
        val channels = listOf("a", "b", "c").map { LiveTvChannel(id = it, name = it, streamUrl = it) }
        assertEquals("c", LiveTvRepository.neighbour(channels, "a", -1)?.streamUrl)
        assertEquals("a", LiveTvRepository.neighbour(channels, "c", 1)?.streamUrl)
        assertEquals("a", LiveTvRepository.neighbour(channels, "missing", 1)?.streamUrl)
    }

    @Test
    fun guideIsReadAgainWhenACutChannelRunsOut() {
        val hour = 60L * 60 * 1000
        fun slots(count: Int, length: Long) = (0 until count).map {
            LiveTvProgramme(title = "p$it", startEpochMs = it * length, stopEpochMs = (it + 1) * length)
        }
        val schedule = mapOf(
            "short" to slots(4, hour / 2), // cut, runs out after 2 h
            "ending" to slots(1, hour / 4), // the guide itself ends: no reason to read sooner
        )
        assertEquals(2 * hour, nextScheduleReadAt(schedule, setOf("short"), 0L, hour, 10 * hour))
        assertEquals(hour, nextScheduleReadAt(mapOf("tiny" to slots(4, hour / 10)), setOf("tiny"), 0L, hour, 10 * hour))
        assertEquals(10 * hour, nextScheduleReadAt(emptyMap(), emptySet(), 0L, hour, 10 * hour))
        // A portal's guide asked for 8 h, refreshed every 12 h: read (and fetched) again when it ends.
        assertEquals(8 * hour, nextScheduleReadAt(mapOf("portal" to slots(8, hour)), emptySet(), 0L, hour, 12 * hour))
    }

    @Test
    fun nameKeysIgnoreCountryTagsQualityAndPunctuation() {
        assertEquals("bbcone", liveTvNameKey("UK: BBC One HD"))
        assertEquals("bbcone", liveTvNameKey("BBC ONE"))
        assertEquals("bbcone", liveTvNameKey("|UK| BBC-One FHD"))
        assertEquals("bbcone", liveTvNameKey("[UK] BBC One"))
        assertEquals("channel4+1", liveTvNameKey("Channel 4 +1"))
        assertEquals("abcnews", liveTvNameKey("ABC News"))
    }

    @Test
    fun guideMatchesByIdThenByNameAndKeepsAWindow() {
        val hour = 60L * 60 * 1000
        val now = 10 * hour
        val channels = listOf(
            LiveTvChannel(id = "1", name = "BBC One HD", streamUrl = "a", tvgId = "bbc1.uk", guideKey = liveTvGuideKey("bbc1.uk", "BBC One HD")),
            LiveTvChannel(id = "2", name = "UK: ITV 1", streamUrl = "b", guideKey = liveTvGuideKey(null, "UK: ITV 1")),
            LiveTvChannel(id = "3", name = "Sky News", streamUrl = "c", tvgId = "wrong.id", guideKey = liveTvGuideKey("wrong.id", "Sky News")),
        )
        val builder = LiveTvScheduleBuilder(
            LiveTvGuideRequest.from(channels),
            now,
            LiveTvGuideWindow(pastMs = 2 * hour, maxPast = 1, aheadMs = 3 * hour, maxAhead = 2),
        )
        builder.channel("bbc1.uk", listOf("BBC One"), "https://logo/bbc.png")
        builder.channel("itv1.uk", listOf("ITV 1"), "https://logo/itv.png")
        builder.channel("skynews.uk", listOf("Sky News"), null)
        builder.channel("other.uk", listOf("Other"), null)
        assertEquals(null, builder.keysFor("other.uk"))
        listOf("bbc1.uk", "itv1.uk", "skynews.uk").forEach { id ->
            val keys = builder.keysFor(id)!!
            (7L..14L).forEach { h -> builder.add(keys, "$id $h", h * hour, (h + 1) * hour) }
        }
        val guide = builder.build()
        val bbc = guide.schedule.getValue(channels[0].guideKey)
        // One ended programme (the latest), then the one on now and the next: the cap is 2 ahead.
        assertEquals(listOf(9L, 10L, 11L).map { it * hour }, bbc.map { it.startEpochMs })
        assertTrue(channels[0].guideKey in guide.truncated)
        assertEquals(3, guide.schedule.getValue(channels[1].guideKey).size)
        assertEquals(3, guide.schedule.getValue(channels[2].guideKey).size)
        // None of these has a logo in the playlist, so the guide's is used.
        assertEquals("https://logo/itv.png", guide.logos[channels[1].guideKey])
    }

    @Test
    fun keepsTvgNameWhenItDiffersFromTheShownName() {
        val playlist = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="BBC One HD",UK: BBC 1
            https://stream.example/1.ts
            #EXTINF:-1 tvg-name="Two",Two
            https://stream.example/2.ts
            """.trimIndent(),
        )
        assertEquals("BBC One HD", playlist.channels[0].tvgName)
        assertEquals(null, playlist.channels[1].tvgName)
    }

    @Test
    fun findsTheGuideOfAnXtreamM3uLink() {
        assertEquals(
            "http://dns.example:8080/xmltv.php?username=user&password=pass",
            xtreamGuideUrlFor("http://dns.example:8080/get.php?username=user&password=pass&type=m3u_plus&output=ts"),
        )
        assertEquals(null, xtreamGuideUrlFor("https://lists.example/list.m3u"))
        assertEquals(null, xtreamGuideUrlFor("http://dns.example/get.php?type=m3u"))
    }

    @Test
    fun readsGuideTimesInEveryCommonForm() {
        val expected = java.time.Instant.parse("2026-09-27T19:30:00Z").toEpochMilli()
        listOf(
            "20260927213000 +0200",
            "20260927213000+0200",
            "20260927213000 +02:00",
            "202609272130 +0200",
            "20260927193000 Z",
            "20260927193000 UTC",
            "20260927213000.000 +0200",
        ).forEach { assertEquals(it, expected, LiveTvClock.parseXmlTvTimestamp(it)) }
        assertEquals(null, LiveTvClock.parseXmlTvTimestamp("2026092719"))
    }
}
