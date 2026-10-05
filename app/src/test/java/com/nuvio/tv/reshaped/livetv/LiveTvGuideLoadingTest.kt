package com.nuvio.tv.reshaped.livetv

import androidx.compose.ui.unit.Constraints
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class LiveTvGuideLoadingTest {
    private val now = Instant.parse("2026-10-02T08:15:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val window = LiveTvGuideWindow(2 * hour, 4, 4 * hour, 8)

    private fun channel(source: String, name: String = "News", id: String? = "shared") = LiveTvChannel(
        id = "$source/1", name = name, streamUrl = "https://$source.example/1.ts", tvgId = id,
        sourceId = source, guideKey = liveTvGuideKey(id, name, source),
    )

    private fun read(xml: String, channels: List<LiveTvChannel>) = readXmlTvGuide(
        xml.byteInputStream(), LiveTvGuideRequest.from(channels), now, window, ::KXmlParser,
    )

    private fun xml(title: String) = """
        <tv>
          <channel id="shared"><display-name>News</display-name></channel>
          <programme channel="shared" start="20261002080000 +0000" stop="20261002090000 +0000"><title>$title</title></programme>
        </tv>
    """.trimIndent()

    @Test fun matchingXmlTvIdsFromDifferentPlaylistsDoNotOverwriteEachOther() {
        val one = channel("one")
        val two = channel("two")
        val first = read(xml("First provider"), listOf(one))
        val second = read(xml("Second provider"), listOf(two))
        val combined = first.schedule + second.schedule
        assertEquals("First provider", combined.getValue(one.guideKey).single().title)
        assertEquals("Second provider", combined.getValue(two.guideKey).single().title)
        assertFalse(first.schedule.containsKey(two.guideKey))
    }

    @Test fun channelsMatchedByNameRemainScopedToTheirPlaylist() {
        val one = channel("one", "News HD", null)
        val two = channel("two", "News HD", null)
        assertFalse(one.guideKey == two.guideKey)
        assertEquals(listOf(one.guideKey), read(xml("Name match"), listOf(one)).schedule.keys.toList())
    }

    @Test fun renamedPlaylistChannelsInvalidateGuideMatchingEvenWithTheSameId() {
        val original = channel("one")
        val renamed = original.copy(name = "Weather", tvgName = "Weather HD")
        assertEquals(original.guideKey, renamed.guideKey)
        assertFalse(liveTvGuideMatchingKey(listOf(original)) == liveTvGuideMatchingKey(listOf(renamed)))
    }

    @Test fun severalPlaylistChannelsCanShareOneGuideId() {
        val one = channel("one")
        val two = channel("two")
        val guide = read(xml("Shared feed"), listOf(one, two))
        assertEquals(setOf(one.guideKey, two.guideKey), guide.schedule.keys)
    }

    @Test fun guidesSayWhichChannelsTheyFoundOnlyByName() {
        // The playlist assigns "shared"; a guide without that id still finds the channel by name.
        val selected = channel("one")
        val byName = read("""
            <tv>
              <channel id="news.other"><display-name>News</display-name></channel>
              <programme channel="news.other" start="20261002080000 +0000" stop="20261002090000 +0000"><title>Name guide</title></programme>
            </tv>
        """.trimIndent(), listOf(selected))
        val byId = read(xml("Assigned guide"), listOf(selected))
        assertTrue(selected.guideKey in byName.nameMatched)
        assertFalse(selected.guideKey in byId.nameMatched)
        assertTrue(selected.guideKey in byName.afterFailedRefresh().nameMatched)
    }

    @Test fun aNameMatchFillsInForAnIdWithoutProgrammes() {
        // The guide lists the assigned id, but its programmes are under another entry with the name.
        val selected = channel("one", "VRT 1", "VRT1.be")
        val guide = read("""
            <tv>
              <channel id="VRT1.be"><display-name>VRT 1</display-name></channel>
              <channel id="Een.be"><display-name>VRT 1</display-name></channel>
              <programme channel="Een.be" start="20261002080000 +0000" stop="20261002090000 +0000"><title>Named</title></programme>
            </tv>
        """.trimIndent(), listOf(selected))
        assertEquals("Named", guide.schedule.getValue(selected.guideKey).single().title)
        assertTrue(selected.guideKey in guide.nameMatched)
    }

    @Test fun theGuideChannelWithProgrammesFeedsANameShownTwice() {
        val selected = channel("one", "VRT 1", "not.in.guide")
        val guide = read("""
            <tv>
              <channel id="vrt1.hd"><display-name>VRT 1 HD</display-name></channel>
              <channel id="vrt1.be"><display-name>VRT 1</display-name></channel>
              <programme channel="vrt1.be" start="20261002080000 +0000" stop="20261002090000 +0000"><title>Listed</title></programme>
            </tv>
        """.trimIndent(), listOf(selected))
        assertEquals("Listed", guide.schedule.getValue(selected.guideKey).single().title)
    }

    @Test fun idsMatchWhicheverWayTheirAccentsAreWritten() {
        // "Één.be" with separate accent marks in the playlist, composed letters in the guide.
        val selected = channel("one", "Een", "E\u0301e\u0301n.be")
        val guide = read("""
            <tv>
              <programme channel="${"\u00c9\u00e9n.be"}" start="20261002080000 +0000" stop="20261002090000 +0000"><title>Accented</title></programme>
            </tv>
        """.trimIndent(), listOf(selected))
        assertEquals("Accented", guide.schedule.getValue(selected.guideKey).single().title)
        assertEquals(liveTvNameKey("\u00c9\u00e9n"), liveTvNameKey("E\u0301e\u0301n"))
    }

    @Test fun channelsListedAmongTheProgrammesStillMatchByName() {
        // Each <channel> right before its own programmes, as some generators write guides.
        val first = channel("one", "VRT 1", null)
        val second = channel("one", "Canvas", null).copy(id = "one/2", streamUrl = "https://one.example/2.ts")
        val guide = read("""
            <tv>
              <channel id="a"><display-name>VRT 1</display-name></channel>
              <programme channel="a" start="20261002080000 +0000" stop="20261002090000 +0000"><title>First</title></programme>
              <channel id="b"><display-name>Canvas</display-name></channel>
              <programme channel="b" start="20261002080000 +0000" stop="20261002090000 +0000"><title>Second</title></programme>
            </tv>
        """.trimIndent(), listOf(first, second))
        assertEquals("First", guide.schedule.getValue(first.guideKey).single().title)
        assertEquals("Second", guide.schedule.getValue(second.guideKey).single().title)
    }

    @Test fun aNameMatchWithTheChannelsOwnCountryTagWins() {
        val selected = channel("one", "UK: Discovery", null)
        val guide = read("""
            <tv>
              <channel id="us"><display-name>US: Discovery</display-name></channel>
              <channel id="uk"><display-name>UK | Discovery</display-name></channel>
              <programme channel="us" start="20261002080000 +0000" stop="20261002090000 +0000"><title>American</title></programme>
              <programme channel="uk" start="20261002080000 +0000" stop="20261002090000 +0000"><title>British</title></programme>
              <programme channel="us" start="20261002090000 +0000" stop="20261002100000 +0000"><title>American later</title></programme>
            </tv>
        """.trimIndent(), listOf(selected))
        assertEquals(listOf("British"), guide.schedule.getValue(selected.guideKey).map { it.title })
        // With no guide channel of its own country, another's still feeds it, as before.
        val other = read("""
            <tv>
              <channel id="us"><display-name>US: Discovery</display-name></channel>
              <programme channel="us" start="20261002080000 +0000" stop="20261002090000 +0000"><title>American</title></programme>
            </tv>
        """.trimIndent(), listOf(selected))
        assertEquals("American", other.schedule.getValue(selected.guideKey).single().title)
        assertEquals("uk", liveTvNameTag("UK: Discovery"))
        assertEquals(null, liveTvNameTag("VRT 1"))
    }

    @Test fun channelsWithTheSameLinkShareAGuide() {
        val provider = channel("one", "VRT 1", "provider.id")
        val copy = channel("two", "VRT 1", "VRT1.be").copy(streamUrl = provider.streamUrl)
        val groups = liveTvSameStreamKeys(listOf(provider, copy))
        assertEquals(listOf(listOf(provider.guideKey, copy.guideKey)), groups)
        val programmes = read(xml("Shared"), listOf(channel("x"))).schedule.values.single()
        val filled = mapOf(copy.guideKey to programmes).sharedAcrossStreams(groups)
        assertEquals(programmes, filled[provider.guideKey])
        // A channel with a guide of its own keeps it.
        val own = mapOf(provider.guideKey to programmes, copy.guideKey to emptyList())
        assertEquals(programmes, own.sharedAcrossStreams(groups)[copy.guideKey])
        assertTrue(liveTvSameStreamKeys(listOf(provider, channel("two", "VRT 1", "VRT1.be"))).isEmpty())
    }

    @Test fun exactIdWinsOverAnotherChannelsNameMatch() {
        val selected = channel("one")
        val guide = read("""
            <tv>
              <channel id="other"><display-name>News</display-name></channel>
              <channel id="shared"><display-name>Provider News</display-name></channel>
              <programme channel="other" start="20261002080000 +0000" stop="20261002090000 +0000"><title>Wrong</title></programme>
              <programme channel="shared" start="20261002080000 +0000" stop="20261002090000 +0000"><title>Exact</title></programme>
            </tv>
        """.trimIndent(), listOf(selected))
        assertEquals("Exact", guide.schedule.getValue(selected.guideKey).single().title)
    }

    @Test fun aChannelIsFedByOneGuideChannelEvenWithoutItsChannelEntry() {
        val selected = channel("one")
        val guide = read("""
            <tv>
              <channel id="news.plus1"><display-name>News</display-name></channel>
              <programme channel="news.plus1" start="20261002083000 +0000" stop="20261002093000 +0000"><title>Morning</title></programme>
              <programme channel="shared" start="20261002080000 +0000" stop="20261002090000 +0000"><title>Morning</title></programme>
              <programme channel="news.plus1" start="20261002093000 +0000" stop="20261002103000 +0000"><title>Later</title></programme>
            </tv>
        """.trimIndent(), listOf(selected))
        val programmes = guide.schedule.getValue(selected.guideKey)
        assertEquals(1, programmes.size)
        assertEquals(Instant.parse("2026-10-02T08:00:00Z").toEpochMilli(), programmes.single().startEpochMs)
    }

    @Test fun anAllDayPlaceholderNeverHidesTheProgrammesInsideIt() {
        val selected = channel("one")
        val guide = read("""
            <tv><channel id="shared"/>
              <programme channel="shared" start="20261002060000 +0000" stop="20261002120000 +0000"><title>To Be Announced</title></programme>
              <programme channel="shared" start="20261002080000 +0000" stop="20261002090000 +0000"><title>Morning</title></programme>
              <programme channel="shared" start="20261002090000 +0000" stop="20261002100000 +0000"><title>Late morning</title></programme>
            </tv>
        """.trimIndent(), listOf(selected))
        val programmes = guide.schedule.getValue(selected.guideKey)
        assertEquals(listOf("To Be Announced", "Morning", "Late morning", "To Be Announced"), programmes.map { it.title })
        programmes.zipWithNext().forEach { (a, b) -> assertTrue(a.stopEpochMs <= b.startEpochMs) }
        assertEquals("Morning", currentProgrammes(guide.schedule, setOf(selected.guideKey), now)[selected.guideKey]?.title)
    }

    @Test fun missingStopUsesTheNextStartOfTheSameChannel() {
        val selected = channel("one")
        val guide = read("""
            <tv><channel id="shared"/>
              <programme channel="shared" start="20261002080000 +0000"><title>Current</title></programme>
              <programme channel="unrelated" start="20261002083000 +0000"><title>Other</title></programme>
              <programme channel="shared" start="20261002090000 +0000" stop="20261002100000 +0000"><title>Next</title></programme>
            </tv>
        """.trimIndent(), listOf(selected))
        val programmes = guide.schedule.getValue(selected.guideKey)
        assertEquals(listOf("Current", "Next"), programmes.map { it.title })
        assertEquals(programmes[1].startEpochMs, programmes[0].stopEpochMs)
        assertEquals(hour, programmes[0].stopEpochMs - programmes[0].startEpochMs)
    }

    @Test fun truncatedXmlAndHtmlCannotReplaceASavedGuide() {
        val selected = channel("one")
        val full = xml("Saved")
        assertTrue(read(full, listOf(selected)).canReplaceSavedGuide)
        val truncated = read(full.substringBefore("</tv>"), listOf(selected))
        assertFalse(truncated.complete)
        assertFalse(truncated.canReplaceSavedGuide)
        assertFalse(read("<html><body>Error</body></html>", listOf(selected)).canReplaceSavedGuide)
        // Cut after a whole programme, and a second guide joined on and cut.
        assertFalse(read(full.substringBefore("</tv>").trimEnd(), listOf(selected)).complete)
        assertFalse(read(full + "\n" + full.substringBefore("</tv>"), listOf(selected)).complete)
        assertTrue(read(full + "\n" + full, listOf(selected)).complete)
        assertTrue(read("<tv/>", listOf(selected)).complete)
    }

    @Test fun staleFallbackKeepsProgrammesAndRequestsAnotherRefresh() {
        val selected = channel("one")
        val good = read(xml("Saved"), listOf(selected))
        val stale = good.afterFailedRefresh()
        assertEquals(good.schedule, stale.schedule)
        assertTrue(stale.refreshFailed)
        assertFalse(stale.canReplaceSavedGuide)
    }

    @Test fun malformedProviderDurationCannotCreateAnOversizedGuideCell() {
        assertEquals(0L to hour, liveTvGuideSpan(Long.MIN_VALUE, Long.MAX_VALUE, 0, hour))
        assertEquals(null, liveTvGuideSpan(hour, 0, 0, hour))
        assertEquals(null, liveTvGuideSpan(2 * hour, 3 * hour, 0, hour))
        assertEquals(0L to 10L, liveTvGuideSpan(-10, 10, 0, hour))
    }

    @Test fun longProviderProgrammeExceedsComposeConstraintsUntilClipped() {
        val stop = 90 * 24 * hour
        // The guide uses 6 dp/minute. At density 1 the old full-duration cell is 777,600 px.
        val oldWidth = (stop / 60_000 * 6).toInt()
        assertThrows(IllegalArgumentException::class.java) { Constraints.fixed(oldWidth, 56) }
        val (start, end) = liveTvGuideSpan(0, stop, 0, hour)!!
        val visibleWidth = ((end - start) / 60_000 * 6).toInt()
        assertEquals(360, Constraints.fixed(visibleWidth, 56).maxWidth)
    }

    @Test fun fasterGuidePublishesBeforeASlowFirstFeedFinishes() = runBlocking {
        withTimeout(5_000) {
            val slow = CompletableDeferred<Unit>()
            val fastPublished = CompletableDeferred<Unit>()
            val published = mutableListOf<Int>()
            val job = launch {
                loadLiveTvGuides(2, read = { index ->
                    if (index == 0) slow.await()
                    LiveTvGuide(emptyMap(), emptyMap(), emptySet())
                }, publish = { index, _ ->
                    published += index
                    if (index == 1) fastPublished.complete(Unit)
                })
            }
            fastPublished.await()
            assertEquals(listOf(1), published)
            slow.complete(Unit)
            job.join()
            assertEquals(listOf(1, 0), published)
        }
    }

    @Test fun cancellationStopsImportsWithoutPublishingLateResults() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val blocked = CompletableDeferred<Unit>()
            var published = 0
            val job = launch {
                loadLiveTvGuides(1, read = {
                    entered.complete(Unit)
                    blocked.await()
                    null
                }, publish = { _, _ -> published++ })
            }
            entered.await()
            job.cancelAndJoin()
            blocked.complete(Unit)
            assertEquals(0, published)
        }
    }

    @Test fun bareAmpersandsAndHtmlEntitiesDoNotCutTheGuideShort() {
        val selected = channel("one")
        val guide = read("""
            <tv>
              <channel id="shared"><display-name>News</display-name></channel>
              <programme channel="shared" start="20261002080000 +0000" stop="20261002090000 +0000"><title>Tom & Jerry</title></programme>
              <programme channel="shared" start="20261002090000 +0000" stop="20261002100000 +0000"><title>Caf&eacute;&nbsp;Live</title></programme>
            </tv>
        """.trimIndent(), listOf(selected))
        assertTrue(guide.complete)
        assertEquals(2, guide.schedule.getValue(selected.guideKey).size)
    }
}
