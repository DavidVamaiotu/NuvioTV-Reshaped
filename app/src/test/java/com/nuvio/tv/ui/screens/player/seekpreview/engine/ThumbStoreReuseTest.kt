// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Stores of one title are found together, keep their duration and verdicts, and take pictures without a re-decode. */
class ThumbStoreReuseTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun slotUs(slot: Int) = slot * 10_000_000L

    @Test fun releasesOfOneTitleShareTheKeyPrefix() {
        val a = ThumbStore.keyFor("tt0111161", 4_000_000_000L)
        val b = ThumbStore.keyFor("tt0111161", 4_100_000_000L)
        val c = ThumbStore.keyFor("tt0068646", 4_000_000_000L)
        val prefix = ThumbStore.identityPrefix("tt0111161")
        assertTrue(a.startsWith(prefix) && b.startsWith(prefix))
        assertFalse(c.startsWith(prefix))
        assertTrue(a != b)
        assertEquals(24, a.length)
    }

    @Test fun siblingsAreFoundWithTheirDurationAndPictureCount() {
        val dir = tmp.newFolder("store")
        val id = "tt0111161"
        val own = ThumbStore.keyFor(id, 1_000L)
        val other = ThumbStore.keyFor(id, 2_000L)
        val unrelated = ThumbStore.keyFor("tt0068646", 2_000L)
        ThumbStore.openIn(dir, own, memoryOnly = false).apply {
            noteDuration(8_520_000L)
            putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(1)), 10_000_000L, byteArrayOf(1))))
        }
        ThumbStore.openIn(dir, other, memoryOnly = false).apply {
            noteDuration(8_521_500L)
            putCompressed((0 until 5).map { ThumbStore.Compressed(longArrayOf(slotUs(it)), it * 10_000_000L, byteArrayOf(it.toByte())) })
        }
        ThumbStore.openIn(dir, unrelated, memoryOnly = false).apply {
            noteDuration(8_520_000L)
            putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(1)), 10_000_000L, byteArrayOf(1))))
        }
        val found = ThumbStore.siblingsIn(dir, id, exceptKey = own)
        assertEquals(1, found.size)
        assertEquals(other, found[0].key)
        assertEquals(8_521_500L, found[0].durationMs)
        assertEquals(5, found[0].pictures)
    }

    @Test fun compressedPicturesAreCopiedAsTheyAre() {
        val dir = tmp.newFolder("store")
        val webpA = ByteArray(700) { (it * 7).toByte() }
        val webpB = ByteArray(300) { (it * 3).toByte() }
        val store = ThumbStore.openIn(dir, "copy", memoryOnly = false)
        assertTrue(store.putCompressed(listOf(
            ThumbStore.Compressed(longArrayOf(slotUs(4), slotUs(5)), 47_000_000L, webpA),
            ThumbStore.Compressed(longArrayOf(slotUs(9)), 92_000_000L, webpB),
        )))
        val reopened = ThumbStore.openIn(dir, "copy", memoryOnly = false)
        assertEquals(setOf(slotUs(4), slotUs(5), slotUs(9)), reopened.slots())
        assertArrayEquals(webpA, reopened.compressed(slotUs(5)))
        assertArrayEquals(webpB, reopened.compressed(slotUs(9)))
        assertEquals(47_000_000L, reopened.keyframePtsFor(slotUs(4)))
        assertEquals(1_000L, java.io.File(dir, "copy.dat").length())
    }

    @Test fun wholeMovieRemainsOnDiskAfterRamIsEmptiedAndTheStoreReopens() {
        val dir = tmp.newFolder("whole-movie")
        val store = ThumbStore.openIn(dir, "movie", memoryOnly = false)
        // Two hours at ten-second slots; aliases share one encoded keyframe rather than duplicate bytes.
        val encoded = ByteArray(512) { (it * 7).toByte() }
        val slots = (0 until 720).map(::slotUs)
        assertTrue(store.putCompressed((0 until 720 step 3).map { first ->
            ThumbStore.Compressed(LongArray(3) { slotUs(first + it) }, slotUs(first), encoded)
        }))
        store.markComplete(slots)
        assertTrue(java.io.File(dir, "movie.done").exists())
        store.trimMemory(0)
        val reopened = ThumbStore.openIn(dir, "movie", memoryOnly = false)
        for (slot in slots) assertArrayEquals(encoded, reopened.compressed(slot))
        assertEquals(240L * encoded.size, java.io.File(dir, "movie.dat").length())
        assertEquals(720, reopened.slots().size)
    }

    @Test fun aNearlyCompleteMovieWithABlankSectionIsNotMarkedComplete() {
        val dir = tmp.newFolder("missing-section")
        val store = ThumbStore.openIn(dir, "gaps", memoryOnly = false)
        val required = (0 until 100).map(::slotUs)
        assertTrue(store.putCompressed((0 until 100).filter { it !in 50..52 }.map {
            ThumbStore.Compressed(longArrayOf(slotUs(it)), slotUs(it), byteArrayOf(1))
        }))
        store.markComplete(required)
        assertFalse(java.io.File(dir, "gaps.done").exists())
        assertTrue(store.putCompressed((50..52).map {
            ThumbStore.Compressed(longArrayOf(slotUs(it)), slotUs(it), byteArrayOf(1))
        }))
        store.markComplete(required)
        assertTrue(java.io.File(dir, "gaps.done").exists())
    }

    @Test fun chunkedCopiesWriteTheIndexOnceAtTheEnd() {
        val dir = tmp.newFolder("store")
        val store = ThumbStore.openIn(dir, "chunks", memoryOnly = false)
        for (c in 0 until 3) {
            assertTrue(store.putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(c)), c * 10_000_000L, byteArrayOf(c.toByte()))),
                indexNow = false))
        }
        assertFalse(java.io.File(dir, "chunks.idx").exists())
        store.flush()
        assertEquals(3, ThumbStore.openIn(dir, "chunks", memoryOnly = false).slots().size)
    }

    @Test fun durationAndVerdictsSurviveAReopen() {
        val dir = tmp.newFolder("store")
        val store = ThumbStore.openIn(dir, "meta", memoryOnly = false)
        store.noteDuration(5_400_000L)
        store.noteVerdict("aaaa", true)
        store.noteVerdict("bbbb", false)
        store.setKeyframeIndex(ByteArray(64) { it.toByte() })
        store.flush()
        val reopened = ThumbStore.openIn(dir, "meta", memoryOnly = false)
        assertEquals(5_400_000L, reopened.durationMs())
        assertEquals(true, reopened.verdictOn("aaaa"))
        assertEquals(false, reopened.verdictOn("bbbb"))
        assertNull(reopened.verdictOn("cccc"))
        assertEquals(64, reopened.cachedKeyframeIndex()?.size)
    }

    @Test fun aMemoryOnlyStoreTakesNoCopies() {
        val dir = tmp.newFolder("store")
        val store = ThumbStore.openIn(dir, "mem", memoryOnly = true)
        assertFalse(store.putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(1)), 0L, byteArrayOf(1)))))
        assertFalse(store.has(slotUs(1)))
        assertEquals(0, dir.listFiles()!!.size)
    }
    @Test fun aDamagedPictureIsRemovedFromEverySlotSoItCanBeRegenerated() {
        val dir = tmp.newFolder("damaged")
        val store = ThumbStore.openIn(dir, "damaged", memoryOnly = false)
        store.putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(2), slotUs(3)), 20_000_000L, byteArrayOf(1, 2, 3))))
        assertNull(store.load(slotUs(2)))
        assertFalse(store.has(slotUs(2)))
        assertFalse(store.has(slotUs(3)))
        store.flush()
        assertFalse(ThumbStore.openIn(dir, "damaged", memoryOnly = false).has(slotUs(3)))
    }

    @Test fun deletedImageDataCannotLeaveAFullColourSlotPermanentlyAvailable() {
        val dir = tmp.newFolder("deleted")
        val store = ThumbStore.openIn(dir, "deleted", memoryOnly = false)
        store.putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(2)), 20_000_000L, byteArrayOf(1))))
        assertTrue(java.io.File(dir, "deleted.dat").delete())
        assertNull(store.loadFullColour(slotUs(2)))
        assertFalse(store.has(slotUs(2)))
    }

}
