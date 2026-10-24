package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.player.recovery.AutoSourceRecoveryHandoff
import com.nuvio.tv.core.player.recovery.AutoSourceRecoveryPlan
import com.nuvio.tv.core.player.recovery.AutoSourceRecoverySettings
import com.nuvio.tv.core.player.recovery.StartupSourceRecovery
import com.nuvio.tv.core.player.recovery.recoverySourceKey
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/** One job per automatic startup. Existing player recovery runs inside the same attempt deadline. */
internal class PlayerAutoSourceRecovery(
    private val player: PlayerRuntimeController,
    private var plan: AutoSourceRecoveryPlan
) {
    private val policy = StartupSourceRecovery(SystemClock.elapsedRealtime(), plan.startupState)
    private val enabled = AutoSourceRecoverySettings.enabled(player.context)
    private val attempted = plan.attempted.toMutableSet()
    private var job: Job? = null
    private var active = true
    private var definitiveFailure = false
    private var failureReason: String? = null
    private val failureSignals = Channel<Unit>(Channel.CONFLATED)
    private var switchNotice: Toast? = null
    private var refreshedCandidates = false
    private enum class NextResult { Started, Failed, Empty }

    fun remainingMs(): Long = policy.remainingMs(SystemClock.elapsedRealtime())

    fun cancel() {
        active = false
        job?.cancel()
        switchNotice?.cancel()
        if (player.autoSourceRecovery === this) player.autoSourceRecovery = null
    }

    /** Claim the failure before it reaches error UI, then wake the waiting attempt immediately. */
    fun failDefinitively(reason: String): Boolean {
        val state = player._uiState.value
        if (!active || !enabled.value || player.isInBackground || player.userPausedManually ||
            player.hasRenderedFirstFrame || state.showSourcesPanel || state.showEpisodesPanel
        ) return false
        definitiveFailure = true
        failureReason = reason
        player.showRecoveryOverlay()
        failureSignals.trySend(Unit)
        return true
    }

    fun start() {
        plan.initialSource?.let { attempted += recoverySourceKey(it) }
        // Also exclude an exact resolved/cached URL whose original file identity was unavailable.
        attempted += "url:${player.currentStreamUrl}"
        job = player.scope.launch {
            try {
                var previousPosition: Long? = null
                while (active && enabled.value) {
                    val now = SystemClock.elapsedRealtime()
                    val position = player.currentPlaybackPositionMs()
                    val readyToConfirm = player.isPlaybackCurrentlyPlaying() &&
                        (player.hasRenderedFirstFrame || !player.currentStreamHasVideoTrack)
                    val previous = previousPosition
                    val progressing = readyToConfirm && previous != null && position != null && position - previous >= 250L
                    // Seed only after playback is active: a restored seek position is not progress.
                    previousPosition = if (readyToConfirm) position else null
                    val state = player._uiState.value
                    // A deliberate player pause (including optional preview preparation) is not a failed source.
                    val deliberatelyPaused = player.hasRenderedFirstFrame && player._exoPlayer?.playWhenReady == false
                    val interrupted = player.isInBackground || player.userPausedManually || deliberatelyPaused ||
                        state.showSourcesPanel || state.showEpisodesPanel
                    if (interrupted) break
                    when (policy.evaluate(now, progressing, interrupted = false, failed = definitiveFailure || state.error != null)) {
                        StartupSourceRecovery.Decision.Complete -> break
                        StartupSourceRecovery.Decision.Wait -> {
                            // Keep sampling progress/deadlines, but never make a known failure wait for a tick.
                            withTimeoutOrNull(minOf(500L, remainingMs()).coerceAtLeast(1L)) {
                                failureSignals.receive()
                            }
                        }
                        StartupSourceRecovery.Decision.Stop -> { showPicker(); break }
                        StartupSourceRecovery.Decision.NextSource -> {
                            val reason = if (definitiveFailure || state.error != null) "startup_error" else "startup_timeout"
                            Log.i(PlayerRuntimeController.TAG, "AUTO_SOURCE_RECOVERY: $reason")
                            val noticeReason = failureReason ?: state.error
                                ?: player.context.getString(R.string.player_auto_source_recovery_timeout)
                            showSwitchNotice(noticeReason)
                            stopAttempt()
                            if (!policy.beginNextAttempt(now)) { showPicker(); break }
                            definitiveFailure = false
                            failureReason = null
                            val result = withTimeoutOrNull(remainingMs()) { tryNext() } ?: NextResult.Failed
                            coroutineContext.ensureActive()
                            if (!active || player.autoSourceRecovery !== this@PlayerAutoSourceRecovery) break
                            if (result == NextResult.Empty) { showPicker(); break }
                            if (result == NextResult.Failed) {
                                definitiveFailure = true
                                failureReason = player.context.getString(R.string.player_auto_source_recovery_resolve_failed)
                            }
                            previousPosition = null
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(PlayerRuntimeController.TAG, "AUTO_SOURCE_RECOVERY: stopped after ${e.javaClass.simpleName}")
                if (active && !player.isInBackground && !player.userPausedManually) showPicker()
            } finally {
                active = false
                if (player.autoSourceRecovery === this@PlayerAutoSourceRecovery) player.autoSourceRecovery = null
            }
        }
    }

    private fun showSwitchNotice(reason: String) {
        // A short explanation stays readable without showing a dialog or taking remote focus.
        val summary = reason.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(140)
            ?: player.context.getString(R.string.error_unknown)
        switchNotice?.cancel()
        switchNotice = Toast.makeText(
            player.context,
            player.context.getString(R.string.player_auto_source_recovery_switching, summary),
            Toast.LENGTH_LONG
        ).also { it.show() }
    }

    private suspend fun tryNext(): NextResult {
        var candidate = plan.next(attempted)
        if (candidate == null && !refreshedCandidates) {
            refreshedCandidates = true
            // Cached links and instant selection may precede other addon results. Refresh only when needed.
            var streams = emptyList<Stream>()
            player.streamRepository.setLocalPluginSearchPaused(false)
            withTimeoutOrNull(minOf(5_000L, remainingMs())) {
                player.streamRepository.getStreamsFromAllAddons(
                    type = player.contentType ?: "movie",
                    videoId = player.currentVideoId ?: player.contentId ?: return@withTimeoutOrNull,
                    season = player.currentSeason, episode = player.currentEpisode
                ).collect { result ->
                    if (result is NetworkResult.Success) {
                        streams = StreamAutoPlaySelector.orderAddonStreams(result.data, plan.installedAddonNames.toList())
                            .flatMap { it.streams }
                    }
                }
            }
            if (streams.isNotEmpty()) plan = plan.copy(streams = streams)
            candidate = plan.next(attempted)
        }
        val next = candidate ?: return NextResult.Empty
        attempted += recoverySourceKey(next)
        // One chosen candidate consumes one attempt, even if URL resolution fails.
        val resolved = if (next.youTubeIdToResolve() != null) player.resolveYouTubeStream(next)
            else player.resolveDirectDebridStreamIfNeeded(next, player.currentSeason, player.currentEpisode)
        coroutineContext.ensureActive()
        if (!active || player.autoSourceRecovery !== this || resolved == null) return NextResult.Failed
        if (resolved.getStreamUrl() == null && !player.torrentSettings.settings.first().p2pEnabled) return NextResult.Failed
        attempted += recoverySourceKey(resolved)
        resolved.getStreamUrl()?.let { attempted += "url:$it" }
        Log.i(PlayerRuntimeController.TAG, "AUTO_SOURCE_RECOVERY: trying next eligible source")
        player.switchToSourceStream(
            resolved,
            resumeSavedProgress = !player.navigationArgs.startFromBeginning || player.currentVideoId != player.navigationArgs.videoId
        )
        return NextResult.Started
    }

    private fun stopAttempt() {
        player.playerInitializationJob?.cancel()
        player.playerInitializationJob = null
        player.playbackPreparationJob?.cancel()
        player.debridResolveJob?.cancel()
        player.torrentStreamJob?.cancel()
        player.resetErrorRetryState()
        player.cancelFirstFrameWatchdog()
        player.cancelStallWatchdog()
        player.releasePlayer(flushPlaybackState = false)
        player.stopTorrentStream()
        player.hasRenderedFirstFrame = false
        player._uiState.update { it.copy(error = null, isBuffering = true) }
    }

    private fun showPicker() {
        switchNotice?.cancel()
        stopAttempt()
        player.sourceRecoveryPickerError = player.context.getString(R.string.player_auto_source_recovery_exhausted)
        player._uiState.update {
            it.copy(
                error = null, isBuffering = false, showLoadingOverlay = false
            )
        }
        android.widget.Toast.makeText(player.context, R.string.player_auto_source_recovery_exhausted, android.widget.Toast.LENGTH_SHORT).show()
        player.showSourcesPanel()
    }
}

internal fun PlayerRuntimeController.beginAutoSourceRecovery(plan: AutoSourceRecoveryPlan) {
    autoSourceRecovery?.cancel()
    if (!AutoSourceRecoverySettings.enabled(context).value || plan.mode == com.nuvio.tv.data.local.StreamAutoPlayMode.MANUAL) return
    if (contentType in listOf("tv", "channel", "cloud")) return
    val recovery = PlayerAutoSourceRecovery(this, plan)
    autoSourceRecovery = recovery
    recovery.start()
}

internal fun PlayerRuntimeController.claimAutoSourceRecovery() {
    AutoSourceRecoveryHandoff.take(navigationArgs.autoSourceRecoveryToken, profileId, currentVideoId)
        ?.let { beginAutoSourceRecovery(it) }
}

