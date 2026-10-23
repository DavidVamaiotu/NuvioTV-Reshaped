package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TorboxSpriteCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun sheet(data: ByteArray, path: String = "sprites/sheet.webp") = TorboxSpriteSheet(
        path, 1600, 900, data.size,
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    )

    @Test fun compressedBytesSurviveClosingAndReopeningTheMovie() = runBlocking {
        val directory = temporary.newFolder()
        val data = ByteArray(64) { it.toByte() }
        val s = sheet(data)
        val first = TorboxSpriteByteCache(TorboxSpriteDiskCache(directory))
        assertArrayEquals(data, first.get(s) { data })
        first.close()
        val second = TorboxSpriteByteCache(TorboxSpriteDiskCache(directory))
        assertArrayEquals(data, second.get(s) { error("Must reuse the verified disk copy") })
    }

    @Test fun foregroundRequestJoinsAnInFlightPrefetchInsteadOfDownloadingAgain() = runBlocking {
        val cache = TorboxSpriteByteCache(TorboxSpriteDiskCache(temporary.newFolder()))
        val data = byteArrayOf(1, 2, 3)
        val s = sheet(data)
        val ready = CompletableDeferred<Unit>()
        var requests = 0
        val warm = async(start = CoroutineStart.UNDISPATCHED) { cache.get(s) {
            requests++
            ready.await()
            data
        } }
        val foreground = async(start = CoroutineStart.UNDISPATCHED) { cache.get(s) {
            requests++
            data
        } }
        ready.complete(Unit)
        withTimeout(2_000) {
            assertArrayEquals(data, warm.await())
            assertArrayEquals(data, foreground.await())
        }
        assertEquals(1, requests)
    }

    @Test fun aDifferentTimestampDoesNotWaitForAnUnrelatedBackgroundSheet() = runBlocking {
        val cache = TorboxSpriteByteCache(TorboxSpriteDiskCache(temporary.newFolder()))
        val one = byteArrayOf(1)
        val two = byteArrayOf(2)
        val ready = CompletableDeferred<Unit>()
        val warm = async(start = CoroutineStart.UNDISPATCHED) { cache.get(sheet(one)) {
            ready.await()
            one
        } }
        withTimeout(2_000) { assertArrayEquals(two, cache.get(sheet(two)) { two }) }
        ready.complete(Unit)
        warm.await()
        Unit
    }

    @Test fun corruptDiskDataIsDiscardedAndFetchedAgain() = runBlocking {
        val directory = temporary.newFolder()
        val data = byteArrayOf(1, 2, 3)
        val s = sheet(data)
        val disk = TorboxSpriteDiskCache(directory)
        disk.write(s, data)
        File(directory, s.sha256).writeBytes(byteArrayOf(9, 9, 9))
        var fetched = 0
        val cache = TorboxSpriteByteCache(disk)
        assertArrayEquals(data, cache.get(s) { fetched++; data })
        assertEquals(1, fetched)
        assertArrayEquals(data, disk.read(s))
    }

    @Test fun invalidDownloadedBytesNeverEnterEitherCache() = runBlocking {
        val directory = temporary.newFolder()
        val cache = TorboxSpriteByteCache(TorboxSpriteDiskCache(directory))
        val data = byteArrayOf(1, 2, 3)
        val s = sheet(data)
        try {
            cache.get(s) { byteArrayOf(9, 9, 9) }
            fail("Corrupt download was accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, cache.memoryBytes)
        assertFalse(File(directory, s.sha256).exists())
        assertArrayEquals(data, cache.get(s) { data })
    }

    @Test fun canceledFetchDoesNotPoisonTheNextRequest() = runBlocking {
        val directory = temporary.newFolder()
        val cache = TorboxSpriteByteCache(TorboxSpriteDiskCache(directory))
        val data = byteArrayOf(1, 2, 3)
        val s = sheet(data)
        val pending = CompletableDeferred<ByteArray>()
        val request = async(start = CoroutineStart.UNDISPATCHED) { cache.get(s) { pending.await() } }
        request.cancelAndJoin()
        assertEquals(0, cache.memoryBytes)
        assertFalse(File(directory, s.sha256).exists())
        assertArrayEquals(data, cache.get(s) { data })
    }

    @Test fun ramLimitIsBoundedAndEvictedSheetsStayOnDisk() = runBlocking {
        val cache = TorboxSpriteByteCache(TorboxSpriteDiskCache(temporary.newFolder()), maxBytes = 32)
        val one = ByteArray(32) { 1 }
        val two = ByteArray(32) { 2 }
        cache.get(sheet(one)) { one }
        cache.get(sheet(two)) { two }
        assertEquals(32, cache.memoryBytes)
        assertArrayEquals(one, cache.get(sheet(one)) { error("RAM eviction must not force a download") })
        assertEquals(32, cache.memoryBytes)
        cache.close()
        assertEquals(0, cache.memoryBytes)
    }

    @Test fun diskLimitEvictsTheLeastRecentlyUsedSheet() {
        val directory = temporary.newFolder()
        val disk = TorboxSpriteDiskCache(directory, maxBytes = 64)
        val one = ByteArray(32) { 1 }
        val two = ByteArray(32) { 2 }
        val three = ByteArray(32) { 3 }
        val a = sheet(one); val b = sheet(two); val c = sheet(three)
        disk.write(a, one); disk.write(b, two)
        File(directory, a.sha256).setLastModified(1_000)
        File(directory, b.sha256).setLastModified(2_000)
        assertArrayEquals(one, disk.read(a))
        disk.write(c, three)
        assertArrayEquals(one, disk.read(a))
        assertNull(disk.read(b))
        assertArrayEquals(three, disk.read(c))
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 64L)
    }

    private fun frames() = (0..4).map { i ->
        TorboxSpriteFrame(i * 10_000L, (i + 1) * 10_000L, i * 10_000L,
            "sprites/$i.webp", 0, 0, 320, 180)
    }
    private fun sheets() = frames().associate { it.sheet to TorboxSpriteSheet(it.sheet, 320, 180, 4, "a".repeat(64)) }

    @Test fun prefetchStartsAtTheResumePointAndNewSeeksMoveToTheFront() {
        val queue = TorboxPrefetchQueue(frames(), sheets(), 25_000)
        assertEquals("sprites/2.webp", queue.next()!!.path)
        queue.prioritize("sprites/4.webp")
        assertEquals("sprites/4.webp", queue.next()!!.path)
        val rest = generateSequence { queue.next()?.path }.toList()
        assertEquals(setOf("sprites/0.webp", "sprites/1.webp", "sprites/3.webp"), rest.toSet())
        assertEquals(3, rest.size)
    }

    @Test fun prefetchRespectsBothTransferAndSheetLimits() {
        val byteLimit = TorboxPrefetchQueue(frames(), sheets(), 25_000, maxBytes = 8)
        assertEquals(2, generateSequence { byteLimit.next() }.count())
        val countLimit = TorboxPrefetchQueue(frames(), sheets(), 25_000, maxSheets = 1)
        assertEquals("sprites/2.webp", countLimit.next()!!.path)
        assertNull(countLimit.next())
    }
}
