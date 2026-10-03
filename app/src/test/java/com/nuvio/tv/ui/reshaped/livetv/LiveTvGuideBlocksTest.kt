package com.nuvio.tv.ui.reshaped.livetv

import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvGuideBlocksTest {
    private val hour = 3_600_000L
    private val minute = 60_000L
    private fun p(title: String, start: Long, stop: Long) = LiveTvProgramme(title, start, stop)

    @Test
    fun overlapsGoToTheLaterProgramme() {
        val blocks = liveTvGuideBlocks(listOf(p("a", 0, hour + 5 * minute), p("b", hour, 2 * hour), p("c", 2 * hour, 3 * hour)))
        assertEquals(listOf(0L to hour, hour to 2 * hour, 2 * hour to 3 * hour), blocks.map { it.start to it.stop })
        assertNoOverlaps(blocks)
    }

    @Test
    fun aProgrammeInsideAnotherSplitsIt() {
        val umbrella = p("sport", 0, 8 * hour)
        val blocks = liveTvGuideBlocks(listOf(umbrella, p("news", 3 * hour, 3 * hour + 15 * minute)))
        assertEquals(listOf("sport", "news", "sport"), blocks.map { it.programme?.title })
        assertEquals(8 * hour, blocks.last().stop)
        assertNoOverlaps(blocks)
    }

    @Test
    fun twoShiftedGuidesNeverOverlap() {
        val a = (0 until 10).map { p("a$it", it * hour, (it + 1) * hour) }
        val b = (0 until 10).map { p("b$it", it * hour + 20 * minute, (it + 1) * hour + 20 * minute) }
        assertNoOverlaps(liveTvGuideBlocks((a + b).sortedBy { it.startEpochMs }))
    }

    @Test
    fun theSameShowListedTwiceIsOneBlock() {
        val blocks = liveTvGuideBlocks(
            listOf(p("Film", 0, 2 * hour), p("film ", 5 * minute, 2 * hour + 5 * minute), p("News", 2 * hour + 5 * minute, 3 * hour)),
        )
        assertEquals(listOf("Film", "News"), blocks.map { it.programme?.title })
        assertEquals(0L to 2 * hour + 5 * minute, blocks.first().start to blocks.first().stop)
        assertNoOverlaps(blocks)
    }

    @Test
    fun emptyTimeStepsByHalfHoursCutAtProgrammes() {
        val blocks = liveTvGuideBlocks(listOf(p("a", 0, hour + 10 * minute), p("b", 2 * hour + 20 * minute, 3 * hour)))
        val gap = blocks.after(blocks.first())
        assertNull(gap.programme)
        assertEquals(hour + 10 * minute to hour + 30 * minute, gap.start to gap.stop)
        val next = blocks.after(gap)
        assertEquals(hour + 30 * minute to 2 * hour, next.start to next.stop)
        assertEquals("b", blocks.after(blocks.after(next)).programme?.title)
        assertEquals(gap, blocks.before(next))
    }

    @Test
    fun cellsCoverTheWindowWithoutOverlaps() {
        val blocks = liveTvGuideBlocks(listOf(p("a", hour, 2 * hour), p("b", 3 * hour, 4 * hour)))
        val cells = blocks.cellsIn(0, 5 * hour)
        assertEquals(0L, cells.first().start)
        assertEquals(5 * hour, cells.last().stop)
        cells.zipWithNext().forEach { (x, y) -> assertEquals(x.stop, y.start) }
        assertEquals(listOf(null, "a", null, "b", null), cells.map { it.block.programme?.title })
    }

    @Test
    fun theViewMovesOnlyAsFarAsNeeded() {
        val span = 100 * minute
        val shown = LiveTvGuideBlock(30 * minute, hour, null)
        assertEquals(0L, liveTvGuideViewFor(shown, 0, span, -10 * hour, 10 * hour))
        val later = LiveTvGuideBlock(2 * hour, 3 * hour, null)
        val view = liveTvGuideViewFor(later, 0, span, -10 * hour, 10 * hour)
        assertTrue(view <= later.start && view + span >= later.stop)
        val long = LiveTvGuideBlock(5 * hour, 12 * hour, null)
        assertEquals(5 * hour, liveTvGuideViewFor(long, 0, span, -10 * hour, 20 * hour))
    }

    private fun assertNoOverlaps(blocks: List<LiveTvGuideBlock>) {
        blocks.zipWithNext().forEach { (x, y) -> assertTrue("$x overlaps $y", x.stop <= y.start) }
        blocks.forEach { assertTrue(it.stop > it.start) }
    }
}
