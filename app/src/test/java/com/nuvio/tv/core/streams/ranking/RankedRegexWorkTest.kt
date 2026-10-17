package com.nuvio.tv.core.streams.ranking

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RankedRegexWorkTest {
    @Test fun `ranked work leaves the caller thread responsive`() = runBlocking {
        val caller = Thread.currentThread()
        val entered = CompletableDeferred<Thread>()
        val release = CountDownLatch(1)
        val job = async {
            RankedRegexWork.run(enabled = true) { checkpoint ->
                entered.complete(Thread.currentThread())
                while (!release.await(10, TimeUnit.MILLISECONDS)) checkpoint()
                42
            }
        }
        try {
            assertNotSame(caller, withTimeout(2000) { entered.await() })
            assertFalse(job.isCompleted)
        } finally {
            release.countDown()
        }
        assertEquals(42, job.await())
    }

    @Test fun `cancelling a search stops the actual worker`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val exited = CountDownLatch(1)
        val job = async {
            RankedRegexWork.run(enabled = true) { checkpoint ->
                entered.complete(Unit)
                try {
                    while (true) checkpoint()
                } finally {
                    exited.countDown()
                }
            }
        }
        withTimeout(2000) { entered.await() }
        job.cancelAndJoin()
        assertTrue(exited.await(1, TimeUnit.SECONDS))
    }

    @Test fun `legacy path stays on the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        assertSame(caller, RankedRegexWork.run(enabled = false) { Thread.currentThread() })
    }

    @Test fun `worker errors reach the caller`() = runBlocking {
        try {
            RankedRegexWork.run(enabled = true) { throw IllegalArgumentException("invalid") }
            fail("Expected failure")
        } catch (expected: IllegalArgumentException) {
            assertEquals("invalid", expected.message)
        }
    }
}
