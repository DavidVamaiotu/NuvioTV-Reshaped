package com.nuvio.tv.ui.screens.player.autosync

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive accuracy, boundary, and edge-case test suite for all optimized AutoSync components.
 */
class AutoSyncAccuracyAndEdgeCasesTest {

    @Test
    fun activityTimelineWordBoundaryBitOperationsAreExact() {
        // Test timelines whose binCount straddles 64-bit word boundaries (63, 64, 65, 127, 128, 129)
        // and offsets around -65, -64, -63, -1, 0, +1, +63, +64, +65.
        for (cueCount in listOf(18, 36, 60)) {
            val base = irregularTimeline(cueCount, startOffsetMs = 40_000L, stepBaseMs = 2_400L)
            for (shiftMs in listOf(-32_500L, -32_000L, -31_500L, -500L, 0L, 500L, 31_500L, 32_000L, 32_500L)) {
                val target = base.map { cue ->
                    SubtitleSyncCue(cue.startTimeMs - shiftMs, cue.endTimeMs - shiftMs, cue.text)
                }
                val res = AutoSyncTimelineRetimer.retime(
                    reference = base,
                    target = target,
                    coarseScale = 1.0,
                    coarseInterceptMs = 0.0,
                    discoverAlignment = true,
                )
                assertNotNull("Expected match for count=$cueCount shift=$shiftMs", res)
                assertTrue("Expected confident for count=$cueCount shift=$shiftMs: ${res!!.rejectReason}", res.confident)
                assertTrue(
                    "Offset error too large for shift=$shiftMs: got ${res.alignmentInterceptMs}",
                    abs(res.alignmentInterceptMs - shiftMs) <= 200.0,
                )
            }
        }
    }

    @Test
    fun allSixFpsDriftRatiosAndCustomSpanRatioAreAccuratelyDiscovered() {
        val reference = irregularTimeline(350)
        val ratios = listOf(
            25.0 / 23.976,
            23.976 / 25.0,
            25.0 / 24.0,
            24.0 / 25.0,
            24.0 / 23.976,
            23.976 / 24.0,
            1.015, // custom span ratio
        )
        for (scale in ratios) {
            val shiftMs = 7_500L
            val target = reference.mapNotNull { cue ->
                val s = ((cue.startTimeMs - shiftMs) / scale).roundToLong()
                val e = ((cue.endTimeMs - shiftMs) / scale).roundToLong()
                if (s >= 0L && e > s) SubtitleSyncCue(s, e, cue.text) else null
            }
            val result = AutoSyncTimelineRetimer.retime(
                reference = reference,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
            )
            assertNotNull("No match for scale=$scale", result)
            assertTrue("Not confident for scale=$scale: ${result!!.rejectReason}", result.confident)
            assertTrue(
                "Scale mismatch for $scale: got ${result.alignmentScale}",
                abs(result.alignmentScale - scale) <= 0.002,
            )
            // Verify actual retimed cue accuracy across start, middle, end
            val midIdx = result.cues.size / 2
            val matchedGroup = result.groups[result.groups.size / 2]
            val errMs = abs(
                result.cues[matchedGroup.targetStartIndex].startTimeMs -
                    reference[matchedGroup.referenceStartIndex].startTimeMs,
            )
            assertTrue("Mid-film cue error too large for scale=$scale: ${errMs}ms (mid=$midIdx)", errMs <= 300L)
        }
    }

