// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The strong-box decision from real memory numbers, and stopping the coverage pass when memory runs out. */
class ThumbMemoryStopTest {

    private val mb = 1024L * 1024

    private fun strong(
        is64Bit: Boolean = true, totalMb: Long = 3_800, heapMb: Long = 384, heapUsedMb: Long = 297,
        lowMemory: Boolean = false, availMb: Long = 1_500, swapTotalMb: Long = 1_750, swapFreeMb: Long = 1_200,
    ) = MemoryGovernor.strongBoxVerdict(
        is64Bit, totalMb * mb, heapMb * mb, heapUsedMb * mb, lowMemory, availMb, swapTotalMb, swapFreeMb,
    )

    @Test
    fun hardwareClassIgnoresTheMemoryFreeRightNow() {
        assertTrue("AM9", MemoryGovernor.strongHardware(true, 3_712 * mb, 384 * mb))
        assertTrue("Shield Pro", MemoryGovernor.strongHardware(true, 2_900 * mb, 512 * mb))
        assertFalse("32-bit Fire TV Stick", MemoryGovernor.strongHardware(false, 1_669 * mb, 384 * mb))
        assertFalse("2 GB box", MemoryGovernor.strongHardware(true, 1_900 * mb, 384 * mb))
        assertFalse("small Java heap", MemoryGovernor.strongHardware(true, 3_712 * mb, 256 * mb))
    }

    @Test
    fun am9IsStrong() {
        assertTrue("297 MB of 384 used, as in a 4K session on the AM9", strong().allowed)
    }

    @Test
    fun shieldWithSwapFullIsProtectedThoughItHas3Gb() {
        val v = strong(totalMb = 2_900, heapMb = 512, heapUsedMb = 280, availMb = 900, swapTotalMb = 1_024, swapFreeMb = 0)
        assertFalse(v.allowed)
        assertTrue(v.reason, v.reason.startsWith("SwapFree"))
        assertFalse(strong(totalMb = 2_900, heapMb = 512, availMb = 450).allowed)
        assertTrue(strong(totalMb = 2_900, heapMb = 512, availMb = 900).allowed)
    }

    @Test
    fun fireStickAndSmallHeapsAreProtected() {
        assertFalse(strong(is64Bit = false, totalMb = 1_900).allowed)
        assertFalse(strong(totalMb = 1_900).allowed)
        assertFalse(strong(heapMb = 256, heapUsedMb = 100).allowed)
        assertFalse(strong(lowMemory = true).allowed)
    }

    @Test
    fun aHeapThatIsAlmostFullIsProtectedWhateverItsLimit() {
        assertFalse(strong(heapUsedMb = 340).allowed)
        val v = strong(totalMb = 2_900, heapMb = 512, heapUsedMb = 470, availMb = 900)
        assertFalse(v.allowed)
        assertTrue(v.reason, v.reason.startsWith("Java heap free"))
        assertTrue(strong(totalMb = 2_900, heapMb = 512, heapUsedMb = 280, availMb = 900).allowed)
    }

    @Test
    fun heapGarbageIsCollectedOnceBeforeTheBoxCountsAsShort() {
        var used = 330L
        var collected = 0
        val v = MemoryGovernor.verdictAfterCollect({ used * mb }, { collected++; used = 250L }) {
            strong(heapUsedMb = it / mb)
        }
        assertTrue(v.reason, v.allowed)
        assertEquals(1, collected)
    }

    @Test
    fun heapStillFullAfterCollectingStaysProtected() {
        var collected = 0
        val v = MemoryGovernor.verdictAfterCollect({ 340L * mb }, { collected++ }) { strong(heapUsedMb = it / mb) }
        assertFalse(v.allowed)
        assertEquals(1, collected)
    }

    @Test
    fun noCollectWhenSomethingElseIsShort() {
        var collected = 0
        val v = MemoryGovernor.verdictAfterCollect({ 340L * mb }, { collected++ }) {
            strong(heapUsedMb = it / mb, swapTotalMb = 1_024, swapFreeMb = 0)
        }
        assertFalse(v.allowed)
        assertEquals(0, collected)
        MemoryGovernor.verdictAfterCollect({ 200L * mb }, { collected++ }) { strong(heapUsedMb = it / mb) }
        assertEquals("nothing to collect for when the box is strong", 0, collected)
    }

