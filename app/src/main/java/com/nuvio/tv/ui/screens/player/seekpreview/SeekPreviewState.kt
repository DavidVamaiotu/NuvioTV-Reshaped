package com.nuvio.tv.ui.screens.player.seekpreview

import com.nuvio.tv.ui.screens.player.seekpreview.torbox.TorboxFirstPreviewTrack
import com.nuvio.tv.ui.screens.player.seekpreview.torbox.TorboxMovieRequest
import com.nuvio.tv.ui.screens.player.seekpreview.torbox.TorboxSpriteTrack
import com.nuvio.tv.ui.screens.player.seekpreview.torbox.torboxPreviewKeys
import com.nuvio.tv.ui.screens.player.seekpreview.torbox.torboxPreviewRequest
import com.nuvio.tv.ui.screens.player.seekpreview.torbox.calibrateTorboxPreview
import kotlinx.coroutines.flow.first
import com.nuvio.tv.ui.screens.player.PlayerEvent
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.currentPlaybackDurationMs
import com.nuvio.tv.ui.screens.player.currentPlaybackPositionMs
import com.nuvio.tv.ui.screens.player.hideControls
import com.nuvio.tv.ui.screens.player.setPlaybackPaused
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalPreviewSource
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalPreviewSources
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalPreviewTrack
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalSeekPreviewSettings
import com.nuvio.tv.ui.screens.player.seekpreview.local.localSeekPreviewCacheKey
import com.nuvio.tv.ui.screens.player.seekpreview.engine.SessionPreviewTrack
import com.nuvio.tv.ui.screens.player.seekpreview.engine.PreviewPreferences
import com.nuvio.tv.ui.screens.player.seekpreview.engine.PreviewOptions
import androidx.media3.exoplayer.SeekParameters
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.math.abs

/**
 * Seek-preview (Seekr) state for one player session: the loaded track, the cue behind the
 * frame on screen, and the manual Preview Sync correction.
 *
 * Holds what the seek-preview fork (AKhalil609/NuvioTV) keeps in PlayerUiState, PlayerViewModel
 * and the runtime controller's events, so upstream player state stays untouched. The key is the
 * user's own (SeekrKeyPreferences), else BuildConfig.SEEKR_API_KEY; without one, no Seekr track
 * loads. On-device previews (see the `local` package) work with or without it.
 */
