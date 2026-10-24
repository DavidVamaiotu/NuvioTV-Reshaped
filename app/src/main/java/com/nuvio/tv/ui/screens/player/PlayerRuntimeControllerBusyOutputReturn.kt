package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A title that went to PCM because another app held the bitstream output gets passthrough back once the
 * output is free. A quick open of the title's format is cheap (about 1 ms when refused) and touches nothing
 * the player uses. Checks are frequent for the first minute, when a paused app is most likely closed, and
 * then slow down and stop after about an hour.
 */
internal object BusyOutputReturn {
    const val MAX_REBUILDS_PER_STREAM = 3

    private const val FAST_MS = 5_000L
    private const val FAST_CHECKS = 12
    private const val MEDIUM_MS = 15_000L
    private const val MEDIUM_CHECKS = 40
    private const val SLOW_MS = 60_000L
    private const val SLOW_CHECKS = 50

    const val MAX_CHECKS = FAST_CHECKS + MEDIUM_CHECKS + SLOW_CHECKS

    /** After a rebuild was refused again the checks start at the medium pace, so a flapping output cannot loop. */
    fun nextDelayMs(ticksDone: Int, afterRefusedReturn: Boolean): Long? {
        val done = if (afterRefusedReturn) ticksDone + FAST_CHECKS else ticksDone
        return when {
            done < FAST_CHECKS -> FAST_MS
            done < FAST_CHECKS + MEDIUM_CHECKS -> MEDIUM_MS
            done < MAX_CHECKS -> SLOW_MS
            else -> null
        }
    }

    fun startsFor(reading: BitstreamOutputReading?, retryScheduled: Boolean): Boolean =
        !retryScheduled && reading?.outputUnavailable == true

    fun mayCheckNow(
        sameStream: Boolean,
        playerReady: Boolean,
        rebuildOrSeekPending: Boolean,
        inBackground: Boolean,
        playingAsPcm: Boolean,
        bitstreamFormat: Boolean,
        policyDenies: Boolean,
        normalSpeed: Boolean
    ): Boolean = sameStream && playerReady && !rebuildOrSeekPending && !inBackground &&
        playingAsPcm && bitstreamFormat && !policyDenies && normalSpeed

    fun shouldRebuild(opened: Boolean, rebuildsSoFar: Int): Boolean =
        opened && rebuildsSoFar < MAX_REBUILDS_PER_STREAM
}

internal fun PlayerRuntimeController.startBusyOutputChecks() {
    val url = currentStreamUrl
    if (busyOutputCheckJob?.isActive == true && busyOutputCheckStreamUrl == url) return
    busyOutputCheckJob?.cancel()
    busyOutputCheckStreamUrl = url
    val afterRefusedReturn = (busyOutputReturnRebuilds[url] ?: 0) > 0
    Log.i(PlayerRuntimeController.TAG, "PASSTHROUGH_BUSY_WAIT start afterReturn=$afterRefusedReturn")
    busyOutputCheckJob = scope.launch {
        var ticks = 0
        var checks = 0
        while (true) {
            val waitMs = BusyOutputReturn.nextDelayMs(ticks, afterRefusedReturn) ?: break
            ticks++
            delay(waitMs)
            if (currentStreamUrl != url) return@launch
            val player = _exoPlayer
            val format = player?.audioFormat
            val encoding = PassthroughOutputReturn.encodingOf(format)?.let {
                PassthroughOutputReturn.outputEncodingFor(PassthroughOutputReturn.liveEncodings(context), it)
            }
            val mayCheck = BusyOutputReturn.mayCheckNow(
                sameStream = true,
                playerReady = player?.playbackState == Player.STATE_READY,
                rebuildOrSeekPending = isReleasingPlayer || playbackRecoveryJob?.isActive == true ||
                    _uiState.value.pendingSeekPosition != null,
                inBackground = isInBackground,
                playingAsPcm = hasTriedAudioPcmFallback,
                bitstreamFormat = encoding != null,
                policyDenies = currentAudioPassthroughPolicy?.deniesPassthrough(format?.sampleMimeType) == true,
                normalSpeed = _uiState.value.playbackSpeed == 1f
            )
            if (!mayCheck || player == null || encoding == null) continue
            checks++
            val opened = withContext(Dispatchers.IO) { AudioRejectionReverifier.opensNow(encoding) }
            if (_exoPlayer !== player || currentStreamUrl != url) continue
            val rebuildsSoFar = busyOutputReturnRebuilds[url] ?: 0
            if (!BusyOutputReturn.shouldRebuild(opened, rebuildsSoFar)) {
                if (opened) break
                continue
            }
            busyOutputReturnRebuilds[url] = rebuildsSoFar + 1
            Log.i(
                PlayerRuntimeController.TAG,
                "PASSTHROUGH_BUSY_RETURN rebuild encoding=$encoding checks=$checks " +
                    "positionMs=${player.currentPosition} route=${currentAudioOutputRoute?.key}"
            )
            scheduleOwnedPlaybackRecovery(player.currentPosition, 0L)
            return@launch
        }
        Log.i(PlayerRuntimeController.TAG, "PASSTHROUGH_BUSY_WAIT stopped after $checks checks")
    }
}