    @Test
    fun unknownNumbersDoNotCount() {
        assertTrue(strong(availMb = -1, swapTotalMb = 0, swapFreeMb = 0).allowed)
    }

    @Test
    fun pressureSignals() {
        assertEquals("system low on memory", MemoryGovernor.pressureReason(true, 2_000, 0, 0, 300))
        assertTrue(MemoryGovernor.pressureReason(false, 250, 0, 0, 300)!!.startsWith("MemAvailable"))
        assertTrue(MemoryGovernor.pressureReason(false, 900, 1_024, 10, 300)!!.startsWith("SwapFree"))
        assertNull(MemoryGovernor.pressureReason(false, 900, 1_024, 500, 300))
        assertNull(MemoryGovernor.pressureReason(false, -1, 0, 0, 300))
    }

    @Test
    fun outOfMemoryStopsAtOnceAndForGood() {
        val stop = MemoryStop()
        var asked = 0
        assertTrue(stop.allowsSideWork)
        assertTrue(stop.outOfMemory("out of memory reading a keyframe"))
        assertFalse("tapped keyframes and the copy pass stop too", stop.allowsSideWork)
        assertFalse("only the first one counts", stop.outOfMemory("again"))
        assertTrue(stop.check(0L) { asked++; null })
        assertTrue(stop.check(60_000L) { asked++; null })
        assertEquals("nothing is retried, memory is not even asked", 0, asked)
        assertEquals("out of memory reading a keyframe", stop.reason)
    }

    @Test
    fun shortMemoryStopsOnlyOnceItLasts() {
        val stop = MemoryStop(checkEveryMs = 5_000L, stopAfterMs = 30_000L)
        var asked = 0
        val short = { asked++; "SwapFree 0 MB < 64" }
        assertFalse(stop.check(0L, short))
        assertFalse(stop.check(1_000L, short))
        assertEquals("asked at most every 5 s", 1, asked)
        assertFalse(stop.check(25_000L, short))
        assertTrue(stop.allowsSideWork)
        assertTrue(stop.check(30_000L, short))
        assertTrue(stop.stopped)
        assertFalse(stop.allowsSideWork)
        assertEquals("SwapFree 0 MB < 64", stop.reason)
    }

    @Test
    fun memoryThatRecoversStartsTheWaitAgain() {
        val stop = MemoryStop(checkEveryMs = 5_000L, stopAfterMs = 30_000L)
        assertFalse(stop.check(0L) { "short" })
        assertFalse(stop.check(20_000L) { null })
        assertFalse(stop.check(25_000L) { "short" })
        assertFalse(stop.check(50_000L) { "short" })
        assertTrue(stop.check(55_000L) { "short" })
    }

    private fun decode(availMb: Long, swapFreeMb: Long, chargeMb: Long = 80, funded: Boolean = false,
                       swapTotalMb: Long = 512, lowRam: Boolean = true) =
        MemoryGovernor.decodeVerdict(MemoryGovernor.Meminfo(availMb, swapTotalMb, swapFreeMb), chargeMb, funded, lowRam)

    @Test
    fun fundedDecodesStopWhenSwapIsNearlyGone() {
        assertFalse(decode(availMb = 300, swapFreeMb = 0, funded = true).allowed)
        assertFalse(decode(availMb = 300, swapFreeMb = 47, chargeMb = 0, funded = true).allowed)
        assertTrue(decode(availMb = 300, swapFreeMb = 48, funded = true).allowed)
        assertTrue(decode(availMb = 300, swapFreeMb = 48, funded = true).reason.endsWith("funded"))
    }

    @Test
    fun unfundedDecodesKeepTheirSwapFloor() {
        assertFalse(decode(availMb = 400, swapFreeMb = 63).allowed)
        assertTrue(decode(availMb = 400, swapFreeMb = 64).allowed)
    }

