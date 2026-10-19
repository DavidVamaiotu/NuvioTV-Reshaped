package com.nuvio.tv.ui.screens.player.seekpreview.engine

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpRangeReaderTest {
    private fun reader(url: String, headers: Map<String, String> = emptyMap()) = HttpRangeReader(url, headers,
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(2, TimeUnit.SECONDS).build(),
        ConnectionLedger(clock = { System.nanoTime() / 1_000_000 }))
    private fun bytes(text: String, start: Int = 0, total: Int = 10) = MockResponse().setResponseCode(206)
        .setHeader("Content-Range", "bytes $start-${start + text.length - 1}/$total").setBody(text)

    @Test fun validatesRangesAndRejectsAChangedFile(): Unit = MockWebServer().use { server ->
        server.enqueue(bytes("abc")); server.enqueue(bytes("def", 3, 11))
        val reader = reader(server.url("/film.mkv").toString())
        try {
            assertArrayEquals("abc".toByteArray(), reader.read(0, 3))
            assertThrows(UnsupportedMediaException::class.java) { reader.read(3, 3) }
        } finally { reader.close() }
    }
    @Test fun refusesIgnoredAndMisalignedRanges(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("abc")); server.enqueue(bytes("abc", 1))
        val reader = reader(server.url("/film.mkv").toString())
        try {
            assertThrows(UnsupportedMediaException::class.java) { reader.read(0, 3) }
            assertThrows(UnsupportedMediaException::class.java) { reader.read(0, 3) }
        } finally { reader.close() }
    }
    @Test fun truncatedDownloadFailsWithoutPoisoningTheNextRead(): Unit = MockWebServer().use { server ->
        server.enqueue(bytes("ab").setHeader("Content-Range", "bytes 0-2/10"))
        server.enqueue(bytes("abc"))
        val reader = reader(server.url("/film.mkv").toString())
        try {
            assertThrows(IOException::class.java) { reader.read(0, 3) }
            assertArrayEquals("abc".toByteArray(), reader.read(0, 3))
        } finally { reader.close() }
    }
    @Test fun rateLimitedReadCanRecoverWithoutRestartingThePlayer(): Unit = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "1"))
        server.enqueue(bytes("abc"))
        val reader = reader(server.url("/film.mkv").toString())
        try {
            val failure = assertThrows(RateLimitedException::class.java) { reader.read(0, 3) }
            assertEquals(503, failure.status)
            assertArrayEquals("abc".toByteArray(), reader.read(0, 3))
        } finally { reader.close() }
    }
    @Test fun redirectAndCachedCdnReadsNeverReceiveOriginHeaders(): Unit = MockWebServer().use { origin ->
        MockWebServer().use { cdn ->
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/film.mkv")))
            cdn.enqueue(bytes("abc")); cdn.enqueue(bytes("def", 3))
            val reader = reader(origin.url("/link").toString(), mapOf("Authorization" to "secret", "X-Api-Key" to "secret", "User-Agent" to "preview-test"))
            try {
                reader.read(0, 3); reader.read(3, 3)
                assertEquals("secret", origin.takeRequest().getHeader("Authorization"))
                repeat(2) {
                    val request = cdn.takeRequest()
                    assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("X-Api-Key"))
                    assertEquals("preview-test", request.getHeader("User-Agent"))
                }
                assertEquals(1, origin.requestCount)
            } finally { reader.close() }
        }
    }
    @Test fun retriesAnExpiredCdnAddressThroughTheOriginalLink(): Unit = MockWebServer().use { origin ->
        MockWebServer().use { cdn ->
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/old")))
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/new")))
            cdn.enqueue(bytes("abc")); cdn.enqueue(MockResponse().setResponseCode(403)); cdn.enqueue(bytes("def", 3))
            val reader = reader(origin.url("/link").toString())
            try {
                reader.read(0, 3)
                assertArrayEquals("def".toByteArray(), reader.read(3, 3))
                assertEquals(2, origin.requestCount)
            } finally { reader.close() }
        }
    }
    @Test fun cancellingInflightReadsDoesNotPoisonTheNextRequest(): Unit = MockWebServer().use { server ->
        server.enqueue(bytes("abc").setBodyDelay(3, TimeUnit.SECONDS)); server.enqueue(bytes("def", 3))
        val reader = reader(server.url("/film.mkv").toString())
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val worker = Thread { try { reader.read(0, 3) } catch (error: Throwable) { failure.set(error) } }
        worker.start()
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); reader.cancel(); worker.join(3000)
        try {
            assertFalse(worker.isAlive); assertTrue(failure.get() is IOException)
            assertArrayEquals("def".toByteArray(), reader.read(3, 3))
        } finally { reader.close() }
    }
}
