package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.*
import org.junit.Test

class PreviewPolicyTest {
    @Test fun playbackDropsSuspendWorkButPauseStillAllowsAnExplicitPreview() {
        val health = PreviewPlaybackHealth()
        health.observe(0, true, false, 0)
        health.observe(2_000, true, false, 2)
        assertFalse(health.allows(3_000, true))
        assertTrue(health.allows(3_000, false))
        assertTrue(health.allows(63_000, true))
    }
    @Test fun laterUncachedTargetSupersedesOldSeek() {
        val planner = PreviewPlanner(7_200_000)
        val old = planner.request(400_000)
        val latest = planner.request(5_400_000)
        assertFalse(planner.isCurrent(old))
        assertTrue(planner.isCurrent(latest))
        assertEquals(5_400_000L, planner.next({ false }, false))
        planner.completed(5_400_000)
        assertEquals(5_410_000L, planner.next({ false }, false))
    }
    @Test fun repeatedReadDoesNotInvalidateActiveFetch() {
        val planner = PreviewPlanner(1_000_000)
        val request = planner.request(500_000)
        repeat(100) { planner.request(500_000) }
        assertTrue(planner.isCurrent(request))
    }
    @Test fun reverseScrubPreparesBehindFirst() {
        val planner = PreviewPlanner(1_000_000)
        planner.request(500_000); planner.request(400_000)
        planner.completed(400_000)
        assertEquals(390_000L, planner.next({ false }, false))
    }
    @Test fun byteCacheEvictsByWorkingSetRatherThanItemCount() {
        val cache = PreviewLru<String, ByteArray>(10) { it.size }
        cache.put("a", ByteArray(6)); cache.put("b", ByteArray(5))
        assertNull(cache["a"]); assertNotNull(cache["b"])
        assertEquals(5L, cache.usedBytes())
        cache.put("too large", ByteArray(11))
        assertNull(cache["too large"])
        assertTrue(cache.usedBytes() <= 10)
    }
    @Test fun smallDeviceOnlyDecodes4kForAnExplicitPausedTargetWithRealHeadroom() {
        val budget = PreviewBudget(true)
        assertFalse(budget.canDecode(3840, 2160, 1000, 200))
        assertFalse(budget.canDecode(3840, 2160, 1000, 200, paused = true))
        assertFalse(budget.canDecode(3840, 2160, 449, 200, paused = true, explicit = true))
        assertFalse(budget.canDecode(3840, 2160, 1000, 63, paused = true, explicit = true))
        assertFalse(budget.canDecode(7680, 4320, 1000, 200, paused = true, explicit = true))
        assertTrue(budget.canDecode(3840, 2160, 450, 64, paused = true, explicit = true))
        assertTrue(budget.canDecode(1280, 720, 400, 100))
        assertFalse(budget.canDecode(1280, 720, 100, 100))
    }
    @Test fun backgroundImageWorkYieldsToDroppedFramesEvenWithoutARecentVideoDecode() {
        val health = PreviewPlaybackHealth()
        health.observe(0, true, false, 10)
        health.observe(1_000, true, false, 11)
        assertFalse(health.allows(2_000, true))
        assertTrue(health.allows(2_000, false))
        assertTrue(health.allows(61_001, true))
    }
    @Test fun distantFrameCannotBeReportedAsMatching() {
        assertFalse(PreviewTiming.near(60_000, 5_400_000))
        assertTrue(PreviewTiming.near(5_399_000, 5_400_000))
    }
    @Test fun copyNeedsThreeStrongIndependentAnchors() {
        assertFalse(PreviewCopies.verified(listOf(0.99f, 0.99f)))
        assertFalse(PreviewCopies.verified(listOf(0.99f, 0.50f, 0.99f)))
        assertTrue(PreviewCopies.verified(listOf(0.99f, 0.95f, 0.94f)))
    }
}
