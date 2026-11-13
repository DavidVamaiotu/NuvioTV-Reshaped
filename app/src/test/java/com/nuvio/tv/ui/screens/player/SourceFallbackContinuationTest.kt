package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.data.local.PlayerSettings
import io.mockk.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SourceFallbackContinuationTest {
    private lateinit var controller: PlayerRuntimeController
    private lateinit var state: MutableStateFlow<PlayerUiState>
    private val failedUrl = "https://cdn.example/failed"
    private var currentUrl = failedUrl
    private var exhaustedUrl: String? = null
    private var fallbackJob: Job? = null
    private var rendered = false

    @Before
    fun setUp() {
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerStreamsKt")
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerErrorRecoveryKt")
        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerServerKt")
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        controller = mockk(relaxed = true)
        state = MutableStateFlow(PlayerUiState(sourceStreamsError = "No sources"))
        every { controller._uiState } returns state
        every { controller.currentStreamUrl } answers { currentUrl }
        every { controller.currentVideoId } returns "episode"
        every { controller.hasRenderedFirstFrame } answers { rendered }
        every { controller.autoSelectedSourceUrl } returns failedUrl
        every { controller.streamAutoPlayFallbackActive } returns true
        every { controller.streamAutoPlayFallbackMaxSources } returns 3
        every { controller.sourceFallbackAttempts } returns 0
        every { controller.sourceFallbackTriedKeys } returns mutableSetOf()
        every { controller.sourceFallbackExhaustedUrl } answers { exhaustedUrl }
        every { controller.sourceFallbackExhaustedUrl = any() } answers { exhaustedUrl = firstArg(); Unit }
        every { controller.sourceFallbackJob } answers { fallbackJob }
        every { controller.sourceFallbackJob = any() } answers { fallbackJob = firstArg(); Unit }
        every { controller.playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        every { controller.streamCacheKey } returns null
        every { controller.loadSourceStreams(false) } just Runs
        every { controller.showRecoveryOverlay() } just Runs
        coEvery { controller.streamAddonsFor("episode") } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerStreamsKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerErrorRecoveryKt")
        unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerServerKt")
        unmockkStatic(Log::class)
    }

    @Test
    fun `exhausted source search resumes original recovery once without looping`() = runTest {
        every { controller.scope } returns this
        var resumed = 0
        every { controller.streamCacheKey } returns "cache-key"
        coEvery { controller.streamLinkCacheDataStore.remove("cache-key") } returns Unit
        assertTrue(controller.tryFallbackToNextSourceAfterError("failed") {
            resumed++
            assertFalse(controller.tryFallbackToNextSourceAfterError("failed"))
        })
        advanceUntilIdle()
        assertEquals(1, resumed)
        assertEquals(failedUrl, exhaustedUrl)
        coVerify(exactly = 1) { controller.streamLinkCacheDataStore.remove("cache-key") }
        assertNull(state.value.error)
    }

    @Test
    fun `switching sources cancels an obsolete fallback continuation`() = runTest {
        every { controller.scope } returns this
        state.value = PlayerUiState(isLoadingSourceStreams = true)
        var resumed = 0
        assertTrue(controller.tryFallbackToNextSourceAfterError("failed") { resumed++ })
        runCurrent()
        currentUrl = "https://cdn.example/manually-selected"
        assertFalse(controller.tryFallbackToNextSourceAfterError("manual source error"))
        advanceUntilIdle()
        assertEquals(0, resumed)
        assertNull(exhaustedUrl)
        assertNull(state.value.error)
    }
    @Test
    fun `first frame stops a pending fallback without replacing recovered playback`() = runTest {
        every { controller.scope } returns this
        state.value = PlayerUiState(isLoadingSourceStreams = true)
        var resumed = 0
        assertTrue(controller.tryFallbackToNextSourceAfterError("failed") { resumed++ })
        runCurrent()
        rendered = true
        assertFalse(controller.tryFallbackToNextSourceAfterError("another error"))
        advanceUntilIdle()
        assertEquals(0, resumed)
        assertNull(exhaustedUrl)
        assertNull(state.value.error)
    }

}