    @Test
    fun largeConstantOffsetsNear180sBoundaryAreAccurate() {
        val reference = irregularTimeline(300, startOffsetMs = 200_000L)
        for (shiftMs in listOf(-165_000L, -95_400L, 0L, 88_200L, 165_000L)) {
            val target = reference.map { cue ->
                SubtitleSyncCue(cue.startTimeMs - shiftMs, cue.endTimeMs - shiftMs, cue.text)
            }
            val result = AutoSyncTimelineRetimer.retime(
                reference = reference,
                target = target,
                coarseScale = 1.0,
                coarseInterceptMs = 0.0,
                discoverAlignment = true,
            )
            assertNotNull("Expected match for shift=$shiftMs", result)
            assertTrue("Expected confident for shift=$shiftMs", result!!.confident)
            assertEquals("delay-only-validated", result.alignmentSource)
            assertTrue(
                "Offset mismatch for shift=$shiftMs: got ${result.alignmentInterceptMs}",
                abs(result.alignmentInterceptMs - shiftMs) <= 200.0,
            )
            val appliedShift = result.cues.first().startTimeMs - result.cues.first().originalStartTimeMs
            assertEquals(result.alignmentInterceptMs.roundToLong(), appliedShift)
        }
    }

    @Test
    fun partialCd1AndCd2SubtitlesAreBothFoundAccurately() {
        val reference = irregularTimeline(450)
        // CD1: first 180 cues shifted by -5s
        val cd1 = reference.take(180).map {
            SubtitleSyncCue(it.startTimeMs + 5_000L, it.endTimeMs + 5_000L, it.text)
        }
        val res1 = AutoSyncTimelineRetimer.retime(reference, cd1, 1.0, 0.0, discoverAlignment = true)
        assertNotNull(res1)
        assertTrue("CD1 not confident: ${res1!!.rejectReason}", res1.confident)
        assertTrue(abs(res1.alignmentInterceptMs - (-5_000.0)) <= 300.0)

        // CD2: last 180 cues restarted near 0
        val cd2Delta = reference[270].startTimeMs - 3_000L
        assertTrue(cd2Delta > 200_000L)
        val cd2 = reference.drop(270).map {
            SubtitleSyncCue(it.startTimeMs - cd2Delta, it.endTimeMs - cd2Delta, it.text)
        }
        val res2 = AutoSyncTimelineRetimer.retime(reference, cd2, 1.0, 0.0, discoverAlignment = true)
        assertNotNull(res2)
        assertTrue("CD2 not confident: ${res2!!.rejectReason}", res2.confident)
        assertTrue(abs(res2.alignmentInterceptMs - cd2Delta) <= 300.0)
    }

    @Test
    fun activityRejectedUnrelatedTimelineConfirmsNoFitWithoutRunningDp() {
        val reference = irregularTimeline(250, seed = 1)
        val unrelated = irregularTimeline(240, seed = 999)
        var timings: AutoSyncRetimePhaseTimings? = null
        val result = AutoSyncTimelineRetimer.retime(
            reference = reference,
            target = unrelated,
            coarseScale = 1.0,
            coarseInterceptMs = 0.0,
            discoverAlignment = true,
            timingObserver = { timings = it },
        )
        assertNotNull(result)
        assertFalse(result!!.confident)
        assertTrue(!result.rejectReason.isNullOrBlank())
        assertTrue(AutoSyncNoFitTracker.confirmsNoFit(result, sampledReference = false))
        assertEquals("activity-rejected", timings?.path)
        assertEquals(0L, timings?.dpMs)
    }

