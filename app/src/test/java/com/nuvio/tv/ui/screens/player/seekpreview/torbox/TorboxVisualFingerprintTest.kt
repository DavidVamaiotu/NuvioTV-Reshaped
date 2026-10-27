package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class TorboxVisualFingerprintTest {
    private val unusable = PreviewHash128(0, 0, false)
    private fun anchor(i: Int, ms: Long = i * 10_000L): TorboxVisualAnchor {
        val r = Random(i.toLong() + 913L)
        return TorboxVisualAnchor(i, ms, PreviewHash128(r.nextLong(), r.nextLong()))
    }
    private fun feed(matcher: TorboxVisualMatcher, pairs: List<Pair<Long, PreviewHash128>>) {
        var previous = 0L
        for ((at, hash) in pairs.sortedBy { it.first }) {
            var tick = previous + 500
            while (tick < at) { matcher.observe(tick, unusable); tick += 500 }
            matcher.observe(at, hash)
            previous = at
        }
        matcher.observe(previous + 500, unusable) // Close the final observation window.
    }
    @Test fun matchesPillowCenterDhash128GoldenVectors() {
        val expected = listOf("3cf0800001071e7839f1232323677e62", "4a4ab54ab54ab54ab54ab54ab54ab54a",
            "00000000000000000000000000000000", "459afe0941a16a95c531d63859a54ab5")
        for (mode in 0..3) {
            val pixels = IntArray(96 * 64) { i ->
                val x = i % 96; val y = i / 96
                val rgb = when (mode) {
                    0 -> intArrayOf((x*7+y*3)%256, (x*2+y*9)%256, (x*11+y)%256)
                    1 -> IntArray(3) { if ((x/7+y/5)%2 == 1) 255 else 0 }
                    2 -> IntArray(3) { 100 }
                    else -> intArrayOf((x*x+y*13)%256, (x*17+y*y)%256, (x*y*3)%256)
                }
                (255 shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
            }
            val actual = TorboxVisualFingerprint.hash(96, 64, pixels)
            assertEquals("mode $mode", expected[mode], actual.hex())
            assertEquals(mode != 2, actual.usable)
        }
    }
    @Test fun hashParsingPreservesHighAndLowBitOrder() {
        val hex = "80000000000000010000000000000002"
        val hash = PreviewHash128.parse(hex, true)
        assertEquals(hex, hash.hex()); assertEquals(3, hash.distance(PreviewHash128(0, 0)))
    }
    @Test fun threeDistinctScenesEstablishAnOffsetDespiteCompressionNoise() {
        val anchors = (1..20).map { anchor(it) }
        val matcher = TorboxVisualMatcher(anchors)
        feed(matcher, anchors.take(3).map { it.positionMs - 2000 to it.hash.copy(low = it.hash.low xor 3) })
        val map = matcher.mapping()!!
        assertEquals(2000.0, map.offsetMs, .01); assertEquals(1.0, map.scale, .001)
        assertEquals(28_000L, map.playback(30_000))
    }
    @Test fun ambiguousOrDarkFramesCannotAlign() {
        val a = anchor(1)
        val matcher = TorboxVisualMatcher(listOf(a, a.copy(frame = 2, positionMs = 20_000), anchor(3)))
        assertNull(matcher.observe(10_000, a.hash)); assertNull(matcher.observe(20_000, unusable))
        assertNull(matcher.mapping())
    }
    @Test fun repeatedSingleSceneIsInsufficient() {
        val anchors = (1..8).map { anchor(it) }; val matcher = TorboxVisualMatcher(anchors)
        feed(matcher, listOf(10_000L, 10_100L, 10_200L).map { it to anchors[0].hash })
        assertNull(matcher.mapping())
    }
    @Test fun cutsAndInconsistentOffsetsAreRejected() {
        val a = (1..10).map { anchor(it) }; val matcher = TorboxVisualMatcher(a)
        feed(matcher, listOf(8_000L to a[0].hash, 18_000L to a[1].hash, 25_000L to a[2].hash))
        assertNull(matcher.mapping())
    }
    @Test fun sourcePositionJumpDropsPreviousEvidence() {
        val a = (1..20).map { anchor(it) }; val matcher = TorboxVisualMatcher(a)
        feed(matcher, a.take(3).map { it.positionMs to it.hash }); assertNotNull(matcher.mapping())
        matcher.observe(150_000L, a[14].hash); assertNull(matcher.mapping())
    }
    @Test fun mappedCueBoundariesStillSelectTheFollowingSprite() {
        for (scale in listOf(1.0, 24.0 / 25, 25.0 / 24)) {
            val map = TorboxVisualMapping(scale, -2371.5)
            for (reference in 0L..1_000_000L step 10_000L) {
                assertTrue(map.reference(map.playback(reference)) >= reference)
                assertTrue(map.reference(map.playback(reference)) - reference <= 2)
            }
        }
    }
    @Test fun fpsChangeRequiresMoreScenesAcrossTwoMinutes() {
        val a = (1..15).map { anchor(it, it * 30_000L) }
        val matcher = TorboxVisualMatcher(a, 24.0 / 25)
        val pairs = a.take(5).map { ((it.positionMs - 3000) / (24.0 / 25)).toLong() to it.hash }
        feed(matcher, pairs.take(3)); assertNull(matcher.mapping())
        // Continue, including low-quality intervening playback observations.
        var tick = pairs[2].first + 500
        for ((at, hash) in pairs.drop(3)) {
            while (tick < at) { matcher.observe(tick, unusable); tick += 500 }
            matcher.observe(at, hash); tick = at + 500
        }
        matcher.observe(pairs.last().first + 500, unusable)
        val map = matcher.mapping()!!
        assertEquals(24.0 / 25, map.scale, .00001); assertEquals(3000.0, map.offsetMs, 1.0)
        assertTrue(abs(map.playback(map.reference(500_000)) - 500_000) <= 2)
    }

    @Test fun productionTenSecondCadenceCanVerifyBothFrameRateCorrections() {
        val a = (1..30).map { anchor(it) }
        for (scale in listOf(24.0 / 25, 25.0 / 24)) {
            val matcher = TorboxVisualMatcher(a, scale)
            feed(matcher, a.map { ((it.positionMs - 2000) / scale).toLong() to it.hash })
            val map = matcher.mapping()!!
            assertEquals(scale, map.scale, .00001)
            assertEquals(2000.0, map.offsetMs, 1.0)
        }
    }

    @Test fun matchingStillShotsDoNotCreateAFalseTwoSecondOffset() {
        val a = (1..20).map { anchor(it) }
        val matcher = TorboxVisualMatcher(a)
        for (at in 0L..35_000L step 500L) {
            val shot = a.take(3).firstOrNull { at in (it.positionMs - 2000)..(it.positionMs + 2000) }
            matcher.observe(at, shot?.hash ?: unusable)
            assertNull("Must not accept a provisional first capture at $at", matcher.mapping())
        }
        feed(matcher, a.drop(4).take(3).map { it.positionMs to it.hash })
        assertEquals(0.0, matcher.mapping()!!.offsetMs, .01)
    }

    @Test fun aMatchingWindowMustCloseBeforeItBecomesEvidence() {
        val a = (1..20).map { anchor(it) }
        val matcher = TorboxVisualMatcher(a)
        feed(matcher, a.take(2).map { it.positionMs to it.hash })
        matcher.observe(30_000, a[2].hash)
        assertNull(matcher.mapping())
        matcher.observe(30_500, unusable)
        assertNotNull(matcher.mapping())
    }

    @Test fun anEarlyOutlierExpiresWithoutPoisoningTheFiveMinuteMatchingBudget() {
        val a = (1..30).map { anchor(it) }
        val matcher = TorboxVisualMatcher(a)
        feed(matcher, a.mapIndexed { i, anchor ->
            (anchor.positionMs - if (i == 0) 3000 else 0) to anchor.hash
        })
        assertEquals(0.0, matcher.mapping()!!.offsetMs, .01)
    }
}
