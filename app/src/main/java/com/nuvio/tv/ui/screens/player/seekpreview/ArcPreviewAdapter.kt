@file:OptIn(androidx.media3.common.util.UnstableApi::class)
package com.nuvio.tv.ui.screens.player.seekpreview

import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.ParallelRangeDataSource
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.ui.screens.player.seekpreview.engine.*
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalPreviewSource
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only this adapter knows Nuvio's player, source models and networking counters. */
internal class ArcPreviewAdapter(controller: PlayerRuntimeController, source: LocalPreviewSource) {
    private val sourceKey = source.sourceKey
    private val owner = WeakReference(controller)
    private data class PlaybackBinding(val player: WeakReference<ExoPlayer>, val control: WeakReference<DefaultLoadControl>)
    @Volatile private var binding: PlaybackBinding? = null
    fun player(): PreviewPlayer? {
        val c = owner.get() ?: return unavailable("player closed")
        if (c.currentStreamUrl != sourceKey) return unavailable("source changed")
        if (!c.hasRenderedFirstFrame) return unavailable("first video frame")
        val player = c._exoPlayer ?: return unavailable("ExoPlayer instance")
        val control = c._loadControl ?: return unavailable("buffer control")
        val known = binding
        if (known == null) binding = PlaybackBinding(WeakReference(player), WeakReference(control))
        else if (known.player.get() !== player || known.control.get() !== control) return unavailable("player rebuilt")
        return PlayerView(player) {
            owner.get()?.let { !it.userPausedManually && it.currentStreamUrl == sourceKey && it._exoPlayer === player } == true
        }
    }
    private fun unavailable(reason: String): PreviewPlayer? {
        PreviewDiagnostics.note("waiting for player: $reason")
        return null
    }
    fun reserve(): PlaybackBufferReserve? {
        val c = owner.get()?.takeIf { it.currentStreamUrl == sourceKey } ?: return null
        val known = binding ?: return null
        if (known.player.get() !== c._exoPlayer || known.control.get() !== c._loadControl) return null
        return synchronized(controls) { controls[c]?.takeIf { it.original === known.control.get() } }
    }
    fun sampleSignals() {
        PreviewHostSignals.hudClampLatched = ParallelRangeDataSource.hudClampLatched
        PreviewHostSignals.hudClampLastHitAtMs = ParallelRangeDataSource.hudClampLastHitAtMs
    }
    fun copySources(): CopySources = object : CopySources {
        private var links = emptyMap<String, Pair<String, Map<String, String>>>()
        override suspend fun offers(): CopyOffers? {
            val snapshot = withContext(Dispatchers.Main) {
                val c = owner.get()?.takeIf { it.currentStreamUrl == sourceKey } ?: return@withContext null
                val state = c.uiState.value
                val streams = state.sourceAllStreams + state.episodeAllStreams
                if (streams.isEmpty()) return@withContext null
                CopySnapshot(streams, c.currentSeason, c.currentEpisode,
                    listOfNotNull(state.currentStreamName, c.currentFilename, c.currentStreamDescription).joinToString(" "), c.directDebridResolver)
            } ?: return null
            // Classification and cached-link lookup stay off the playback/UI thread.
            val available = withContext(Dispatchers.IO) {
                snapshot.streams.mapNotNull { stream ->
                    copyOffer(stream, sourceKey) ?: snapshot.resolver.cachedPlayableStream(stream, snapshot.season, snapshot.episode)
                        ?.let { copyOffer(it, sourceKey, trustedResolved = true) }
                }.distinctBy { it.first.id }
            }
            links = available.associate { (offer, link) -> offer.id to link }
            return CopyOffers(snapshot.playingText, available.map { it.first })
        }
        override suspend fun open(offer: CopyOffer): CopyLink? = links[offer.id]?.let { (url, headers) -> CopyLink(url, headers, "ready source") }
    }
    fun detach(keepPlaybackObservation: Boolean = false) { if (!keepPlaybackObservation) owner.clear() }

    private data class CopySnapshot(val streams: List<Stream>, val season: Int?, val episode: Int?,
        val playingText: String, val resolver: DirectDebridResolver)

    private class PlayerView(private val player: ExoPlayer, private val allowResume: () -> Boolean) : PreviewPlayer {
        override val videoFormat: Format? get() = player.videoFormat
        override val duration: Long get() = player.duration
        override val currentPosition: Long get() = player.currentPosition
        override val bufferedPosition: Long get() = player.bufferedPosition
        override val playbackState: Int get() = player.playbackState
        override val isLoading: Boolean get() = player.isLoading
        override val isPlaying: Boolean get() = player.isPlaying
        override val playWhenReady: Boolean get() = player.playWhenReady
        override val playerError: PlaybackException? get() = player.playerError
        override val droppedFrames: Int get() = player.videoDecoderCounters?.let { it.ensureUpdated(); it.droppedBufferCount } ?: -1
        override val speed: Float get() = player.playbackParameters.speed
        override fun play() { if (allowResume()) player.play() }
    }
}

