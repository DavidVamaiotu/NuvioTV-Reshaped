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
    fun guideIsReadAgainWhenAFullChannelRunsOut() {
        val hour = 60L * 60 * 1000
        fun slots(count: Int, length: Long) = (0 until count).map {
            LiveTvProgramme(title = "p$it", startEpochMs = it * length, stopEpochMs = (it + 1) * length, timeLabel = "")
        }
        val schedule = mapOf(
            "short" to slots(EPG_MAX_PER_CHANNEL, hour / 2), // runs out after 2 h
            "ending" to slots(1, hour / 4), // the guide itself ends: no reason to read sooner
        )
        assertEquals(2 * hour, nextScheduleReadAt(schedule, 0L, hour, 10 * hour))
        assertEquals(hour, nextScheduleReadAt(mapOf("tiny" to slots(EPG_MAX_PER_CHANNEL, hour / 10)), 0L, hour, 10 * hour))
        assertEquals(10 * hour, nextScheduleReadAt(emptyMap(), 0L, hour, 10 * hour))
    }
}
