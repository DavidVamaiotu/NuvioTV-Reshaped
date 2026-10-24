package com.nuvio.tv.core.streams.ranking

import org.junit.Assert.*
import org.junit.Test

class RankedRegexSettingsCodecTest {
    @Test fun `round trip preserves order and regex escapes`() {
        val patterns = listOf("(?s)^(?!.*\\b(cam|ts)\\b).*2160p", "[\\\"]1080p[\\\"]", "line1\nline2", ".*")
        assertEquals(patterns, RankedRegexSettingsCodec.decode(RankedRegexSettingsCodec.encode(patterns)))
    }

    @Test fun `old profiles and cleared rules keep legacy behavior`() {
        assertEquals(emptyList<String>(), RankedRegexSettingsCodec.decode(null))
        assertEquals(emptyList<String>(), RankedRegexSettingsCodec.decode(RankedRegexSettingsCodec.encode(emptyList())))
    }

    @Test fun `malformed storage cannot turn on a fallback selector`() {
        val decoded = RankedRegexSettingsCodec.decode("not json")
        assertTrue(decoded.isNotEmpty())
        assertNull(RankedRegexRules.create(decoded))
        assertNull(RankedRegexRules.create(RankedRegexSettingsCodec.decode("[12]")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid edits cannot be saved`() {
        RankedRegexSettingsCodec.encode(listOf("[", "1080p"))
    }
}
