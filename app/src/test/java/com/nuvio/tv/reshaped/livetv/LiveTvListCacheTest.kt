package com.nuvio.tv.reshaped.livetv

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LiveTvListCacheTest {

    private val source = LiveTvSource(
        id = "a",
        type = LiveTvSourceType.Xtream,
        xtream = LiveTvXtreamSettings("http://panel.example", "user", "pass"),
        userAgent = "Agent",
    )

    @Test
    fun savedListReadsBackAsItWasGiven() {
        val dir = Files.createTempDirectory("lists").toFile()
        val headers = mapOf("User-Agent" to "VLC", "Referer" to "http://ref.example")
        val catchup = LiveTvCatchup(LiveTvCatchup.Kind.Xtream, 3)
        val channels = listOf(
            LiveTvChannel(id = "1", name = "One", streamUrl = "http://s/1.ts", tvgId = "one.uk", logoUrl = "http://l/1.png", group = "News", headers = headers, catchup = catchup),
            LiveTvChannel(id = "2", name = "Two", streamUrl = "http://s/2.ts", group = "News", headers = headers, tvgName = "Two HD"),
        )
        val file = LiveTvListCache.file(dir, 1, source.id)
        LiveTvListCache.write(file, source, LiveTvListCache.Entry(channels, listOf("http://g.xml"), listOf("News"), 1234L))

        val read = LiveTvListCache.read(file, source)!!
        assertEquals(channels, read.channels)
        assertEquals(listOf("http://g.xml"), read.epgUrls)
        assertEquals(listOf("News"), read.groupOrder)
        assertEquals(1234L, read.savedAtMs)
        // Shared as when the list was read from the provider.
        assertSame(read.channels[0].headers, read.channels[1].headers)
        assertSame(read.channels[0].group, read.channels[1].group)
    }

    @Test
    fun anEditedLoginDoesNotGetTheOldList() {
        val dir = Files.createTempDirectory("lists").toFile()
        val file = LiveTvListCache.file(dir, 1, source.id)
        LiveTvListCache.write(file, source, LiveTvListCache.Entry(listOf(LiveTvChannel("1", "One", "http://s/1.ts")), emptyList(), emptyList(), 1L))
        assertNull(LiveTvListCache.read(file, source.copy(xtream = source.xtream.copy(password = "other"))))
        assertNull(LiveTvListCache.read(file, source.copy(userAgent = "")))
    }

    @Test
    fun onlyRemovedSourcesListsAreDeleted() {
        val dir = Files.createTempDirectory("lists").toFile()
        val entry = LiveTvListCache.Entry(listOf(LiveTvChannel("1", "One", "http://s/1.ts")), emptyList(), emptyList(), 1L)
        val kept = LiveTvListCache.file(dir, 1, "a")
        val removed = LiveTvListCache.file(dir, 1, "b")
        val otherProfile = LiveTvListCache.file(dir, 2, "b")
        listOf(kept, removed, otherProfile).forEach { LiveTvListCache.write(it, source, entry) }
        LiveTvListCache.keepOnly(dir, 1, listOf("a"))
        assertEquals(listOf(true, false, true), listOf(kept, removed, otherProfile).map { it.isFile })
    }

    @Test
    fun attributesReadAsThePatternDid() {
        assertEquals(
            mapOf("tvg-id" to "a.uk", "group-title" to "News, World", "tvg_name" to "A"),
            parseM3uAttributes("#EXTINF:-1 tvg-id=\"a.uk\" group-title=\" News, World \" TVG_NAME=\"A\""),
        )
        // A later key wins; an unclosed value ends the reading.
        assertEquals(mapOf("x" to "2"), parseM3uAttributes("x=\"1\" x=\"2\" y=\"open"))
        assertEquals(emptyMap<String, String>(), parseM3uAttributes("no quotes here"))
    }
}
