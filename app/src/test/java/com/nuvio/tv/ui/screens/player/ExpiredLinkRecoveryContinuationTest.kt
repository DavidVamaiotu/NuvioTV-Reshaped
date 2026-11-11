package com.nuvio.tv.ui.screens.player

import android.util.Log
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExpiredLinkRecoveryContinuationTest {
    private val failedUrl = "https://cdn.example/file?token=expired"
    private lateinit var controller: PlayerRuntimeController
    private lateinit var state: MutableStateFlow<PlayerUiState>
    private var currentUrl = failedUrl
    private var attempted: String? = null

    @Before
    fun setUp() {
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerStreamsKt")
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerErrorRecoveryKt")
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        controller = mockk(relaxed = true)
        state = MutableStateFlow(PlayerUiState(currentStreamInfoHash = "hash"))
        every { controller._uiState } returns state
        every { controller.currentStreamUrl } answers { currentUrl }
        every { controller.currentVideoId } returns "episode"
        every { controller.currentAddonName } returns "Addon"
        every { controller.linkCacheSourceUrl } returns failedUrl
        every { controller.linkRefreshAttemptedUrl } answers { attempted }
        every { controller.linkRefreshAttemptedUrl = any() } answers { attempted = firstArg(); Unit }
        every { controller.streamCacheKey } returns null
        every { controller.errorRetryJob } returns null
        every { controller.loadSourceStreams(true) } just Runs
        every { controller.showRecoveryOverlay() } just Runs
    }

    @After
    fun tearDown() {
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerStreamsKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerErrorRecoveryKt")
        unmockkStatic(Log::class)
    }

    private fun bind(scope: CoroutineScope) { every { controller.scope } returns scope }

    @Test
    fun `failed refresh resumes existing recovery once without replacing it with an error`() = runTest {
        bind(this)
        var resumed = 0
        assertTrue(controller.tryRefreshExpiredLinkAfterError("expired") { resumed++ })
        assertEquals(0, resumed)
        advanceUntilIdle()
        assertEquals(1, resumed)
        assertNull(state.value.error)
        assertFalse(controller.tryRefreshExpiredLinkAfterError("expired") { resumed++ })
        advanceUntilIdle()
        assertEquals(1, resumed)
    }

    @Test
    fun `source switch invalidates a pending refresh and its recovery continuation`() = runTest {
        bind(this)
        var resumed = 0
        assertTrue(controller.tryRefreshExpiredLinkAfterError("expired") { resumed++ })
        runCurrent()
        currentUrl = "https://cdn.example/another-episode"
        advanceUntilIdle()
        assertEquals(0, resumed)
        assertNull(state.value.error)
    }
}
