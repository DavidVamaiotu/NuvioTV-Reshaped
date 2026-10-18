package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppYouTubeExtractorVideoSizeTest {
    private val extractor = InAppYouTubeExtractor()

    @Test
    fun `preview cap excludes 4K and ultrawide streams even below 1080 pixels tall`() {
        val hd = video(1920, 1080)
        val sd = video(1280, 720)
        val candidates = listOf(video(3840, 2160), video(2560, 1072), hd, sd)
        assertEquals(listOf(hd, sd), extractor.capVideoSize(candidates, 1080))
    }

    @Test
    fun `oversized only direct streams fall through instead of bypassing the cap`() {
        assertTrue(extractor.capVideoSize(listOf(video(3840, 2160)), 1080).isEmpty())
    }

    @Test
    fun `unlimited mode preserves all stream candidates`() {
        val candidates = listOf(video(3840, 2160), video(1920, 1080))
        assertEquals(candidates, extractor.capVideoSize(candidates, Int.MAX_VALUE))
    }

    private fun video(width: Int, height: Int) = StreamCandidate(
        client = "test", priority = 0, url = "$width-$height", score = height.toDouble(),
        hasN = false, itag = "test", height = height, fps = 24, ext = "mp4", width = width
    )
}
