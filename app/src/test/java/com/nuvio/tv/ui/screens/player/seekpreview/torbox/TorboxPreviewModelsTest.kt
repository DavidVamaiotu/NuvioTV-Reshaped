package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

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

    @Test fun anyReleaseOfTheSameMovieIsAcceptedRegardlessOfRuntimeOrFileIndex() {
        assertFalse(pack.match(request(source = "c".repeat(40)))!!.exactSource)
        assertFalse(pack.match(request(id = "tmdb:27205", source = null, file = null))!!.exactSource)
        assertFalse(pack.match(request(duration = 103_000, source = null))!!.exactSource)
        assertFalse(pack.match(request(duration = 7_200_000, source = null, file = null))!!.exactSource)
        assertFalse(pack.match(request(duration = 50_000, source = null))!!.exactSource)
        assertNull(pack.match(request(id = null, source = null)))
        assertFalse(pack.match(request(file = 3))!!.exactSource)
        assertFalse(pack.match(request(file = null))!!.exactSource)
        assertFalse(pack.match(request(id = "movie:tmdb:27205", source = null))!!.exactSource)
        assertFalse(pack.match(request(id = "imdb:tt1375666", source = null))!!.exactSource)
    }

    @Test fun bestMatchPrefersAnExactFileThenRuntimeThenRevision() {
        val movie = request(duration = 100_000)
        val exact = pack.copy(durationMs = 200_000).match(movie)!!
        val closest = pack.copy(id = "closest", sourceHash = "c".repeat(40), durationMs = 101_000).match(movie)!!
        val newestButFurtherAway = pack.copy(id = "newest", sourceHash = "d".repeat(40),
            revision = 99, durationMs = 150_000).match(movie)!!
        val revisedClosest = closest.copy(pack = closest.pack.copy(revision = 2))
        val candidates = listOf(newestButFurtherAway, closest, exact, revisedClosest)
        assertEquals(listOf(exact, revisedClosest, closest, newestButFurtherAway),
            candidates.sortedWith(torboxPreviewMatchOrder(movie)))
    }

    @Test fun aMissingDurationOrAnUnknownMovieWithoutAnExactSourceStillCannotBeMatched() {
        assertNull(pack.match(request(duration = 0)))
        assertNull(pack.match(request(id = null, file = null)))
        assertNull(pack.match(request(id = "unrecognized", source = null)))
    }

    @Test fun tmdbRequestsUseCachedImdbForCatalogsWithoutTmdbIds() = runBlocking {
        val resolved = resolveTorboxMovieRequest(request(id = "tmdb:27205", source = null),
            cachedImdb = { "tt1375666" }, tmdbToImdb = { error("Cache must win") })
        assertNotNull(pack.copy(tmdbId = null).match(resolved))
    }

    @Test fun tmdbRequestsReuseExistingResolverWhenMetadataIsMissing() = runBlocking {
        var calls = 0
        val resolved = resolveTorboxMovieRequest(request(id = "tmdb:27205", source = null),
            cachedImdb = { null }, tmdbToImdb = { assertEquals(27205, it); calls++; "tt1375666" })
        assertEquals(1, calls)
        assertNotNull(pack.copy(tmdbId = null).match(resolved))
        val missing = resolveTorboxMovieRequest(request(id = "tmdb:27205", source = null),
            cachedImdb = { "invalid" }, tmdbToImdb = { null })
        assertNull(pack.copy(tmdbId = null).match(missing))
    }

    @Test fun imdbRequestsAndUnsupportedContentDoNotCallMetadataServices() = runBlocking {
        for (r in listOf(request(), request(id = "tmdb:27205", type = "series"), request(duration = 0))) {
            assertEquals(r, resolveTorboxMovieRequest(r, { error("Unexpected cache lookup") }, { error("Unexpected lookup") }))
        }
    }

    @Test fun identifierResolutionPreservesCancellation() = runBlocking {
        try {
            resolveTorboxMovieRequest(request(id = "tmdb:27205"), { null }, { throw CancellationException() })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
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

    @Test fun otherReleasesMapAcrossTheWholePlaybackTimeline() {
        val timeline = timeline()
        val longer = TorboxPreviewTiming(100_000, 50_000, exactSource = false)
        val center = timeline.frameAt(longer.sourcePosition(30_000))!!
        assertEquals(10_000L, center.startMs)
        assertEquals(20_000L, longer.cueStart(center))
        assertEquals(40_000L, longer.cueEnd(center))
        val last = timeline.frameAt(longer.sourcePosition(99_999))!!
        assertEquals(40_000L, last.startMs)
        assertEquals(100_000L, longer.cueEnd(last))

        val shorter = TorboxPreviewTiming(25_000, 50_000, exactSource = false)
        val shortLast = timeline.frameAt(shorter.sourcePosition(24_999))!!
        assertEquals(40_000L, shortLast.startMs)
        assertEquals(20_000L, shorter.cueStart(shortLast))
        assertEquals(25_000L, shorter.cueEnd(shortLast))
    }

    @Test fun exactFilesKeepTheirTimestampsAndHoldTheFinalImageForLongerAudioTails() {
        val timeline = timeline()
        val timing = TorboxPreviewTiming(100_000, 50_000, exactSource = true)
        assertEquals(30_000L, timing.sourcePosition(30_000))
        val last = timeline.frameAt(timing.sourcePosition(99_999))!!
        assertEquals(40_000L, timing.cueStart(last))
        assertEquals(100_000L, timing.cueEnd(last))
    }

    @Test fun fractionalRuntimeScalingNeverLeavesAGapInTheDisplayedCue() {
        val timeline = timeline()
        for (duration in listOf(33_333L, 77_777L)) {
            val timing = TorboxPreviewTiming(duration, 50_000, exactSource = false)
            for (position in 0L until duration) {
                val frame = timeline.frameAt(timing.sourcePosition(position))!!
                assertTrue(timing.cueStart(frame) <= position)
                assertTrue(timing.cueEnd(frame) > position)
            }
        }
    }

    @Test fun verifiedAlignmentReplacesTheRuntimeEstimateWithoutHidingEitherEdge() {
        val timeline = timeline()
        val timing = TorboxPreviewTiming(100_000, 50_000, exactSource = false)
        assertEquals(15_000L, timing.sourcePosition(30_000))
        val mapping = TorboxVisualMapping(scale = 1.0, offsetMs = -5_000.0)
        assertEquals(25_000L, timing.sourcePosition(30_000, mapping))
        assertEquals(15_000L, timing.playbackPosition(10_000, mapping))
        val first = timeline.frameAt(timing.sourcePosition(0, mapping))!!
        assertEquals(0L, timing.cueStart(first.startMs, mapping))
        assertTrue(timing.cueEnd(first.startMs, first.endMs, mapping) > 0)
        val last = timeline.frameAt(timing.sourcePosition(99_999, mapping))!!
        assertTrue(timing.cueStart(last.startMs, mapping) <= 99_999L)
        assertTrue(timing.cueEnd(last.startMs, last.endMs, mapping) > 99_999L)
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
