package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import java.io.File
import java.io.IOException
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

class TorboxPreviewMetadataCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun setupAndPlaybackShareOneStreamedListingAcrossCacheInstances() = runBlocking {
        val folder = temporary.newFolder()
        var requests = 0
        suspend fun read(cache: TorboxPreviewMetadataCache) = cache.get("account/a/list/0", 1024, 60_000,
            decode = { it.bufferedReader().readText() },
            fetch = { read -> requests++; read("listing".byteInputStream()) })
        assertEquals("listing", read(TorboxPreviewMetadataCache(folder)))
        assertEquals("listing", read(TorboxPreviewMetadataCache(folder)))
        assertEquals(1, requests)
    }

    @Test fun accountIsolationExpiryAndExplicitRefreshFetchNewLocations() = runBlocking {
        var clock = 10_000L
        val cache = TorboxPreviewMetadataCache(temporary.newFolder(), now = { clock })
        var requests = 0
        suspend fun read(account: String, refresh: Boolean = false) = cache.get(account, 64, 1000, refresh,
            decode = { it.readBytes().decodeToString() }, fetch = { read -> requests++; read("$requests".byteInputStream()) })
        assertEquals("1", read("a")); assertEquals("1", read("a"))
        assertEquals("2", read("b"))
        clock += 1001
        assertEquals("3", read("a")); assertEquals("3", read("a"))
        assertEquals("4", read("a", refresh = true)); assertEquals("4", read("a"))
    }

    @Test fun corruptMetadataIsReplacedAndOversizeResponsesAreNotCached() = runBlocking {
        val folder = temporary.newFolder()
        val cache = TorboxPreviewMetadataCache(folder)
        var requests = 0
        suspend fun read(payload: String = "valid") = cache.get("index", 8, Long.MAX_VALUE,
            decode = { it.readBytes().decodeToString().also { require(it == "valid") } },
            fetch = { read -> requests++; read(payload.byteInputStream()) })
        assertEquals("valid", read())
        folder.listFiles()!!.single().writeText("bad")
        assertEquals("valid", read()); assertEquals(2, requests)
        try {
            cache.get("too-big", 8, Long.MAX_VALUE, decode = { it.readBytes() },
                fetch = { read -> read(ByteArray(100).inputStream()) })
            fail("Oversize metadata accepted")
        } catch (_: IOException) { }
        assertEquals(1, folder.listFiles()!!.size)
    }

    @Test fun concurrentReadersJoinAndCancellationDoesNotPublishPartialData() = runBlocking {
        val folder = temporary.newFolder()
        val cache = TorboxPreviewMetadataCache(folder)
        val ready = CompletableDeferred<Unit>()
        var requests = 0
        suspend fun read() = cache.get("listing", 64, 60_000, decode = { it.readBytes().decodeToString() },
            fetch = { read -> requests++; ready.await(); read("valid".byteInputStream()) })
        val one = async(start = CoroutineStart.UNDISPATCHED) { read() }
        val two = async(start = CoroutineStart.UNDISPATCHED) { read() }
        ready.complete(Unit)
        assertEquals("valid", one.await()); assertEquals("valid", two.await()); assertEquals(1, requests)
        val cancel = async(start = CoroutineStart.UNDISPATCHED) {
            cache.get("cancel", 64, 60_000, decode = { it.readBytes() },
                fetch = { CompletableDeferred<ByteArray>().await() })
        }
        cancel.cancelAndJoin()
        assertFalse(folder.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun refreshDuringAFileFetchDoesNotDeadlockAndDiskUsageIsBounded() = runBlocking {
        val folder = temporary.newFolder()
        val cache = TorboxPreviewMetadataCache(folder, maxBytes = 8)
        withTimeout(2000) {
            cache.get("index", 64, Long.MAX_VALUE, decode = { it.readBytes() }, fetch = { read ->
                cache.get("locations", 64, 60_000, decode = { it.readBytes() },
                    fetch = { listing -> listing("list".byteInputStream()) })
                read("index".byteInputStream())
            })
        }
        assertTrue(folder.listFiles()!!.sumOf { it.length() } <= 8)
    }

    @Test fun unwritableCacheFallsBackToStreamingWithoutLosingPreviews() = runBlocking {
        val file = temporary.newFile()
        val cache = TorboxPreviewMetadataCache(File(file, "child"))
        assertEquals("valid", cache.get("listing", 64, 60_000,
            decode = { it.readBytes().decodeToString() }, fetch = { read -> read("valid".byteInputStream()) }))
    }
}
