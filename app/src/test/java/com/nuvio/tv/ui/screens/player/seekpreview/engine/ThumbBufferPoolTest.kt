// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap

class ThumbBufferPoolTest {

    private class FakeMemory(var refuse: Boolean = false) : ThumbMemory {
        var allocations = 0
        var frees = 0
        val live: MutableSet<ByteBuffer> = Collections.newSetFromMap(IdentityHashMap())
        override fun allocate(size: Int): ThumbMemory.Block? {
            if (refuse) return null
            allocations++
            return ThumbMemory.Block(ByteBuffer.allocateDirect(size).also { live += it }, null)
        }
        override fun free(block: ThumbMemory.Block) {
            frees++
            assertTrue("freed twice", live.remove(block.buffer))
        }
    }

    private val mb = 1024 * 1024

    @Test
    fun aReturnedBufferIsReusedNotAllocatedAgain() {
        val memory = FakeMemory()
        val pool = ThumbBufferPool(2, memory)
        val a = pool.tryAcquire()!!
        assertNotNull(a.ensure(3 * mb))
        assertTrue(a.isNative)
        a.release()
        val b = pool.tryAcquire()!!
        assertSame(a, b)
        assertNotNull(b.ensure(2 * mb))
        assertEquals(1, memory.allocations)
        b.release()
        assertEquals(0, memory.frees)
    }

    @Test
    fun noMoreThanTheLimitIsLent() {
        val pool = ThumbBufferPool(2, FakeMemory())
        val a = pool.tryAcquire()
        val b = pool.tryAcquire()
        assertNotNull(a)
        assertNotNull(b)
        assertNull(pool.tryAcquire())
        assertEquals(0, pool.available)
        a!!.release()
        assertEquals(1, pool.available)
        assertNotNull(pool.tryAcquire())
    }

    @Test
    fun closeFreesIdleBuffersAtOnceAndLentOnesWhenTheyComeBack() {
        val memory = FakeMemory()
        val pool = ThumbBufferPool(2, memory)
        val a = pool.tryAcquire()!!.also { it.ensure(mb) }
        val b = pool.tryAcquire()!!.also { it.ensure(mb) }
        a.release()
        pool.close()
        assertEquals(1, memory.frees)
        assertNull(pool.tryAcquire())
        b.release()
        assertEquals(2, memory.frees)
        assertTrue(memory.live.isEmpty())
    }

    @Test
    fun aLowerLimitFreesIdleBuffersAboveIt() {
        val memory = FakeMemory()
        val pool = ThumbBufferPool(4, memory)
        val all = List(4) { pool.tryAcquire()!!.also { b -> b.ensure(mb) } }
        all.forEach { it.release() }
        assertEquals(4, pool.idleCount)
        pool.limit = ThumbBufferPool.limitFor(lanes = 1, fourK = true, strong = false)
        assertEquals(2, pool.idleCount)
        assertEquals(2, memory.frees)
    }

    @Test
    fun releaseIdleFreesTheIdleBuffersNotTheLentOnes() {
        val memory = FakeMemory()
        val pool = ThumbBufferPool(3, memory)
        val lent = pool.tryAcquire()!!.also { it.ensure(mb) }
        val a = pool.tryAcquire()!!.also { it.ensure(mb) }
        val b = pool.tryAcquire()!!.also { it.ensure(mb) }
        a.release()
        b.release()
        pool.releaseIdle()
        assertEquals(2, memory.frees)
        assertEquals(0, pool.idleCount)
        assertTrue(lent.data.capacity() >= mb)
        assertEquals(1, pool.lentCount)
        val again = pool.tryAcquire()!!
        assertNotNull(again.ensure(mb))
        assertEquals("a new buffer is made when needed again", 4, memory.allocations)
        lent.release()
        again.release()
        pool.close()
        assertTrue(memory.live.isEmpty())
    }

    @Test
    fun anAccessUnitGivesItsBufferBackOnce() {
        val pool = ThumbBufferPool(2, FakeMemory())
        val first = pool.tryAcquire()!!
        val au = ThumbAu(first.ensure(mb)!!, 10, first)
        au.release()
        val again = pool.tryAcquire()!!      // the same buffer, now lent to someone else
        au.release()
        assertEquals(1, pool.lentCount)
        assertSame(first, again)
    }

    @Test
    fun refusedNativeMemoryFallsBackToAJavaArrayNeverAboveTheCap() {
        val memory = FakeMemory(refuse = true)
        val b = ThumbBuffer.single(memory)
        val data = b.ensure(5 * mb + 1)
        assertNotNull(data)
        assertFalse(b.isNative)
        assertFalse(data!!.isDirect)
        assertTrue(data.capacity() <= ThumbReads.BUFFER_MAX)
        assertEquals(ThumbBuffer.roundUp(5 * mb + 1), data.capacity())
        assertNull(b.ensure(ThumbReads.BUFFER_MAX + 1))
        b.release()
    }

    @Test
    fun growingKeepsTheBytesAndFreesTheSmallerBlock() {
        val memory = FakeMemory()
        val b = ThumbBuffer.single(memory)
        val small = b.ensure(1000)!!
        for (i in 0 until 1000) small.put(i, (i % 127).toByte())
        val big = b.ensure(3 * mb, keep = 1000)!!
        for (i in 0 until 1000) assertEquals((i % 127).toByte(), big.get(i))
        assertEquals(2, memory.allocations)
        assertEquals(1, memory.frees)
        b.release()
        assertTrue(memory.live.isEmpty())
    }

    @Test
    fun poolSizeFollowsTheDecodeLanesAndTheBox() {
        assertEquals(2, ThumbBufferPool.limitFor(lanes = 1, fourK = true, strong = false))
        assertEquals(4, ThumbBufferPool.limitFor(lanes = 2, fourK = true, strong = false))
        assertEquals(2, ThumbBufferPool.limitFor(lanes = 0, fourK = true, strong = false))
        assertEquals("lanes plus four fetches", 6, ThumbBufferPool.limitFor(lanes = 2, fourK = true, strong = true))
        assertEquals(8, ThumbBufferPool.limitFor(lanes = 4, fourK = true, strong = true))
        assertEquals(2 + ThumbFetchLanes.MAX, ThumbBufferPool.limitFor(lanes = 2, fourK = false, strong = false))
        assertEquals(2 + ThumbFetchLanes.MAX, ThumbBufferPool.limitFor(lanes = 2, fourK = false, strong = true))
    }
}
