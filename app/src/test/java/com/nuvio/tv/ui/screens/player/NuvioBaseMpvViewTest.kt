package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.view.SurfaceHolder
import io.mockk.mockk
import `is`.xyz.mpv.MPV
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NuvioBaseMpvViewTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun existingConfigFilesAreExplicitlyLoadedInOrder() {
        val config = temporaryFolder.newFolder("config")
        val mpvConfig = config.resolve("mpv.conf").apply { writeText("volume=75\n") }
        val inputConfig = config.resolve("input.conf").apply { writeText("SPACE cycle pause\n") }
        val commands = mutableListOf<Pair<String, String>>()

        restoreLegacyMpvConfiguration(config.path) { command, path -> commands += command to path }

        assertEquals(listOf("load-config-file" to mpvConfig.path, "load-input-conf" to inputConfig.path), commands)
    }

    @Test
    fun missingConfigFilesDoNotIssueLoadCommands() {
        val config = temporaryFolder.newFolder("empty-config")
        val commands = mutableListOf<Pair<String, String>>()

        restoreLegacyMpvConfiguration(config.path) { command, path -> commands += command to path }

        assertEquals(emptyList<Pair<String, String>>(), commands)
    }

    @Test
    fun destroyingAnUnusedViewDoesNotCreateANativePlayer() {
        val view = TestView(mockk(relaxed = true))

        view.destroy()

        assertEquals(0, view.nativeCreations)
    }

    private class TestView(
        private val surfaceHolder: SurfaceHolder,
    ) : NuvioBaseMpvView(mockk<Context>(relaxed = true), null) {
        var nativeCreations = 0
            private set

        override fun createMpv(): MPV {
            nativeCreations++
            error("An unused view must not initialize the native player")
        }
        override fun getHolder(): SurfaceHolder = surfaceHolder
        override fun initOptions() = Unit
        override fun postInitOptions() = Unit
        override fun observeProperties() = Unit
    }
}
