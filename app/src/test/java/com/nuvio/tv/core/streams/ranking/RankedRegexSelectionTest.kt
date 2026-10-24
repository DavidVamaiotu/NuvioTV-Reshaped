package com.nuvio.tv.core.streams.ranking

import com.nuvio.tv.core.player.StreamAutoPlayPolicy
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class RankedRegexSelectionTest {
    private val tiers = listOf("(?s)(?=.*2160p)(?=.*remux)", "2160p", "1080p")

    @Test fun `best tier wins across addons regardless of response order`() {
        val low = stream("1080p", "AddonA")
        val middle = stream("2160p WEB", "AddonB")
        val best = stream("2160p REMUX", "AddonC")
        assertEquals(best, select(listOf(low, middle, best)))
        assertEquals(best, select(listOf(best, middle, low)))
    }

    @Test fun `matching tiers retain installed addon ordering for ties`() {
        val a = stream("2160p A", "AddonA")
        val b = stream("2160p B", "AddonB")
        assertEquals(a, select(listOf(a, b)))
        assertEquals(b, select(listOf(b, a)))
    }

    @Test fun `lower tier works if best unavailable and unmatched stays manual`() {
        val fallback = stream("1080p", "AddonA")
        assertEquals(fallback, select(listOf(stream("720p", "AddonB"), fallback)))
        assertNull(select(listOf(stream("720p", "AddonA"))))
    }

    @Test fun `ranked rules replace legacy regex only while configured`() {
        val a = stream("1080p", "AddonA")
        val b = stream("2160p", "AddonB")
        assertEquals(b, select(listOf(a, b), legacy = "1080p"))
        assertEquals(a, select(listOf(a, b), patterns = emptyList(), legacy = "1080p"))
    }

    @Test fun `invalid high priority cannot silently fall through to a lower match`() {
        assertNull(select(listOf(stream("1080p", "AddonA")), patterns = listOf("[", "1080p")))
    }

    @Test fun `source scope and selected addons are applied before ranking`() {
        val a = stream("1080p", "AddonA")
        val b = stream("2160p REMUX", "Plugin")
        assertEquals(a, select(listOf(b, a), source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY))
        val c = stream("2160p REMUX", "AddonB")
        assertEquals(a, select(listOf(c, a), selectedAddons = setOf("AddonA")))
    }

    @Test fun `selected plugins remain respected`() {
        val a = stream("1080p", "PluginA")
        val b = stream("2160p REMUX", "PluginB")
        assertEquals(a, select(listOf(b, a), selectedPlugins = setOf("PluginA")))
    }

    @Test fun `unplayable top tiers are never auto-selected`() {
        val external = stream("2160p REMUX", "AddonA").copy(url = null, externalUrl = "https://example.com/page")
        val unavailable = stream("2160p REMUX", "AddonA").copy(
            url = null, infoHash = "abcd", debridCacheStatus = StreamDebridCacheStatus(
                "torbox", "TorBox", StreamDebridCacheState.NOT_CACHED
            )
        )
        val fallback = stream("1080p", "AddonB")
        assertEquals(fallback, select(listOf(external, unavailable, fallback)))
    }

    @Test fun `filename metadata can win even when the display name omits quality`() {
        val best = stream("Play", "AddonA").copy(
            behaviorHints = StreamBehaviorHints(null, null, null, null, filename = "Movie.2160p.REMUX.mkv")
        )
        assertEquals(best, select(listOf(stream("1080p", "AddonB"), best)))
    }

    @Test fun `explicit binge continuity remains ahead of tier ranking`() {
        val nextEpisode = stream("1080p", "AddonA").copy(
            behaviorHints = StreamBehaviorHints(null, "same-group", null, null)
        )
        assertEquals(nextEpisode, select(listOf(stream("2160p REMUX", "AddonB"), nextEpisode), binge = true))
    }

    @Test fun `manual and first-stream modes retain their behavior`() {
        val a = stream("1080p", "AddonA")
        val b = stream("2160p REMUX", "AddonB")
        assertNull(select(listOf(a, b), mode = StreamAutoPlayMode.MANUAL))
        assertEquals(a, select(listOf(a, b), mode = StreamAutoPlayMode.FIRST_STREAM))
    }

    @Test fun `configuration gate accepts new rules without legacy regex`() {
        assertTrue(StreamAutoPlayPolicy.isRegexSelectionConfigured("", tiers))
        assertFalse(StreamAutoPlayPolicy.isRegexSelectionConfigured("2160p", listOf("[")))
        assertTrue(StreamAutoPlayPolicy.isRegexSelectionConfigured("", listOf(".*")))
        assertFalse(StreamAutoPlayPolicy.isRegexSelectionConfigured("", emptyList()))
    }

    @Test fun `concurrent selections never reuse the wrong compiled rules`() = runBlocking {
        val streams = listOf(stream("1080p", "AddonA"), stream("2160p", "AddonB"))
        (0 until 40).map { index ->
            async {
                val pattern = if (index % 2 == 0) "1080p" else "2160p"
                val selected = StreamAutoPlaySelector.selectAutoPlayStreamAsync(
                    streams = streams, mode = StreamAutoPlayMode.REGEX_MATCH, regexPattern = "",
                    source = StreamAutoPlaySource.ALL_SOURCES,
                    installedAddonNames = setOf("AddonA", "AddonB"),
                    selectedAddons = emptySet(), selectedPlugins = emptySet(),
                    rankedRegexPatterns = listOf(pattern)
                )
                assertEquals(pattern, selected?.name)
            }
        }.awaitAll()
        Unit
    }

    private fun select(
        streams: List<Stream>, patterns: List<String> = tiers, legacy: String = "",
        source: StreamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
        selectedAddons: Set<String> = emptySet(), selectedPlugins: Set<String> = emptySet(),
        mode: StreamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, binge: Boolean = false
    ): Stream? = StreamAutoPlaySelector.selectAutoPlayStream(
        streams, mode, legacy, source, setOf("AddonA", "AddonB", "AddonC"),
        selectedAddons, selectedPlugins, preferredBingeGroup = "same-group",
        preferBingeGroupInSelection = binge, rankedRegexPatterns = patterns
    )

    private fun stream(name: String, addon: String): Stream = Stream(
        name = name, title = null, description = null, url = "https://example.com/video",
        ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
        behaviorHints = null, addonName = addon, addonLogo = null
    )
}
