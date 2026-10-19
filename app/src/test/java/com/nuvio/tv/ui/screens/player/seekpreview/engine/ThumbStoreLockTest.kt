// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.graphics.Bitmap
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The store's disk writes never hold the lock the seek bar's lookups take on the main thread. */
class ThumbStoreLockTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bmp: Bitmap = mockk(relaxed = true)
    private val key = "lockcheck"
    @Before fun bitmapCompressionProducesNonemptyImageData() {
        every { bmp.config } returns Bitmap.Config.RGB_565
        every { bmp.allocationByteCount } returns 64
        every { bmp.compress(any(), any(), any()) } answers {
            thirdArg<java.io.OutputStream>().write(byteArrayOf(1, 2, 3))
            true
        }
    }


    private fun slotUs(slot: Int) = slot * 10_000_000L

    @Test fun lookupsDoNotWaitForAWriteUnderWay() {
        val dir = tmp.newFolder("store")
        val store = ThumbStore.openIn(dir, key, memoryOnly = false)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val writing = CountDownLatch(1)
            val release = CountDownLatch(1)
            pool.submit(Runnable {
                synchronized(store.diskLock) {        // a write stuck on slow flash
                    writing.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            })
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val put = pool.submit(Runnable { store.put(longArrayOf(slotUs(3)), 30_100_000L, bmp) })

            val lookups = pool.submit(Callable {
                store.has(slotUs(3))
                store.memGet(slotUs(3))
                store.isEmpty()
            })
            lookups.get(2, TimeUnit.SECONDS)
            assertFalse(put.isDone)

            release.countDown()
            put.get(5, TimeUnit.SECONDS)
            assertTrue(store.has(slotUs(3)))
            assertEquals(30_100_000L, store.keyframePtsFor(slotUs(3)))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun everyEntryAndTheKeyframeIndexReachTheDisk() {
        val dir = tmp.newFolder("store")
        val store = ThumbStore.openIn(dir, key, memoryOnly = false)
        val index = ByteArray(300_000) { (it % 251).toByte() }
        store.setKeyframeIndex(index)
        for (slot in 0 until 25) store.put(longArrayOf(slotUs(slot)), slot * 9_000_000L, bmp)
        store.put(longArrayOf(slotUs(40), slotUs(41)), 401_000_000L, bmp)
        store.flush()

        val reopened = ThumbStore.openIn(dir, key, memoryOnly = false)
        assertEquals(27, reopened.slots().size)
        assertEquals(12 * 9_000_000L, reopened.keyframePtsFor(slotUs(12)))
        assertEquals(401_000_000L, reopened.keyframePtsFor(slotUs(41)))
        assertArrayEquals(index, reopened.cachedKeyframeIndex())
    }

    @Test fun theFirstPictureIsWrittenWithoutAFlush() {
        val dir = tmp.newFolder("store")
        ThumbStore.openIn(dir, key, memoryOnly = false).put(longArrayOf(slotUs(7)), 70_000_000L, bmp)
        assertTrue(ThumbStore.openIn(dir, key, memoryOnly = false).has(slotUs(7)))
    }

    @Test fun aMemoryOnlyStoreWritesNothing() {
        val dir = tmp.newFolder("store")
        val store = ThumbStore.openIn(dir, key, memoryOnly = true)
        store.put(longArrayOf(slotUs(1)), 10_000_000L, bmp)
        store.flush()
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun aPublishedFrameIsUsableBeforeItsDiskWrite() {
        val dir = tmp.newFolder("published")
        val store = ThumbStore.openIn(dir, key, memoryOnly = false)
        val frame = store.publish(longArrayOf(slotUs(1), slotUs(2)), 15_000_000L, bmp)
        assertTrue(store.has(slotUs(2)))
        assertEquals(15_000_000L, store.keyframePtsFor(slotUs(2)))
        assertEquals(bmp, store.memGet(slotUs(1)))
        assertEquals(bmp, store.loadFullColour(slotUs(2)))
        assertFalse(java.io.File(dir, "$key.dat").exists())
        store.persist(frame)
        store.flush()
        assertEquals(15_000_000L, ThumbStore.openIn(dir, key, false).keyframePtsFor(slotUs(2)))
    }

    @Test fun aDelayedWriteCannotReplaceANewerImage() {
        val dir = tmp.newFolder("replacement")
        val store = ThumbStore.openIn(dir, key, memoryOnly = false)
        val old = store.publish(longArrayOf(slotUs(1)), 10_000_000L, bmp)
        val newer = store.publish(longArrayOf(slotUs(1)), 11_000_000L, bmp)
        store.persist(newer)
        store.persist(old)
        store.flush()
        assertEquals(11_000_000L, ThumbStore.openIn(dir, key, false).keyframePtsFor(slotUs(1)))
        assertEquals(3L, java.io.File(dir, "$key.dat").length())
    }

    @Test fun aCompletionMarkerRequiresDurableImagesAndTheirIndex() {
        val dir = tmp.newFolder("completion")
        val store = ThumbStore.openIn(dir, key, memoryOnly = false)
        val frame = store.publish(longArrayOf(slotUs(1)), 10_000_000L, bmp)
        store.markComplete(listOf(slotUs(1)))
        assertFalse(java.io.File(dir, "$key.done").exists())
        store.persist(frame)
        store.markComplete(listOf(slotUs(1)))
        assertTrue(java.io.File(dir, "$key.done").exists())
        assertTrue(ThumbStore.openIn(dir, key, false).has(slotUs(1)))
    }

    @Test fun anUnwrittenReplacementIsNotTreatedAsACorruptDiskImage() {
        val dir = tmp.newFolder("unwritten")
        val store = ThumbStore.openIn(dir, key, memoryOnly = false)
        store.putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(1)), 5_000_000L, byteArrayOf(0))))
        store.fullColourGet(slotUs(0)) // the retained full-colour slot belongs elsewhere
        val frame = store.publish(longArrayOf(slotUs(1)), 10_000_000L, bmp)
        assertEquals(bmp, store.loadFullColour(slotUs(1)))
        assertTrue(store.has(slotUs(1)))
        store.persist(frame)
        store.flush()
        assertEquals(10_000_000L, ThumbStore.openIn(dir, key, false).keyframePtsFor(slotUs(1)))
    }

    @Test fun anAdoptedImageSupersedesAnOlderPendingPublication() {
        val dir = tmp.newFolder("adopted")
        val store = ThumbStore.openIn(dir, key, memoryOnly = false)
        val pending = store.publish(longArrayOf(slotUs(1)), 10_000_000L, bmp)
        store.putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(1)), 11_000_000L, byteArrayOf(7))))
        store.persist(pending)
        store.flush()
        assertArrayEquals(byteArrayOf(7), ThumbStore.openIn(dir, key, false).compressed(slotUs(1)))
    }

    @Test fun differentStoredImagesAtTheSameTimestampNeverShareACachedBitmap() {
        val dir = tmp.newFolder("same-time")
        val store = ThumbStore.openIn(dir, key, memoryOnly = false)
        store.put(longArrayOf(slotUs(1), slotUs(2)), 10_000_000L, bmp)
        store.putCompressed(listOf(ThumbStore.Compressed(longArrayOf(slotUs(1)), 10_000_000L, byteArrayOf(0))))
        assertNull(store.load(slotUs(1))) // invalid replacement must not reuse the old alias's bitmap
        assertEquals(bmp, store.memGet(slotUs(2)))
    }
}
