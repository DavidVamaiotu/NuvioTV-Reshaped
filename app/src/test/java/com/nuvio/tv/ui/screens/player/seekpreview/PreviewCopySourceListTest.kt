package com.nuvio.tv.ui.screens.player.seekpreview

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.repository.StreamSearchRequestKey
import com.nuvio.tv.data.repository.StreamSearchSessionCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreviewCopySourceListTest {
    private fun stream(name: String) = Stream(
        name = name, title = null, description = null, url = "https://cdn.example/$name.mkv",
        ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
        behaviorHints = null, addonName = "Test", addonLogo = null,
    )

    @Test fun cachedSmallerSourcesAreAvailableWithoutOpeningPlayerPanels() = runBlocking {
        val playing = stream("2160p")
        val smaller = stream("720p")
        var reads = 0
        val streams = previewCopySourceStreams(emptyList()) {
            reads++
            listOf(playing, smaller)
        }
        assertEquals(listOf(playing, smaller), streams)
        assertEquals(1, reads)
    }

    @Test fun discoveryReusesTheSearchThatSuppliedPlaybackWithoutStartingAnotherSearch() = runBlocking {
        val smaller = stream("720p")
        val cache = StreamSearchSessionCache(scope = this)
        val key = StreamSearchRequestKey(1, "movie", "tt123", null, null, "configured addons")
        var searches = 0
        cache.observe(key, forceRefresh = false) {
            searches++
            flowOf(NetworkResult.Success(listOf(AddonStreams("Test", null, listOf(smaller)))))
        }.toList()

        assertEquals(listOf(smaller), previewCopySourceStreams(emptyList()) {
            cache.peek(key)?.flatMap { it.streams }
        })
        assertEquals(1, searches)
    }

    @Test fun aPreloadedNextEpisodeAndAnOldSourcePanelAreExcluded() {
        assertTrue(previewCopyPanelStreams(
            "series|show:1:2|1|2",
            "series|show:1:1|1|1", listOf(stream("previous")),
            "series|show:1:3|1|3", listOf(stream("next")),
        ).isEmpty())
    }

    @Test fun onlyPanelResultsForThePlayingEpisodeAreMerged() {
        val current = stream("current")
        assertEquals(listOf(current), previewCopyPanelStreams(
            "series|show:1:2|1|2",
            "series|show:1:2|1|2", listOf(current),
            "series|show:1:3|1|3", listOf(stream("next")),
        ))
        assertEquals(listOf(current), previewCopyPanelStreams(
            "series|show:1:2|1|2", null, emptyList(),
            "series|show:1:2|1|2", listOf(current),
        ))
    }

    @Test fun currentPanelResultsAndCachedResultsAreDeduplicated() = runBlocking {
        val current = stream("1080p")
        val smaller = stream("720p")
        assertEquals(listOf(current, smaller), previewCopySourceStreams(listOf(current)) { listOf(current, smaller) })
        assertEquals(listOf(current), previewCopySourceStreams(listOf(current)) { null })
    }

    @Test fun missingOrEmptyResultsKeepDiscoveryWaiting() = runBlocking {
        assertNull(previewCopySourceStreams(emptyList()) { null })
        assertNull(previewCopySourceStreams(emptyList()) { emptyList() })
    }

    @Test(expected = CancellationException::class)
    fun aCancelledCacheReadStopsDiscovery(): Unit = runBlocking {
        previewCopySourceStreams(emptyList()) { throw CancellationException("source replaced") }
        Unit
    }
}
