package com.nuvio.tv.core.player.recovery

import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrepareAutoSourceTest {
    private fun stream(name: String) = Stream(name, null, null, "https://example.com/$name", null, null, null, null, null, "A", null)
    private val a = stream("first")
    private val b = stream("second")
    private val c = stream("third")
    private fun plan() = AutoSourceRecoveryPlan(
        listOf(a, b, c, stream("fourth")), StreamAutoPlayMode.FIRST_STREAM, "", emptyList(),
        StreamAutoPlaySource.ALL_SOURCES, setOf("A"), emptySet(), emptySet(), null, false, a, 1, "movie"
    )

    // Run real selection synchronously on the virtual test clock. Worker dispatch is covered by plan/ranking tests.
    private suspend fun <T : Any> prepare(
        p: AutoSourceRecoveryPlan, now: () -> Long, resolve: suspend (Stream) -> T?
    ) = prepareAutoSource(p, now, selectNext = { attempted ->
        StreamAutoPlaySelector.selectAutoPlayStream(
            streams = p.streams.filter { recoverySourceKey(it) !in attempted }, mode = p.mode,
            regexPattern = p.regex, rankedRegexPatterns = p.rankedRegex, source = p.source,
            installedAddonNames = p.installedAddonNames, selectedAddons = p.selectedAddons,
            selectedPlugins = p.selectedPlugins
        )
    }, resolve = resolve)

    @Test fun `successful resolution hands remaining time to player instead of renewing it`() = runTest {
        val prepared = prepare(plan(), { currentTime }) { delay(6_000); "resolved" }!!
        val resumed = StartupSourceRecovery(currentTime, prepared.plan.startupState)
        assertEquals(9_000L, resumed.remainingMs(currentTime))
        assertEquals(setOf(recoverySourceKey(a)), prepared.plan.attempted)
    }

    @Test fun `resolution timeout advances at fifteen seconds in normal source order`() = runTest {
        val seen = mutableListOf<Stream>()
        val prepared = prepare(plan(), { currentTime }) {
            seen += it
            if (it == a) delay(15_001)
            "resolved"
        }!!
        assertEquals(listOf(a, b), seen)
        assertEquals(15_000L, currentTime)
        assertEquals(2, prepared.plan.startupState!!.attempts)
        assertEquals(b, prepared.plan.initialSource)
    }

    @Test fun `three stalled resolutions stop after forty five seconds`() = runTest {
        val seen = mutableListOf<Stream>()
        assertNull(prepare(plan(), { currentTime }) { seen += it; delay(20_000); "late" })
        assertEquals(listOf(a, b, c), seen)
        assertEquals(45_000L, currentTime)
    }

    @Test fun `resolution and player share the three attempt limit`() = runTest {
        val prepared = prepare(plan(), { currentTime }) { if (it == c) "resolved" else null }!!
        val resumed = StartupSourceRecovery(currentTime, prepared.plan.startupState)
        assertEquals(StartupSourceRecovery.Decision.Stop, resumed.evaluate(currentTime, false, false, true))
        assertFalse(resumed.beginNextAttempt(currentTime))
    }

    @Test fun `resolution retries respect ranked regex priorities`() = runTest {
        val low = stream("1080p")
        val top1 = stream("2160p one")
        val top2 = stream("2160p two")
        val seen = mutableListOf<Stream>()
        prepare(plan().copy(
            streams = listOf(low, top1, top2), initialSource = top1,
            mode = StreamAutoPlayMode.REGEX_MATCH, rankedRegex = listOf("2160p", "1080p")
        ), { currentTime }) { seen += it; if (it == low) "resolved" else null }
        assertEquals(listOf(top1, top2, low), seen)
    }

    @Test fun `cancellation is propagated without selecting another source`() = runTest {
        val seen = mutableListOf<Stream>()
        try {
            prepare<String>(plan(), { currentTime }) { seen += it; throw CancellationException("user left") }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertEquals(listOf(a), seen)
    }
}
