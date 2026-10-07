package com.nuvio.tv.reshaped.livetv

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.kxml2.io.KXmlParser

class LiveTvArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    private val now = Instant.parse("2026-10-07T08:15:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun channel(days: Int?) = LiveTvChannel(
        id = "one/1", name = "News", streamUrl = "https://one.example/1.ts", tvgId = "shared",
        sourceId = "one", guideKey = liveTvGuideKey("shared", "News", "one"),
        catchup = days?.let { LiveTvCatchup(LiveTvCatchup.Kind.Xtream, it) },
    )

    private fun stamp(ms: Long) = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
        .withZone(java.time.ZoneOffset.UTC).format(Instant.ofEpochMilli(ms))

    /** A guide of hour-long programmes named by how many hours before [now] they start, from [fromHours] back. */
    private fun guide(fromHours: Int, toHours: Int = -3): String = buildString {
        append("<tv><channel id=\"shared\"><display-name>News</display-name></channel>\n")
        for (h in fromHours downTo toHours) {
            val start = now - now % hour - h * hour
            append("<programme channel=\"shared\" start=\"${stamp(start)}\" stop=\"${stamp(start + hour)}\"><title>h$h</title></programme>\n")
        }
        append("</tv>")
    }

    private fun read(xml: String, channel: LiveTvChannel, spills: LiveTvArchive.Spills, at: Long = now) =
        readXmlTvGuide(xml.byteInputStream(), LiveTvGuideRequest.from(listOf(channel)), at, LiveTvGuideWindow.LowMemory, ::KXmlParser, spills)

    private fun archive(file: File, guide: LiveTvGuide, channel: LiveTvChannel, at: Long = now): Boolean =
        LiveTvArchive.write(file, mapOf(channel.guideKey to guide.spill!!), mapOf(channel.guideKey to channel.catchup!!.days), at)

    @Test fun pastDaysGoToDiskWhileMemoryKeepsLessThanADay() {
        val channel = channel(7)
        val spills = LiveTvArchive.Spills(folder.newFolder("spill"))
        val file = File(folder.root, "archive.bin")
        val guide = read(guide(fromHours = 5 * 24), channel, spills)
        val kept = guide.schedule.getValue(channel.guideKey)
        assertTrue(kept.first().startEpochMs >= now - LiveTvGuideWindow.LowMemory.catchupPastMs - hour)
        assertTrue(archive(file, guide, channel))
        val archived = LiveTvArchive.read(file, channel.guideKey)
        // Every ended programme of the five days, in order, and nothing still on or to come.
        assertEquals(5 * 24, archived.size)
        assertEquals("h${5 * 24}", archived.first().title)
        assertTrue(archived.all { it.stopEpochMs <= now })
        assertEquals(archived.sortedBy { it.startEpochMs }, archived)
    }

    @Test fun olderDaysStayWhenANewGuideNoLongerListsThem() {
        val channel = channel(7)
        val file = File(folder.root, "archive.bin")
        val first = LiveTvArchive.Spills(folder.newFolder("first"))
        assertTrue(archive(file, read(guide(fromHours = 4 * 24), channel, first), channel))
        // A day later the provider's file starts only a day back.
        val later = now + day
        val second = LiveTvArchive.Spills(folder.newFolder("second"))
        val next = read(guide(fromHours = 0, toHours = -27), channel, second, later)
        assertTrue(archive(file, next, channel, later))
        val archived = LiveTvArchive.read(file, channel.guideKey)
        assertEquals("h${4 * 24}", archived.first().title)
        assertTrue(archived.zipWithNext().all { (a, b) -> a.stopEpochMs <= b.startEpochMs })
        assertTrue(archived.last().stopEpochMs <= later)
        assertTrue(archived.last().stopEpochMs > now + 20 * hour)
    }

    @Test fun aChannelKeepsOnlyItsOwnCatchUpDays() {
        val channel = channel(2)
        val file = File(folder.root, "archive.bin")
        val spills = LiveTvArchive.Spills(folder.newFolder("spill"))
        assertTrue(archive(file, read(guide(fromHours = 5 * 24), channel, spills), channel))
        val archived = LiveTvArchive.read(file, channel.guideKey)
        assertTrue(archived.all { it.stopEpochMs > now - 2 * day })
        assertEquals(2 * 24, archived.size)
    }

    @Test fun channelsThatLostCatchUpAreDropped() {
        val channel = channel(7)
        val file = File(folder.root, "archive.bin")
        assertTrue(archive(file, read(guide(fromHours = 48), channel, LiveTvArchive.Spills(folder.newFolder("a"))), channel))
        assertTrue(LiveTvArchive.write(file, emptyMap(), emptyMap(), now))
        assertEquals(emptyList<LiveTvProgramme>(), LiveTvArchive.read(file, channel.guideKey))
    }

    @Test fun programmesOfAReplacedMatchAreNotArchived() {
        val spill = LiveTvArchive.Spills(folder.newFolder("spill")).create()!!
        spill.write("key", now - 3 * hour, now - 2 * hour, "Weaker match")
        spill.drop("key")
        spill.write("key", now - 2 * hour, now - hour, "Better match")
        spill.finish()
        val file = File(folder.root, "archive.bin")
        assertTrue(LiveTvArchive.write(file, mapOf("key" to spill), mapOf("key" to 7), now))
        assertEquals(listOf("Better match"), LiveTvArchive.read(file, "key").map { it.title })
    }

    @Test fun noArchiveReadsAsNothing() {
        assertEquals(emptyList<LiveTvProgramme>(), LiveTvArchive.read(File(folder.root, "missing.bin"), "key"))
    }
}
