package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SourceSwitchResumeTest {
    private lateinit var controller: PlayerRuntimeController
    private lateinit var state: MutableStateFlow<PlayerUiState>
    private var currentUrl = "https://source-switch.example/old.mp4"
    private var position: Long? = 123_450L
    private var seekAtInitialization: Long? = null
    private val files = arrayOf(
        "PlayerRuntimeControllerStreamsKt", "PlayerRuntimeControllerMpvKt",
        "PlayerRuntimeControllerPlaybackEventsKt", "PlayerRuntimeControllerInitializationKt",
        "PlayerRuntimeControllerLifecycleKt", "PlayerRuntimeControllerTorrentKt",
        "PlayerRuntimeControllerErrorRecoveryKt", "PlayerRuntimeControllerChaptersKt",
        "PlayerRuntimeControllerObserversKt", "PlayerRuntimeControllerMetadataKt",
        "PlayerStreamRouteStateKt"
    ).map { "com.nuvio.tv.ui.screens.player.$it" }.toTypedArray()

    @Before
    fun setUp() {
        mockkStatic(*files)
        mockkObject(PlayerMediaSourceFactory.Companion)
        every { PlayerMediaSourceFactory.inferMimeType(any(), any(), any()) } returns null
        controller = mockk(relaxed = true)
        state = MutableStateFlow(PlayerUiState(pendingSeekPosition = 20_000L))
        every { controller._uiState } returns state
        every { controller._exoPlayer } returns null
        every { controller.sourceStreamsScope } returns null
        every { controller.nextEpisodeAutoPlayJob } returns null
        every { controller.contentId } returns null
        every { controller.streamReuseLastLinkEnabled } returns false
        every { controller.currentStreamUrl } answers { currentUrl }
        every { controller.currentStreamUrl = any() } answers { currentUrl = firstArg(); Unit }
        every { controller.currentHeaders } returns emptyMap()
        every { controller.currentPlaybackPositionMs() } answers { position }
        every { controller.stopTorrentStream() } just Runs
        every { controller.flushPlaybackSnapshotForSwitchOrExit() } just Runs
        every { controller.applyStreamAudioDelay(any(), any()) } just Runs
        every { controller.releasePlayer(any(), any(), any()) } just Runs
        every { controller.resetLoadingOverlayForNewStream() } just Runs
        every { controller.resetErrorRetryState() } just Runs
        every { controller.clearChapterSkipState() } just Runs
        every { controller.recomputeMergedSkipIntervals() } just Runs
        every { controller.persistStreamRouteState(any(), any()) } just Runs
        every { controller.showStreamSourceIndicator(any()) } just Runs
        every { controller.resetPostPlayOverlayState(any()) } just Runs
        every { controller.recordDistinctPluginPlay(any(), any()) } just Runs
        every { controller.loadSavedProgressFor(any(), any()) } just Runs
        every { controller.initializePlayer(any(), any(), any(), any(), any()) } answers {
            seekAtInitialization = state.value.pendingSeekPosition
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(*files)
        unmockkObject(PlayerMediaSourceFactory.Companion)
    }

    @Test
    fun alternateVodSourceKeepsCurrentPositionBeforeInitialization() {
        controller.switchToSourceStream(stream("https://source-switch.example/new.mp4"))
        assertEquals(123_450L, state.value.pendingSeekPosition)
        assertEquals(123_450L, seekAtInitialization)
        verify(exactly = 0) { controller.loadSavedProgressFor(any(), any()) }
        verify { controller.pendingResumeProgress = null }
    }

    @Test
    fun startupWithoutPlaybackStillLoadsSavedProgress() {
        position = 0L
        controller.switchToSourceStream(stream("https://source-switch.example/startup.mp4"))
        assertEquals(20_000L, seekAtInitialization)
        verify(exactly = 1) { controller.loadSavedProgressFor(any(), any()) }
    }

    @Test
    fun liveChannelSwitchClearsThePreviousTimeline() {
        currentUrl = "https://source-switch.example/live-old.m3u8"
        val target = "https://source-switch.example/live-new.m3u8"
        LiveTvPlaybackRegistry.register(currentUrl)
        LiveTvPlaybackRegistry.register(target)
        controller.switchToSourceStream(stream(target))
        assertNull(state.value.pendingSeekPosition)
        assertNull(seekAtInitialization)
        verify(exactly = 0) { controller.loadSavedProgressFor(any(), any()) }
    }

    @Test
    fun catchupProgrammeSwitchStartsAtItsOwnBeginning() {
        currentUrl = "https://source-switch.example/replay-old.ts"
        val target = "https://source-switch.example/replay-new.ts"
        LiveTvPlaybackRegistry.register(currentUrl, catchup = true)
        LiveTvPlaybackRegistry.register(target, catchup = true)
        controller.switchToSourceStream(stream(target))
        assertNull(seekAtInitialization)
        verify(exactly = 0) { controller.loadSavedProgressFor(any(), any()) }
    }

    @Test
    fun enteringLivePlaybackDoesNotCarryTheVodPosition() {
        val target = "https://source-switch.example/enter-live.m3u8"
        LiveTvPlaybackRegistry.register(target)
        controller.switchToSourceStream(stream(target))
        assertNull(seekAtInitialization)
        verify(exactly = 0) { controller.loadSavedProgressFor(any(), any()) }
    }

    private fun stream(url: String) = Stream(
        name = null, title = null, description = null, url = url, ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = "Fixture", addonLogo = null
    )
}
