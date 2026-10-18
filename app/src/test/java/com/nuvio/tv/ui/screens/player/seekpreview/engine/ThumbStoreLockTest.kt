// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.graphics.Bitmap
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
