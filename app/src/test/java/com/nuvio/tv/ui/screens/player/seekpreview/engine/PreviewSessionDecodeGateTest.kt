package com.nuvio.tv.ui.screens.player.seekpreview.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PreviewSessionDecodeGateTest {
    @Test fun originalAndCopyCannotHoldNativeDecodersTogether() = runBlocking {
        val gate = PreviewSessionDecodeGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val original = async(Dispatchers.IO) {
            gate.run(serial = true) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val copy = async { gate.run(serial = true) { "copy" } }
            yield()
            assertFalse(copy.isCompleted)
            // Strong devices retain independent lanes.
            assertEquals("parallel", gate.run(serial = false) { "parallel" })
            release.countDown()
            withTimeout(5_000) { original.await(); assertEquals("copy", copy.await()) }
        } finally { release.countDown(); original.join() }
    }

    @Test fun cancellationAndDecoderFailureDoNotLeakTheSharedTurn() = runBlocking {
        val gate = PreviewSessionDecodeGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val original = launch(Dispatchers.IO) {
            gate.run(serial = true) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val copy = launch { gate.run(serial = true) { fail("Cancelled copy entered the decoder") } }
            yield()
            copy.cancelAndJoin()
            release.countDown()
            original.join()
            try {
                gate.run(serial = true) { throw java.io.IOException("decode failed") }
                fail("Failure was swallowed")
            } catch (_: java.io.IOException) { }
            assertEquals("recovered", withTimeout(5_000) { gate.run(serial = true) { "recovered" } })
        } finally { release.countDown(); original.join() }
    }
}
