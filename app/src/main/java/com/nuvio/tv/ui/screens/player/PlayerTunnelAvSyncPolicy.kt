package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.core.player.SurroundFormatResolver

internal object PlayerTunnelAvSyncPolicy {

    val deadAudioClasses: MutableSet<String> = java.util.concurrent.CopyOnWriteArraySet()

    fun chainSignature(
        fingerprint: String,
        routeKey: String,
        direct: SurroundFormatResolver.DirectSupport?,
        maxPcmChannels: Int?,
    ): String {
        val claims = direct?.let {
            listOf(it.ac3, it.eac3, it.trueHd, it.dts, it.dtsHd)
                .joinToString("") { claimed -> if (claimed) "1" else "0" }
        } ?: UNREAD
        return "$fingerprint|$routeKey|$claims|${maxPcmChannels ?: UNREAD}"
    }

    private const val UNREAD = "na"

    @Volatile
    private var seededSignature: String? = null

    fun seedFromStore(
        classes: Set<String>,
        storedSignature: String?,
        currentSignature: String,
    ): Set<String> {
        if (seededSignature == currentSignature) return emptySet()
        if (seededSignature != null) deadAudioClasses.clear()
        seededSignature = currentSignature
        if (classes.isEmpty() || storedSignature != currentSignature) return emptySet()
        deadAudioClasses.addAll(classes)
        return classes
    }

    fun resetMemo() {
        deadAudioClasses.clear()
        seededSignature = null
    }

    private val CLASS_LABELS: List<Pair<String, String>> = listOf(
        MimeTypes.AUDIO_AC3 to "AC-3",
        MimeTypes.AUDIO_E_AC3 to "E-AC-3",
        MimeTypes.AUDIO_E_AC3_JOC to "E-AC-3 JOC",
        MimeTypes.AUDIO_AC4 to "AC-4",
        MimeTypes.AUDIO_TRUEHD to "TrueHD",
        MimeTypes.AUDIO_DTS to "DTS",
        MimeTypes.AUDIO_DTS_HD to "DTS-HD",
        MimeTypes.AUDIO_DTS_EXPRESS to "DTS Express",
        MimeTypes.AUDIO_DTS_X to "DTS:X",
        MimeTypes.AUDIO_AAC to "AAC",
        MimeTypes.AUDIO_MPEG to "MP3",
        MimeTypes.AUDIO_OPUS to "Opus",
        MimeTypes.AUDIO_FLAC to "FLAC",
    )

    /** Readable names for dead-clock classes, known formats first, PCM last. */
    fun memoLabels(classes: Collection<String>): List<String> {
        val present = classes.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val known = CLASS_LABELS.filter { (mime, _) -> mime in present }.map { it.second }
        val knownMimes = CLASS_LABELS.map { it.first }.toSet()
        val other = present
            .filter { it !in knownMimes && it != PlaybackSpeedAwareAudioSink.TUNNEL_AUDIO_CLASS_PCM }
            .map { it.substringAfter('/').substringBefore(';').uppercase() }
            .sorted()
        val pcm = if (PlaybackSpeedAwareAudioSink.TUNNEL_AUDIO_CLASS_PCM in present) listOf("PCM") else emptyList()
        return (known + other + pcm).distinct()
    }

    const val MEMO_CONFIRM_SAMPLES = 45
    const val MEMO_CONFIRM_PLAYED_MS = 10_000L
    const val OUTPUT_CHANGE_QUIET_MS = 13_000L

    /** Decoders seen reporting tunnelled frames; for any other "no frames" proves nothing. */
    val frameReportDecoders: MutableSet<String> = java.util.concurrent.CopyOnWriteArraySet()

    fun frameReportKey(fingerprint: String, decoderName: String?): String? =
        decoderName?.takeIf { it.isNotBlank() }?.let { "$fingerprint|$it" }

    /** One sample's share of untunnelled playback: seeks and stalls add nothing. */
    fun memoAdvanceMs(previousPositionMs: Long, positionMs: Long, intervalMs: Long): Long {
        val step = positionMs - previousPositionMs
        return if (step in 1..(2 * intervalMs)) step else 0L
    }

    fun memoConfirmed(playedMs: Long): Boolean = playedMs >= MEMO_CONFIRM_PLAYED_MS

    /** A receiver re-locking after a display or audio output change can hold the clock for seconds. */
    fun remembersStall(nowMs: Long, lastOutputChangeMs: Long): Boolean =
        lastOutputChangeMs <= 0L || nowMs - lastOutputChangeMs >= OUTPUT_CHANGE_QUIET_MS

    data class Input(
        val isTunnelingActive: Boolean,
        val isTunnelingApplied: Boolean,
        val hasVideoTrack: Boolean,
        val isReady: Boolean,
        val playWhenReady: Boolean,
        val userPausedManually: Boolean,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val lastPositionMs: Long?,
        val stalledMs: Long,
        val intervalMs: Long,
        val stallThresholdMs: Long,
        val renderedOutputBufferCount: Int?,
        val frameReportsTrusted: Boolean,
        val readyMs: Long,
        val noFrameThresholdMs: Long,
        val tunnelingAlreadyDisarmed: Boolean,
    )

    sealed class Decision {
        data object None : Decision()
        data object Stop : Decision()
        data object DisableTunnelingAndRebuild : Decision()
    }

    enum class Reason { PositionFrozen, NoFramesRendered }

    data class StallStrike(val streamUrl: String, val firstReadyPositionMs: Long?)

    enum class StallResponse { ReloadKeepingTunnel, DisableTunneling }

    /** The first frozen picture in a play gets one reload with the tunnel kept; the next one turns it off. */
    fun stallResponse(reason: Reason?, streamUrl: String, strike: StallStrike?): StallResponse =
        if (reason == Reason.PositionFrozen && strike?.streamUrl != streamUrl) {
            StallResponse.ReloadKeepingTunnel
        } else {
            StallResponse.DisableTunneling
        }

    /** A strike belongs to one stream; opening another one starts fresh. */
    fun strikeForStream(streamUrl: String, strike: StallStrike?): StallStrike? =
        strike?.takeIf { it.streamUrl == streamUrl }

    data class Result(
        val decision: Decision,
        val stalledMs: Long,
        val readyMs: Long,
        val reason: Reason? = null,
    )

    fun evaluate(input: Input): Result {
        if (!input.isTunnelingActive || !input.isTunnelingApplied || !input.hasVideoTrack ||
            input.tunnelingAlreadyDisarmed
        ) {
            return Result(Decision.Stop, 0L, 0L)
        }
        if (!input.isReady || !input.playWhenReady || input.userPausedManually) {
            return Result(Decision.None, 0L, 0L)
        }
        val readyMs = input.readyMs + input.intervalMs
        val last = input.lastPositionMs
        val stalledMs = when {
            last == null -> 0L
            input.positionMs != last -> 0L
            input.bufferedPositionMs <= input.positionMs -> 0L
            else -> input.stalledMs + input.intervalMs
        }
        if (stalledMs >= input.stallThresholdMs) {
            return Result(Decision.DisableTunnelingAndRebuild, stalledMs, readyMs, Reason.PositionFrozen)
        }
        if (input.frameReportsTrusted && input.renderedOutputBufferCount == 0 &&
            readyMs >= input.noFrameThresholdMs
        ) {
            return Result(Decision.DisableTunnelingAndRebuild, stalledMs, readyMs, Reason.NoFramesRendered)
        }
        return Result(Decision.None, stalledMs, readyMs)
    }
}
