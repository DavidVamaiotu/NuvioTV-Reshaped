package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import org.junit.Assert.*
import org.junit.Test

class TorboxPreviewModelsTest {
    private val hash = "a".repeat(40)
    private val pack = TorboxPreviewPack("tt1375666:$hash-f2:v1", "tt1375666", 27205, 1,
        hash, 2, 100_000, "movies/tt1375666/release/v1/index.json", "b".repeat(64))
    private fun request(id: String? = "tt1375666", duration: Long = 100_000,
                        file: Int? = 2, source: String? = hash, type: String = "movie") =
        TorboxMovieRequest(id, type, duration, source, file)

    @Test fun exactFileMatchesEvenWithAudioTailDurationDifference() {
        assertTrue(pack.match(request(duration = 205_000))!!.exactSource)
        assertTrue(pack.match(request(id = null))!!.exactSource)
    }

    @Test fun wrongMovieAndSeriesNeverMatchEvenWhenHashesMatch() {
        assertNull(pack.match(request(id = "tt0109830")))
        assertNull(pack.match(request(type = "series")))
    }

    @Test fun differentReleaseIsOnlyACandidateAndNeedsVisualVerification() {
        assertFalse(pack.match(request(source = "c".repeat(40)))!!.exactSource)
        assertFalse(pack.match(request(id = "tmdb:27205", source = null, file = null))!!.exactSource)
        assertFalse(pack.match(request(duration = 103_000, source = null))!!.exactSource)
        assertNull(pack.match(request(duration = 400_000, source = null)))
        assertNull(pack.match(request(id = null, source = null)))
        assertNull(pack.match(request(file = 3)))
    }

    private fun timeline(): TorboxSpriteTimeline {
        val sheet = TorboxSpriteSheet("sprites/sheet-000.webp", 1600, 180, 1000, "a".repeat(64))
        return TorboxSpriteTimeline((0..4).map { i ->
            TorboxSpriteFrame(i * 10_000L, (i + 1) * 10_000L, i * 10_000L,
                sheet.path, i * 320, 0, 320, 180)
        }, mapOf(sheet.path to sheet))
    }

    @Test fun frameLookupRespectsBoundariesAndDoesNotInventTailFrames() {
        val timeline = timeline()
        assertEquals(0L, timeline.frameAt(0)!!.startMs)
        assertEquals(0L, timeline.frameAt(9_999)!!.startMs)
        assertEquals(10_000L, timeline.frameAt(10_000)!!.startMs)
        assertEquals(40_000L, timeline.frameAt(49_999)!!.startMs)
        assertNull(timeline.frameAt(-1))
        assertNull(timeline.frameAt(50_000))
    }

    @Test fun pathsMatchWholeMovieSpecificPathInsteadOfBasename() {
        val wanted = "movies/tt1375666/release/v1/sprites/sheet-000.webp"
        assertTrue(previewFileMatches("NuvioPreviews-test/$wanted", wanted, "NuvioPreviews-test"))
        assertTrue(previewFileMatches(wanted, wanted, "NuvioPreviews-test"))
        assertFalse(previewFileMatches("movies/tt0109830/release/v1/sprites/sheet-000.webp", wanted, "NuvioPreviews-test"))
        assertFalse(previewFileMatches("sheet-000.webp", wanted, "NuvioPreviews-test"))
        assertFalse(safePreviewPath("../sheet.webp"))
        assertFalse(safePreviewPath("/sheet.webp"))
        assertFalse(safePreviewPath("sprites/../sheet.webp"))
        assertFalse(safePreviewPath("https://example.test/sheet.webp"))
    }

    @Test fun invalidSpriteRectanglesAreRejected() {
        val good = timeline()
        try {
            TorboxSpriteTimeline(listOf(good.frames[0].copy(x = 1500)), good.sheets)
            fail("Expected invalid crop to fail")
        } catch (_: IllegalArgumentException) { }
        try {
            TorboxSpriteTimeline(listOf(good.frames[0], good.frames[1].copy(startMs = 11_000)), good.sheets)
            fail("Expected timeline gap to fail")
        } catch (_: IllegalArgumentException) { }
    }
}
