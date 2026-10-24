package com.nuvio.tv.ui.screens.player.seekpreview.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PreviewFrameWriterTest {
    @Test fun publicationIsImmediateButOnlyOneImageCanAwaitPersistence() = runBlocking {
        val writer = PreviewFrameWriter(Dispatchers.IO) { throw AssertionError(it) }
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = AtomicInteger()
        try {
            writer.submit({ published.incrementAndGet() }) {
                writing.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            assertEquals(1, published.get())
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val next = async { writer.submit({ published.incrementAndGet() }) {} }
            yield()
            assertEquals(1, published.get())
            assertFalse(next.isCompleted)
            release.countDown()
            withTimeout(5_000L) { next.await(); writer.drain() }
            assertEquals(2, published.get())
        } finally { release.countDown(); writer.finish() }
    }

    @Test fun cancellingAWaitingProducerDoesNotPublishOrLeakAPermit() = runBlocking {
        val writer = PreviewFrameWriter(Dispatchers.IO) { throw AssertionError(it) }
        val release = CountDownLatch(1)
        val published = AtomicInteger()
        try {
            writer.submit({ published.incrementAndGet() }) { release.await(5, TimeUnit.SECONDS) }
            val waiting = launch { writer.submit({ published.incrementAndGet() }) {} }
            yield()
            waiting.cancelAndJoin()
            release.countDown()
            writer.drain()
            withTimeout(5_000L) { writer.submit({ published.incrementAndGet() }) {}; writer.drain() }
            assertEquals(2, published.get())
        } finally { release.countDown(); writer.finish() }
    }

    @Test fun aFailedWriteDoesNotBlockLaterFrames() = runBlocking {
        val failures = AtomicInteger()
        val writer = PreviewFrameWriter(Dispatchers.IO) { failures.incrementAndGet() }
        try {
            writer.submit({ 1 }) { throw java.io.IOException("disk full") }
            writer.drain()
            withTimeout(5_000L) { writer.submit({ 2 }) {}; writer.drain() }
            assertEquals(1, failures.get())
        } finally { writer.finish() }
    }
}
