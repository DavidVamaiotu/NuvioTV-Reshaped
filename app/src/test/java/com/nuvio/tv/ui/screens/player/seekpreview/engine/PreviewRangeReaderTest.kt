package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PreviewRangeReaderTest {
    @Test fun cancellationInvalidatesAllReadsOfAnOperationUntilTheNextTargetBegins() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/1000").setBody("abc"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/1000").setBody("def"))
            PreviewRangeReader(server.url("/file.mkv").toString(), emptyMap()).use { reader ->
                assertEquals("abc", String(reader.read(0, 3)))
                reader.cancel()
                assertTrue(runCatching { reader.read(3, 3) }.exceptionOrNull() is IOException)
                assertEquals(1, server.requestCount)
                reader.beginOperation()
                assertEquals("def", String(reader.read(3, 3)))
            }
        }
    }
    @Test fun obsoleteNetworkReadIsCancelledWithoutWaitingForTheBodyDeadline() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/1000")
                .setBody("abc").setBodyDelay(1, TimeUnit.SECONDS))
            PreviewRangeReader(server.url("/file.mkv").toString(), emptyMap()).use { reader ->
                val finished = CompletableFuture.supplyAsync { runCatching { reader.read(0, 3) }.exceptionOrNull() }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                reader.cancel()
                assertTrue(finished.get(2, TimeUnit.SECONDS) is IOException)
            }
        }
    }
    @Test fun validatesRangesAndNeverAcceptsAnIgnoredRange() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 100-102/1000").setBody("abc"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("whole film"))
            PreviewRangeReader(server.url("/file.mkv").toString(), emptyMap()).use { reader ->
                assertEquals("abc", String(reader.read(100, 3)))
                assertEquals(1000L, reader.totalLength)
                assertTrue(runCatching { reader.read(200, 3) }.exceptionOrNull() is IOException)
            }
            assertEquals("bytes=100-102", server.takeRequest().getHeader("Range"))
        }
    }
    @Test fun rejectsWrongOffsetsRatherThanDecodingWrongBytes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/1000").setBody("abc"))
            PreviewRangeReader(server.url("/file.mkv").toString(), emptyMap()).use { reader ->
                assertTrue(runCatching { reader.read(100, 3) }.exceptionOrNull() is IOException)
            }
        }
    }
    @Test fun followsCdnRedirectWithoutForwardingCredentials() {
        MockWebServer().use { origin -> MockWebServer().use { cdn ->
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/file.mkv")))
            cdn.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/1000").setBody("abc"))
            cdn.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/1000").setBody("def"))
            PreviewRangeReader(origin.url("/resolve/file").toString(), mapOf("Authorization" to "private-token", "X-Api-Key" to "key")).use {
                assertEquals("abc", String(it.read(0, 3)))
                assertEquals("def", String(it.read(3, 3)))
            }
            assertEquals("private-token", origin.takeRequest().getHeader("Authorization"))
            repeat(2) {
                val request = cdn.takeRequest()
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("X-Api-Key"))
            }
        } }
    }
    @Test fun respectsRateLimitCooldownAndClosedSessions() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
            val reader = PreviewRangeReader(server.url("/file.mkv").toString(), emptyMap())
            assertTrue(runCatching { reader.read(0, 3) }.exceptionOrNull() is IOException)
            assertTrue(runCatching { reader.read(0, 3) }.exceptionOrNull() is IOException)
            assertEquals(1, server.requestCount)
            reader.close()
            assertTrue(runCatching { reader.read(0, 3) }.exceptionOrNull() is IOException)
        }
    }
}
