package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profiling and end-to-end benchmark harness for AutoSync (embedded subtitle path).
 * Prints detailed per-component timings so we can measure baseline and verify speedups.
 */
class AutoSyncPerformanceBenchmarkTest {

    @Test
    fun benchmarkAllComponentsAndEndToEnd() {
        val ref2000 = realisticMovieTimeline(count = 2_000, seed = 42)
        val targetDelay = realisticVariant(ref2000, shiftMs = -12_750L, scale = 1.0, seed = 101)
        val targetFps = realisticVariant(ref2000, shiftMs = -3_200L, scale = 23.976 / 25.0, seed = 102)
        val targetWrong = realisticMovieTimeline(count = 1_950, seed = 999)
        val refSdh = addSdhExtras(ref2000, seed = 77)
        val partialDeltaMs = ref2000[1_100].startTimeMs - 5_000L
        val targetPartial = realisticVariant(
            ref2000.subList(1_100, ref2000.size),
            shiftMs = -partialDeltaMs,
            scale = 1.0,
            seed = 103,
        )

        // Warmup JIT across hot paths
        repeat(5) {
            val ra = AutoSyncTimelineRetimer.prepareUnitActivity(ref2000)!!
            val ta = AutoSyncTimelineRetimer.prepareUnitActivity(targetDelay)!!
            val pa = AutoSyncTimelineRetimer.prepareUnitActivity(targetPartial)!!
            AutoSyncTimelineRetimer.prepareDelayOnlySearchEvidence(ra, ta)
            AutoSyncTimelineRetimer.prepareDelayOnlySearchEvidence(ra, pa)
            AutoSyncTimelineRetimer.retime(ref2000, targetDelay, 1.0, 0.0, discoverAlignment = true)
            AutoSyncTimelineRetimer.retime(ref2000, targetFps, 1.0, 0.0, discoverAlignment = true)
        }

        val sb = StringBuilder()
        sb.appendLine("================ AUTOSYNC BENCHMARK REPORT ================")
        try {

        // 1. prepareUnitActivity
        val prepMs = measureAvgMs(10) {
            assertNotNull(AutoSyncTimelineRetimer.prepareUnitActivity(ref2000))
        }
        sb.appendLine("1. prepareUnitActivity (2000 cues):            ${fmt(prepMs)} ms")

        val refAct = AutoSyncTimelineRetimer.prepareUnitActivity(ref2000)!!
        val delayAct = AutoSyncTimelineRetimer.prepareUnitActivity(targetDelay)!!
        val fpsAct = AutoSyncTimelineRetimer.prepareUnitActivity(targetFps)!!
        val wrongAct = AutoSyncTimelineRetimer.prepareUnitActivity(targetWrong)!!
        val sdhAct = AutoSyncTimelineRetimer.prepareUnitActivity(refSdh)!!
        val partialAct = AutoSyncTimelineRetimer.prepareUnitActivity(targetPartial)!!

        // 2. prepareDelayOnlySearchEvidence (normal ±180s)
        val evDelayMs = measureAvgMs(10) {
            assertNotNull(AutoSyncTimelineRetimer.prepareDelayOnlySearchEvidence(refAct, delayAct))
        }
        val evWrongMs = measureAvgMs(10) {
            assertNotNull(AutoSyncTimelineRetimer.prepareDelayOnlySearchEvidence(refAct, wrongAct))
        }
        val evPartialMs = measureAvgMs(3) {
            assertNotNull(AutoSyncTimelineRetimer.prepareDelayOnlySearchEvidence(refAct, partialAct))
        }
        sb.appendLine("2a. prepareDelayOnlySearchEvidence (match):    ${fmt(evDelayMs)} ms")
        sb.appendLine("2b. prepareDelayOnlySearchEvidence (wrong):    ${fmt(evWrongMs)} ms")
        sb.appendLine("2c. prepareDelayOnlySearchEvidence (CD2):      ${fmt(evPartialMs)} ms")

        // 3. findDelayOnlyAlignmentPrepared
        val evDelay = AutoSyncTimelineRetimer.prepareDelayOnlySearchEvidence(refAct, delayAct)!!
        val delayValMs = measureAvgMs(10) {
            assertNotNull(
                AutoSyncTimelineRetimer.findDelayOnlyAlignmentPrepared(
                    referenceActivity = refAct,
                    targetActivity = delayAct,
                    targetSize = targetDelay.size,
                    precomputedEvidence = evDelay,
                ),
            )
        }
        val delayFullMs = measureAvgMs(10) {
            assertNotNull(
                AutoSyncTimelineRetimer.findDelayOnlyAlignmentPrepared(
                    referenceActivity = refAct,
                    targetActivity = delayAct,
                    targetSize = targetDelay.size,
                ),
            )
        }
        sb.appendLine("3a. findDelayOnly (with precomputed ev):       ${fmt(delayValMs)} ms")
        sb.appendLine("3b. findDelayOnly (full, no ev):               ${fmt(delayFullMs)} ms")

        // 4. Retime paths with phase breakdown
        var lastTimings: AutoSyncRetimePhaseTimings? = null
        val precomputedDelay = AutoSyncTimelineRetimer.findDelayOnlyAlignmentPrepared(
            refAct, delayAct, targetDelay.size, precomputedEvidence = evDelay,
        )
        val retimeFastDelayMs = measureAvgMs(5) {
            val r = AutoSyncTimelineRetimer.retime(
                reference = ref2000,
                target = targetDelay,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
                preparedReferenceActivity = refAct,
                preparedTargetActivity = delayAct,
                precomputedDelayOnly = precomputedDelay,
                precomputedDelayOnlyEvidence = evDelay,
                allowPrecomputedDelayFastPath = true,
                timingObserver = { lastTimings = it },
            )
            assertTrue(r != null && r.confident)
        }
        sb.appendLine("4a. retime fast-delay (2000 cues):             ${fmt(retimeFastDelayMs)} ms | phases=$lastTimings")

        val evFps = AutoSyncTimelineRetimer.prepareDelayOnlySearchEvidence(refAct, fpsAct)!!
        val retimeFpsMs = measureAvgMs(3) {
            val r = AutoSyncTimelineRetimer.retime(
                reference = ref2000,
                target = targetFps,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
                preparedReferenceActivity = refAct,
                preparedTargetActivity = fpsAct,
                precomputedDelayOnlyEvidence = evFps,
                timingObserver = { lastTimings = it },
            )
            assertTrue(r != null && r.confident)
        }
        sb.appendLine("4b. retime FPS-drift (2000 cues, with ev):     ${fmt(retimeFpsMs)} ms | phases=$lastTimings")

        val retimeFpsNoEvMs = measureAvgMs(3) {
            val r = AutoSyncTimelineRetimer.retime(
                reference = ref2000,
                target = targetFps,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
                preparedReferenceActivity = refAct,
                preparedTargetActivity = fpsAct,
                timingObserver = { lastTimings = it },
            )
            assertTrue(r != null && r.confident)
        }
        sb.appendLine("4c. retime FPS-drift (2000 cues, cold):        ${fmt(retimeFpsNoEvMs)} ms | phases=$lastTimings")

        val evWrong = AutoSyncTimelineRetimer.prepareDelayOnlySearchEvidence(refAct, wrongAct)!!
        val retimeWrongMs = measureAvgMs(3) {
            val r = AutoSyncTimelineRetimer.retime(
                reference = ref2000,
                target = targetWrong,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
                preparedReferenceActivity = refAct,
                preparedTargetActivity = wrongAct,
                precomputedDelayOnlyEvidence = evWrong,
                timingObserver = { lastTimings = it },
            )
            assertTrue(r == null || !r.confident)
        }
        sb.appendLine("4d. retime wrong-release (2000 cues):          ${fmt(retimeWrongMs)} ms | phases=$lastTimings")

        val retimeSdhMs = measureAvgMs(3) {
            val r = AutoSyncTimelineRetimer.retime(
                reference = refSdh,
                target = targetDelay,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
                allowAmbiguousDelayOnlyMargin = true,
                preparedReferenceActivity = sdhAct,
                preparedTargetActivity = delayAct,
                timingObserver = { lastTimings = it },
            )
            assertTrue(r != null && r.confident)
        }
        sb.appendLine("4e. retime SDH ref (3000v2000 cues):           ${fmt(retimeSdhMs)} ms | phases=$lastTimings")

        val retimePartialMs = measureAvgMs(2) {
            val r = AutoSyncTimelineRetimer.retime(
                reference = ref2000,
                target = targetPartial,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
                preparedReferenceActivity = refAct,
                preparedTargetActivity = partialAct,
                timingObserver = { lastTimings = it },
            )
            assertTrue(r != null && r.confident)
        }
        sb.appendLine("4f. retime partial CD2 (2000v900 cues):        ${fmt(retimePartialMs)} ms | phases=$lastTimings")

        // 5. AutoSyncReferenceConsistency.findOutliers (8 tracks, 6 independent)
        val refTracks20 = (0 until 20).map { idx ->
            val cues = independentLanguageAuthoring(ref2000, seed = 200 + idx)
            ReferenceTrack(
                key = "mkv-cues:${idx + 1}",
                language = "l$idx",
                cues = cues,
                label = "Track $idx",
            )
        }
        val consistencyRefs = refTracks20.take(8).map { tr ->
            AutoSyncReferenceConsistency.Reference(
                key = tr.key,
                activity = AutoSyncTimelineRetimer.prepareUnitActivity(tr.cues)!!,
                cueCount = tr.cues.size,
            )
        }
        val consistencyGoodMs = measureAvgMs(3) {
            val outliers = AutoSyncReferenceConsistency.findOutliers(consistencyRefs)
            assertTrue(outliers.isEmpty())
        }
        val consistencyWithOutlierRefs = consistencyRefs.take(5) + AutoSyncReferenceConsistency.Reference(
            key = "mkv-cues:outlier",
            activity = AutoSyncTimelineRetimer.prepareUnitActivity(
                realisticVariant(ref2000, shiftMs = 6_500L, scale = 1.0, seed = 333),
            )!!,
            cueCount = ref2000.size,
        )
        val consistencyOutlierMs = measureAvgMs(3) {
            val outliers = AutoSyncReferenceConsistency.findOutliers(consistencyWithOutlierRefs)
            assertTrue("Expected outlier in $outliers", "mkv-cues:outlier" in outliers)
        }
        sb.appendLine("5a. ReferenceConsistency (8 good tracks):      ${fmt(consistencyGoodMs)} ms")
        sb.appendLine("5b. ReferenceConsistency (7 good + 1 bad):     ${fmt(consistencyOutlierMs)} ms")

        // 6. AutoSyncDelayPreflight.evaluate (20 embedded tracks)
        val preflightCache = mutableMapOf<String, AutoSyncTimelineRetimer.PreparedActivity?>()
        val preflightColdMs = measureAvgMs(1) {
            val res = AutoSyncDelayPreflight.evaluate(refTracks20, targetDelay, preflightCache, delayAct)
            assertNotNull(res.best)
        }
        val preflightWarmMatchMs = measureAvgMs(3) {
            val res = AutoSyncDelayPreflight.evaluate(refTracks20, targetDelay, preflightCache, delayAct)
            assertNotNull(res.best)
        }
        val preflightWarmWrongMs = measureAvgMs(3) {
            AutoSyncDelayPreflight.evaluate(refTracks20, targetWrong, preflightCache, wrongAct)
        }
        sb.appendLine("6a. DelayPreflight (20 tracks, cold cache):    ${fmt(preflightColdMs)} ms")
        sb.appendLine("6b. DelayPreflight (20 tracks, warm, match):   ${fmt(preflightWarmMatchMs)} ms")
        sb.appendLine("6c. DelayPreflight (20 tracks, warm, wrong):   ${fmt(preflightWarmWrongMs)} ms")

        // 7. Parsers & Text Retimer
        val srtBody = buildSrt(targetDelay)
        val vttBody = buildVtt(targetDelay)
        val assBody = buildFansubAss(targetDelay)
        val parseSrtMs = measureAvgMs(10) {
            assertTrue(AutoSyncSubtitleCueParser.parse(srtBody, "https://x/sub.srt").size == targetDelay.size)
        }
        val parseVttMs = measureAvgMs(10) {
            assertTrue(AutoSyncSubtitleCueParser.parse(vttBody, "https://x/sub.vtt").size == targetDelay.size)
        }
        val parseAssMs = measureAvgMs(10) {
            assertTrue(AutoSyncSubtitleCueParser.parse(assBody, "https://x/sub.ass").size == targetDelay.size)
        }
        val timelineForRetimer = AutoSyncTimelineRetimer.retime(
            ref2000, targetDelay, 1.0, 0.0, discoverAlignment = true,
            preparedReferenceActivity = refAct, preparedTargetActivity = delayAct,
            precomputedDelayOnly = precomputedDelay,
        )!!
        val retimeSrtTextMs = measureAvgMs(10) {
            assertNotNull(AutoSyncSubtitleTextRetimer.retime(srtBody, timelineForRetimer))
        }
        val retimeAssTextMs = measureAvgMs(10) {
            assertNotNull(AutoSyncSubtitleTextRetimer.retime(assBody, timelineForRetimer))
        }
        sb.appendLine("7a. Parse SRT (2000 cues):                     ${fmt(parseSrtMs)} ms")
        sb.appendLine("7b. Parse VTT (2000 cues):                     ${fmt(parseVttMs)} ms")
        sb.appendLine("7c. Parse ASS fansub (3200 events):            ${fmt(parseAssMs)} ms")
        sb.appendLine("7d. TextRetimer SRT (2000 cues):               ${fmt(retimeSrtTextMs)} ms")
        sb.appendLine("7e. TextRetimer ASS (3200 events):             ${fmt(retimeAssTextMs)} ms")

        // 8. SampledReferenceAligner
        val sampledRef = ref2000.filterIndexed { i, _ -> i % 5 == 0 }
        val sampledMs = measureAvgMs(10) {
            assertNotNull(AutoSyncSampledReferenceAligner.findDelay(sampledRef, targetDelay))
        }
        sb.appendLine("8.  SampledReferenceAligner (400v2000):        ${fmt(sampledMs)} ms")

        // 9. End-to-End EmbeddedSubtitleTimelineLoader + AutomaticSubtitleSync over MockWebServer
        val mkvBytes = buildSyntheticMkvWithCuesAtEnd(refTracks20)
        runBlocking {
            val server = MockWebServer()
            val srtDelay = buildSrt(targetDelay)
            val srtFps = buildSrt(targetFps)
            val wrongSrts = (0 until 8).map { i ->
                buildSrt(realisticMovieTimeline(count = 1_900, seed = 500 + i))
            }
            var mkvRangeRequests = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (path.startsWith("/movie")) {
                        mkvRangeRequests++
                        val range = request.getHeader("Range")
                        if (range != null && range.startsWith("bytes=")) {
                            val parts = range.removePrefix("bytes=").split("-")
                            val start = parts[0].toLong().coerceAtLeast(0L)
                            val end = parts.getOrNull(1)?.toLongOrNull()
                                ?.coerceAtMost(mkvBytes.size.toLong() - 1L)
                                ?: (mkvBytes.size.toLong() - 1L)
                            val slice = mkvBytes.copyOfRange(start.toInt(), end.toInt() + 1)
                            return MockResponse()
                                .setResponseCode(206)
                                .setHeader("Content-Range", "bytes $start-$end/${mkvBytes.size}")
                                .setHeader("Content-Length", slice.size)
                                .setHeadersDelay(40L, TimeUnit.MILLISECONDS)
                                .setBody(Buffer().write(slice))
                        }
                        return MockResponse()
                            .setResponseCode(200)
                            .setBody(Buffer().write(mkvBytes))
                    }
                    if (path.startsWith("/sub-delay.srt")) {
                        return MockResponse().setResponseCode(200).setBody(srtDelay)
                    }
                    if (path == "/sub-fps.srt") {
                        return MockResponse().setResponseCode(200).setBody(srtFps)
                    }
                    val wrongMatch = Regex("/sub-wrong-(\\d+)\\.srt").matchEntire(path)
                    if (wrongMatch != null) {
                        val idx = wrongMatch.groupValues[1].toInt()
                        return MockResponse().setResponseCode(200).setBody(wrongSrts[idx])
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            server.start()
            try {
                // 9a. MKV index load (with 40ms simulated RTT per request)
                // Warm OkHttp client once on a tiny 404 so we measure true steady-state playback load
                EmbeddedSubtitleTimelineLoader.load(server.url("/warmup-miss").toString(), emptyMap())
                mkvRangeRequests = 0
                val mkvUrl1 = server.url("/movie1.mkv").toString()
                var loadedIndex: IndexedEmbeddedTimeline? = null
                val mkvLoadMs = measureNanoTime {
                    loadedIndex = EmbeddedSubtitleTimelineLoader.load(mkvUrl1, emptyMap())
                } / 1_000_000.0
                assertNotNull(loadedIndex)
                val idxResult = loadedIndex!!
                sb.appendLine(
                    "9a. MKV index load (20 tracks, 40ms RTT):      ${fmt(mkvLoadMs)} ms " +
                        "| reqs=$mkvRangeRequests bytes=${idxResult.bytesDownloaded} tracks=${idxResult.tracks.size}",
                )

                // 9b. End-to-end SELECTED_ONLY (delay match, MKV already cached)
                val delayUrl = server.url("/sub-delay.srt").toString()
                val e2eSelectedDelayMs = measureNanoTime {
                    val res = AutomaticSubtitleSync.findTimelineRetime(
                        sourceKey = mkvUrl1,
                        selectedSubtitleUrl = delayUrl,
                        selectedSubtitleHeaders = emptyMap(),
                        preferredLanguage = "en",
                    )
                    assertNotNull(res)
                    assertTrue(res!!.timeline.confident)
                } / 1_000_000.0
                sb.appendLine("9b. E2E SELECTED_ONLY (delay, 20 refs):        ${fmt(e2eSelectedDelayMs)} ms")

                // 9b2. End-to-end SELECTED_ONLY after prefetch warming (real playback flow!)
                val mkvUrlWarmed = server.url("/movie-warmed.mkv").toString()
                val warmedIdx = EmbeddedSubtitleTimelineLoader.load(mkvUrlWarmed, emptyMap())!!
                AutomaticSubtitleSync.warmIndexedReferences(warmedIdx)
                val delayUrl2 = server.url("/sub-delay.srt?v=2").toString()
                val e2eWarmedDelayMs = measureNanoTime {
                    val res = AutomaticSubtitleSync.findTimelineRetime(
                        sourceKey = mkvUrlWarmed,
                        selectedSubtitleUrl = delayUrl2,
                        selectedSubtitleHeaders = emptyMap(),
                        preferredLanguage = "en",
                    )
                    assertNotNull(res)
                    assertTrue(res!!.timeline.confident)
                } / 1_000_000.0
                sb.appendLine("9b2. E2E SELECTED_ONLY (prefetched+warmed):    ${fmt(e2eWarmedDelayMs)} ms")

                // 9c. End-to-end SELECTED_ONLY (FPS drift, 20 refs)
                val fpsUrl = server.url("/sub-fps.srt").toString()
                val e2eSelectedFpsMs = measureNanoTime {
                    val res = AutomaticSubtitleSync.findTimelineRetime(
                        sourceKey = mkvUrl1,
                        selectedSubtitleUrl = fpsUrl,
                        selectedSubtitleHeaders = emptyMap(),
                        preferredLanguage = "en",
                    )
                    assertNotNull(res)
                    assertTrue(res!!.timeline.confident)
                } / 1_000_000.0
                sb.appendLine("9c. E2E SELECTED_ONLY (FPS drift, 20 refs):    ${fmt(e2eSelectedFpsMs)} ms")

                // 9d. End-to-end STARTUP_SEARCH (8 wrong candidates + 1 delay match at end)
                val mkvUrl2 = server.url("/movie2.mkv").toString()
                EmbeddedSubtitleTimelineLoader.load(mkvUrl2, emptyMap())
                val wrongCandidates = (0 until 8).map { i ->
                    AutoSyncSubtitleCandidate(
                        url = server.url("/sub-wrong-$i.srt").toString(),
                        language = "en",
                        name = "Wrong $i",
                    )
                }
                val goodCandidate = AutoSyncSubtitleCandidate(
                    url = delayUrl,
                    language = "en",
                    name = "Good Delay",
                )
                val e2eStartupMs = measureNanoTime {
                    val res = AutomaticSubtitleSync.findTimelineRetime(
                        sourceKey = mkvUrl2,
                        selectedSubtitleUrl = wrongCandidates.first().url,
                        selectedSubtitleHeaders = emptyMap(),
                        preferredLanguage = "en",
                        alternativeSubtitles = wrongCandidates + goodCandidate,
                    )
                    assertNotNull(res)
                    assertTrue(res!!.subtitleUrl == delayUrl)
                } / 1_000_000.0
                sb.appendLine("9d. E2E STARTUP_SEARCH (8 bad + 1 good, 20r):  ${fmt(e2eStartupMs)} ms")

                // 9e. End-to-end STARTUP_SEARCH (all 6 bad -> NO_FIT)
                val mkvUrl3 = server.url("/movie3.mkv").toString()
                EmbeddedSubtitleTimelineLoader.load(mkvUrl3, emptyMap())
                var outcome: AutoSyncAnalysisOutcome? = null
                val e2eAllBadMs = measureNanoTime {
                    val res = AutomaticSubtitleSync.findTimelineRetime(
                        sourceKey = mkvUrl3,
                        selectedSubtitleUrl = wrongCandidates.first().url,
                        selectedSubtitleHeaders = emptyMap(),
                        preferredLanguage = "en",
                        alternativeSubtitles = wrongCandidates.take(6),
                        onAnalysisOutcome = { outcome = it },
                    )
                    assertTrue(res == null)
                } / 1_000_000.0
                sb.appendLine("9e. E2E STARTUP_SEARCH (6 all-bad, 20 refs):   ${fmt(e2eAllBadMs)} ms | outcome=$outcome")
            } finally {
                server.shutdown()
            }
        }
        } finally {
            sb.appendLine("===========================================================")
            println(sb.toString())
        }
    }

    private fun measureAvgMs(iterations: Int, block: () -> Unit): Double {
        var totalNs = 0L
        repeat(iterations) {
            totalNs += measureNanoTime(block)
        }
        return totalNs / iterations.toDouble() / 1_000_000.0
    }

    private fun fmt(ms: Double): String = String.format("%8.2f", ms)

    private fun realisticMovieTimeline(count: Int, seed: Int): List<SubtitleSyncCue> {
        val rng = Random(seed)
        val out = ArrayList<SubtitleSyncCue>(count)
        var t = 45_000L
        for (i in 0 until count) {
            val dur = 1_100L + rng.nextInt(2_600)
            out += SubtitleSyncCue(t, t + dur, "Dialogue line $i with <i>emphasis</i> &amp; text")
            val gap = if (rng.nextInt(10) == 0) {
                4_000L + rng.nextInt(12_000)
            } else {
                200L + rng.nextInt(2_200)
            }
            t += dur + gap
        }
        return out
    }

    private fun independentLanguageAuthoring(
        base: List<SubtitleSyncCue>,
        seed: Int,
    ): List<SubtitleSyncCue> {
        val rng = Random(seed)
        val out = ArrayList<SubtitleSyncCue>(base.size)
        for (cue in base) {
            val jitter = rng.nextInt(-160, 161).toLong()
            val start = (cue.startTimeMs + jitter).coerceAtLeast(0L)
            val baseDur = cue.endTimeMs - cue.startTimeMs
            val dur = (baseDur + rng.nextInt(-350, 351).toLong()).coerceAtLeast(650L)
            out += SubtitleSyncCue(start, start + dur, cue.text)
        }
        return out.sortedBy { it.startTimeMs }
    }

    private fun realisticVariant(
        base: List<SubtitleSyncCue>,
        shiftMs: Long,
        scale: Double,
        seed: Int,
    ): List<SubtitleSyncCue> {
        val rng = Random(seed)
        val out = ArrayList<SubtitleSyncCue>(base.size)
        var i = 0
        while (i < base.size) {
            val cue = base[i]
            val jitter = rng.nextInt(-80, 81).toLong()
            val start = ((cue.startTimeMs + jitter) * scale).roundToLong() + shiftMs
            val durDelta = rng.nextInt(-150, 151).toLong()
            val dur = ((cue.endTimeMs - cue.startTimeMs + durDelta).coerceAtLeast(500L) * scale).roundToLong()
            if (start >= 0L) {
                out += SubtitleSyncCue(start, start + dur, cue.text)
            }
            i++
        }
        return out
    }

    private fun addSdhExtras(base: List<SubtitleSyncCue>, seed: Int): List<SubtitleSyncCue> {
        val rng = Random(seed)
        val out = ArrayList<SubtitleSyncCue>(base.size + base.size / 2)
        for ((idx, cue) in base.withIndex()) {
            out += cue
            if (idx % 2 == 0) {
                val s = cue.endTimeMs + 150L
                out += SubtitleSyncCue(s, s + 700L + rng.nextInt(500), "[sound effect $idx]")
            }
        }
        return out.sortedBy { it.startTimeMs }
    }

    private fun buildSrt(cues: List<SubtitleSyncCue>): String = buildString(cues.size * 64) {
        for ((idx, cue) in cues.withIndex()) {
            append(idx + 1).append('\n')
            append(fmtSrtTime(cue.startTimeMs)).append(" --> ").append(fmtSrtTime(cue.endTimeMs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    private fun buildVtt(cues: List<SubtitleSyncCue>): String = buildString(cues.size * 64) {
        append("WEBVTT\n\n")
        for (cue in cues) {
            append(fmtVttTime(cue.startTimeMs)).append(" --> ").append(fmtVttTime(cue.endTimeMs)).append('\n')
            append(cue.text).append("\n\n")
        }
    }

    private fun buildFansubAss(cues: List<SubtitleSyncCue>): String = buildString(cues.size * 100) {
        appendLine("[Script Info]")
        appendLine("ScriptType: v4.00+")
        appendLine()
        appendLine("[V4+ Styles]")
        appendLine("Format: Name, Fontname, Fontsize, Alignment")
        appendLine("Style: Default,Arial,48,2")
        appendLine("Style: OP Karaoke,Arial,40,8")
        appendLine("Style: Signs,Arial,40,5")
        appendLine()
        appendLine("[Events]")
        appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        for (i in 0 until 800) {
            val s = 5_000L + i * 100L
            appendLine("Dialogue: 0,${fmtAssTime(s)},${fmtAssTime(s + 100L)},OP Karaoke,,0,0,0,,{\\k10}la$i")
        }
        for (i in 0 until 400) {
            val s = 30_000L + i * 15_000L
            appendLine("Dialogue: 0,${fmtAssTime(s)},${fmtAssTime(s + 4_000L)},Signs,,0,0,0,,{\\pos(100,200)}Sign $i")
        }
        for (cue in cues) {
            appendLine("Dialogue: 0,${fmtAssTime(cue.startTimeMs)},${fmtAssTime(cue.endTimeMs)},Default,,0,0,0,,{\\i1}${cue.text}{\\i0}")
        }
    }

    private fun fmtSrtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1_000) % 60
        val f = ms % 1_000
        return String.format("%02d:%02d:%02d,%03d", h, m, s, f)
    }

    private fun fmtVttTime(ms: Long): String = fmtSrtTime(ms).replace(',', '.')

    private fun fmtAssTime(ms: Long): String {
        val cs = ms / 10
        return String.format("%d:%02d:%02d.%02d", cs / 360_000, (cs / 6_000) % 60, (cs / 100) % 60, cs % 100)
    }

    // Minimal valid Matroska binary builder: EBML + Segment(SeekHead + Info + Tracks + Cluster padding + Cues)
    private fun buildSyntheticMkvWithCuesAtEnd(tracks: List<ReferenceTrack>): ByteArray {
        val ebml = ebmlElement(0x1A45DFA3L, ebmlElement(0x4282L, "matroska".toByteArray()))
        val info = ebmlElement(0x1549A966L, ebmlUintElement(0x2AD7B1L, 1_000_000L))
        val tracksPayload = ByteArrayOutputStream()
        for ((idx, tr) in tracks.withIndex()) {
            val trackNum = (idx + 3).toLong()
            val entry = ByteArrayOutputStream().apply {
                write(ebmlUintElement(0xD7L, trackNum))
                write(ebmlUintElement(0x83L, 17L)) // subtitle
                write(ebmlElement(0x86L, "S_TEXT/UTF8".toByteArray()))
                write(ebmlElement(0x22B59CL, (tr.language ?: "eng").toByteArray()))
                write(ebmlElement(0x536EL, (tr.label ?: "Sub $idx").toByteArray()))
            }.toByteArray()
            tracksPayload.write(ebmlElement(0xAEL, entry))
        }
        val tracksEl = ebmlElement(0x1654AE6BL, tracksPayload.toByteArray())

        // Build Cues element (interleaved by timestamp across all 20 tracks)
        val cuesPayload = ByteArrayOutputStream()
        val allPoints = ArrayList<Triple<Long, Int, Long>>(tracks.size * 2_000)
        for ((idx, tr) in tracks.withIndex()) {
            val trackNum = idx + 3
            for (cue in tr.cues) {
                allPoints += Triple(cue.startTimeMs, trackNum, cue.endTimeMs - cue.startTimeMs)
            }
        }
        allPoints.sortBy { it.first }
        for ((startMs, trackNum, durMs) in allPoints) {
            val pos = ByteArrayOutputStream().apply {
                write(ebmlUintElement(0xF7L, trackNum.toLong()))
                write(ebmlUintElement(0xF1L, 700_000L)) // cluster pos
                write(ebmlUintElement(0xB2L, durMs.coerceAtLeast(1L)))
            }.toByteArray()
            val point = ByteArrayOutputStream().apply {
                write(ebmlUintElement(0xB3L, startMs))
                write(ebmlElement(0xB7L, pos))
            }.toByteArray()
            cuesPayload.write(ebmlElement(0xBBL, point))
        }
        val cuesEl = ebmlElement(0x1C53BB6BL, cuesPayload.toByteArray())

        // Put 700 KB dummy cluster between Tracks and Cues so Cues is outside the 512 KiB initial probe
        val dummyCluster = ebmlElement(0x1F43B675L, ByteArray(700 * 1024))

        // Build SeekHead with exact offsets (two-pass to settle SeekHead size)
        fun makeSeekHead(infoOff: Long, tracksOff: Long, cuesOff: Long): ByteArray {
            fun seekEntry(id: Long, pos: Long): ByteArray {
                val idBytes = idToBytes(id)
                val body = ByteArrayOutputStream().apply {
                    write(ebmlElement(0x53ABL, idBytes))
                    write(ebmlUintElement(0x53ACL, pos))
                }.toByteArray()
                return ebmlElement(0x4DBBL, body)
            }
            val payload = ByteArrayOutputStream().apply {
                write(seekEntry(0x1549A966L, infoOff))
                write(seekEntry(0x1654AE6BL, tracksOff))
                write(seekEntry(0x1C53BB6BL, cuesOff))
            }.toByteArray()
            return ebmlElement(0x114D9B74L, payload)
        }

        var seekHead = makeSeekHead(100L, 200L, 1_000_000L)
        repeat(3) {
            val infoOff = seekHead.size.toLong()
            val tracksOff = infoOff + info.size
            val cuesOff = tracksOff + tracksEl.size + dummyCluster.size
            seekHead = makeSeekHead(infoOff, tracksOff, cuesOff)
        }

        val segmentBody = ByteArrayOutputStream().apply {
            write(seekHead)
            write(info)
            write(tracksEl)
            write(dummyCluster)
            write(cuesEl)
        }.toByteArray()
        val segment = ebmlElement(0x18538067L, segmentBody)
        return ebml + segment
    }

    private fun idToBytes(id: Long): ByteArray {
        var len = 1
        while (len < 8 && id >= (1L shl (len * 8))) len++
        return ByteArray(len) { i -> ((id shr ((len - 1 - i) * 8)) and 0xFF).toByte() }
    }

    private fun encodeVintSize(size: Long): ByteArray {
        val len = when {
            size < (1L shl 7) - 1 -> 1
            size < (1L shl 14) - 1 -> 2
            size < (1L shl 21) - 1 -> 3
            else -> 4
        }
        val out = ByteArray(len)
        var v = size
        for (i in len - 1 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        out[0] = (out[0].toInt() or (1 shl (8 - len))).toByte()
        return out
    }

    private fun ebmlElement(id: Long, payload: ByteArray): ByteArray {
        val idBytes = idToBytes(id)
        val sizeBytes = encodeVintSize(payload.size.toLong())
        return idBytes + sizeBytes + payload
    }

    private fun ebmlUintElement(id: Long, value: Long): ByteArray {
        var len = 1
        while (len < 8 && value >= (1L shl (len * 8))) len++
        val payload = ByteArray(len) { i -> ((value shr ((len - 1 - i) * 8)) and 0xFF).toByte() }
        return ebmlElement(id, payload)
    }
}
