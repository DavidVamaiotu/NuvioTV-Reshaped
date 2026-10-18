// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thumbnail decodes on boxes with swap: refused under the swap floors in every state, and the decoders let go. */
class ThumbSwapHoldTest {

    private fun mem(availMb: Long = 400, swapTotalMb: Long = 512, swapFreeMb: Long = 300) =
        MemoryGovernor.Meminfo(availMb, swapTotalMb, swapFreeMb)

    private fun fireStick(availMb: Long = 400, swapFreeMb: Long, funded: Boolean, chargeMb: Long = 0) =
        MemoryGovernor.decodeVerdict(mem(availMb = availMb, swapFreeMb = swapFreeMb), chargeMb, funded, lowRam = true)

    @Test
    fun pausedFundedDecodeIsRefusedUnder48MbSwapWhateverMemAvailableSays() {
        val v = fireStick(availMb = 900, swapFreeMb = 47, funded = true)
        assertFalse(v.allowed)
        assertTrue(v.swapLow)
        assertEquals(47L, v.swapFreeMb)
        assertTrue(v.reason, v.reason.startsWith("SwapFree 47 MB < 48"))
        assertTrue(fireStick(availMb = 900, swapFreeMb = 48, funded = true).allowed)
        val empty = fireStick(availMb = 190, swapFreeMb = 0, funded = true)
        assertFalse("swap at 0 while paused", empty.allowed)
        assertTrue(empty.swapLow)
    }

    @Test
    fun decodeDuringPlaybackBacksOffForSwapEvenWithMemAvailableFine() {
        val v = fireStick(availMb = 600, swapFreeMb = 63, funded = false, chargeMb = 80)
        assertFalse(v.allowed)
        assertTrue(v.swapLow)
        assertTrue(fireStick(availMb = 600, swapFreeMb = 64, funded = false, chargeMb = 80).allowed)
    }

    @Test
    fun memAvailableRefusalIsNotASwapRefusal() {
        val v = fireStick(availMb = 150, swapFreeMb = 0, funded = false)
        assertFalse(v.allowed)
        assertFalse("the MemAvailable floor is checked first", v.swapLow)
        assertTrue(GovernorVerdict(true, "ok").let { !it.swapLow })
    }

    @Test
    fun swapCheckAloneMatchesTheDecodeVerdict() {
        assertNotNull(MemoryGovernor.swapVerdict(mem(swapFreeMb = 47), funded = true))
        assertNull(MemoryGovernor.swapVerdict(mem(swapFreeMb = 48), funded = true))
        assertNotNull(MemoryGovernor.swapVerdict(mem(swapFreeMb = 63), funded = false))
        assertNull(MemoryGovernor.swapVerdict(mem(swapFreeMb = 64), funded = false))
        assertNull("no swap on the box", MemoryGovernor.swapVerdict(mem(swapTotalMb = 0, swapFreeMb = 0), funded = true))
    }

    @Test
    fun boxesWithoutSwapAndStrongBoxesKeepTheirDecodes() {
        assertTrue(MemoryGovernor.decodeVerdict(mem(availMb = 500, swapTotalMb = 0, swapFreeMb = 0), 80, false, lowRam = false).allowed)
        assertTrue(MemoryGovernor.decodeVerdict(mem(availMb = 1_500, swapFreeMb = 900), 80, false, lowRam = false).allowed)
    }

    @Test
    fun aSwapRefusalClosesTheDecodersOnSmallBoxesOnly() {
        val swap = fireStick(swapFreeMb = 10, funded = true)
        assertTrue("Fire TV Stick", MemoryGovernor.closesDecodersFor(swap, strongHardware = false))
        assertFalse("AM9 / Shield keep trimming", MemoryGovernor.closesDecodersFor(swap, strongHardware = true))
        val floor = fireStick(availMb = 120, swapFreeMb = 10, funded = true, chargeMb = 80)
        assertFalse(MemoryGovernor.closesDecodersFor(floor, strongHardware = false))
        assertFalse(MemoryGovernor.closesDecodersFor(GovernorVerdict(true, "ok"), strongHardware = false))
        val hovering = fireStick(swapFreeMb = 40, funded = true)
        assertFalse(hovering.allowed)
        assertFalse("just under the floor: trimmed, not closed", MemoryGovernor.closesDecodersFor(hovering, strongHardware = false))
        assertTrue(MemoryGovernor.closesDecodersFor(fireStick(swapFreeMb = 31, funded = true), strongHardware = false))
        assertFalse(MemoryGovernor.closesDecodersFor(fireStick(swapFreeMb = 32, funded = true), strongHardware = false))
    }

    @Test
    fun aRunningLaneFinishesItsDecodeAndThenLetsItsDecoderGo() {
        val lane = DecoderLane()
        lane.acquire()
        lane.close()
        assertTrue("the decode under way is not cut off", lane.inUse)
        assertTrue(lane.closePending)
        lane.release()
        assertFalse(lane.inUse)
        assertFalse(lane.closePending)
        assertFalse(lane.decoder.warm)
        lane.close()
        assertFalse("an idle lane closes at once", lane.closePending)
    }

    @Test
    fun onlyTheFirstSwapRefusalOfASessionIsLoggedAndTheSummaryCountsThemAll() {
        val holds = SwapHolds()
        assertNull(holds.summary())
        val floor = fireStick(availMb = 120, swapFreeMb = 10, funded = false, chargeMb = 80)
        assertFalse(holds.refused(floor, paused = true, funded = false, afterFetch = false, closed = false, nowMs = 0))
        assertNull("a MemAvailable refusal is not counted", holds.summary())

        val first = fireStick(swapFreeMb = 41, funded = true)
        assertTrue(holds.refused(first, paused = true, funded = true, afterFetch = false, closed = true, nowMs = 1_000))
        assertEquals(
            "decodes held for swap: SwapFree 41 MB < 48 (paused, funded), decoders closed until it recovers",
            holds.firstLine(first, paused = true, funded = true, afterFetch = false, closed = true),
        )
        val later = fireStick(swapFreeMb = 0, funded = false)
        assertFalse(holds.refused(later, paused = false, funded = false, afterFetch = true, closed = true, nowMs = 51_000))
        assertEquals(2, holds.count)
        assertEquals(0L, holds.minSwapFreeMb)
        assertEquals(
            "swap holds this session: refused 2 (paused 1, funded 1, after the fetch 1), lowest SwapFree 0 MB, " +
                "decoders closed 2, over 50 s",
            holds.summary(),
        )
    }
}
