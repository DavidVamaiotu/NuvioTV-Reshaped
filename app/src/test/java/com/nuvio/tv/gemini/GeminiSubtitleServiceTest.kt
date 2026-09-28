package com.nuvio.tv.gemini

import com.nuvio.tv.ui.screens.player.PlayerSubtitleCueParser
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSubtitleServiceTest {

    @Test
    fun testLanguageLookup() {
        val spanish = GeminiTranslationPreferences.getLanguageName("es")
        assertEquals("Spanish", spanish)

        val french = GeminiTranslationPreferences.getLanguageName("fr")
        assertEquals("French", french)

        val turkish = GeminiTranslationPreferences.getLanguageName("tr")
        assertEquals("Turkish", turkish)
    }

    @Test
    fun testParseAndReconstructSrt() {
        val srtSample = """
            1
            00:00:01,000 --> 00:00:04,000
            Hello, welcome to the movie!

            2
            00:00:05,000 --> 00:00:08,500
            Enjoy the show.
        """.trimIndent()

        val cues = PlayerSubtitleCueParser.parseFromText(srtSample, "sample.srt")
        assertEquals(2, cues.size)
        assertEquals(1000L, cues[0].startTimeMs)
        assertEquals(4000L, cues[0].endTimeMs)
        assertEquals("Hello, welcome to the movie!", cues[0].text)

        assertEquals(5000L, cues[1].startTimeMs)
        assertEquals(8500L, cues[1].endTimeMs)
        assertEquals("Enjoy the show.", cues[1].text)
    }

    @Test
    fun testSupportedLanguagesListNotEmpty() {
        assertTrue(GeminiTranslationPreferences.SUPPORTED_LANGUAGES.isNotEmpty())
        assertTrue(GeminiTranslationPreferences.AVAILABLE_MODELS.contains("gemini-1.5-flash"))
    }
}
