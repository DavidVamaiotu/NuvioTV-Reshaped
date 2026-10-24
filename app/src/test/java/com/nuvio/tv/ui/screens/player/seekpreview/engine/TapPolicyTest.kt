// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playback's tap: what it copies to flash, and what a small box playing a 4K remux leaves alone. */
class TapPolicyTest {
    private val remuxStride = 6     // LATTICE_4K_REMUX: every 60 s

    private fun copies(slot: Int, holds: Boolean = true, finished: Boolean = false, full: Boolean = false) =
        TapPolicy.copies(slot, holds, remuxStride, finished, full)

    @Test fun boxesThatDecodeWhilePlayingCopyEverySlot() {
        for (slot in 0..12) assertTrue(copies(slot, holds = false))
        assertTrue(copies(7, holds = false, finished = true, full = true))
    }

    @Test fun smallBoxRemuxCopiesOnlyCoverageSlots() {
        val copied = (0 until 60).filter { copies(it) }
        assertEquals(listOf(0, 6, 12, 18, 24, 30, 36, 42, 48, 54), copied)
    }

    @Test fun smallBoxRemuxCopiesNothingOnceCoverageIsFinished() {
        assertFalse(copies(12, finished = true))
    }

    @Test fun aFullSpoolIsNotTurnedOverUnused() {
        assertFalse(copies(12, full = true))
        assertTrue(copies(12, full = false))
    }

    @Test fun flashWrittenPerHourOfARemux() {
        val slotsPerHour = 3_600 / 10
        val keyframeMb = 1.5
        val before = slotsPerHour * keyframeMb
        val after = (0 until slotsPerHour).count { copies(it) } * keyframeMb
        assertEquals(540.0, before, 0.001)
        assertEquals(90.0, after, 0.001)
    }
}