private val controls = WeakHashMap<PlayerRuntimeController, PreviewLoadControl>()
/** Installed once per new player; no lease means exactly the existing buffering behavior. */
internal fun PlayerRuntimeController.seekPreviewLoadControl(control: DefaultLoadControl, settings: PlayerSettings): LoadControl {
    PreviewHostSignals.playbackConnections = if (settings.parallelNetworkEnabled && settings.useParallelConnections) settings.parallelConnectionCount else 1
    val bitrate = currentBitrateAwareLoadControl
    val performanceBudget = if (settings.nuvioPerformanceModeEnabled)
        (NuvioExoPlayerPerformanceHelper.targetBufferSizeMb - currentParallelChunkOverheadMb).coerceAtLeast(MemoryBudget.MIN_BUFFER_MB).toLong() * 1024 * 1024 else 0L
    val startMs = if (settings.nuvioPerformanceModeEnabled) NuvioExoPlayerPerformanceHelper.bufferForPlaybackMs
        else if (settings.bufferEngineEnabled) settings.bufferSettings.bufferForPlaybackMs else DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
    val rebufferMs = if (settings.nuvioPerformanceModeEnabled) NuvioExoPlayerPerformanceHelper.bufferForPlaybackAfterRebufferMs
        else if (settings.bufferEngineEnabled) settings.bufferSettings.bufferForPlaybackAfterRebufferMs else DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
    val wrapped = PreviewLoadControl.wrap(control, { bitrate?.effectiveBufferBudgetBytes() ?: performanceBudget }, startMs, rebufferMs) ?: return control
    synchronized(controls) { controls[this] = wrapped }
    return wrapped
}

/** Already playable file links only; opening a preview never changes the selected playback source. */
internal fun copyOffer(stream: Stream, playingUrl: String?, trustedResolved: Boolean = false): Pair<CopyOffer, Pair<String, Map<String, String>>>? {
    val url = stream.getStreamUrl()?.takeIf { it != playingUrl && !stream.isTorrent() && previewSingleFileUrl(it) } ?: return null
    val text = listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints?.filename).joinToString(" ")
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val filename = stream.behaviorHints?.filename.orEmpty().lowercase()
    val container = listOf("mkv", "mp4", "m4v").firstOrNull { path.endsWith(".$it") || trustedResolved && filename.endsWith(".$it") } ?: return null
    if (!trustedResolved && CopyChoice.linkRejection(url, text) != null) return null
    val height = when { Copy1080.containsMatchIn(text) -> 1080; Copy720.containsMatchIn(text) -> 720; else -> 0 }
    val codec = when { CopyAvc.containsMatchIn(text) -> CopyCodec.H264
        CopyHevc.containsMatchIn(text) -> CopyCodec.HEVC; else -> CopyCodec.UNKNOWN }
    val offer = CopyOffer(PreviewStore.digest(url.toByteArray()), "${height}p $codec", CopyKind.LINK, height, codec,
        CopyHdr.containsMatchIn(text),
        CopyWeb.containsMatchIn(text), null, container, null, text)
    return offer to (url to stream.behaviorHints?.proxyHeaders?.request.orEmpty())
}

/** Reuses the next-episode search Nuvio already performs; never starts another addon search. */
internal fun PlayerRuntimeController.seekPreviewNextEpisode(streams: List<Stream>, season: Int?, episode: Int?) {
    if (!com.nuvio.tv.ui.screens.player.seekpreview.local.LocalSeekPreviewSettings.enabled(context).value ||
        !PreviewPreferences.options(context).value.nextEpisode) return
    val episodeContentId = contentId ?: return
    val candidate = streams.firstNotNullOfOrNull { stream -> copyOffer(stream, currentStreamUrl) } ?: return
    val (_, link) = candidate
    val source = LocalPreviewSource(this, currentStreamUrl ?: return, context.applicationContext)
    val adapter = ArcPreviewAdapter(this, source)
    val identity = listOf(episodeContentId, season, episode).joinToString("|")
    NextEpisodeThumbs.start(context, ThumbSource(link.first, link.second, identity,
        holdBeforePlay = false, identityExact = true), allow4K = true, playerProvider = adapter::player)
}

private val Copy1080 = Regex("(?i)1080[pil]?")
private val Copy720 = Regex("(?i)720[pil]?")
private val CopyAvc = Regex("(?i)(h[ ._-]?264|x264|avc)")
private val CopyHevc = Regex("(?i)(h[ ._-]?265|x265|hevc)")
private val CopyHdr = Regex("(?i)(hdr|dolby[ ._-]?vision|\\bDV\\b)")
private val CopyWeb = Regex("(?i)web")
