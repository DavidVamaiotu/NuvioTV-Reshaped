package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class PreviewBackgroundModeTest {
    @Test fun savedPreparationCannotHoldPlaybackAfterAnUpgradeOrBeEnabledAgain() {
        val context = mockk<Context>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("nuvio_seek_preview_engine", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getBoolean(any(), any()) } answers { if (firstArg<String>() == "prepare") true else secondArg<Boolean>() }
        every { prefs.getString("coverage", any()) } returns "FASTER"
        every { prefs.edit() } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        justRun { editor.apply() }

        assertFalse(PreviewPreferences.options(context).value.prepare)
        PreviewPreferences.set(context, PreviewOptions(prepare = true, copies = false, nextEpisode = false))
        val options = PreviewPreferences.options(context).value
        assertFalse(options.prepare)
        assertFalse(options.copies)
        assertFalse(options.nextEpisode)
        assertEquals(ThumbPrepareMode.OFF, SeekThumbnailPreferences.prepareMode(context))
        assertFalse(ThumbSource("https://example.test/film.mkv", emptyMap(), "film").holdBeforePlay)
        verify(atLeast = 1) { editor.remove("prepare") }
        verify(exactly = 0) { prefs.getBoolean("prepare", any()) }
        verify(exactly = 0) { editor.putBoolean("prepare", true) }
    }
}