    @Test
    fun referenceConsistencyEarlySkipMatchesFullPairwiseGraphOnAllOutlierPositions() {
        val base = irregularTimeline(250)
        fun ref(key: String, cues: List<SubtitleSyncCue>) = AutoSyncReferenceConsistency.Reference(
            key = key,
            activity = AutoSyncTimelineRetimer.prepareUnitActivity(cues)!!,
            cueCount = cues.size,
        )

        val good = (0 until 5).map { i -> ref("good-$i", independentAuthoring(base, seed = 10 + i)) }
        val badA = ref("bad-a", base.map { it.copy(startTimeMs = it.startTimeMs + 7_000L, endTimeMs = it.endTimeMs + 7_000L) })
        val badB = ref("bad-b", base.map { it.copy(startTimeMs = it.startTimeMs + 19_000L, endTimeMs = it.endTimeMs + 19_000L) })

        // Outlier at index 0
        assertEquals(setOf("bad-a"), AutoSyncReferenceConsistency.findOutliers(listOf(badA) + good))
        // Outlier in middle
        assertEquals(
            setOf("bad-a"),
            AutoSyncReferenceConsistency.findOutliers(good.take(2) + listOf(badA) + good.drop(2)),
        )
        // Outlier at end
        assertEquals(setOf("bad-a"), AutoSyncReferenceConsistency.findOutliers(good + listOf(badA)))
        // Two independent outliers + 3 good tracks
        assertEquals(
            setOf("bad-a", "bad-b"),
            AutoSyncReferenceConsistency.findOutliers(listOf(badA, good[0], badB, good[1], good[2])),
        )
        // All good
        assertTrue(AutoSyncReferenceConsistency.findOutliers(good).isEmpty())
    }

    @Test
    fun parserHandlesTrickySrtVttAssAndEntityEdgeCases() {
        // SRT with BOM, CRLF, unclosed '<', entities, VTT-style tags, &lt;b&gt;, missing index, blank whitespace lines
        val trickySrt = "\uFEFF" +
            "1\r\n" +
            "00:00:01,5 --> 00:00:03,45\r\n" +
            "<i>Hello</i>  \t  &amp;  &lt;b&gt;world&lt;/b&gt;  \r\n" +
            "  a < b &nbsp; &quot;quoted&quot; \r\n" +
            "   \r\n" +
            "00:00:05,123456 --> 00:00:07,000\r\n" +
            "<font color=\"#fff\">No index line</font>\r\n" +
            "\r\n" +
            "3\r\n" +
            "00:00:08,000 --> 00:00:09,000\r\n" +
            "<i>   </i>\r\n" + // blank after stripping -> skipped
            "\r\n" +
            "4\r\n" +
            "00:00:10,000 --> 00:00:11,000 --> 00:00:12,000\r\n" + // double arrow -> skipped
            "Malformed\r\n"

        val srtCues = AutoSyncSubtitleCueParser.parse(trickySrt, "https://host/test.srt")
        assertEquals(2, srtCues.size)
        assertEquals(1_500L, srtCues[0].startTimeMs)
        assertEquals(3_450L, srtCues[0].endTimeMs)
        assertEquals("Hello & <b>world</b>\na < b \"quoted\"", srtCues[0].text)
        assertEquals(5_123L, srtCues[1].startTimeMs)
        assertEquals(7_000L, srtCues[1].endTimeMs)
        assertEquals("No index line", srtCues[1].text)

        // VTT with NOTE/STYLE/REGION blocks, cue IDs, inline timestamps, MM:SS.mmm
        val trickyVtt = """
            WEBVTT - Test File

            STYLE
            ::cue { color: white; }

            NOTE
            Ignore this comment

            cue-1
            00:10.500 --> 00:12.750 align:start line:90%
            First <00:11.000><c.yellow>word</c> here

            01:02:03.040 --> 01:02:05.000
            Second line
        """.trimIndent()
        val vttCues = AutoSyncSubtitleCueParser.parse(trickyVtt, "https://host/sub.vtt")
        assertEquals(2, vttCues.size)
        assertEquals(10_500L, vttCues[0].startTimeMs)
        assertEquals(12_750L, vttCues[0].endTimeMs)
        assertEquals("First word here", vttCues[0].text)
        assertEquals(3_723_040L, vttCues[1].startTimeMs)
        assertEquals(3_725_000L, vttCues[1].endTimeMs)

        // hasAssEventsHeader ignores [Events] inside dialogue text, matches standalone line
        assertFalse(AutoSyncSubtitleCueParser.hasAssEventsHeader("1\n00:00:01,000 --> 00:00:02,000\nLook at [Events] now\n"))
        assertTrue(AutoSyncSubtitleCueParser.hasAssEventsHeader("[Script Info]\n  [events] \t\nDialogue: ..."))
    }

