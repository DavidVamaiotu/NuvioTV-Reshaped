package com.nuvio.tv.ui.screens.player.autosync

import androidx.media3.common.C
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reliability, accuracy, and compatibility test suite for hard-to-lock AutoSync scenarios:
 * PGS phase anomalies, forced/unflagged-forced/sparse subtitles, low-dialogue films, SDH
 * mismatches, unbracketed extra cues & TV recaps, large reference gaps, sub-100 ms delay
 * refinement, missing MKV CueDuration, 24/23.976 FPS drift, non-standard affine drift,
 * wide >180s recap offsets, malformed SRTs, sparse live references, and zero-false-positive
 * safety checks.
 */
class AutoSyncHardCasesReliabilityTest {

    @Test
    fun pgsBackToBackWithoutClearsDoesNotInvertPhaseAndLocksEndToEnd() = runBlocking {
        val trueDialogue = realisticMovieCues(count = 300, startMs = 15_000L, seed = 101)
        // Build realistic Blu-ray PGS events:
        // - Normally (start, end) display+clear pairs
        // - Every 12th cue: back-to-back transition (omit clear display set -> odd run!)
        // - Every 20th cue: add an 80 ms stacked multi-object update inside the display
        val pgsEvents = mutableListOf<Pair<Long, Long>>()
        for (i in trueDialogue.indices) {
            val cue = trueDialogue[i]
            val backToBack = (i % 12 == 5) && i + 1 < trueDialogue.size &&
                (trueDialogue[i + 1].startTimeMs - cue.startTimeMs) in 1_000L..5_500L
            pgsEvents += cue.startTimeMs to 0L
            if (i % 20 == 9 && cue.endTimeMs - cue.startTimeMs >= 1_200L) {
                pgsEvents += (cue.startTimeMs + 80L) to 0L
            }
            if (!backToBack) {
                pgsEvents += cue.endTimeMs to 0L
            }
        }

        val mkv = buildMkv(
            tracks = listOf(
                MkvTrackSpec(
                    trackNumber = 3,
                    codecId = "S_HDMV/PGS",
                    language = "eng",
                    name = "English PGS",
                    events = pgsEvents,
                ),
            ),
        )

        val trueShiftMs = 3_420L
        val targetCues = trueDialogue.map { cue ->
            SubtitleSyncCue(cue.startTimeMs - trueShiftMs, cue.endTimeMs - trueShiftMs, cue.text)
        }
        val srtBody = buildSrt(targetCues)

        serveMkvAndSrt(mkv, "/pgs-anomalies.mkv", srtBody, "/pgs-ext.srt") { mkvUrl, srtUrl ->
            val indexed = EmbeddedSubtitleTimelineLoader.load(mkvUrl, emptyMap())
            assertNotNull(indexed)
            val pgsTrack = indexed!!.tracks.single()
            // Verify phase stayed aligned across the last third of the movie (not inverted!).
            val lastQuarterRef = trueDialogue.takeLast(50)
            var matchedStarts = 0
            for (expected in lastQuarterRef) {
                if (pgsTrack.cues.any { abs(it.startTimeMs - expected.startTimeMs) <= 150L }) {
                    matchedStarts++
                }
            }
            assertTrue(
                "PGS phase inverted in last quarter: only $matchedStarts/50 matched",
                matchedStarts >= 42,
            )

            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = mkvUrl,
                selectedSubtitleUrl = srtUrl,
                selectedSubtitleHeaders = emptyMap(),
                preferredLanguage = "en",
            )
            assertNotNull("Expected confident sync against anomalous PGS track", resolved)
            assertTrue(resolved!!.timeline.confident)
            assertTrue(
                "Expected offset near $trueShiftMs, got ${resolved.timeline.alignmentInterceptMs}",
                abs(resolved.timeline.alignmentInterceptMs - trueShiftMs) <= 50.0,
            )
        }
    }

    @Test
    fun pgsWithSingleFrameCueDurationsStillPairsProperly() = runBlocking {
        val trueDialogue = realisticMovieCues(count = 220, startMs = 20_000L, seed = 202)
        // Every PGS event has a bogus 1-frame 41 ms CueDuration (common muxer artifact).
        val pgsEvents = mutableListOf<Pair<Long, Long>>()
        for (cue in trueDialogue) {
            pgsEvents += cue.startTimeMs to 41L
            pgsEvents += cue.endTimeMs to 41L
        }

        val mkv = buildMkv(
            tracks = listOf(
                MkvTrackSpec(
                    trackNumber = 3,
                    codecId = "S_HDMV/PGS",
                    language = "eng",
                    name = "English PGS",
                    events = pgsEvents,
                ),
            ),
        )
        val trueShiftMs = -2_185L
        val targetCues = trueDialogue.map { cue ->
            SubtitleSyncCue(cue.startTimeMs - trueShiftMs, cue.endTimeMs - trueShiftMs, cue.text)
        }

        serveMkvAndSrt(mkv, "/pgs-41ms.mkv", buildSrt(targetCues), "/pgs-41ms.srt") { mkvUrl, srtUrl ->
            val indexed = EmbeddedSubtitleTimelineLoader.load(mkvUrl, emptyMap())!!
            val cues = indexed.tracks.single().cues
            assertEquals("Expected paired PGS cue count", trueDialogue.size, cues.size)
            assertTrue(
                "Expected real display->clear durations, got ${cues[10].endTimeMs - cues[10].startTimeMs}ms",
                cues[10].endTimeMs - cues[10].startTimeMs >= 800L,
            )

            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = mkvUrl,
                selectedSubtitleUrl = srtUrl,
                selectedSubtitleHeaders = emptyMap(),
                preferredLanguage = "en",
            )
            assertNotNull(resolved)
            assertTrue(resolved!!.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentInterceptMs - trueShiftMs) <= 30.0)
        }
    }

    @Test
    fun forcedPgsTrackAlongsideFullSrtPeerPairsAndSyncsForcedExternal() = runBlocking {
        val fullDialogue = realisticMovieCues(count = 1_200, startMs = 15_000L, seed = 303)
        // 35 forced lines spread across a 90-minute movie (~0.4 cues/min density!).
        val forcedDialogue = (0 until 35).map { i -> fullDialogue[i * 33 + 10] }
        val forcedPgsEvents = mutableListOf<Pair<Long, Long>>()
        for (cue in forcedDialogue) {
            forcedPgsEvents += cue.startTimeMs to 0L
            forcedPgsEvents += cue.endTimeMs to 0L
        }

        val mkv = buildMkv(
            tracks = listOf(
                MkvTrackSpec(
                    trackNumber = 3,
                    codecId = "S_TEXT/UTF8",
                    language = "eng",
                    name = "English Full",
                    events = fullDialogue.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) },
                ),
                MkvTrackSpec(
                    trackNumber = 4,
                    codecId = "S_HDMV/PGS",
                    language = "eng",
                    name = "English Forced",
                    forced = true,
                    events = forcedPgsEvents,
                ),
            ),
        )

        val trueShiftMs = 4_320L
        val externalForced = forcedDialogue.map { cue ->
            SubtitleSyncCue(cue.startTimeMs - trueShiftMs, cue.endTimeMs - trueShiftMs, cue.text)
        }

        serveMkvAndSrt(mkv, "/forced-pgs.mkv", buildSrt(externalForced), "/forced-ext.srt") { mkvUrl, srtUrl ->
            val indexed = EmbeddedSubtitleTimelineLoader.load(mkvUrl, emptyMap())!!
            val forcedTrack = indexed.tracks.first { (it.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 }
            assertEquals("Forced PGS must still pair despite full SRT peer", forcedDialogue.size, forcedTrack.cues.size)

            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = mkvUrl,
                selectedSubtitleUrl = srtUrl,
                selectedSubtitleHeaders = emptyMap(),
                preferredLanguage = "en",
            )
            assertNotNull("Expected forced external SRT to sync against forced PGS track", resolved)
            assertTrue(resolved!!.timeline.confident)
            assertTrue(
                "Expected offset near $trueShiftMs, got ${resolved.timeline.alignmentInterceptMs}",
                abs(resolved.timeline.alignmentInterceptMs - trueShiftMs) <= 30.0,
            )
        }
    }

    @Test
    fun forcedExternalSubtitleLocksAgainstUnflaggedForcedEmbeddedTrack() = runBlocking {
        val fullDialogue = realisticMovieCues(count = 1_100, startMs = 18_000L, seed = 404)
        val unflaggedForced = (0 until 40).map { i -> fullDialogue[i * 26 + 5] }

        val mkv = buildMkv(
            tracks = listOf(
                MkvTrackSpec(
                    trackNumber = 3,
                    codecId = "S_TEXT/UTF8",
                    language = "eng",
                    name = "English",
                    events = fullDialogue.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) },
                ),
                MkvTrackSpec(
                    trackNumber = 4,
                    codecId = "S_TEXT/UTF8",
                    language = "eng",
                    name = "English 2", // intentionally NOT labeled forced and FlagForced = 0!
                    forced = false,
                    events = unflaggedForced.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) },
                ),
            ),
        )

        val trueShiftMs = -6_150L
        val externalForced = unflaggedForced.map { cue ->
            SubtitleSyncCue(cue.startTimeMs - trueShiftMs, cue.endTimeMs - trueShiftMs, cue.text)
        }

        serveMkvAndSrt(mkv, "/unflagged-forced.mkv", buildSrt(externalForced), "/unflagged-ext.srt") { mkvUrl, srtUrl ->
            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = mkvUrl,
                selectedSubtitleUrl = srtUrl,
                selectedSubtitleHeaders = emptyMap(),
                preferredLanguage = "en",
            )
            assertNotNull("Expected lock against unflagged forced track", resolved)
            assertTrue(resolved!!.timeline.confident)
            assertTrue(
                "Expected offset near $trueShiftMs, got ${resolved.timeline.alignmentInterceptMs}",
                abs(resolved.timeline.alignmentInterceptMs - trueShiftMs) <= 30.0,
            )
        }
    }

    @Test
    fun forcedExternalSubtitleLocksDirectlyAgainstFullDialogueReferenceOnly() {
        val fullRef = realisticMovieCues(count = 1_500, startMs = 25_000L, seed = 505)

        // Case A: 32 forced cues with constant delay +12,480 ms (DP gap bridge or sparse matcher)
        val forced32 = (0 until 32).map { i -> fullRef[i * 45 + 12] }
        val delayShiftMs = 12_480L
        val targetDelay = forced32.map { cue ->
            SubtitleSyncCue(cue.startTimeMs - delayShiftMs, cue.endTimeMs - delayShiftMs, cue.text)
        }
        val resDelay = AutoSyncTimelineRetimer.retime(
            reference = fullRef,
            target = targetDelay,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
        )
        assertNotNull("Expected forced-vs-full delay lock", resDelay)
        assertTrue("Expected confident forced delay lock: ${resDelay!!.rejectReason}", resDelay.confident)
        assertTrue(
            "Expected offset near $delayShiftMs, got ${resDelay.alignmentInterceptMs}",
            abs(resDelay.alignmentInterceptMs - delayShiftMs) <= 35.0,
        )

        // Case B: 18 clustered forced cues with >350-cue gaps (forces sparse-target-delay fallback!)
        val clusteredIndices = listOf(
            15, 22, 31, 45, 58, 70,
            450, 462, 478, 495, 510, 525,
            950, 968, 985, 1_360, 1_390, 1_420,
        )
        val forcedClustered = clusteredIndices.map { fullRef[it] }
        val clusteredShiftMs = 19_640L
        val targetClustered = forcedClustered.map { cue ->
            SubtitleSyncCue(cue.startTimeMs - clusteredShiftMs, cue.endTimeMs - clusteredShiftMs, cue.text)
        }
        val resClustered = AutoSyncTimelineRetimer.retime(
            reference = fullRef,
            target = targetClustered,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
        )
        assertNotNull("Expected sparse-target-delay lock on >350-cue gaps", resClustered)
        assertTrue("Expected confident sparse-target-delay lock: ${resClustered!!.rejectReason}", resClustered.confident)
        assertEquals("sparse-target-delay", resClustered.alignmentSource)
        assertTrue(
            "Expected offset near $clusteredShiftMs, got ${resClustered.alignmentInterceptMs}",
            abs(resClustered.alignmentInterceptMs - clusteredShiftMs) <= 35.0,
        )

        // Case C: Clustered forced cues with PAL/NTSC 25/23.976 FPS drift + 3,200 ms offset
        val scale = 25.0 / 23.976
        val interceptMs = 3_200L
        val targetFps = forcedClustered.map { cue ->
            val s = ((cue.startTimeMs - interceptMs) / scale).roundToLong()
            val e = ((cue.endTimeMs - interceptMs) / scale).roundToLong()
            SubtitleSyncCue(s, e.coerceAtLeast(s + 100L), cue.text)
        }
        val resFps = AutoSyncTimelineRetimer.retime(
            reference = fullRef,
            target = targetFps,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
        )
        assertNotNull("Expected sparse-target FPS lock", resFps)
        assertTrue("Expected confident sparse FPS lock: ${resFps!!.rejectReason}", resFps.confident)
        assertEquals("sparse-target-affine", resFps.alignmentSource)
        assertTrue(
            "Expected scale near $scale, got ${resFps.alignmentScale}",
            abs(resFps.alignmentScale - scale) <= 0.0005,
        )
        val midIdx = forcedClustered.size / 2
        assertTrue(
            "Mid forced cue error too large: ${abs(resFps.cues[midIdx].startTimeMs - forcedClustered[midIdx].startTimeMs)}ms",
            abs(resFps.cues[midIdx].startTimeMs - forcedClustered[midIdx].startTimeMs) <= 50L,
        )

        // Case D: Small 12-cue forced subtitle with +8,750 ms delay
        val forced12 = (0 until 12).map { i -> fullRef[i * 120 + 20] }
        val shift12 = 8_750L
        val target12 = forced12.map { cue ->
            SubtitleSyncCue(cue.startTimeMs - shift12, cue.endTimeMs - shift12, cue.text)
        }
        val res12 = AutoSyncTimelineRetimer.retime(
            reference = fullRef,
            target = target12,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
        )
        assertNotNull("Expected 12-cue sparse lock", res12)
        assertTrue("Expected confident 12-cue sparse lock: ${res12!!.rejectReason}", res12.confident)
        assertTrue(abs(res12.alignmentInterceptMs - shift12) <= 35.0)
    }

    @Test
    fun sparseTargetMatcherHasZeroFalsePositivesAcross100RandomTimelines() {
        val denseRef = realisticMovieCues(count = 1_800, startMs = 10_000L, seed = 606)
        val refActivity = AutoSyncTimelineRetimer.prepareUnitActivity(denseRef)!!
        val refSpan = denseRef.last().startTimeMs - denseRef.first().startTimeMs
        val sizes = intArrayOf(8, 10, 12, 16, 25, 45, 80)

        var falsePositives = 0
        for (trial in 0 until 100) {
            val rng = Random(7_000 + trial)
            val count = sizes[trial % sizes.size]
            val starts = LongArray(count) { 10_000L + rng.nextLong(refSpan) }
            starts.sort()
            val randomSparse = ArrayList<SubtitleSyncCue>(count)
            var prevEnd = 0L
            for (sRaw in starts) {
                val s = maxOf(sRaw, prevEnd + 500L)
                val dur = 1_000L + rng.nextLong(2_500L)
                randomSparse += SubtitleSyncCue(s, s + dur, "Unrelated $trial")
                prevEnd = s + dur
            }
            val res = AutoSyncTimelineRetimer.retime(
                reference = denseRef,
                target = randomSparse,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
                preparedReferenceActivity = refActivity,
            )
            if (res?.confident == true) {
                falsePositives++
            }
        }
        assertEquals("Sparse matcher produced false positives!", 0, falsePositives)
    }

    @Test
    fun lowDialogueMovieBelowTwoCuesPerMinuteLocksEndToEnd() = runBlocking {
        // 100-minute quiet film (like A Quiet Place / Wall-E) with 110 cues = 1.1 cues/min.
        val quietCues = realisticMovieCues(
            count = 110,
            startMs = 60_000L,
            stepMinMs = 30_000L,
            stepSpreadMs = 45_000L,
            seed = 707,
        )
        val spanMinutes = (quietCues.last().endTimeMs - quietCues.first().startTimeMs) / 60_000.0
        val density = quietCues.size / spanMinutes
        assertTrue("Expected density < 2.0 cues/min, got $density", density in 0.5..1.95)

        val mkv = buildMkv(
            tracks = listOf(
                MkvTrackSpec(
                    trackNumber = 3,
                    codecId = "S_TEXT/UTF8",
                    language = "eng",
                    name = "English",
                    events = quietCues.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) },
                ),
            ),
        )
        val shiftMs = 5_640L
        val targetCues = quietCues.map { cue ->
            SubtitleSyncCue(cue.startTimeMs - shiftMs, cue.endTimeMs - shiftMs, cue.text)
        }

        serveMkvAndSrt(mkv, "/quiet-film.mkv", buildSrt(targetCues), "/quiet-ext.srt") { mkvUrl, srtUrl ->
            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = mkvUrl,
                selectedSubtitleUrl = srtUrl,
                selectedSubtitleHeaders = emptyMap(),
                preferredLanguage = "en",
            )
            assertNotNull("Expected low-dialogue movie to lock instead of NO_USABLE_REFERENCE", resolved)
            assertTrue(resolved!!.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentInterceptMs - shiftMs) <= 30.0)
        }
    }

    @Test
    fun externalSdhSubtitleLocksAgainstCleanAndSdhReferencesPreservingAllSdhCues() = runBlocking {
        val dialogue = realisticMovieCues(count = 400, startMs = 20_000L, seed = 808)
        val trueShiftMs = 2_875L

        // Build an external SDH subtitle: 400 dialogue cues (some with inline speaker/mood tags
        // that must be kept) + 160 pure SDH cues ([door slams], (sighs), ♪ music ♪) interleaved.
        val externalAll = mutableListOf<SubtitleSyncCue>()
        val sdhSamples = listOf(
            "[door slams]",
            "(sighing heavily)",
            "♪ dramatic music playing ♪",
            "- [coughing]\n- [panting]",
            "♫ ♪",
        )
        for (i in dialogue.indices) {
            val cue = dialogue[i]
            val text = if (i % 15 == 0) "(whispering) ${cue.text}" else cue.text
            externalAll += SubtitleSyncCue(cue.startTimeMs - trueShiftMs, cue.endTimeMs - trueShiftMs, text)
            if (i % 5 == 0 || i % 5 == 2) {
                val gapMid = cue.endTimeMs + 300L
                val nextStart = dialogue.getOrNull(i + 1)?.startTimeMs ?: (cue.endTimeMs + 3_000L)
                if (nextStart - gapMid >= 900L) {
                    externalAll += SubtitleSyncCue(
                        startTimeMs = gapMid - trueShiftMs,
                        endTimeMs = (gapMid + 700L) - trueShiftMs,
                        text = sdhSamples[i % sdhSamples.size],
                    )
                }
            }
        }
        val filtered = AutoSyncSdhCueFilter.dialogueCues(externalAll)
        assertEquals("Only pure-SDH cues should be removed from analysis", dialogue.size, filtered.size)

        val sdhSrtBody = buildSrt(externalAll)
        // 1. Clean embedded reference
        val cleanMkv = buildMkv(
            tracks = listOf(
                MkvTrackSpec(
                    trackNumber = 3,
                    codecId = "S_TEXT/UTF8",
                    language = "eng",
                    name = "English",
                    events = dialogue.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) },
                ),
            ),
        )
        serveMkvAndSrt(cleanMkv, "/sdh-vs-clean.mkv", sdhSrtBody, "/ext-sdh.srt") { mkvUrl, srtUrl ->
            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = mkvUrl,
                selectedSubtitleUrl = srtUrl,
                selectedSubtitleHeaders = emptyMap(),
                preferredLanguage = "en",
            )
            assertNotNull("Expected SDH external subtitle to lock against clean MKV", resolved)
            assertTrue(resolved!!.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentInterceptMs - trueShiftMs) <= 30.0)

            // Verify retimed SRT text preserves ALL original cues (including pure SDH cues!)
            val retimedText = AutoSyncSubtitleTextRetimer.retime(resolved.subtitleBody!!, resolved.timeline)
            assertNotNull(retimedText)
            assertTrue("Pure SDH cue [door slams] must be preserved in rendered SRT", retimedText!!.contains("[door slams]"))
            assertTrue("Music cue must be preserved in rendered SRT", retimedText.contains("♪ dramatic music playing ♪"))
            val reparsed = AutoSyncSubtitleCueParser.parse(retimedText, "file.srt")
            assertEquals("All cues (dialogue + SDH) must survive retiming", externalAll.size, reparsed.size)
        }

        // 2. SDH embedded reference (same cues + SDH)
        val sdhRefEvents = externalAll.map { (it.startTimeMs + trueShiftMs) to (it.endTimeMs - it.startTimeMs) }
        val sdhMkv = buildMkv(
            tracks = listOf(
                MkvTrackSpec(
                    trackNumber = 3,
                    codecId = "S_TEXT/UTF8",
                    language = "eng",
                    name = "English SDH",
                    events = sdhRefEvents,
                ),
            ),
        )
        serveMkvAndSrt(sdhMkv, "/sdh-vs-sdh.mkv", sdhSrtBody, "/ext-sdh-2.srt") { mkvUrl, srtUrl ->
            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = mkvUrl,
                selectedSubtitleUrl = srtUrl,
                selectedSubtitleHeaders = emptyMap(),
                preferredLanguage = "en",
            )
            assertNotNull("Expected SDH external subtitle to lock against SDH MKV", resolved)
            assertTrue(resolved!!.timeline.confident)
            assertTrue(abs(resolved.timeline.alignmentInterceptMs - trueShiftMs) <= 30.0)
        }
    }

    @Test
    fun unbracketedExtraCuesAndTvRecapPassSymmetricReferenceCoverageGate() {
        val reference = realisticMovieCues(count = 230, startMs = 45_000L, seed = 909)
        val shiftMs = 1_750L
        // Target has 40 unbracketed recap cues before the episode + 25 unbracketed song/sign cues
        // scattered in gaps (total ~295 cues -> targetCoverage ~0.78 < 0.90, refCoverage ~0.99).
        val target = mutableListOf<SubtitleSyncCue>()
        var recapCursor = 2_000L
        for (i in 0 until 40) {
            target += SubtitleSyncCue(recapCursor, recapCursor + 700L, "Previously on the show line $i")
            recapCursor += 950L
        }
        for (i in reference.indices) {
            val cue = reference[i]
            target += SubtitleSyncCue(cue.startTimeMs - shiftMs, cue.endTimeMs - shiftMs, cue.text)
            if (i % 9 == 4 && i + 1 < reference.size) {
                val gapStart = cue.endTimeMs + 250L
                val nextStart = reference[i + 1].startTimeMs
                if (nextStart - gapStart >= 900L) {
                    target += SubtitleSyncCue(
                        gapStart - shiftMs,
                        (gapStart + 650L) - shiftMs,
                        "Unbracketed background sign text",
                    )
                }
            }
        }
        val normalizedTarget = AutoSyncTimelineRetimer.normalizeExternalTimeline(target)
        val res = AutoSyncTimelineRetimer.retime(
            reference = reference,
            target = normalizedTarget,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
        )
        assertNotNull("Expected symmetric high-reference-coverage lock", res)
        assertTrue("Expected confident lock despite extra target cues: ${res!!.rejectReason}", res.confident)
        assertTrue("Expected targetCoverage < 0.90 to prove asymmetric gate worked", res.targetCoverage < 0.90)
        assertTrue("Expected referenceCoverage >= 0.90, got ${res.referenceCoverage}", res.referenceCoverage >= 0.90)
        assertTrue(
            "Expected offset near $shiftMs, got ${res.alignmentInterceptMs}",
            abs(res.alignmentInterceptMs - shiftMs) <= 40.0,
        )
    }

    @Test
    fun largeUnsubtitledReferenceGapIsBridgedByBandedDp() {
        val fullRef = realisticMovieCues(count = 400, startMs = 20_000L, seed = 1010)
        // Drop a massive 140-cue unsubtitled block (indices 130..269) from the target!
        val kept = fullRef.take(130) + fullRef.drop(270)

        // 1. Constant delay
        val shiftMs = 5_430L
        val targetDelay = kept.map { cue ->
            SubtitleSyncCue(cue.startTimeMs - shiftMs, cue.endTimeMs - shiftMs, cue.text)
        }
        val resDelay = AutoSyncTimelineRetimer.retime(
            reference = fullRef,
            target = targetDelay,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
        )
        assertNotNull("Expected DP to bridge 140-cue reference gap (delay)", resDelay)
        assertTrue("Expected confident lock across 140-cue gap: ${resDelay!!.rejectReason}", resDelay.confident)
        assertTrue(abs(resDelay.alignmentInterceptMs - shiftMs) <= 30.0)

        // 2. FPS drift across the 140-cue reference gap
        val scale = 25.0 / 23.976
        val targetFps = kept.map { cue ->
            SubtitleSyncCue(
                ((cue.startTimeMs - 2_000L) / scale).roundToLong(),
                ((cue.endTimeMs - 2_000L) / scale).roundToLong(),
                cue.text,
            )
        }
        val resFps = AutoSyncTimelineRetimer.retime(
            reference = fullRef,
            target = targetFps,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
        )
        assertNotNull("Expected DP to bridge 140-cue reference gap (FPS)", resFps)
        assertTrue("Expected confident FPS lock across 140-cue gap: ${resFps!!.rejectReason}", resFps.confident)
        assertTrue(abs(resFps.alignmentScale - scale) <= 0.001)
        // Verify cues AFTER the 140-cue gap are accurately retimed!
        val afterGapTargetIdx = 135
        val expectedRef = kept[afterGapTargetIdx]
        assertTrue(
            "Cue after 140-cue gap mistimed by ${abs(resFps.cues[afterGapTargetIdx].startTimeMs - expectedRef.startTimeMs)}ms",
            abs(resFps.cues[afterGapTargetIdx].startTimeMs - expectedRef.startTimeMs) <= 100L,
        )
    }

    @Test
    fun sub100msDelayPrecisionAndMissingMkvCueDurationPlateauElimination() {
        val base = realisticMovieCues(count = 300, startMs = 20_000L, seed = 1111)
        // Simulate MKV with NO CueDuration: every cue's end is estimated as min(nextStart, start + 4000ms)
        val estimatedRef = base.mapIndexed { idx, cue ->
            val nextStart = base.getOrNull(idx + 1)?.startTimeMs ?: (cue.startTimeMs + 4_000L)
            val estEnd = minOf(nextStart, cue.startTimeMs + 4_000L).coerceAtLeast(cue.startTimeMs + 100L)
            SubtitleSyncCue(cue.startTimeMs, estEnd, "")
        }
        val estimatedStarts = estimatedRef.mapTo(hashSetOf()) { it.startTimeMs }

        for (trueShiftMs in listOf(47L, 1_247L, -3_863L)) {
            val target = base.map { cue ->
                SubtitleSyncCue(cue.startTimeMs - trueShiftMs, cue.endTimeMs - trueShiftMs, cue.text)
            }
            val res = AutoSyncTimelineRetimer.retime(
                reference = estimatedRef,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
                referenceEstimatedEndStartsMs = estimatedStarts,
            )
            assertNotNull("Expected match for shift=$trueShiftMs", res)
            assertTrue("Expected confident for shift=$trueShiftMs: ${res!!.rejectReason}", res.confident)
            assertEquals("delay-only-validated", res.alignmentSource)
            assertTrue(
                "Expected sub-15ms accuracy even with missing MKV CueDuration for $trueShiftMs, got ${res.alignmentInterceptMs}",
                abs(res.alignmentInterceptMs - trueShiftMs) <= 15.0,
            )
        }
    }

    @Test
    fun fps24To23976DriftIsPreservedOverTwoHourMovieEvenWithSdhReference() {
        // 2-hour movie (~7,200,000 ms span), 1,500 cues -> 24.0 / 23.976 drifts by ~7.2 seconds!
        val ref = realisticMovieCues(
            count = 1_500,
            startMs = 20_000L,
            stepMinMs = 2_400L,
            stepSpreadMs = 4_800L,
            seed = 1212,
        )
        val scale = 24.0 / 23.976
        val interceptMs = 1_500L
        val target = ref.map { cue ->
            val s = ((cue.startTimeMs - interceptMs) / scale).roundToLong()
            val e = ((cue.endTimeMs - interceptMs) / scale).roundToLong()
            SubtitleSyncCue(s, e.coerceAtLeast(s + 100L), cue.text)
        }

        for (sdhFlag in listOf(false, true)) {
            val res = AutoSyncTimelineRetimer.retime(
                reference = ref,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
                allowAmbiguousDelayOnlyMargin = sdhFlag,
            )
            assertNotNull("Expected match for 24/23.976 (sdh=$sdhFlag)", res)
            assertTrue("Expected confident for 24/23.976 (sdh=$sdhFlag): ${res!!.rejectReason}", res.confident)
            assertEquals("Must NOT squash 24/23.976 drift to delay-only!", "activity-correlation", res.alignmentSource)
            assertTrue(
                "Expected scale near $scale (sdh=$sdhFlag), got ${res.alignmentScale}",
                abs(res.alignmentScale - scale) <= 0.0003,
            )
            val lateErr = abs(res.cues.last().startTimeMs - ref.last().startTimeMs)
            assertTrue(
                "End-of-movie cue error too large for 24/23.976 (sdh=$sdhFlag): ${lateErr}ms",
                lateErr <= 100L,
            )
        }
    }

    @Test
    fun nonStandardFpsDriftWithExtraIntroOutroCreditsLocksViaTrimmedSpanAndAffineRefinement() {
        val core = realisticMovieCues(count = 400, startMs = 90_000L, seed = 1313)
        // Reference has 20 extra outro credit cues extending its tail by 60 seconds.
        val ref = core.toMutableList()
        var tailCursor = core.last().endTimeMs + 5_000L
        for (i in 0 until 20) {
            ref += SubtitleSyncCue(tailCursor, tailCursor + 1_500L, "Credit $i")
            tailCursor += 2_800L
        }

        // Target has non-standard 1.0038 speed drift + 4,500 ms shift + 20 extra intro cues.
        val trueScale = 1.0038
        val trueInterceptMs = 4_500.0
        val target = mutableListOf<SubtitleSyncCue>()
        var introCursor = 2_000L
        for (i in 0 until 20) {
            target += SubtitleSyncCue(introCursor, introCursor + 1_200L, "Intro $i")
            introCursor += 2_500L
        }
        for (cue in core) {
            val s = ((cue.startTimeMs - trueInterceptMs) / trueScale).roundToLong()
            val e = ((cue.endTimeMs - trueInterceptMs) / trueScale).roundToLong()
            target += SubtitleSyncCue(s, e.coerceAtLeast(s + 100L), cue.text)
        }

        val res = AutoSyncTimelineRetimer.retime(
            reference = ref,
            target = target,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
        )
        assertNotNull("Expected non-standard affine drift lock", res)
        assertTrue("Expected confident non-standard affine lock: ${res!!.rejectReason}", res.confident)
        assertTrue(
            "Expected scale near $trueScale, got ${res.alignmentScale}",
            abs(res.alignmentScale - trueScale) <= 0.0006,
        )
        val sampleIdx = 20 + core.size / 2
        assertTrue(
            "Mid-film retimed error too large: ${abs(res.cues[sampleIdx].startTimeMs - core[core.size / 2].startTimeMs)}ms",
            abs(res.cues[sampleIdx].startTimeMs - core[core.size / 2].startTimeMs) <= 150L,
        )
    }

    @Test
    fun wideRecapOffsetsBeyond180SecondsLockViaTwoTierFallback() {
        // 45-minute episode (600 cues, span > 2,500 s)
        val ref = realisticMovieCues(
            count = 600,
            startMs = 450_000L,
            stepMinMs = 2_000L,
            stepSpreadMs = 3_500L,
            seed = 1414,
        )

        // Case A: +360,000 ms (+6 minutes)
        val shiftPos = 360_000L
        val targetPos = ref.map { cue ->
            SubtitleSyncCue(cue.startTimeMs - shiftPos, cue.endTimeMs - shiftPos, cue.text)
        }
        val resPos = AutoSyncTimelineRetimer.retime(
            reference = ref,
            target = targetPos,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
        )
        assertNotNull("Expected +360s wide offset lock", resPos)
        assertTrue("Expected confident +360s lock: ${resPos!!.rejectReason}", resPos.confident)
        assertTrue(
            "Expected offset near $shiftPos, got ${resPos.alignmentInterceptMs}",
            abs(resPos.alignmentInterceptMs - shiftPos) <= 30.0,
        )

        // Case B: -420,000 ms (-7 minutes) WITH 45 extra recap cues at the start of target!
        val refNoLead = realisticMovieCues(
            count = 600,
            startMs = 15_000L,
            stepMinMs = 2_000L,
            stepSpreadMs = 3_500L,
            seed = 1415,
        )
        val shiftNeg = -420_000L
        val targetNeg = mutableListOf<SubtitleSyncCue>()
        var recapPos = 5_000L
        for (i in 0 until 45) {
            targetNeg += SubtitleSyncCue(recapPos, recapPos + 2_200L, "Long recap line $i")
            recapPos += 8_500L
        }
        for (cue in refNoLead) {
            targetNeg += SubtitleSyncCue(cue.startTimeMs - shiftNeg, cue.endTimeMs - shiftNeg, cue.text)
        }
        val resNeg = AutoSyncTimelineRetimer.retime(
            reference = refNoLead,
            target = targetNeg,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
        )
        assertNotNull("Expected -420s wide recap lock", resNeg)
        assertTrue("Expected confident -420s lock: ${resNeg!!.rejectReason}", resNeg.confident)
        assertTrue(
            "Expected offset near $shiftNeg, got ${resNeg.alignmentInterceptMs}",
            abs(resNeg.alignmentInterceptMs - shiftNeg) <= 40.0,
        )
    }

    @Test
    fun malformedSrtMissingBlankLinesAndTabCueSettingsParseCleanly() {
        val malformed = """
            1
            00:00:01,000 --> 00:00:03,000
            First line without trailing blank
            2
            00:00:04,500 --> 00:00:06,200	X1:10 X2:200
            Second cue right after first
            00:00:08,000 --> 00:00:10,000	align:middle
            Third cue missing both blank line and index
            4
            00:00:12,000 --> 00:00:14,000
            Line with a number on its own:
            42
            still part of cue 4!
        """.trimIndent()

        val cues = AutoSyncSubtitleCueParser.parse(malformed, "https://host/broken.srt")
        assertEquals(4, cues.size)
        assertEquals(1_000L, cues[0].startTimeMs)
        assertEquals(3_000L, cues[0].endTimeMs)
        assertEquals("First line without trailing blank", cues[0].text)

        assertEquals(4_500L, cues[1].startTimeMs)
        assertEquals(6_200L, cues[1].endTimeMs)
        assertEquals("Second cue right after first", cues[1].text)

        assertEquals(8_000L, cues[2].startTimeMs)
        assertEquals(10_000L, cues[2].endTimeMs)
        assertEquals("Third cue missing both blank line and index", cues[2].text)

        assertEquals(12_000L, cues[3].startTimeMs)
        assertEquals(14_000L, cues[3].endTimeMs)
        assertEquals("Line with a number on its own:\n42\nstill part of cue 4!", cues[3].text)
    }

    @Test
    fun sparseLiveReferenceWithSilentMiddleThirdStillLocks() {
        val full = realisticMovieCues(count = 400, startMs = 20_000L, seed = 1515)
        // Sampled reference only has cues in the first third and last third (silent middle act!).
        val firstStart = full.first().startTimeMs
        val span = full.last().startTimeMs - firstStart
        val sampled = full.filterIndexed { idx, cue ->
            val rel = (cue.startTimeMs - firstStart).toDouble() / span.toDouble()
            idx % 4 == 0 && (rel < 0.30 || rel > 0.70)
        }
        assertTrue(sampled.size >= 30)

        val delayMs = 9_350L
        val target = full.map { cue ->
            SubtitleSyncCue(cue.startTimeMs - delayMs, cue.endTimeMs - delayMs, cue.text)
        }
        val aligned = AutoSyncSampledReferenceAligner.findDelay(sampled, target)
        assertNotNull("Expected sparse live reference with silent middle third to lock", aligned)
        assertTrue(
            "Expected offset near $delayMs, got ${aligned!!.offsetMs}",
            abs(aligned.offsetMs - delayMs) <= 250.0,
        )
    }

    @Test
    fun negativeRegressionGatesStillRejectMidFilmCutsUnrelatedAndAmbiguous() {
        // 1. Mid-film commercial break cut (+2s first half, +14s second half)
        val ref = realisticMovieCues(count = 500, startMs = 30_000L, seed = 1616)
        val cutTarget = ref.mapIndexed { i, cue ->
            val shift = if (i < 250) 2_000L else 14_000L
            SubtitleSyncCue(cue.startTimeMs - shift, cue.endTimeMs - shift, cue.text)
        }
        val cutRes = AutoSyncTimelineRetimer.retime(ref, cutTarget, 1.0, 0.0, discoverAlignment = true)
        assertFalse("Mid-film cut must NEVER be accepted!", cutRes?.confident == true)

        // 2. Unrelated 45-minute episode (exercises wide ±600s fallback too!)
        val unrelated = realisticMovieCues(count = 480, startMs = 30_000L, seed = 9999)
        val unrelatedRes = AutoSyncTimelineRetimer.retime(ref, unrelated, 1.0, 0.0, discoverAlignment = true)
        assertFalse("Unrelated timeline must NEVER be accepted!", unrelatedRes?.confident == true)

        // 3. Strictly periodic ambiguous cadence (both full and subset with exact 0.0 margin)
        val periodicRef = (0 until 120).map { i ->
            val s = 20_000L + i * 5_000L
            SubtitleSyncCue(s, s + 2_000L, "Line $i")
        }
        val periodicTgt = periodicRef.map { SubtitleSyncCue(it.startTimeMs + 10_000L, it.endTimeMs + 10_000L, it.text) }
        val periodicRes = AutoSyncTimelineRetimer.retime(periodicRef, periodicTgt, 1.0, 0.0, discoverAlignment = true)
        assertFalse("Periodic ambiguous cadence must NEVER be accepted!", periodicRes?.confident == true)

        val periodicSubsetTgt = periodicRef.subList(10, 110).map {
            SubtitleSyncCue(it.startTimeMs + 10_000L, it.endTimeMs + 10_000L, it.text)
        }
        val periodicSubsetRes = AutoSyncTimelineRetimer.retime(
            periodicRef,
            periodicSubsetTgt,
            1.0,
            0.0,
            discoverAlignment = true,
        )
        assertFalse("Periodic subset ambiguous cadence must NEVER be accepted!", periodicSubsetRes?.confident == true)
    }

    @Test
    fun assFansubWithFontsKaraokeSignsAndSdhLocksEndToEndAndPreservesExactSignAndKaraokeTiming() = runBlocking {
        val dialogue = realisticMovieCues(count = 260, startMs = 25_000L, seed = 1717)
        val trueShiftMs = 3_125L

        fun assTime(ms: Long): String {
            val cs = (ms + 5L) / 10L
            return "%d:%02d:%02d.%02d".format(cs / 360_000, (cs / 6_000) % 60, (cs / 100) % 60, cs % 100)
        }

        // Build a heavy fansub ASS with:
        // - Embedded [Fonts] containing UU-encoded lines that start with '[' and end with ']'
        // - OP karaoke syllables right next to dialogue (within 500ms!)
        // - Positioned signs starting 300ms after dialogue starts
        // - Pure SDH lines in Default style
        // - Uppercase {\POS(100,200)} tag & literal backslash in dialogue text
        val assBody = buildString {
            appendLine("[Script Info]")
            appendLine("ScriptType: v4.00+")
            appendLine()
            appendLine("[V4+ Styles]")
            appendLine("Format: Name, Fontname, Fontsize, Alignment")
            appendLine("Style: Default,Arial,48,2")
            appendLine("Style: OP Karaoke,Arial,40,8")
            appendLine("Style: Signs,Arial,40,5")
            appendLine()
            appendLine("[Fonts]")
            appendLine("fontname: custom_0.ttf")
            for (i in 0 until 1_500) {
                // Include tricky UU lines starting with '[' and ending with ']'
                if (i % 50 == 0) appendLine("[ABC123UUENCODEDFONTDATA$i]")
                else appendLine("M9TAT\"\"(\"\"0@*\"<@*\"9FONTDATA$i")
            }
            appendLine()
            appendLine("[Events]")
            appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
            // 60 OP karaoke syllables at the start
            for (i in 0 until 60) {
                val s = 1_000L + i * 250L
                appendLine("Dialogue: 0,${assTime(s)},${assTime(s + 240L)},OP Karaoke,,0,0,0,,{\\k24}sy$i")
            }
            // Dialogue + nearby signs (+300ms) + nearby karaoke syllables (+150ms, +300ms) + SDH lines
            for (i in dialogue.indices) {
                val cue = dialogue[i]
                val extS = cue.startTimeMs - trueShiftMs
                val extE = cue.endTimeMs - trueShiftMs
                val txt = if (i == 5) """Path C:\pos(1,2) in spoken text""" else cue.text
                appendLine("Dialogue: 0,${assTime(extS)},${assTime(extE)},Default,,0,0,0,,$txt")
                if (i == 10) {
                    // Sign starting 300ms after dialogue start (must NOT collapse onto extS!)
                    appendLine("Dialogue: 0,${assTime(extS + 300L)},${assTime(extS + 2_300L)},Signs,,0,0,0,,{\\POS(320,40)}Station Sign")
                    // Two rapid karaoke syllables 150ms & 300ms after dialogue start
                    appendLine("Dialogue: 0,${assTime(extS + 150L)},${assTime(extS + 300L)},OP Karaoke,,0,0,0,,{\\k15}la")
                    appendLine("Dialogue: 0,${assTime(extS + 300L)},${assTime(extS + 450L)},OP Karaoke,,0,0,0,,{\\k15}ti")
                }
                if (i % 6 == 0 && i + 1 < dialogue.size) {
                    val gap = extE + 250L
                    val nextExtS = dialogue[i + 1].startTimeMs - trueShiftMs
                    if (nextExtS - gap >= 900L) {
                        appendLine("Dialogue: 0,${assTime(gap)},${assTime(gap + 650L)},Default,,0,0,0,,[door slams]")
                    }
                }
            }
        }

        val mkv = buildMkv(
            tracks = listOf(
                MkvTrackSpec(
                    trackNumber = 3,
                    codecId = "S_TEXT/ASS",
                    language = "eng",
                    name = "English ASS",
                    events = dialogue.map { it.startTimeMs to (it.endTimeMs - it.startTimeMs) },
                ),
            ),
        )

        serveMkvAndSrt(mkv, "/anime-ass.mkv", assBody, "/fansub.ass") { mkvUrl, assUrl ->
            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = mkvUrl,
                selectedSubtitleUrl = assUrl,
                selectedSubtitleHeaders = emptyMap(),
                preferredLanguage = "en",
            )
            assertNotNull("Expected ASS fansub to lock cleanly", resolved)
            assertTrue("Expected confident ASS lock: ${resolved!!.timeline.rejectReason}", resolved.timeline.confident)
            assertTrue(
                "Expected ASS offset near $trueShiftMs, got ${resolved.timeline.alignmentInterceptMs}",
                abs(resolved.timeline.alignmentInterceptMs - trueShiftMs) <= 25.0,
            )

            // Retime the full ASS text and verify:
            // 1. Embedded [Fonts] section is preserved verbatim
            // 2. Spoken line with literal backslash was NOT dropped
            // 3. Sign at extS+300ms and karaoke at extS+150ms / extS+300ms kept exact relative spacing!
            val retimedAss = AutoSyncSubtitleTextRetimer.retime(resolved.subtitleBody!!, resolved.timeline)
            assertNotNull(retimedAss)
            assertTrue("Embedded fonts must survive retiming", retimedAss!!.contains("[ABC123UUENCODEDFONTDATA0]"))
            assertTrue("Dialogue with literal backslash must survive", retimedAss.contains("""C:\pos(1,2)"""))

            val ref10Start = dialogue[10].startTimeMs
            val signLine = retimedAss.lineSequence().first { it.contains("Station Sign") }
            val kara1Line = retimedAss.lineSequence().first { it.contains("{\\k15}la") }
            val kara2Line = retimedAss.lineSequence().first { it.contains("{\\k15}ti") }
            fun parseAssStartMs(line: String): Long {
                val ts = line.substringAfter("Dialogue:").split(',')[1].trim()
                val p = ts.split(':', '.')
                return (p[0].toLong() * 3600 + p[1].toLong() * 60 + p[2].toLong()) * 1000 + p[3].toLong() * 10
            }
            val signStart = parseAssStartMs(signLine)
            val kara1Start = parseAssStartMs(kara1Line)
            val kara2Start = parseAssStartMs(kara2Line)

            assertTrue(
                "Sign snapped onto dialogue! expected ~${ref10Start + 300}, got $signStart",
                abs(signStart - (ref10Start + 300L)) <= 30L,
            )
            assertTrue(
                "Karaoke syllable 1 collapsed! expected ~${ref10Start + 150}, got $kara1Start",
                abs(kara1Start - (ref10Start + 150L)) <= 30L,
            )
            assertTrue(
                "Karaoke syllable 2 collapsed! expected ~${ref10Start + 300}, got $kara2Start",
                abs(kara2Start - (ref10Start + 300L)) <= 30L,
            )
        }
    }

    @Test
    fun wideRecapOffsetWithFpsDriftStillPassesFastOuterWingProbe() {
        val ref = realisticMovieCues(
            count = 500,
            startMs = 420_000L,
            stepMinMs = 2_000L,
            stepSpreadMs = 3_500L,
            seed = 1818,
        )
        val scale = 25.0 / 23.976
        val shiftMs = 360_000L
        val target = ref.map { cue ->
            val s = ((cue.startTimeMs - shiftMs) / scale).roundToLong().coerceAtLeast(0L)
            val e = ((cue.endTimeMs - shiftMs) / scale).roundToLong().coerceAtLeast(s + 100L)
            SubtitleSyncCue(s, e, cue.text)
        }
        val res = AutoSyncTimelineRetimer.retime(ref, target, 1.0, 0.0, discoverAlignment = true)
        assertNotNull("Expected wide FPS-drifted recap lock", res)
        assertTrue("Expected confident wide FPS recap lock: ${res!!.rejectReason}", res.confident)
        assertTrue(abs(res.alignmentScale - scale) <= 0.001)
        assertTrue(abs(res.alignmentInterceptMs - shiftMs) <= 400.0)
    }

    // ---- Test fixture helpers ----

    private fun realisticMovieCues(
        count: Int,
        startMs: Long = 15_000L,
        stepMinMs: Long = 1_800L,
        stepSpreadMs: Long = 4_400L,
        seed: Int = 42,
    ): List<SubtitleSyncCue> {
        val rng = Random(seed)
        val cues = ArrayList<SubtitleSyncCue>(count)
        var t = startMs
        for (i in 0 until count) {
            val dur = 900L + rng.nextLong(2_600L)
            cues += SubtitleSyncCue(t, t + dur, "Dialogue line $i")
            val gap = stepMinMs + rng.nextLong(stepSpreadMs) + if (i % 17 == 0) 6_000L else 0L
            t += dur + gap
        }
        return cues
    }

    private fun buildSrt(cues: List<SubtitleSyncCue>): String = buildString {
        cues.forEachIndexed { idx, cue ->
            fun fmt(ms: Long): String = String.format(
                "%02d:%02d:%02d,%03d",
                ms / 3_600_000,
                (ms / 60_000) % 60,
                (ms / 1_000) % 60,
                ms % 1_000,
            )
            append(idx + 1).append('\n')
            append(fmt(cue.startTimeMs)).append(" --> ").append(fmt(cue.endTimeMs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    private data class MkvTrackSpec(
        val trackNumber: Long,
        val codecId: String,
        val language: String,
        val name: String,
        val forced: Boolean = false,
        val events: List<Pair<Long, Long>>, // (startMs, durationMs; 0L omits CueDuration)
    )

    private fun buildMkv(tracks: List<MkvTrackSpec>): ByteArray {
        val ebml = ebmlElement(0x1A45DFA3L, ebmlElement(0x4282L, "matroska".toByteArray()))
        val info = ebmlElement(0x1549A966L, ebmlUintElement(0x2AD7B1L, 1_000_000L))
        val tracksPayload = ByteArrayOutputStream()
        for (spec in tracks) {
            val entry = ByteArrayOutputStream().apply {
                write(ebmlUintElement(0xD7L, spec.trackNumber))
                write(ebmlUintElement(0x83L, 17L)) // subtitle track type
                write(ebmlElement(0x86L, spec.codecId.toByteArray()))
                write(ebmlElement(0x22B59CL, spec.language.toByteArray()))
                write(ebmlElement(0x536EL, spec.name.toByteArray()))
                if (spec.forced) {
                    write(ebmlUintElement(0x55AAL, 1L)) // FlagForced
                }
            }.toByteArray()
            tracksPayload.write(ebmlElement(0xAEL, entry))
        }
        val tracksEl = ebmlElement(0x1654AE6BL, tracksPayload.toByteArray())

        val allPoints = mutableListOf<Triple<Long, Long, Long>>() // (timeMs, trackNumber, durMs)
        for (spec in tracks) {
            for ((startMs, durMs) in spec.events) {
                allPoints += Triple(startMs, spec.trackNumber, durMs)
            }
        }
        allPoints.sortBy { it.first }

        val cuesPayload = ByteArrayOutputStream()
        for ((timeMs, trackNum, durMs) in allPoints) {
            val posBody = ByteArrayOutputStream().apply {
                write(ebmlUintElement(0xF7L, trackNum))
                write(ebmlUintElement(0xF1L, 0L))
                if (durMs > 0L) {
                    write(ebmlUintElement(0xB2L, durMs))
                }
            }.toByteArray()
            val point = ByteArrayOutputStream().apply {
                write(ebmlUintElement(0xB3L, timeMs))
                write(ebmlElement(0xB7L, posBody))
            }.toByteArray()
            cuesPayload.write(ebmlElement(0xBBL, point))
        }
        val cuesEl = ebmlElement(0x1C53BB6BL, cuesPayload.toByteArray())

        val padding = ebmlElement(0xECL, ByteArray(1_024))
        fun buildSeekHead(infoPos: Long, tracksPos: Long, cuesPos: Long): ByteArray {
            fun seek(id: Int, pos: Long): ByteArray {
                val idBytes = byteArrayOf(
                    ((id ushr 24) and 0xFF).toByte(),
                    ((id ushr 16) and 0xFF).toByte(),
                    ((id ushr 8) and 0xFF).toByte(),
                    (id and 0xFF).toByte(),
                )
                return ebmlElement(
                    0x4DBBL,
                    ebmlElement(0x53ABL, idBytes) + ebmlUintElement(0x53ACL, pos),
                )
            }
            return ebmlElement(
                0x114D9B74L,
                seek(0x1549A966, infoPos) + seek(0x1654AE6B, tracksPos) + seek(0x1C53BB6B, cuesPos),
            )
        }

        var seekHead = buildSeekHead(0L, 0L, 0L)
        repeat(4) {
            val infoPos = seekHead.size.toLong()
            val tracksPos = infoPos + info.size
            val cuesPos = tracksPos + tracksEl.size + padding.size
            seekHead = buildSeekHead(infoPos, tracksPos, cuesPos)
        }
        val segBody = ByteArrayOutputStream().apply {
            write(seekHead)
            write(info)
            write(tracksEl)
            write(padding)
            write(cuesEl)
        }.toByteArray()
        return ebml + ebmlElement(0x18538067L, segBody)
    }

    private suspend fun serveMkvAndSrt(
        mkv: ByteArray,
        mkvPath: String,
        srtBody: String,
        srtPath: String,
        block: suspend (mkvUrl: String, srtUrl: String) -> Unit,
    ) {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (path == mkvPath) {
                        val range = request.getHeader("Range")
                        if (range != null && range.startsWith("bytes=")) {
                            val parts = range.removePrefix("bytes=").split("-")
                            val start = parts[0].toLong()
                            val end = parts[1].toLong().coerceAtMost(mkv.size.toLong() - 1L)
                            val slice = mkv.copyOfRange(start.toInt(), end.toInt() + 1)
                            return MockResponse()
                                .setResponseCode(206)
                                .setHeader("Content-Range", "bytes $start-$end/${mkv.size}")
                                .setBody(Buffer().write(slice))
                        }
                        return MockResponse().setResponseCode(200).setBody(Buffer().write(mkv))
                    }
                    if (path == srtPath) {
                        return MockResponse().setResponseCode(200).setBody(srtBody)
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            server.start()
            block(server.url(mkvPath).toString(), server.url(srtPath).toString())
        }
    }

    private fun ebmlElement(id: Long, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeId(id))
        out.write(encodeVintSize(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    private fun ebmlUintElement(id: Long, value: Long): ByteArray {
        var v = value
        val tmp = ByteArray(8)
        var len = 0
        do {
            tmp[7 - len] = (v and 0xFF).toByte()
            v = v ushr 8
            len++
        } while (v != 0L)
        return ebmlElement(id, tmp.copyOfRange(8 - len, 8))
    }

    private fun encodeId(id: Long): ByteArray {
        val len = when {
            id <= 0xFFL -> 1
            id <= 0xFFFFL -> 2
            id <= 0xFFFFFFL -> 3
            else -> 4
        }
        return ByteArray(len) { idx -> ((id ushr (8 * (len - 1 - idx))) and 0xFFL).toByte() }
    }

    private fun encodeVintSize(size: Long): ByteArray {
        val len = when {
            size < (1L shl 7) - 1 -> 1
            size < (1L shl 14) - 1 -> 2
            size < (1L shl 21) - 1 -> 3
            else -> 4
        }
        val out = ByteArray(len)
        for (i in 0 until len) {
            out[len - 1 - i] = ((size ushr (8 * i)) and 0xFFL).toByte()
        }
        out[0] = (out[0].toInt() or (1 shl (8 - len))).toByte()
        return out
    }
}
