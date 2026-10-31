package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import android.content.Context
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.currentPlaybackPositionMs
import com.nuvio.tv.ui.screens.player.seekpreview.colourmatch.PreviewColourPlayback
import com.nuvio.tv.ui.screens.player.seekpreview.colourmatch.calibratePreviewColour
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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

internal suspend fun torboxPreviewRequest(controller: PlayerRuntimeController, request: TorboxMovieRequest) =
    resolveTorboxMovieRequest(request,
        cachedImdb = {
            val id = request.contentId.orEmpty()
            controller.metaRepository.getCachedMeta(request.contentType ?: "movie", id)?.imdbId
        },
        tmdbToImdb = { controller.tmdbService.tmdbToImdb(it, "movie") })

/** Neither provider is owned by this adapter; their session flows close them. */
internal class TorboxFirstPreviewTrack(
    private val torbox: TorboxSpriteTrack,
    private val fallback: SeekPreviewTrack?
) : SeekPreviewTrack {
    override var offsetMs: Long
        get() = torbox.offsetMs
        set(value) { torbox.offsetMs = value; fallback?.offsetMs = value }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val changes = MutableStateFlow(0)
    override val revision get() = changes
    init {
        scope.launch {
            combine(torbox.revision, fallback?.revision ?: MutableStateFlow(0)) { a, b -> a to b }
                .collect { changes.value++ }
        }
    }
    override fun close() = scope.cancel()
    override fun noteHeldScrub(held: Boolean) { torbox.noteHeldScrub(held); fallback?.noteHeldScrub(held) }
    override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? =
        torbox.thumbnailFor(positionMs) ?: fallback?.thumbnailFor(positionMs)
    override suspend fun sideThumbnailFor(positionMs: Long): SeekPreviewThumbnail? =
        torbox.sideThumbnailFor(positionMs) ?: fallback?.sideThumbnailFor(positionMs)
}

/** Only this adapter knows the Nuvio player. The calibrator never controls or rebuilds playback. */
internal suspend fun calibrateTorboxPreview(controller: PlayerRuntimeController, streamUrl: String?, track: TorboxSpriteTrack) = coroutineScope {
    val playback: suspend () -> PreviewColourPlayback? = {
            withContext(Dispatchers.Main.immediate) {
                val state = controller.uiState.value
                if (controller.currentStreamUrl != streamUrl || !state.isPlaying || state.isBuffering ||
                    state.pendingPreviewSeekPosition != null || state.showSeekOverlay) return@withContext null
                val view = if (controller.currentInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER)
                    controller.mpvView else controller.exoPlayerView?.videoSurfaceView
                val position = controller.currentPlaybackPositionMs() ?: return@withContext null
                view?.takeIf { it.isShown }?.let { PreviewColourPlayback(it, position, controller.currentStreamUrl) }
            }
        }
    suspend fun colours() = calibratePreviewColour(
        playback = playback,
        nextTarget = track::nextColourTarget,
        thumbnail = track::colourReference,
        accept = track::acceptColourAdjustment,
    )
    val colourJob = launch { colours() }
    if (track.prepareFingerprintMatcher() && alignPreviewFingerprints(playback, track::observeFingerprint)) {
        colourJob.cancelAndJoin()
        colours() // A corrected timeline needs a matched colour reference on that timeline.
    }
}