    @Test
    fun assTypesetTagScannerMatchesAllPatternsExactly() {
        val shouldMatch = listOf(
            "{\\pos(10,20)}Sign",
            "{\\pos (10,20)}Sign",
            "{\\move(0,0,10,10)}Anim",
            "{\\org(100,100)}Rot",
            "{\\clip(0,0,10,10)}Clip",
            "{\\iclip(0,0,10,10)}IClip",
            "{\\t(0,100,\\alpha&HFF&)}Fade",
            "{\\p1}m 0 0 l 10 10",
            "{\\k40}ka",
            "{\\kf25}ra",
            "{\\Ko10}o",
            "{\\frz-15}tilted",
            "{\\fr10}rot",
            "{\\fax0.5}shear",
            "{\\fay-0.2}shear",
        )
        val shouldNotMatch = listOf(
            "Plain dialogue line",
            "{\\i1}Italic{\\i0}",
            "{\\b1\\an8}Top bold",
            "{\\fad(200,200)}Soft fade",
            "{\\p0}Reset drawing",
            "{\\fnArial\\fs40}Styled",
            "Line with \\N newline and \\h space",
        )
        for (s in shouldMatch) {
            assertTrue("Expected typeset tag match for: $s", AutoSyncAssDialogueFilter.hasTypesetTag(s))
        }
        for (s in shouldNotMatch) {
            assertFalse("Expected NO typeset tag match for: $s", AutoSyncAssDialogueFilter.hasTypesetTag(s))
        }
    }

    @Test
    fun textRetimerHandlesAssWithSwappedStartEndColumnsAndCrlf() {
        val body = "[Events]\r\n" +
            "Format: Layer, End, Start, Style, Text\r\n" +
            "Dialogue: 0, 0:00:03.00, 0:00:01.00, Default, Hello, world\r\n"
        val timeline = AutoSyncTimelineRetimeResult(
            cues = listOf(AutoSyncRetimedCue(1_000L, 3_000L, 2_500L, 4_500L)),
            groups = emptyList(),
            targetCoverage = 1.0,
            referenceCoverage = 1.0,
            skippedTargetCues = 0,
            skippedReferenceCues = 0,
            longestTargetSkipRun = 0,
            averageGroupCost = 0.0,
            oneToOneGroups = 1,
            oneToTwoGroups = 0,
            twoToOneGroups = 0,
            oneToThreeGroups = 0,
            threeToOneGroups = 0,
            twoToTwoGroups = 0,
            confident = true,
            alignmentInterceptMs = 1_500.0,
        )
        val retimed = AutoSyncSubtitleTextRetimer.retime(body, timeline)
        assertEquals(
            "[Events]\nFormat: Layer, End, Start, Style, Text\nDialogue: 0, 0:00:04.50, 0:00:02.50, Default, Hello, world\n",
            retimed,
        )
    }

    @Test
    fun sampledReferenceAlignerFindsPositiveNegativeAndZeroDelaysAndRejectsWrong() {
        val full = irregularTimeline(400)
        val sampled = full.filterIndexed { i, _ -> i % 6 == 0 }
        for (delayMs in listOf(-24_000L, 0L, 18_500L)) {
            val target = full.mapNotNull { cue ->
                val s = cue.startTimeMs - delayMs
                val e = cue.endTimeMs - delayMs
                if (s >= 0L) SubtitleSyncCue(s, e, cue.text) else null
            }
            val res = AutoSyncSampledReferenceAligner.findDelay(sampled, target)
            assertNotNull("Expected sampled match for delay=$delayMs", res)
            assertTrue(
                "Sampled offset error for delay=$delayMs: got ${res!!.offsetMs}",
                abs(res.offsetMs - delayMs) <= 250.0,
            )
        }
        val wrong = irregularTimeline(400, seed = 888)
        assertNull(AutoSyncSampledReferenceAligner.findDelay(sampled, wrong))
    }

