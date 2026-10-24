package com.nuvio.tv.core.streams.ranking

import org.junit.Assert.*
import org.junit.Test

class RankedRegexRulesTest {
    @Test fun `higher priority beats arrival order and multiple lower matches`() {
        val rules = RankedRegexRules.create(listOf("remux", "2160p", "web", "1080p"))!!
        assertEquals(
            listOf("1080p remux", "2160p web", "1080p web", "1080p"),
            rules.rank(listOf("1080p web", "1080p", "2160p web", "1080p remux", "720p")) { it }
        )
    }

    @Test fun `ties preserve original order including duplicates`() {
        val rules = RankedRegexRules.create(listOf("2160p", "1080p"))!!
        assertEquals(listOf("2160p B", "2160p A", "2160p B"),
            rules.rank(listOf("2160p B", "2160p A", "2160p B")) { it })
    }

    @Test fun `lower priorities are fallback tiers and no match stays empty`() {
        val rules = RankedRegexRules.create(listOf("remux", "1080p"))!!
        assertEquals(listOf("1080p"), rules.rank(listOf("720p", "1080p")) { it })
        assertEquals(emptyList<String>(), rules.rank(listOf("720p")) { it })
    }

    @Test fun `lookaheads and inline flags retain native semantics`() {
        val rules = RankedRegexRules.create(listOf("(?s)^(?!.*\\b(cam|ts)\\b)(?=.*2160p)(?=.*remux).*$", "1080p"))!!
        assertEquals(listOf("2160P\nREMUX", "1080p"),
            rules.rank(listOf("2160p remux cam", "1080p", "2160P\nREMUX")) { it })
    }

    @Test fun `negative lookahead is not converted to an unrelated global exclusion`() {
        // Only reject cam at the beginning, not an unrelated occurrence later in the text.
        val rules = RankedRegexRules.create(listOf("^(?!cam).*1080p"))!!
        assertEquals(listOf("1080p camera documentary"),
            rules.rank(listOf("cam 1080p", "1080p camera documentary")) { it })
    }

    @Test fun `one invalid rule fails the whole configuration`() {
        assertNull(RankedRegexRules.create(listOf("[", "1080p")))
        assertNull(RankedRegexRules.create(listOf("1080p", "[")))
        assertNull(RankedRegexRules.create(listOf("")))
        assertNull(RankedRegexRules.create(emptyList()))
        assertNull(RankedRegexRules.create(List(21) { "1080p" }))
        assertNull(RankedRegexRules.create(listOf("a".repeat(2049))))
    }

    @Test fun `explicit catch all is allowed and matches any source`() {
        val rules = RankedRegexRules.create(listOf("2160p", ".*"))!!
        assertEquals(listOf("2160p", "720p"), rules.rank(listOf("720p", "2160p")) { it })
    }

    @Test(timeout = 2000) fun `pathological backtracking aborts without selecting a lower tier`() {
        val rules = RankedRegexRules.create(listOf("(a+)+$", "1080p"))!!
        assertNull(rules.rank(listOf("a".repeat(2000) + "!", "1080p")) { it })
    }

    @Test fun `oversized source text fails closed instead of silently truncating`() {
        val rules = RankedRegexRules.create(listOf("2160p", "1080p"))!!
        assertNull(rules.rank(listOf("1080p", "x".repeat(32769) + "2160p")) { it })
    }

    @Test fun `mixed large result set gets globally ranked without mutating input`() {
        val rules = RankedRegexRules.create(listOf("2160p remux", "2160p", "1080p"))!!
        val input = (0 until 1500).map { "1080p source $it" } + "2160p web" + "2160p remux"
        val ranked = rules.rank(input) { it }!!
        assertEquals("2160p remux", ranked.first())
        assertEquals("2160p web", ranked[1])
        assertEquals("1080p source 0", input.first())
        assertEquals(input.take(1500), ranked.drop(2))
    }

    @Test fun `large ordinary twenty rule batch is not rejected by source count`() {
        val rules = RankedRegexRules.create((1..19).map { "missing-tier-$it" } + "1080p")!!
        val input = List(1500) { realisticSource(it) }
        assertEquals(input, rules.rank(input) { it })
    }

    @Test fun `ordinary unanchored lookaheads retain valid fallback matches`() {
        val rules = RankedRegexRules.create(listOf("(?s)(?=.*2160p)(?=.*remux)", "2160p", "1080p"))!!
        val input = List(200) { realisticSource(it) }
        assertEquals(input, rules.rank(input) { it })
    }

    @Test fun `whole pass deadline still fails closed`() {
        val rules = RankedRegexRules.create(listOf("1080p"))!!
        var ticks = 0L
        assertNull(rules.rank(listOf("1080p", "1080p"), nanoTime = { ticks++ * 1_000_000_000L }) { it })
    }

    @Test(expected = IllegalStateException::class)
    fun `caller cancellation is propagated rather than converted to no match`() {
        val rules = RankedRegexRules.create(listOf("(a+)+$"))!!
        var checkpoints = 0
        rules.rank(listOf("a".repeat(2000) + "!"), checkCancelled = {
            // The second check occurs inside matching, after the candidate-start check.
            if (++checkpoints == 2) throw IllegalStateException("cancelled")
        }) { it }
    }

    private fun realisticSource(index: Int): String =
        "Torrentio TorBox cached\nExample.Movie.2026.1080p.WEB-DL.DDP5.1.H.264-GROUP\n" +
            "English subtitles\nSize: 8.4GB\nhttps://example.com/video/1080p.mkv?token=" + "x".repeat(200) + index
}