    @Test
    fun aBoxWithoutSwapIsNotHeldBackBySwap() {
        assertTrue(decode(availMb = 300, swapFreeMb = 0, swapTotalMb = 0, funded = true).allowed)
        assertTrue(decode(availMb = 300, swapFreeMb = 0, swapTotalMb = 0).allowed)
    }

    @Test
    fun memoryFloorsAreUnchanged() {
        assertTrue(decode(availMb = 180, swapFreeMb = 140, funded = true).allowed)
        assertFalse(decode(availMb = 179, swapFreeMb = 140, funded = true).allowed)
        assertTrue(decode(availMb = 280, swapFreeMb = 140).allowed)
        assertFalse(decode(availMb = 279, swapFreeMb = 140).allowed)
        assertTrue(decode(availMb = 230, swapFreeMb = 140, funded = true, lowRam = false).allowed)
        assertFalse(decode(availMb = 379, swapFreeMb = 140, lowRam = false).allowed)
    }

    @Test
    fun aRoomyBoxDecodesAsBefore() {
        assertTrue(decode(availMb = 1_500, swapFreeMb = 1_200, lowRam = false).allowed)
        assertTrue(decode(availMb = 1_500, swapFreeMb = 1_200, funded = true, lowRam = false).allowed)
    }

    private fun funded(tier: DecodeTier = DecodeTier.SW_UP_TO_1080P, screenHolds: Boolean = false,
                       lowRam: Boolean = false, busy: Int = 0, reserve: Boolean = false, natural: Boolean = false) =
        MemoryGovernor.fundedDecode(busy, tier, reserve, natural, screenHolds, lowRam)

    @Test
    fun hdOnTheGeneratingScreenUsesTheFundedFloor() {
        assertTrue(funded(screenHolds = true))
        // 3 GB box with a full buffer: 1080p decode, cold decoder.
        assertFalse(decode(availMb = 320, swapFreeMb = 140, chargeMb = 35, lowRam = false).allowed)
        assertTrue(decode(availMb = 320, swapFreeMb = 140, chargeMb = 35, funded = funded(screenHolds = true),
            lowRam = false).allowed)
        val f = funded(screenHolds = true)
        assertTrue(decode(availMb = 185, swapFreeMb = 140, chargeMb = 35, funded = f, lowRam = false).allowed)
        assertFalse(decode(availMb = 184, swapFreeMb = 140, chargeMb = 35, funded = f, lowRam = false).allowed)
    }

    @Test
    fun hdOnTheGeneratingScreenStillStopsWhenSwapIsNearlyGone() {
        val f = funded(screenHolds = true)
        assertFalse(decode(availMb = 600, swapFreeMb = 47, chargeMb = 35, funded = f, lowRam = false).allowed)
        assertTrue(decode(availMb = 600, swapFreeMb = 48, chargeMb = 35, funded = f, lowRam = false).allowed)
    }

    @Test
    fun hdDuringPlaybackIsNotFunded() {
        assertFalse(funded(screenHolds = false))
        assertFalse(decode(availMb = 334, swapFreeMb = 140, chargeMb = 35, funded = funded(), lowRam = false).allowed)
    }

    @Test
    fun onlyTheFirstLaneIsFundedOnTheGeneratingScreen() {
        assertFalse(funded(screenHolds = true, busy = 1))
    }

    @Test
    fun lowRamBoxesAndFourKAreUnchangedOnTheGeneratingScreen() {
        assertFalse(funded(screenHolds = true, lowRam = true))
        assertFalse(funded(tier = DecodeTier.SW_4K, screenHolds = true))
        assertFalse(funded(tier = DecodeTier.SW_4K, screenHolds = true, lowRam = true))
        assertTrue(funded(tier = DecodeTier.SW_4K, reserve = true, lowRam = true))
        assertTrue(funded(tier = DecodeTier.SW_4K, natural = true))
        assertFalse(funded(tier = DecodeTier.SW_4K, reserve = true, busy = 1))
    }
}
