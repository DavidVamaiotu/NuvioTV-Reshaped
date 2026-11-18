package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.SubtitleDto
import org.junit.Assert.*
import org.junit.Test

class StreamSubtitleLabelCompatibilityTest {
    @Test
    fun labelledSubtitleRetainsSelectionIdentityLanguageAndAuthenticationHeaders() {
        val headers = mapOf("Authorization" to "Bearer example")
        val dto = SubtitleDto(id = "stable-selection-id", url = "https://example.invalid/sub.srt", lang = "eng", label = "  English SDH  ", headers = headers)
        val subtitle = StreamDto(url = "https://example.invalid/video", subtitles = listOf(dto)).toDomain("Addon", "logo").subtitles.single()
        assertEquals("English SDH", subtitle.label)
        assertEquals("stable-selection-id", subtitle.id)
        assertEquals(dto.url, subtitle.url)
        assertEquals("eng", subtitle.lang)
        assertEquals(headers, subtitle.headers)
        assertTrue(subtitle.isStreamProvided)
        assertEquals("Addon", subtitle.addonName)
    }

    @Test
    fun blankLabelFallsBackWithoutLosingUrlBasedIdentityAndInvalidEntriesStayFiltered() {
        val valid = SubtitleDto(id = "", url = "https://example.invalid/fallback.srt", lang = "", label = "  ")
        val invalid = valid.copy(url = "")
        val subtitles = StreamDto(url = "https://example.invalid/video", subtitles = listOf(invalid, valid)).toDomain("Addon", null).subtitles
        assertEquals(1, subtitles.size)
        assertNull(subtitles.single().label)
        assertEquals(valid.url, subtitles.single().id)
        assertEquals("Unknown", subtitles.single().lang)
    }
}