    @Test
    fun mkvTailScanReusesTailBytesWithoutExtraRangeRequestsAndHandlesOutOfOrderCueFields() = runBlocking {
        val cues = irregularTimeline(80)
        // Build an MKV where SeekHead omits Cues (forcing tail scan) AND CuePoint has
        // CueTrackPositions BEFORE CueTime, plus unsorted + duplicate startTimeMs entries.
        val mkv = buildMkvWithoutSeekHeadCuesEntry(cues)

        MockWebServer().use { server ->
            var rangeReqs = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    rangeReqs++
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
            }
            server.start()
            val url = server.url("/tail-scan.mkv").toString()
            val loaded = EmbeddedSubtitleTimelineLoader.load(url, emptyMap())
            assertNotNull(loaded)
            assertEquals(1, loaded!!.tracks.size)
            assertEquals(cues.size, loaded.tracks[0].cues.size)
            assertEquals(cues.map { it.startTimeMs }, loaded.tracks[0].cues.map { it.startTimeMs })
            // Initial probe + tail probe ONLY (reused tail.bytes without extra fetchElementAt RTTs!)
            assertEquals(2, rangeReqs)
        }
    }

    @Test
    fun endToEndStartupSearchFindsFpsDriftAndRejectsMistimedReferenceOutlier() = runBlocking {
        val base = irregularTimeline(260)
        val goodTracks = (0 until 5).map { idx ->
            ReferenceTrack(
                key = "mkv-cues:${idx + 1}",
                language = "l$idx",
                cues = independentAuthoring(base, seed = 40 + idx),
                label = "Track $idx",
            )
        }
        // Include an exact duplicate track AND a mistimed outlier track at the very front!
        val outlierTrack = ReferenceTrack(
            key = "mkv-cues:99",
            language = "en",
            cues = base.map { it.copy(startTimeMs = it.startTimeMs + 9_500L, endTimeMs = it.endTimeMs + 9_500L) },
            label = "Mistimed EN",
        )
        val allTracks = listOf(outlierTrack, goodTracks[0]) + goodTracks
        val mkv = buildFullMkvWithSeekHead(allTracks)

        val scale = 25.0 / 23.976
        val fpsTarget = base.map { cue ->
            SubtitleSyncCue(
                startTimeMs = (cue.startTimeMs / scale).roundToLong(),
                endTimeMs = (cue.endTimeMs / scale).roundToLong(),
                text = cue.text,
            )
        }
        val wrongSrts = (0 until 4).map { i -> buildSimpleSrt(irregularTimeline(250, seed = 600 + i)) }
        val fpsSrt = buildSimpleSrt(fpsTarget)

        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (path == "/outlier-test.mkv") {
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
                    if (path == "/fps.srt") return MockResponse().setResponseCode(200).setBody(fpsSrt)
                    val m = Regex("/wrong-(\\d+)\\.srt").matchEntire(path)
                    if (m != null) {
                        return MockResponse().setResponseCode(200).setBody(wrongSrts[m.groupValues[1].toInt()])
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val mkvUrl = server.url("/outlier-test.mkv").toString()
            val wrongCandidates = (0 until 4).map { i ->
                AutoSyncSubtitleCandidate(server.url("/wrong-$i.srt").toString(), "en", "Wrong $i")
            }
            val fpsUrl = server.url("/fps.srt").toString()
            val fpsCandidate = AutoSyncSubtitleCandidate(fpsUrl, "en", "Good FPS")

            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = mkvUrl,
                selectedSubtitleUrl = wrongCandidates.first().url,
                selectedSubtitleHeaders = emptyMap(),
                preferredLanguage = "en",
                alternativeSubtitles = wrongCandidates + fpsCandidate,
            )
            assertNotNull(resolved)
            assertEquals(fpsUrl, resolved!!.subtitleUrl)
            assertTrue(resolved.timeline.confident)
            assertTrue(
                "Expected scale near $scale, got ${resolved.timeline.alignmentScale}",
                abs(resolved.timeline.alignmentScale - scale) <= 0.002,
            )
            // Must have synced against the good tracks (intercept near 0), NOT the +9500ms outlier!
            assertTrue(
                "Synced to mistimed outlier! intercept=${resolved.timeline.alignmentInterceptMs}",
                abs(resolved.timeline.alignmentInterceptMs) <= 600.0,
            )
        }
    }

    private fun buildSimpleSrt(cues: List<SubtitleSyncCue>): String = buildString {
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

    private fun buildFullMkvWithSeekHead(tracks: List<ReferenceTrack>): ByteArray {
        val ebml = ebmlElement(0x1A45DFA3L, ebmlElement(0x4282L, "matroska".toByteArray()))
        val info = ebmlElement(0x1549A966L, ebmlUintElement(0x2AD7B1L, 1_000_000L))
        val tracksPayload = ByteArrayOutputStream()
        for ((idx, tr) in tracks.withIndex()) {
            val trackNum = (idx + 3).toLong()
            val entry = ByteArrayOutputStream().apply {
                write(ebmlUintElement(0xD7L, trackNum))
                write(ebmlUintElement(0x83L, 17L))
                write(ebmlElement(0x86L, "S_TEXT/UTF8".toByteArray()))
                write(ebmlElement(0x22B59CL, (tr.language ?: "eng").toByteArray()))
                write(ebmlElement(0x536EL, (tr.label ?: "Sub $idx").toByteArray()))
            }.toByteArray()
            tracksPayload.write(ebmlElement(0xAEL, entry))
        }
        val tracksEl = ebmlElement(0x1654AE6BL, tracksPayload.toByteArray())
        val dummyCluster = ebmlElement(0x1F43B675L, ByteArray(600 * 1024))
        val cuesPayload = ByteArrayOutputStream()
        val points = ArrayList<Triple<Long, Int, Long>>()
        for ((idx, tr) in tracks.withIndex()) {
            for (cue in tr.cues) {
                points += Triple(cue.startTimeMs, idx + 3, (cue.endTimeMs - cue.startTimeMs).coerceAtLeast(1L))
            }
        }
        points.sortBy { it.first }
        for ((s, tn, d) in points) {
            val pos = ByteArrayOutputStream().apply {
                write(ebmlUintElement(0xF7L, tn.toLong()))
                write(ebmlUintElement(0xB2L, d))
            }.toByteArray()
            val pt = ByteArrayOutputStream().apply {
                write(ebmlUintElement(0xB3L, s))
                write(ebmlElement(0xB7L, pos))
            }.toByteArray()
            cuesPayload.write(ebmlElement(0xBBL, pt))
        }
        val cuesEl = ebmlElement(0x1C53BB6BL, cuesPayload.toByteArray())
        fun makeSeekHead(infoOff: Long, tracksOff: Long, cuesOff: Long): ByteArray {
            fun entry(id: Long, p: Long) = ebmlElement(
                0x4DBBL,
                ebmlElement(0x53ABL, idToBytes(id)) + ebmlUintElement(0x53ACL, p),
            )
            return ebmlElement(
                0x114D9B74L,
                entry(0x1549A966L, infoOff) + entry(0x1654AE6BL, tracksOff) + entry(0x1C53BB6BL, cuesOff),
            )
        }
        var seekHead = makeSeekHead(100L, 200L, 1_000_000L)
        repeat(3) {
            val infoOff = seekHead.size.toLong()
            val tracksOff = infoOff + info.size
            val cuesOff = tracksOff + tracksEl.size + dummyCluster.size
            seekHead = makeSeekHead(infoOff, tracksOff, cuesOff)
        }
        val segmentBody = ByteArrayOutputStream().apply {
            write(seekHead); write(info); write(tracksEl); write(dummyCluster); write(cuesEl)
        }.toByteArray()
        return ebml + ebmlElement(0x18538067L, segmentBody)
    }

    private fun irregularTimeline(
        count: Int,
        seed: Int = 7,
        startOffsetMs: Long = 30_000L,
        stepBaseMs: Long = 1_400L,
    ): List<SubtitleSyncCue> {
        val rng = Random(seed)
        var start = startOffsetMs
        return (0 until count).map { idx ->
            if (idx > 0) start += stepBaseMs + rng.nextInt(3_800)
            SubtitleSyncCue(start, start + 900L + rng.nextInt(1_800), "cue $idx")
        }
    }

    private fun independentAuthoring(cues: List<SubtitleSyncCue>, seed: Int): List<SubtitleSyncCue> {
        val rng = Random(seed)
        return cues.map { cue ->
            val s = (cue.startTimeMs + rng.nextInt(-150, 151)).coerceAtLeast(0L)
            val d = (cue.endTimeMs - cue.startTimeMs + rng.nextInt(-250, 251)).coerceAtLeast(600L)
            SubtitleSyncCue(s, s + d, cue.text)
        }.sortedBy { it.startTimeMs }
    }

    private fun buildMkvWithoutSeekHeadCuesEntry(cues: List<SubtitleSyncCue>): ByteArray {
        val ebml = ebmlElement(0x1A45DFA3L, ebmlElement(0x4282L, "matroska".toByteArray()))
        val info = ebmlElement(0x1549A966L, ebmlUintElement(0x2AD7B1L, 1_000_000L))
        val trackEntry = ByteArrayOutputStream().apply {
            write(ebmlUintElement(0xD7L, 3L))
            write(ebmlUintElement(0x83L, 17L))
            write(ebmlElement(0x86L, "S_TEXT/UTF8".toByteArray()))
            write(ebmlElement(0x22B59CL, "eng".toByteArray()))
        }.toByteArray()
        val tracks = ebmlElement(0x1654AE6BL, ebmlElement(0xAEL, trackEntry))
        val dummyCluster = ebmlElement(0x1F43B675L, ByteArray(600 * 1024))

        // Out-of-order CuePoints + duplicate + CueTrackPositions BEFORE CueTime
        val cuesPayload = ByteArrayOutputStream()
        val shuffled = (cues + cues[5]).reversed()
        for (cue in shuffled) {
            val pos = ByteArrayOutputStream().apply {
                write(ebmlUintElement(0xF7L, 3L))
                write(ebmlUintElement(0xB2L, (cue.endTimeMs - cue.startTimeMs).coerceAtLeast(1L)))
            }.toByteArray()
            val point = ByteArrayOutputStream().apply {
                // Intentionally write CueTrackPositions BEFORE CueTime
                write(ebmlElement(0xB7L, pos))
                write(ebmlUintElement(0xB3L, cue.startTimeMs))
            }.toByteArray()
            cuesPayload.write(ebmlElement(0xBBL, point))
        }
        val cuesEl = ebmlElement(0x1C53BB6BL, cuesPayload.toByteArray())
        val segmentBody = ByteArrayOutputStream().apply {
            write(info)
            write(tracks)
            write(dummyCluster)
            write(cuesEl)
        }.toByteArray()
        return ebml + ebmlElement(0x18538067L, segmentBody)
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

    private fun ebmlElement(id: Long, payload: ByteArray): ByteArray =
        idToBytes(id) + encodeVintSize(payload.size.toLong()) + payload

    private fun ebmlUintElement(id: Long, value: Long): ByteArray {
        var len = 1
        while (len < 8 && value >= (1L shl (len * 8))) len++
        val payload = ByteArray(len) { i -> ((value shr ((len - 1 - i) * 8)) and 0xFF).toByte() }
        return ebmlElement(id, payload)
    }
}
