package com.nuvio.tv.core.player.recovery

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutoSourceRecoveryPlanTest {
    private fun stream(name: String, addon: String = "A") = Stream(
        name, null, null, "https://example.com/$addon/$name", null, null, null,
        null, null, addon, null
    )

    private fun plan(
        streams: List<Stream>, mode: StreamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
        ranked: List<String> = emptyList(), regex: String = "", selected: Set<String> = emptySet(),
        binge: String? = null, bingeOnly: Boolean = false
    ) = AutoSourceRecoveryPlan(
        streams, mode, regex, ranked, StreamAutoPlaySource.ALL_SOURCES, linkedSetOf("A", "B"),
        selected, emptySet(), binge, bingeOnly, streams.firstOrNull(), 1, "movie"
    )

    private suspend fun order(plan: AutoSourceRecoveryPlan): List<Stream> {
        val attempted = mutableSetOf<String>()
        return buildList {
            repeat(plan.streams.size + 1) {
                val next = plan.next(attempted) ?: return@buildList
                assertTrue(attempted.add(recoverySourceKey(next)))
                add(next)
            }
            fail("Recovery repeated a source")
        }
    }

    @Test fun `first stream retries follow original order exactly`() = runBlocking {
        val streams = listOf(stream("third-name"), stream("first-name", "B"), stream("second-name"))
        assertEquals(streams, order(plan(streams)))
    }

    @Test fun `ranked regex exhausts each priority and preserves ties across addons`() = runBlocking {
        val low = stream("1080p")
        val topA = stream("2160p REMUX", "A")
        val topB = stream("2160p REMUX", "B")
        val middle = stream("2160p WEB")
        val unmatched = stream("720p")
        assertEquals(listOf(topB, topA, middle, low), order(plan(
            listOf(low, topB, unmatched, middle, topA), StreamAutoPlayMode.REGEX_MATCH,
            listOf("(?s)(?=.*2160p)(?=.*REMUX)", "2160p", "1080p")
        )))
    }

    @Test fun `legacy regex retries only matching sources in original order`() = runBlocking {
        val a = stream("1080p A")
        val b = stream("1080p B", "B")
        assertEquals(listOf(a, b), order(plan(listOf(stream("720p"), a, b), StreamAutoPlayMode.REGEX_MATCH, regex = "1080p")))
    }

    @Test fun `retry never broadens selected addon filters`() = runBlocking {
        val allowed = stream("1080p", "A")
        assertEquals(listOf(allowed), order(plan(listOf(stream("2160p", "B"), allowed), selected = setOf("A"))))
    }

    @Test fun `binge preference remains ahead of ordinary source order`() = runBlocking {
        val ordinary = stream("ordinary")
        val group1 = stream("group 1").copy(behaviorHints = StreamBehaviorHints(null, "group", null, null))
        val group2 = stream("group 2", "B").copy(behaviorHints = StreamBehaviorHints(null, "group", null, null))
        assertEquals(listOf(group1, group2, ordinary), order(plan(listOf(ordinary, group1, group2), binge = "group")))
    }

    @Test fun `binge continuity cannot bypass selected source filters during retries`() = runBlocking {
        val excluded = stream("excluded", "B").copy(behaviorHints = StreamBehaviorHints(null, "group", null, null))
        val allowedGroup = stream("allowed group").copy(behaviorHints = StreamBehaviorHints(null, "group", null, null))
        val allowedFallback = stream("allowed fallback")
        assertEquals(listOf(allowedGroup, allowedFallback), order(plan(
            listOf(excluded, allowedFallback, allowedGroup), selected = setOf("A"), binge = "group"
        )))
    }

    @Test fun `binge only never tries unrelated sources`() = runBlocking {
        val group = stream("group").copy(behaviorHints = StreamBehaviorHints(null, "group", null, null))
        assertEquals(listOf(group), order(plan(listOf(stream("ordinary"), group), binge = "group", bingeOnly = true)))
    }

    @Test fun `invalid ranked configuration stops rather than trying unmatched fallback`() = runBlocking {
        assertNull(plan(listOf(stream("1080p")), StreamAutoPlayMode.REGEX_MATCH, listOf("[", "1080p")).next(emptySet()))
    }

    @Test fun `duplicates and exact cached URL aliases are excluded`() = runBlocking {
        val initial = stream("initial")
        val hashAlias = initial.copy(infoHash = "abc", fileIdx = 1)
        val next = stream("next")
        val attempted = setOf(recoverySourceKey(initial))
        assertEquals(next, plan(listOf(initial, hashAlias, next)).next(attempted))
    }

    @Test fun `signed URL changes keep identity for same provider and file`() {
        val a = stream("first").copy(infoHash = "ABC", fileIdx = 3)
        val b = a.copy(url = "https://example.com/refreshed", infoHash = "abc")
        assertEquals(recoverySourceKey(a), recoverySourceKey(b))
        assertEquals(recoverySourceKey(a), recoverySourceKey(b.copy(
            behaviorHints = StreamBehaviorHints(null, null, null, null, filename = "resolved-name.mkv")
        )))
        assertNotEquals(recoverySourceKey(a), recoverySourceKey(b.copy(fileIdx = 4)))
        assertNotEquals(recoverySourceKey(a), recoverySourceKey(b.copy(addonName = "B")))
    }

    @Test fun `cached P2P identity is stable when addon name was not saved`() = runBlocking {
        val cached = stream("cached", "").copy(url = null, infoHash = "ABC", fileIdx = 1)
        val duplicate = cached.copy(addonName = "A", name = "fresh metadata", infoHash = "abc")
        val other = stream("other")
        assertEquals(recoverySourceKey(cached), recoverySourceKey(duplicate))
        assertEquals(other, plan(listOf(duplicate, other)).next(setOf(recoverySourceKey(cached))))
    }

    @Test fun `handoff is single use and checked against profile and video`() {
        val p = plan(listOf(stream("one")))
        val token = AutoSourceRecoveryHandoff.put(p)
        assertEquals(p, AutoSourceRecoveryHandoff.take(token, 1, "movie"))
        assertNull(AutoSourceRecoveryHandoff.take(token, 1, "movie"))
        assertNull(AutoSourceRecoveryHandoff.take(AutoSourceRecoveryHandoff.put(p), 2, "movie"))
        assertNull(AutoSourceRecoveryHandoff.take(AutoSourceRecoveryHandoff.put(p), 1, "other"))
    }

    @Test fun `handoff preserves consumed resolution budget and attempt count`() {
        val p = plan(listOf(stream("one"))).copy(startupState = StartupSourceRecovery.State(0, 6_000, 2))
        val claimed = AutoSourceRecoveryHandoff.take(AutoSourceRecoveryHandoff.put(p), 1, "movie")!!
        val state = claimed.startupState!!
        assertEquals(2, state.attempts)
        assertEquals(6_000L, state.attemptStartedAtMs - state.sessionStartedAtMs)
    }

    @Test fun `abandoned handoffs are bounded`() {
        val p = plan(listOf(stream("one")))
        val old = AutoSourceRecoveryHandoff.put(p)
        repeat(4) { AutoSourceRecoveryHandoff.put(p) }
        assertNull(AutoSourceRecoveryHandoff.take(old, 1, "movie"))
    }
}