class SeekPreviewState internal constructor(
    scope: CoroutineScope,
    private val controller: PlayerRuntimeController,
) {
    @Volatile private var activeGenerated: ArcPreviewTrack? = null
    @Volatile private var ownedSeekr: BoundedSeekrTrack? = null
    init {
        // A newly opened player (including MPV) supersedes any detached after-playback pass.
        com.nuvio.tv.ui.screens.player.seekpreview.engine.SeekThumbnails.stopSession(handBackPlayer = false)
        com.nuvio.tv.ui.screens.player.seekpreview.engine.NextEpisodeThumbs.cancel()
        com.nuvio.tv.ui.screens.player.seekpreview.engine.NextEpisodeThumbs.resume()
        scope.coroutineContext[Job]?.invokeOnCompletion { ownedSeekr?.close() }
    }
    private val _previewCue = MutableStateFlow<SeekPreviewCue?>(null)

    /**
     * Cue window (playback timebase) behind the preview frame currently on screen, or `null`
     * while no preview has resolved. Drives grid-locked scrubbing and the scrubber's cue ticks.
     */
    val previewCue: StateFlow<SeekPreviewCue?> = _previewCue.asStateFlow()

    private val _offsetMs = MutableStateFlow(0)

    /**
     * Manual sync correction applied to the position before the thumbnail lookup.
     * Session-scoped: it describes the gap between the playing release and the one the
     * sprites were generated from, so it is dropped whenever a new track loads.
     */
    val offsetMs: StateFlow<Int> = _offsetMs.asStateFlow()

    private val _showSyncOverlay = MutableStateFlow(false)
    val showSyncOverlay: StateFlow<Boolean> = _showSyncOverlay.asStateFlow()
    val isSyncOverlayOpen: Boolean get() = _showSyncOverlay.value

    /**
     * The Seekr track alone. Preview Sync only exists for it: on-device frames always line up.
     * The preview itself reads [previewTrack].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val track: StateFlow<BoundedSeekrTrack?> =
        combine(controller.playbackTimeline, controller.uiState, LocalSeekPreviewSettings.enabled(controller.context)) {
            timeline, state, enabled -> Triple(timeline.duration, state.currentStreamUrl, enabled)
        }
            .distinctUntilChanged()
            .mapLatest { (durationMs, _, enabled) ->
                ownedSeekr?.close()
                ownedSeekr = null
                // A new track describes a different release, so any manual sync dialled in
                // for the previous one is meaningless. Drop it.
                setOffset(0)
                _previewCue.value = null
                _showSyncOverlay.value = false
                val apiKey = SeekrKeyPreferences.effectiveKey(controller.context) // Seekr hook: user key, else built-in
                if (!enabled || apiKey.isBlank() || durationMs <= 0L) return@mapLatest null
                val content = seekrContentFor(
                    contentId = controller.contentId,
                    contentType = controller.contentType,
                    season = controller.currentSeason,
                    episode = controller.currentEpisode
                ) ?: return@mapLatest null
                // Seekr hook: bounded sheet memory (see BoundedSeekrTrack), same thumbnails.
                BoundedSeekr.loadTrack(apiKey, content, durationMs)
                    ?.also { track ->
                        ownedSeekr = track
                        track.warm(runCatching { controller.currentPlaybackPositionMs() }.getOrNull() ?: 0L)
                    }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // The sheets around the current position are decoded as soon as the controls open, so
        // the first thumbnail of a scrub is as ready as when every sheet was kept decoded.
        scope.launch {
            controller.uiState
                .map { it.showControls || it.showSeekOverlay }
                .distinctUntilChanged()
                .collect { open ->
                    if (open) track.value?.warm(runCatching { controller.currentPlaybackPositionMs() }.getOrNull() ?: 0L)
                }
        }
    }

    /** Shared TorBox previews; account setup and visual verification stay outside the core player. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val torboxTrack: StateFlow<TorboxSpriteTrack?> =
        combine(
            controller.playbackTimeline.map { if (it.isLive) 0L else it.duration }
                .distinctUntilChanged { a, b -> (a > 0L) == (b > 0L) && abs(a - b) <= 1_000L },
            controller.uiState.map { Triple(it.currentStreamUrl, it.currentStreamInfoHash, it.currentStreamFileIdx) }
                .distinctUntilChanged(),
            combine(torboxPreviewKeys(controller.context), LocalSeekPreviewSettings.enabled(controller.context)) {
                key, enabled -> if (enabled) key else ""
            }
        ) { duration, stream, key ->
            Triple(key, stream.first, TorboxMovieRequest(controller.contentId, controller.contentType,
                duration, stream.second, stream.third))
        }.distinctUntilChanged()
            .transformLatest<Triple<String, String?, TorboxMovieRequest>, TorboxSpriteTrack?> { (key, streamUrl, request) ->
                setOffset(0)
                _previewCue.value = null
                val resolved = if (key.isBlank()) request else torboxPreviewRequest(controller, request)
                val owned = TorboxSpriteTrack.open(key, resolved, controller.context.cacheDir.resolve("torbox-sprites-v1"))
                var calibration: Job? = null
                try {
                    if (owned != null) calibration = scope.launch { calibrateTorboxPreview(controller, streamUrl, owned) }
                    owned?.prefetch(runCatching { controller.currentPlaybackPositionMs() }.getOrNull() ?: 0L)
                    emit(owned) // Fingerprint verification never blocks or hides initial previews.
                    if (owned != null) {
                        owned.available.first { !it }
                        calibration?.cancel()
                        owned.close()
                        emit(null) // Resume existing providers if the TorBox track becomes unavailable.
                    }
                    awaitCancellation()
                } finally { calibration?.cancel(); owned?.close() }
            }.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * On-device thumbnails for the stream this player shows with ExoPlayer, while "Generate
     * previews on device" is on. Opened when the stream registers and its duration is known,
     * closed (and saved to the disk cache) when either changes or the player goes away. The
     * duration of some streams is refined while they play: a change of a second or less keeps
     * the open track rather than tearing it down mid-playback.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val localTrack: StateFlow<SeekPreviewTrack?> =
        combine(
            // Live windows have no fixed timeline to hang thumbnails on.
            controller.playbackTimeline.map { if (it.isLive) 0L else it.duration }
                .distinctUntilChanged { old, new -> (old > 0L) == (new > 0L) && abs(old - new) <= 1_000L },
            LocalSeekPreviewSettings.enabled(controller.context),
            LocalPreviewSources.sourceFor(controller),
            PreviewPreferences.options(controller.context),
            torboxTrack
        ) { durationMs, enabled, source, options, torbox ->
            PreviewLocalRequest(durationMs, enabled && torbox == null, source, options)
        }
            .distinctUntilChanged()
            .transformLatest<PreviewLocalRequest, SeekPreviewTrack?> { (durationMs, enabled, source) ->
                com.nuvio.tv.ui.screens.player.seekpreview.engine.PreviewDiagnostics.note(
                    "local preview request: enabled=$enabled source=${source != null} duration=$durationMs")
                if (!enabled || source == null || durationMs <= 0L) {
                    emit(null)
                    return@transformLatest
                }
                val cacheKey = localSeekPreviewCacheKey(
                    contentId = controller.contentId,
                    season = controller.currentSeason,
                    episode = controller.currentEpisode,
                    durationMs = durationMs
                ) + "|" + com.nuvio.tv.ui.screens.player.seekpreview.engine.PreviewStore.digest(source.sourceKey.toByteArray())
                val adapter = ArcPreviewAdapter(controller, source)
                val serverSource = previewServerSource(source.sourceKey)
                val generated = if (previewSingleFileUrl(source.sourceKey)) ArcPreviewTrack(controller.context,
                    source, listOf(controller.contentId ?: "source:" + com.nuvio.tv.ui.screens.player.seekpreview.engine.PreviewStore.digest(source.sourceKey.toByteArray()), controller.currentSeason, controller.currentEpisode).joinToString("|"),
                    identityExact = !controller.contentId.isNullOrBlank(), durationMs = durationMs, adapter = adapter,
                    startImmediately = serverSource == null) else null
                val captured = if (generated == null) LocalPreviewSources.open(source, cacheKey, durationMs) else null
                val local = generated ?: captured ?: run { emit(null); return@transformLatest }
                val server = if (serverSource != null) controller.serverPreview(source, durationMs,
                    onUnavailable = { generated?.start() }) else null
                if (serverSource != null && server == null) generated?.start()
                val combined = server?.let { SessionPreviewTrack(local, it) }
                activeGenerated = generated
                try {
                    emit(combined ?: local)
                    controller.uiState.combine(controller.playbackTimeline) { state, timeline ->
                        PreviewPlaybackSample(state.isPlaying || state.isBuffering, state.isBuffering,
                            timeline.currentPosition, timeline.bufferedPosition, controller.previewDroppedFrames())
                    }.distinctUntilChanged().collect { (active, stalled, position, buffered, dropped) ->
                        captured?.playbackActive = active
                        adapter.sampleSignals()
                        server?.updatePlayback(active, stalled, position, buffered, dropped)
                    }
                } finally {
                    if (activeGenerated === generated) activeGenerated = null
                    combined?.close()
                    generated?.close(afterPlayback = scope.coroutineContext[Job]?.isActive == false,
                        resumeIfHeld = !source.released && scope.coroutineContext[Job]?.isActive == true)
                    captured?.let { LocalPreviewSources.close(source, it) }
                    if (generated == null) adapter.detach()
                }

            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * What the preview shows: on-device frames where playback has been, Seekr elsewhere (see
     * [HybridSeekPreviewTrack]); either alone when the other is unavailable.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val previewTrack: StateFlow<SeekPreviewTrack?> =
        combine(localTrack, track, torboxTrack) { local, seekr, torbox -> Triple(local, seekr, torbox) }
        .transformLatest<Triple<SeekPreviewTrack?, BoundedSeekrTrack?, TorboxSpriteTrack?>, SeekPreviewTrack?> { (local, seekr, torbox) ->
            val hybrid = if (local != null && seekr != null) HybridSeekPreviewTrack(local, SeekrPreviewTrack(seekr)) else null
            val fallback = hybrid ?: local ?: seekr?.let(::SeekrPreviewTrack)
            val decorated = torbox?.let { TorboxFirstPreviewTrack(it, fallback) }
            try {
                emit(decorated ?: fallback)
                awaitCancellation()
            } finally {
                decorated?.close()
                hybrid?.close()
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // Scrubbing pauses the video and playback resumes once the scrub ends (committed or
        // abandoned), unless the viewer paused or played meanwhile. A paused player leaves the
        // CPU to the preview frames and stops the picture moving under a frame that is elsewhere.
        scope.launch {
            var pausedForScrub = false
            controller.uiState
                .map { it.pendingPreviewSeekPosition != null }
                .distinctUntilChanged()
                .collect { scrubbing ->
                    val state = controller.uiState.value
                    if (scrubbing) {
                        if (state.isPlaying && state.error == null) {
                            pausedForScrub = true
                            controller.setPlaybackPaused(true)
                        }
                    } else if (pausedForScrub) {
                        pausedForScrub = false
                        val latest = controller.uiState.value
                        if (!latest.isPlaying && !controller.userPausedManually &&
                            latest.error == null && !latest.playbackEnded
                        ) {
                            controller.setPlaybackPaused(false)
                        }
                    }
                }
        }
        // Which way the viewer scrubs, so on-device frames ahead of the scrub decode first.
        // Snapping onto a cue start moves the position a little; only whole steps count.
        scope.launch {
            var previous: Long? = null
            controller.uiState
                .map { it.pendingPreviewSeekPosition }
                .distinctUntilChanged()
                .collect { position ->
                    val track = localTrack.value
                    val last = previous
                    previous = position
                    if (position == null || last == null) {
                        if (position == null) track?.noteScrubDirection(0)
                        return@collect
                    }
                    if (abs(position - last) >= SCRUB_STEP_MIN_MS) {
                        track?.noteScrubDirection(if (position > last) 1 else -1)
                    }
                }
        }
    }

    /**
     * The duration gap between the playing release and the preview source, offered as a
     * starting point for Preview Sync. Deliberately not applied automatically: the backend
     * anchors cues at the start, and most gaps are a different credits length around an
     * identical body, for which shifting by the gap would make every thumbnail wrong.
     */
    val suggestedOffsetMs: StateFlow<Long> =
        combine(
            track,
            controller.playbackTimeline.map { it.duration }.distinctUntilChanged()
        ) { track, durationMs ->
            val sourceDurationMs = track?.sourceDurationMs ?: 0L
            if (sourceDurationMs > 0L && durationMs > 0L) sourceDurationMs - durationMs else 0L
        }.stateIn(scope, SharingStarted.Eagerly, 0L)

    /** Spacing between preview cues, or 0 when no preview has resolved. */
    val cueIntervalMs: StateFlow<Long> =
        previewCue
            .map { it?.durationMs ?: 0L }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, 0L)

    /**
     * Grid-locked scrubbing: rewrites a D-pad preview step so it lands on a position an actual
     * preview frame exists for, so the thumbnail and the eventual seek can never disagree.
     * Every other event, and every step without a resolved cue, passes through unchanged.
     */
    internal fun intercept(event: PlayerEvent): PlayerEvent {
        if (event !is PlayerEvent.OnPreviewSeekBy || previewTrack.value == null) return event
        if (controller.playbackTimeline.value.isLive) return event
        val maxDuration = controller.currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
        val basePosition = controller.pendingPreviewSeekPosition
            ?: controller.currentPlaybackPositionMs()?.coerceAtLeast(0L)
            ?: 0L
        val target = activeGenerated?.gridStep(basePosition, event.deltaMs) ?: SeekPreviewCueStepper.targetMs(
            cue = _previewCue.value,
            fromMs = basePosition,
            deltaMs = event.deltaMs,
            durationMs = maxDuration
        )
        return PlayerEvent.OnPreviewSeekBy(target - basePosition)
    }

    /**
     * Reported by the thumbnail host once it knows which cue the frame on screen came from.
     * Snapping the pending scrub position onto its start keeps the number under the thumbnail
     * honest even when the cue grid is not perfectly uniform. The start moves onto the file's
     * keyframe when one is within [SEEK_PREVIEW_KEYFRAME_SNAP_MS], so the committed seek is exact
     * and needs no decoding forward (see [commitSeekParameters]).
     */
    fun onPreviewCueResolved(cue: SeekPreviewCue?) {
        _previewCue.value = cue
        if (controller.playbackTimeline.value.isLive) return
        val maxDuration = controller.currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
        val activeTrack = previewTrack.value
        val aligned = SeekPreviewCueStepper.alignedTargetMs(
            cue = cue,
            pendingMs = controller.pendingPreviewSeekPosition,
            durationMs = maxDuration,
            snap = { startMs -> activeTrack?.keyframeNear(startMs, SEEK_PREVIEW_KEYFRAME_SNAP_MS) ?: startMs }
        ) ?: return
        controller.pendingPreviewSeekPosition = aligned
        controller.updatePlaybackTimeline(currentPosition = aligned)
    }

    internal fun stopGenerationForError() { activeGenerated?.close() }

    fun showSyncOverlay() {
        controller.hideControls()
        _showSyncOverlay.value = true
    }

    fun hideSyncOverlay() {
        _showSyncOverlay.value = false
    }

    /**
     * Sets the manual correction. Only the thumbnail composables consume it, pushing it onto the
     * track before every lookup, so this is safe at D-pad repeat rate.
     */
    fun setOffset(targetMs: Int) {
        val clamped = targetMs.coerceIn(SEEK_PREVIEW_OFFSET_MIN_MS, SEEK_PREVIEW_OFFSET_MAX_MS)
        if (_offsetMs.value == clamped) return
        // The cached cue was converted to the playback timebase with the old offset.
        _offsetMs.value = clamped
        _previewCue.value = null
    }

    fun adjustOffset(deltaMs: Int) = setOffset(_offsetMs.value + deltaMs)

    init {
        synchronized(states) { states[controller] = WeakReference(this) }
    }

    /**
     * How a committed D-pad scrub should land while previews are shown. The player's own choice,
     * the closest keyframe at any distance, can land seconds away from the frame just shown (half
     * the gap between keyframes). A preview target is already a keyframe whenever the file's
     * index is known (see [onPreviewCueResolved]), and landing on it needs no decoding forward;
     * otherwise the decoder advances to the preview's timestamp rather than landing on a different keyframe.
     */
    internal fun commitSeekParameters(): SeekParameters? =
        if (previewTrack.value != null) PreviewCommitSeek else null

    internal companion object {
        private val PreviewCommitSeek = SeekParameters.EXACT
        // The value is weak too: the state references its controller, so a strong value would keep
        // the key, and with it every finished player session, alive for the whole app session.
        private val states = WeakHashMap<PlayerRuntimeController, WeakReference<SeekPreviewState>>()

        fun forController(controller: PlayerRuntimeController): SeekPreviewState? =
            synchronized(states) { states[controller]?.get() }
    }
}

/** A change of the scrub position at least this large is a step, not a snap onto a cue start. */
private const val SCRUB_STEP_MIN_MS = 6_000L

/** Position/counters participate so a full read-ahead buffer does not freeze health observations. */
private data class PreviewPlaybackSample(
    val active: Boolean, val stalled: Boolean, val positionMs: Long, val bufferedMs: Long, val droppedFrames: Int,
)

private data class PreviewLocalRequest(val durationMs: Long, val enabled: Boolean, val source: LocalPreviewSource?, val options: PreviewOptions)
