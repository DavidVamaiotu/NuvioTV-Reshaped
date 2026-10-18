// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.content.Context
import androidx.media3.common.Format
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class SeekThumbnailSkipTest {
    private val player = mockk<PreviewPlayer>()
    private val pause = mockk<() -> Boolean?>()
    private val context = mockk<Context>(relaxed = true)
    private val source = ThumbSource(url = "https://cdn.example/film.mkv", headers = emptyMap(), identity = "tt31450459")

    @Before fun setUp() {
        every { player.videoFormat } returns Format.Builder().setWidth(1280).setHeight(720).build()
        every { player.duration } returns 8_000L
        mockkObject(ThumbNative, MemoryGovernor)
        every { ThumbNative.load() } returns true
        every { MemoryGovernor.initForSession(any()) } just runs
    }

    @After fun tearDown() {
        SeekThumbnails.stopSession(handBackPlayer = false)
        unmockkAll()
    }

    @Test
    fun `a stream the player calls an error card starts no thumbnail work`() = runBlocking {
        var asked = 0
        SeekThumbnails.startWhenEligible(
            context = context,
            source = source,
            playerProvider = { player },
            pauseForPrepare = pause,
            skipReason = { asked++; "error card" }
        )

        assertEquals(1, asked)
        verify(exactly = 0) { MemoryGovernor.tierFor(any(), any()) }
        verify(exactly = 0) { ThumbNative.load() }
        verify(exactly = 0) { MemoryGovernor.initForSession(any()) }
        assertFalse(SeekThumbnails.pausesPlaybackWhileScrubbing())
    }

    @Test
    fun `a reject while the decoder loads still starts no session`() = runBlocking {
        var asked = 0
        SeekThumbnails.startWhenEligible(
            context = context,
            source = source,
            playerProvider = { player },
            pauseForPrepare = pause,
            skipReason = { if (asked++ == 0) null else "error card" }
        )

        assertEquals(2, asked)
        verify(exactly = 1) { MemoryGovernor.initForSession(any()) }
        verify(exactly = 0) { pause.invoke() }
        assertFalse(SeekThumbnails.pausesPlaybackWhileScrubbing())
    }
}
