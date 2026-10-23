package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import android.content.Context
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewThumbnail
import com.nuvio.tv.ui.screens.player.seekpreview.SeekPreviewTrack
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TorboxPreviewDependencies {
    fun debridSettings(): DebridSettingsDataStore
}

internal fun torboxPreviewKeys(context: Context): Flow<String> = try {
    EntryPointAccessors.fromApplication(context.applicationContext, TorboxPreviewDependencies::class.java)
        .debridSettings().settings.map { it.torboxApiKey.trim() }.distinctUntilChanged()
} catch (_: IllegalStateException) { flowOf("") }

/** Neither provider is owned by this adapter; their session flows close them. */
internal class TorboxFirstPreviewTrack(
    private val torbox: TorboxSpriteTrack,
    private val fallback: SeekPreviewTrack?
) : SeekPreviewTrack {
    override var offsetMs: Long
        get() = torbox.offsetMs
        set(value) { torbox.offsetMs = value; fallback?.offsetMs = value }
    override val revision get() = fallback?.revision ?: torbox.revision
    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? =
        torbox.thumbnailFor(positionMs) ?: fallback?.thumbnailFor(positionMs)
    override suspend fun sideThumbnailFor(positionMs: Long): SeekPreviewThumbnail? =
        torbox.sideThumbnailFor(positionMs) ?: fallback?.sideThumbnailFor(positionMs)
}
