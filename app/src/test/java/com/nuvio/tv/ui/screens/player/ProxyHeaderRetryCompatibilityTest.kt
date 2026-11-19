package com.nuvio.tv.ui.screens.player

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class ProxyHeaderRetryCompatibilityTest {
    private var previousHttp2 = false

    @Before fun defaultProtocol() {
        previousHttp2 = NuvioExoPlayerPerformanceHelper.enableHttp2
        NuvioExoPlayerPerformanceHelper.enableHttp2 = false
    }

    @After fun restoreProtocol() {
        NuvioExoPlayerPerformanceHelper.enableHttp2 = previousHttp2
        PlaybackConnectionEvents.clear()
    }

    @Test fun explicitHttp2KeepsReshapedConnectionLanesAndAvoidsAnotherRetryClient() {
        NuvioExoPlayerPerformanceHelper.enableHttp2 = true
        val client = PlayerPlaybackNetworking.createHttpClient(mapOf("Referer" to "https://addon.example/"))
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), client.protocols)
        assertTrue(client.interceptors.none { it is PlayerPlaybackNetworking.Http2On403Interceptor })
        val pools = List(3) { ConnectionPool() }
        val lanes = HttpLanes.callFactory(client, 3, pools::get) as LaneCallFactory
        assertEquals(3, lanes.lanes.size)
        assertSame(client.connectionPool, lanes.lanes[0].connectionPool)
        assertSame(pools[1], lanes.lanes[1].connectionPool)
        assertSame(pools[2], lanes.lanes[2].connectionPool)
    }

    @Test fun automaticRetryRetainsAuthenticationRangePoolDispatcherAndLongReadTimeout() {
        val client = PlayerPlaybackNetworking.createHttpClient(
            mapOf("Authorization" to "Bearer secret", "Referer" to "https://addon.example/", "Range" to "ignored"),
            useLongReadTimeout = true
        )
        val retry = client.interceptors.filterIsInstance<PlayerPlaybackNetworking.Http2On403Interceptor>()
            .single().http2Client as okhttp3.OkHttpClient
        assertSame(client.connectionPool, retry.connectionPool)
        assertSame(client.dispatcher, retry.dispatcher)
        assertEquals(65_000, retry.readTimeoutMillis)
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), retry.protocols)
        assertEquals(client.networkInterceptors, retry.networkInterceptors)
        val request = request("headers").newBuilder().header("Range", "bytes=10-20").build()
        val chain = FakeChain(request, reply(request, 200))
        retry.networkInterceptors.single().intercept(chain).close()
        assertEquals("Bearer secret", chain.proceededRequest!!.header("Authorization"))
        assertEquals("https://addon.example/", chain.proceededRequest!!.header("Referer"))
        assertEquals("bytes=10-20", chain.proceededRequest!!.header("Range"))
    }

    @Test fun successfulHttp2RetryClosesTheOriginalAndRemembersOnlyThatHost() {
        val request = request("h2-success")
        val firstBody = TrackedBody("forbidden")
        val original = reply(request, 403, body = firstBody)
        val factory = RetryFactory { reply(it.request(), 206, Protocol.HTTP_2, TrackedBody("range")) }
        val interceptor = PlayerPlaybackNetworking.Http2On403Interceptor(factory)
        val first = FakeChain(request, original)
        interceptor.intercept(first).use { assertEquals("range", it.body!!.string()) }
        assertTrue(firstBody.closed)
        assertSame(request, factory.calls.first().request())
        val second = FakeChain(request, reply(request, 403))
        interceptor.intercept(second).close()
        assertEquals(0, second.proceeded)
        assertEquals(2, factory.calls.size)
    }

    @Test fun aSuccessfulHttp1RetryDoesNotCreateAFalseHttp2HostPreference() {
        val request = request("h1-success")
        val factory = RetryFactory { reply(it.request(), 200, Protocol.HTTP_1_1) }
        val interceptor = PlayerPlaybackNetworking.Http2On403Interceptor(factory)
        interceptor.intercept(FakeChain(request, reply(request, 403))).close()
        val second = FakeChain(request, reply(request, 403))
        interceptor.intercept(second).close()
        assertEquals(1, second.proceeded)
        assertEquals(2, factory.calls.size)
    }

    @Test fun unsuccessfulRetryReturnsTheOriginal403AndClosesOnlyTheRetryBody() {
        val request = request("failed-response")
        val originalBody = TrackedBody("original")
        val original = reply(request, 403, body = originalBody)
        val retryBody = TrackedBody("retry denied")
        val factory = RetryFactory { reply(it.request(), 403, Protocol.HTTP_2, retryBody) }
        val result = PlayerPlaybackNetworking.Http2On403Interceptor(factory)
            .intercept(FakeChain(request, original))
        assertSame(original, result)
        assertFalse(originalBody.closed)
        assertTrue(retryBody.closed)
        assertEquals("original", result.body!!.string())
    }

    @Test fun retryIoFailureKeepsTheOriginalResponseAndReleasesTheCancellationLink() {
        val request = request("io-failure")
        val parent = FakeCall(request)
        val originalBody = TrackedBody("original")
        val original = reply(request, 403, body = originalBody)
        val factory = RetryFactory { throw IOException("retry unavailable") }
        val result = PlayerPlaybackNetworking.Http2On403Interceptor(factory)
            .intercept(FakeChain(request, original, parent))
        assertSame(original, result)
        assertFalse(originalBody.closed)
        PlaybackConnectionEventListener(1).canceled(parent)
        assertFalse(factory.calls.single().canceled)
        result.close()
    }

    @Test fun cleartext403NeverUsesTheProtocolRetry() {
        val request = request("cleartext").newBuilder().url("http://cleartext.retry.test/video").build()
        val original = reply(request, 403)
        val factory = RetryFactory { error("No cleartext retry") }
        assertSame(original, PlayerPlaybackNetworking.Http2On403Interceptor(factory)
            .intercept(FakeChain(request, original)))
        assertTrue(factory.calls.isEmpty())
        original.close()
    }

    @Test fun playbackCancellationInterruptsAnExecutingProtocolRetry() {
        val request = request("cancel-execute")
        val parent = FakeCall(request)
        val factory = RetryFactory { child ->
            parent.cancel()
            PlaybackConnectionEventListener(2).canceled(parent)
            assertTrue(child.canceled)
            throw IOException("Canceled")
        }
        val original = reply(request, 403)
        PlayerPlaybackNetworking.Http2On403Interceptor(factory)
            .intercept(FakeChain(request, original, parent)).close()
        assertTrue(factory.calls.single().canceled)
    }

    @Test fun cancellationBeforeRetryRegistrationStillCancelsTheNewCall() {
        val request = request("cancel-before")
        val parent = FakeCall(request).apply { cancel() }
        val factory = RetryFactory { error("Canceled retry must not execute its transport") }
        val original = reply(request, 403)
        PlayerPlaybackNetworking.Http2On403Interceptor(factory)
            .intercept(FakeChain(request, original, parent)).close()
        assertTrue(factory.calls.single().canceled)
    }

    @Test fun retryBodyRemainsCancelableUntilClosedThenReleasesItsLink() {
        val request = request("cancel-body")
        val parent = FakeCall(request)
        val body = TrackedBody("streaming")
        val factory = RetryFactory { reply(it.request(), 200, Protocol.HTTP_2, body) }
        val response = PlayerPlaybackNetworking.Http2On403Interceptor(factory)
            .intercept(FakeChain(request, reply(request, 403), parent))
        PlaybackConnectionEventListener(3).canceled(parent)
        assertEquals(1, factory.calls.single().cancelCount)
        response.close()
        assertTrue(body.closed)
        PlaybackConnectionEventListener(4).canceled(parent)
        assertEquals(1, factory.calls.single().cancelCount)
    }

    @Test fun non403ResponsesNeverTriggerAProtocolRetry() {
        val request = request("not-forbidden")
        val original = reply(request, 200)
        val factory = RetryFactory { error("No retry for success") }
        assertSame(original, PlayerPlaybackNetworking.Http2On403Interceptor(factory)
            .intercept(FakeChain(request, original)))
        assertTrue(factory.calls.isEmpty())
        original.close()
    }

    private fun request(host: String) = Request.Builder().url("https://$host.retry.test/video")
        .header("Authorization", "Bearer request-token").header("Range", "bytes=100-").build()

    private fun reply(request: Request, code: Int, protocol: Protocol = Protocol.HTTP_1_1,
                      body: ResponseBody = TrackedBody("body")): Response = Response.Builder()
        .request(request).protocol(protocol).code(code).message("test").body(body).build()

    private class TrackedBody(private val text: String) : ResponseBody() {
        var closed = false
        private val bytes = object : ForwardingSource(Buffer().writeUtf8(text)) {
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = text.toByteArray().size.toLong()
        override fun source(): BufferedSource = bytes
    }

    private class RetryFactory(private val reply: (FakeCall) -> Response) : Call.Factory {
        val calls = mutableListOf<FakeCall>()
        override fun newCall(request: Request): Call = FakeCall(request, reply).also { calls += it }
    }

    private class FakeCall(private val req: Request, private val reply: (FakeCall) -> Response = { error("No transport") }) : Call {
        private val tags = mutableMapOf<Class<*>, Any>()
        override fun <T : Any> tag(type: KClass<T>): T? = tag(type.java)
        override fun <T> tag(type: Class<out T>): T? = type.cast(tags[type])
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
            tag(type.java, computeIfAbsent)
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
            type.cast(tags.getOrPut(type, computeIfAbsent))
        var canceled = false
        var cancelCount = 0
        private var executed = false
        override fun request(): Request = req
        override fun execute(): Response {
            executed = true
            if (canceled) throw IOException("Canceled")
            return reply(this)
        }
        override fun enqueue(responseCallback: Callback) = error("Synchronous retry only")
        override fun cancel() { canceled = true; cancelCount++ }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = canceled
        override fun timeout(): Timeout = Timeout()
        override fun clone(): Call = FakeCall(req, reply)
    }

    private class FakeChain(private val req: Request, private val response: Response,
                            private val parent: Call = FakeCall(req)) : Interceptor.Chain {
        var proceeded = 0
        var proceededRequest: Request? = null
        override fun request(): Request = req
        override fun proceed(request: Request): Response { proceeded++; proceededRequest = request; return response }
        override fun connection(): Connection? = null
        override fun call(): Call = parent
        override fun connectTimeoutMillis(): Int = 15_000
        override fun readTimeoutMillis(): Int = 15_000
        override fun writeTimeoutMillis(): Int = 15_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
