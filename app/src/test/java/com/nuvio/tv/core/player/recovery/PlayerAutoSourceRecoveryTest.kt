package com.nuvio.tv.core.player.recovery

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.*
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerAutoSourceRecoveryTest {
    @After fun tearDown() = unmockkAll()

    private class Fixture(scope: TestScope) {
        val player = mockk<PlayerRuntimeController>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val state = MutableStateFlow(PlayerUiState())
        val enabled = MutableStateFlow(true)
        val toast = mockk<Toast>(relaxed = true)
        val plan = mockk<AutoSourceRecoveryPlan>(relaxed = true)
        var nextSourceAtMs: Long? = null
        val recovery: PlayerAutoSourceRecovery

        init {
            mockkObject(AutoSourceRecoverySettings)
            mockkStatic(SystemClock::class, Toast::class)
            mockkStatic(
                "com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerMpvKt",
                "com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerLifecycleKt",
                "com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerErrorRecoveryKt",
                "com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerObserversKt",
                "com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerTorrentKt"
            )
            every { SystemClock.elapsedRealtime() } answers { scope.testScheduler.currentTime }
            every { AutoSourceRecoverySettings.enabled(context) } returns enabled
            every { context.getString(any()) } returns "Buffering"
            every { context.getString(R.string.player_auto_source_recovery_switching, any()) } returns "Switch notice"
            every { Toast.makeText(context, any<CharSequence>(), Toast.LENGTH_LONG) } returns toast
            every { player.context } returns context
            every { player.scope } returns scope.backgroundScope
            every { player._uiState } returns state
            every { player.currentStreamUrl } returns "https://example.test/first"
            every { player.currentPlaybackPositionMs() } returns 0L
            every { player.isPlaybackCurrentlyPlaying() } returns false
            every { player.resetErrorRetryState() } just Runs
            every { player.cancelFirstFrameWatchdog() } just Runs
            every { player.cancelStallWatchdog() } just Runs
            every { player.releasePlayer(flushPlaybackState = false) } just Runs
            every { player.stopTorrentStream() } just Runs
            every { plan.startupState } returns null
            every { plan.initialSource } returns null
            every { plan.attempted } returns emptySet()
            coEvery { plan.next(any()) } coAnswers {
                nextSourceAtMs = scope.testScheduler.currentTime
                // Keep URL selection pending so tests can observe the exact handoff time.
                awaitCancellation()
            }
            recovery = PlayerAutoSourceRecovery(player, plan)
            every { player.autoSourceRecovery } returns recovery
        }
    }

    @Test fun `terminal error wakes recovery before the next 500 ms sample`() = runTest {
        val f = Fixture(this)
        f.recovery.start()
        runCurrent()
        advanceTimeBy(123L)

        assertTrue(f.recovery.failDefinitively("HTTP 403: access denied\n\nTechnical details"))
        assertNull(f.state.value.error)
        assertTrue(f.state.value.showLoadingOverlay)
        runCurrent()

        assertEquals(123L, f.nextSourceAtMs)
        verify { f.context.getString(R.string.player_auto_source_recovery_switching, "HTTP 403: access denied") }
        verify(exactly = 1) { f.toast.show() }
        f.recovery.cancel()
    }

    @Test fun `failure before recovery coroutine starts is not lost`() = runTest {
        val f = Fixture(this)
        f.recovery.start()
        assertTrue(f.recovery.failDefinitively("Source unavailable"))
        runCurrent()

        assertEquals(0L, f.nextSourceAtMs)
        assertNull(f.state.value.error)
        f.recovery.cancel()
    }

    @Test fun `waiting source keeps its full startup deadline and explains a timeout`() = runTest {
        val f = Fixture(this)
        f.recovery.start()
        runCurrent()
        advanceTimeBy(14_999L)
        runCurrent()
        assertNull(f.nextSourceAtMs)
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(15_000L, f.nextSourceAtMs)
        verify { f.context.getString(R.string.player_auto_source_recovery_timeout) }
        f.recovery.cancel()
    }

    @Test fun `manual pause healthy playback disabled setting and cancellation do not claim errors`() = runTest {
        val f = Fixture(this)
        every { f.player.userPausedManually } returns true
        assertFalse(f.recovery.failDefinitively("Paused"))
        every { f.player.userPausedManually } returns false
        every { f.player.hasRenderedFirstFrame } returns true
        assertFalse(f.recovery.failDefinitively("Already playing"))
        every { f.player.hasRenderedFirstFrame } returns false
        f.enabled.value = false
        assertFalse(f.recovery.failDefinitively("Disabled"))
        f.enabled.value = true
        f.recovery.cancel()
        assertFalse(f.recovery.failDefinitively("Cancelled"))
        verify(exactly = 0) { f.toast.show() }
    }
}
