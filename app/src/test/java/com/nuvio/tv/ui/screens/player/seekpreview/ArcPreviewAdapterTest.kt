@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.player.seekpreview

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalPreviewSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class ArcPreviewAdapterTest {
    @Test fun rebuildingTheSameUrlCannotBorrowOrReleaseTheNewPlayersReserve() {
        val controller = mockk<PlayerRuntimeController>()
        val context = mockk<Context>()
        val firstPlayer = mockk<ExoPlayer>()
        val secondPlayer = mockk<ExoPlayer>()
        val firstControl = control()
        val secondControl = control()
        val settings = PlayerSettings(nuvioPerformanceModeEnabled = false)
        every { controller.currentStreamUrl } returns "https://cdn.example/film.mkv"
        every { controller.hasRenderedFirstFrame } returns true
        every { controller.userPausedManually } returns false
        every { controller.currentBitrateAwareLoadControl } returns null
        every { controller.currentParallelChunkOverheadMb } returns 0
        every { controller._exoPlayer } returns firstPlayer
        every { controller._loadControl } returns firstControl
        controller.seekPreviewLoadControl(firstControl, settings)
        val first = ArcPreviewAdapter(controller, LocalPreviewSource(controller, controller.currentStreamUrl, context))
        assertNotNull(first.player())
        assertNotNull(first.reserve())

        every { controller._exoPlayer } returns secondPlayer
        every { controller._loadControl } returns secondControl
        controller.seekPreviewLoadControl(secondControl, settings)
        assertNull(first.player())
        assertNull(first.reserve())

        val second = ArcPreviewAdapter(controller, LocalPreviewSource(controller, controller.currentStreamUrl, context))
        assertNotNull(second.player())
        assertNotNull(second.reserve())
        first.detach()
        assertNotNull(second.reserve())
    }
    @Test fun aManualPauseWinsOverAutomaticPreparationResume() {
        val controller = mockk<PlayerRuntimeController>()
        val player = mockk<ExoPlayer>(relaxed = true)
        every { controller.currentStreamUrl } returns "https://cdn.example/film.mkv"
        every { controller.hasRenderedFirstFrame } returns true
        every { controller._exoPlayer } returns player
        every { controller._loadControl } returns control()
        every { controller.userPausedManually } returns true
        val adapter = ArcPreviewAdapter(controller,
            LocalPreviewSource(controller, controller.currentStreamUrl, mockk<Context>()))
        val view = adapter.player()!!
        view.play()
        verify(exactly = 0) { player.play() }
        every { controller.userPausedManually } returns false
        view.play()
        verify(exactly = 1) { player.play() }
    }
    private fun control(): DefaultLoadControl = mockk<DefaultLoadControl>().also { control ->
        every { control.allocator } returns mockk<DefaultAllocator>(relaxed = true)
    }
}
