package com.nuvio.tv.ui.screens.player

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.trackselection.MappingTrackSelector
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.data.local.AudioOutputChannels
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.ReshapedFormatSettings
import com.nuvio.tv.data.local.ReshapedFormatSettingsStore
import com.nuvio.tv.ui.screens.player.iec.LiveDirectAudioPlayback
import com.nuvio.tv.ui.screens.player.iec.PlatformIecAudioTrackFactory
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/*
 * Nuvio RS: Nuvio Arc's sound format handling, wired into the player through small hooks.
 *
 * TrueHD and DTS-HD go out through Arc's own IEC61937 engine (MAT packing for TrueHD), Auto surround
 * reads what the HDMI chain takes and decodes the rest, receiver refusals are learned per output, and
 * a few recovery steps keep sound playing when an output refuses a format. Everything here runs only
 * with Settings > Nuvio Reshaped > Sound and picture formats on; off, the player is NuvioTV's own.
 */

internal const val TUNNEL_AV_SYNC_CHECK_MS = 1_000L
internal const val TUNNEL_AV_SYNC_STALL_MS = 5_000L
internal const val TUNNEL_AV_SYNC_NO_FRAME_MS = 8_000L

private const val MAX_FORMATS_RECOVERIES_PER_STREAM = 5

internal fun PlayerRuntimeController.formatSettings(): ReshapedFormatSettings =
    ReshapedFormatSettingsStore.current(context)

/** Reads an Android system property (debug.* switches settable with adb, no root); null if unavailable. */
internal fun formatsSystemProperty(name: String): String? = runCatching {
    Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java)
        .invoke(null, name) as? String
}.getOrNull()

// ── Start of a playback ──

/** Start of initializePlayer: a new playback identity and the tunnel strike for this stream. */
internal fun PlayerRuntimeController.beginFormatsPlayback(url: String, headers: Map<String, String>) {
    playbackRecoveryJob?.cancel()
    playbackRecoveryJob = null
    playbackRecoveryGate.begin(url, headers)
    // Keyed by URL: a stall rebuild re-enters here, and a reset would make each freeze a first one.
    tunnelStallStrike = PlayerTunnelAvSyncPolicy.strikeForStream(url, tunnelStallStrike)
    currentAudioPathDescription = null
    isAudioOutputBypassing = false
    PlayerAudioUnderrunCounter.reset()
    PlayerAudioBitrateMeter.reset()
}

/** After the output route is read for a new player: restore refusal evidence and re-check old refusals. */
internal fun PlayerRuntimeController.onFormatsRouteDetected() {
    val settings = formatSettings()
    if (!settings.enabled) return
    AudioRejectionReverifier.ledger.restoreEvidence(ReshapedFormatSettingsStore.audioRefusalEvidence(context))
    val expired = ReshapedFormatSettingsStore.expireTunnelDeadAudioClasses(context)
    if (expired.isNotEmpty()) {
        PlayerTunnelAvSyncPolicy.deadAudioClasses.removeAll(expired)
        Log.i(PlayerRuntimeController.TAG, "TUNNEL_AV_SYNC: saved dead-clock memo expired for $expired")
    }
    PlayerTunnelAvSyncPolicy.frameReportDecoders.addAll(ReshapedFormatSettingsStore.tunnelFrameReportDecoders(context))
    rearmOutputChecksForNewPlayer()
    currentAudioOutputRoute?.let { route ->
        if (!route.isBluetooth && _exoPlayer == null) {
            AudioRejectionReverifier.start(route.key, settings.audioRejectionsConfirmed) { entry ->
                ReshapedFormatSettingsStore.clearAudioRejection(context, entry)
            }
        }
    }
}

/**
 * Each player gets its own IEC61937 probe and the tunnel watch's quiet time after an output change. A bitstream
 * track of the previous player whose release was not reported counts as closed from here.
 */
internal fun PlayerRuntimeController.rearmOutputChecksForNewPlayer() {
    LiveDirectAudioPlayback.noteOwnTrackClosed()
    PlatformIecAudioTrackFactory.invalidateIec61937ProbeMemo()
    noteOutputChangeForTunnelWatch()
}

/** What the renderers factory builds the audio path with for this player. */
internal data class FormatsAudioPlan(
    val active: Boolean,
    val iecEnabled: Boolean,
    val policy: AudioPassthroughPolicy,
    val downmixEnabled: Boolean,
    val audioOutputChannels: AudioOutputChannels,
    val deniedTranscodeMimes: Set<String>,
    val preferFfmpegAudio: Boolean
)

/** Auto surround for the current output, the learned refusals and the tunnel memo, before the player is built. */
internal fun PlayerRuntimeController.planFormatsAudio(
    url: String,
    isBluetooth: Boolean,
    forceOpticalActive: Boolean,
    effectiveDecoderPriority: Int,
    effectiveDownmixEnabled: Boolean,
    effectiveAudioOutputChannels: AudioOutputChannels
): FormatsAudioPlan {
    var settings = formatSettings()
    formatsActiveForCurrentPlayback = settings.enabled
    if (!settings.enabled) {
        currentAudioPassthroughPolicy = null
        surroundResolveInputs = null
        lastAppliedSurroundResolve = null
        lastAppliedSurroundHdmi = null
        tunnelDeadClockSignature = null
        return FormatsAudioPlan(
            active = false,
            iecEnabled = false,
            policy = AudioPassthroughPolicy.ALLOW_ALL,
            downmixEnabled = effectiveDownmixEnabled,
            audioOutputChannels = effectiveAudioOutputChannels,
            deniedTranscodeMimes = emptySet(),
            preferFfmpegAudio = false
        )
    }
    val currentRouteKey = currentAudioOutputRoute?.key
    // A distinct token is consumed once, including across process restarts.
    if (ReshapedFormatSettingsStore.consumeAudioRejectionReset(
            context, formatsSystemProperty("debug.nuvio.clear_audio_rejections").orEmpty()
        )
    ) {
        Log.i(PlayerRuntimeController.TAG, "Learned audio rejections reset")
        settings = formatSettings()
    }
    val inputs = SurroundResolveInputs(
        routeKey = currentRouteKey,
        isBluetooth = isBluetooth,
        softwareDecodersAvailable =
            effectiveDecoderPriority != androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
        forceOpticalActive = forceOpticalActive,
        effectiveDownmixEnabled = effectiveDownmixEnabled,
        effectiveAudioOutputChannels = effectiveAudioOutputChannels
    )
    surroundResolveInputs = inputs
    val surround = resolveSurroundForRoute(context, settings, inputs)
    val resolution = surround.resolution
    tunnelDeadClockSignature = if (isBluetooth || currentRouteKey == null) {
        null
    } else {
        val chain = AudioChainProbe.snapshot(context, currentRouteKey)
        PlayerTunnelAvSyncPolicy.chainSignature(
            fingerprint = Build.FINGERPRINT,
            routeKey = currentRouteKey,
            direct = chain.direct,
            maxPcmChannels = chain.maxPcmChannels,
        )
    }
    tunnelDeadClockSignature?.let { signature ->
        val seeded = PlayerTunnelAvSyncPolicy.seedFromStore(
            classes = settings.tunnelDeadAudioClasses,
            storedSignature = settings.tunnelDeadAudioSignature,
            currentSignature = signature,
        )
        if (seeded.isNotEmpty()) {
            Log.i(PlayerRuntimeController.TAG, "TUNNEL_AV_SYNC: dead-clock memo restored for $seeded")
        }
    }
    val policy = resolution.policy
    if (!policy.allowsEverything() || surround.targetChannels != null) {
        Log.i(
            PlayerRuntimeController.TAG,
            "SURROUND_RESOLVE: route=$currentRouteKey policy=[ac3=${policy.allowAc3} eac3=${policy.allowEac3} " +
                "truehd=${policy.allowTrueHd} dts=${policy.allowDts} dtshd=${policy.allowDtsHd} " +
                "learned=${policy.learnedDeniedGroups}] transcodePreferred=${resolution.transcodePreferred} " +
                "channelTarget=${surround.targetChannels} tvArcSoundbar=${settings.surroundTvArcSoundbar}"
        )
        queuePlaybackRawEventLine(
            "surround_resolve route=$currentRouteKey ac3=${policy.allowAc3} eac3=${policy.allowEac3} " +
                "truehd=${policy.allowTrueHd} dts=${policy.allowDts} dtshd=${policy.allowDtsHd} " +
                "transcodePreferred=${resolution.transcodePreferred} channelTarget=${surround.targetChannels} " +
                "tvArcSoundbar=${settings.surroundTvArcSoundbar}"
        )
    }
    currentAudioPassthroughPolicy = policy
    lastAppliedSurroundResolve = surround
    lastAppliedSurroundHdmi = AudioChainProbe.hdmiPlugSnapshot(context)
    if (surround.deniedTranscodeMimes.isNotEmpty()) {
        Log.i(PlayerRuntimeController.TAG, "SURROUND_TRANSCODE: route=$currentRouteKey mimes=${surround.deniedTranscodeMimes}")
        queuePlaybackRawEventLine(
            "surround_transcode route=$currentRouteKey mimes=${surround.deniedTranscodeMimes.joinToString(",")}"
        )
    }
    return FormatsAudioPlan(
        active = true,
        iecEnabled = settings.iecEngineEnabled && !forceOpticalActive && !isBluetooth && url !in iecOffStreamUrls,
        policy = policy,
        downmixEnabled = surround.downmixEnabled,
        audioOutputChannels = surround.audioOutputChannels,
        deniedTranscodeMimes = surround.deniedTranscodeMimes,
        preferFfmpegAudio = url in preferFfmpegAudioStreamUrls
    )
}

internal fun surroundTargetToOutputChannels(
    channels: Int,
    fallback: AudioOutputChannels
): AudioOutputChannels = when (channels) {
    2 -> AudioOutputChannels.CHANNELS_2_0
    3 -> AudioOutputChannels.CHANNELS_2_1
    4 -> AudioOutputChannels.CHANNELS_3_1
    6 -> AudioOutputChannels.CHANNELS_5_1
    8 -> AudioOutputChannels.CHANNELS_7_1
    else -> fallback
}

// ── Audio track buffers ──

internal const val PCM_AUDIO_TRACK_BUFFER_US = 500_000

// Decoded PCM always gets a 500 ms track buffer, passthrough keeps the default sizes up to a cap per format.
@androidx.annotation.OptIn(UnstableApi::class)
private val defaultAudioTrackBuffers = DefaultAudioTrackBufferSizeProvider.Builder()
    .setMinPcmBufferDurationUs(PCM_AUDIO_TRACK_BUFFER_US)
    .setMaxPcmBufferDurationUs(PCM_AUDIO_TRACK_BUFFER_US)
    .build()

@androidx.annotation.OptIn(UnstableApi::class)
internal val cappedPassthroughBufferSizeProvider =
    DefaultAudioSink.AudioTrackBufferSizeProvider { minBuffer, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxSpeed ->
        val size = defaultAudioTrackBuffers.getBufferSizeInBytes(
            minBuffer, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxSpeed
        )
        val capBytes = if (outputMode == DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH) {
            passthroughBufferCapBytes(encoding)
        } else {
            Int.MAX_VALUE
        }
        maxOf(minBuffer, minOf(size, capBytes))
    }

@androidx.annotation.OptIn(UnstableApi::class)
private fun passthroughBufferCapBytes(encoding: Int): Int = when (encoding) {
    C.ENCODING_DOLBY_TRUEHD -> 2 * 61_440
    C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2 -> 4 * 30_720
    C.ENCODING_DTS -> 16 * 2_012
    C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> 2 * 10_752
    C.ENCODING_AC3 -> 1_536 * 8
    C.ENCODING_AC4 -> 16_384
    else -> 16_384
}

// ── Track selection ──

/** A passthrough track the sink takes is offered as handled when the audio renderer handles anything at all. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun promotePassthroughAudioWhenRendererAlive(
    mappedTrackInfo: MappingTrackSelector.MappedTrackInfo,
    rendererFormatSupports: Array<out Array<out IntArray>>,
    passthroughPolicy: AudioPassthroughPolicy?,
    audioSink: AudioSink?
) {
    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) continue
        val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
        val supports = rendererFormatSupports[rendererIndex]
        var rendererAlive = false
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                if (RendererCapabilities.getFormatSupport(supports[groupIndex][trackIndex]) == C.FORMAT_HANDLED) {
                    rendererAlive = true
                }
            }
        }
        if (!rendererAlive) continue
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                val mime = group.getFormat(trackIndex).sampleMimeType ?: continue
                if (!PassthroughWaterLevelPacer.isPassthroughMime(mime)) continue
                val current = supports[groupIndex][trackIndex]
                val sinkSupport = audioSink?.getFormatSupport(group.getFormat(trackIndex))
                    ?: AudioSink.SINK_FORMAT_UNSUPPORTED
                if (!PassthroughTrackPromotion.shouldPromote(
                        current,
                        sinkSupport,
                        policyDenies = passthroughPolicy?.deniesPassthrough(mime) == true
                    )
                ) continue
                supports[groupIndex][trackIndex] = RendererCapabilities.create(
                    C.FORMAT_HANDLED,
                    RendererCapabilities.ADAPTIVE_SEAMLESS,
                    RendererCapabilities.getTunnelingSupport(current),
                    RendererCapabilities.getHardwareAccelerationSupport(current),
                    RendererCapabilities.getDecoderSupport(current)
                )
            }
        }
    }
}

/** Tunnelling is cleared for audio the tunnel cannot clock: IEC HBR output and sound types whose clock froze. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun demoteAudioTunnelingWhereItCannotBeClocked(
    mappedTrackInfo: MappingTrackSelector.MappedTrackInfo,
    rendererFormatSupports: Array<out Array<out IntArray>>,
    ffmpegRendererName: String?,
    audioSink: PlaybackSpeedAwareAudioSink?,
    deadClockAudioClasses: Set<String>
) {
    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) continue
        val rendererName = mappedTrackInfo.getRendererName(rendererIndex)
        val isFfmpegRenderer = ffmpegRendererName != null && rendererName == ffmpegRendererName
        val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
        val supports = rendererFormatSupports[rendererIndex]
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                val format = group.getFormat(trackIndex)
                val reason = when {
                    isFfmpegRenderer ->
                        if (deadClockAudioClasses.contains(PlaybackSpeedAwareAudioSink.TUNNEL_AUDIO_CLASS_PCM)) {
                            "dead-tunnel-clock"
                        } else {
                            null
                        }
                    audioSink == null -> null
                    audioSink.demandsNonTunnelledVideo(format) -> "iec-hbr"
                    audioSink.hbrDemandsNonTunnelledVideo(format) -> "hbr-format"
                    deadClockAudioClasses.isNotEmpty() &&
                        deadClockAudioClasses.contains(audioSink.tunnelAudioClass(format)) -> "dead-tunnel-clock"
                    else -> null
                } ?: continue
                val current = supports[groupIndex][trackIndex]
                if (RendererCapabilities.getTunnelingSupport(current) == RendererCapabilities.TUNNELING_NOT_SUPPORTED) {
                    continue
                }
                Log.d(
                    "NuvioTrackSelector",
                    "Tunnelling cleared for renderer=$rendererName mime=${format.sampleMimeType} reason=$reason"
                )
                supports[groupIndex][trackIndex] = RendererCapabilities.create(
                    RendererCapabilities.getFormatSupport(current),
                    RendererCapabilities.getAdaptiveSupport(current),
                    RendererCapabilities.TUNNELING_NOT_SUPPORTED,
                    RendererCapabilities.getHardwareAccelerationSupport(current),
                    RendererCapabilities.getDecoderSupport(current)
                )
            }
        }
    }
}

/** Track selector hook: both Arc rules, only while the formats handling runs for this player. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.applyFormatsTrackRules(
    mappedTrackInfo: MappingTrackSelector.MappedTrackInfo,
    rendererFormatSupports: Array<out Array<out IntArray>>
) {
    if (!formatsActiveForCurrentPlayback) return
    promotePassthroughAudioWhenRendererAlive(
        mappedTrackInfo,
        rendererFormatSupports,
        passthroughPolicy = currentAudioPassthroughPolicy,
        audioSink = playbackSpeedAwareAudioSink
    )
    demoteAudioTunnelingWhereItCannotBeClocked(
        mappedTrackInfo,
        rendererFormatSupports,
        ffmpegRendererName = ffmpegAudioRenderer?.name,
        audioSink = playbackSpeedAwareAudioSink,
        deadClockAudioClasses = PlayerTunnelAvSyncPolicy.deadAudioClasses
    )
}

// ── Player events ──

/** Human-readable source codec from the selected track's mime. */
internal fun describeAudioMime(mime: String?): String = when (mime) {
    MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    MimeTypes.AUDIO_AC3 -> "AC-3"
    MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
    MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3 JOC"
    MimeTypes.AUDIO_AC4 -> "AC-4"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_OPUS -> "Opus"
    null -> "Audio"
    else -> mime.substringAfterLast('/').uppercase()
}

/** Sink output mode from the negotiated AudioTrack encoding. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun describeAudioEncoding(encoding: Int): String = when (encoding) {
    C.ENCODING_AC3 -> "Passthrough (AC-3)"
    C.ENCODING_E_AC3 -> "Passthrough (E-AC-3)"
    C.ENCODING_E_AC3_JOC -> "Passthrough (E-AC-3 JOC)"
    C.ENCODING_DOLBY_TRUEHD -> "Passthrough (TrueHD)"
    C.ENCODING_DTS -> "Passthrough (DTS)"
    C.ENCODING_DTS_HD -> "Passthrough (DTS-HD)"
    C.ENCODING_AC4 -> "Passthrough (AC-4)"
    C.ENCODING_PCM_16BIT,
    C.ENCODING_PCM_16BIT_BIG_ENDIAN,
    C.ENCODING_PCM_24BIT,
    C.ENCODING_PCM_24BIT_BIG_ENDIAN,
    C.ENCODING_PCM_32BIT,
    C.ENCODING_PCM_32BIT_BIG_ENDIAN,
    C.ENCODING_PCM_8BIT,
    C.ENCODING_PCM_FLOAT -> "PCM decode"
    else -> "Encoding $encoding"
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun isPcmEncoding(encoding: Int): Boolean = when (encoding) {
    C.ENCODING_PCM_16BIT,
    C.ENCODING_PCM_16BIT_BIG_ENDIAN,
    C.ENCODING_PCM_24BIT,
    C.ENCODING_PCM_24BIT_BIG_ENDIAN,
    C.ENCODING_PCM_32BIT,
    C.ENCODING_PCM_32BIT_BIG_ENDIAN,
    C.ENCODING_PCM_8BIT,
    C.ENCODING_PCM_FLOAT -> true
    else -> false
}

/** AnalyticsListener.onAudioTrackInitialized: audio path row, learned refusals, PCM-only control gating. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.onFormatsAudioTrackInitialized(
    player: ExoPlayer,
    audioTrackConfig: AudioSink.AudioTrackConfig
) {
    if (formatsActiveForCurrentPlayback) recordStashedAudioRejection(audioTrackConfig.encoding)
    val sourceMime = player.audioFormat?.sampleMimeType
    // The only transcode this app performs is x -> AC-3; name it instead of calling it passthrough.
    val sinkPath = if (
        audioTrackConfig.encoding == C.ENCODING_AC3 && sourceMime != null && sourceMime != MimeTypes.AUDIO_AC3
    ) {
        "AC-3 transcode"
    } else {
        describeAudioEncoding(audioTrackConfig.encoding)
    }
    val detail = buildString {
        append(sinkPath)
        append(" (")
        append(audioTrackConfig.sampleRate / 1000)
        append(" kHz")
        val channels = Integer.bitCount(audioTrackConfig.channelConfig)
        if (channels > 0) append(", ${channels}ch")
        if (audioTrackConfig.tunneling) append(", tunneled")
        if (audioTrackConfig.offload) append(", offload")
        append(')')
    }
    currentAudioPathDescription = "${describeAudioMime(sourceMime)} → $detail"
    if (_exoPlayer === player && formatsActiveForCurrentPlayback) {
        val openedGroup = passthroughGroupOfEncoding(audioTrackConfig.encoding)
        playbackRecoveryGate.noteAudioOutputOpened(openedGroup)
        if (openedGroup != null) {
            LiveDirectAudioPlayback.noteOwnTrackOpened()
            currentAudioOutputRoute?.key?.let {
                if (AudioRejectionReverifier.ledger.noteBitstreamOpened(it)) saveAudioRefusalEvidence()
            }
        }
    }
    // Gain and skip-silence are PCM processors: they do nothing to a bitstream.
    val bypassing = formatsActiveForCurrentPlayback && !isPcmEncoding(audioTrackConfig.encoding)
    if (bypassing != isAudioOutputBypassing) {
        isAudioOutputBypassing = bypassing
        updateAudioControlAvailability()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun onFormatsAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
    if (passthroughGroupOfEncoding(audioTrackConfig.encoding) != null) {
        LiveDirectAudioPlayback.noteOwnTrackClosed()
    }
}

/** AnalyticsListener.onIsPlayingChanged: IEC output counts as an opened bitstream; audio path row fallback. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.onFormatsIsPlayingChanged(player: ExoPlayer, isPlaying: Boolean) {
    val sink = playbackSpeedAwareAudioSink
    if (isPlaying && _exoPlayer === player && formatsActiveForCurrentPlayback && sink?.isIecHbrActive() == true) {
        playbackRecoveryGate.noteAudioOutputOpened(AudioPassthroughPolicy.groupOf(sink.activeInputFormat?.sampleMimeType))
        currentAudioOutputRoute?.key?.let {
            if (AudioRejectionReverifier.ledger.noteBitstreamOpened(it)) saveAudioRefusalEvidence()
        }
    }
    // The FFmpeg renderer path never reports onAudioTrackInitialized; derive the row from the sink.
    if (isPlaying && currentAudioPathDescription == null) {
        runCatching {
            val source = player.audioFormat
            val sinkFormat = sink?.activeInputFormat
            if (source?.sampleMimeType != null && sinkFormat?.sampleMimeType != null) {
                val src = describeAudioMime(source.sampleMimeType)
                val detail = buildString {
                    when {
                        sinkFormat.sampleMimeType == MimeTypes.AUDIO_RAW -> append("PCM decode")
                        sink.isIecHbrActive() -> append("Passthrough, IEC (").append(src).append(')')
                        sinkFormat.sampleMimeType == source.sampleMimeType -> append("Passthrough (").append(src).append(')')
                        else -> append(describeAudioMime(sinkFormat.sampleMimeType)).append(" transcode")
                    }
                    append(" (")
                    if (sinkFormat.sampleRate > 0) append(sinkFormat.sampleRate / 1000).append(" kHz")
                    if (sinkFormat.channelCount > 0) append(", ").append(sinkFormat.channelCount).append("ch")
                    append(')')
                }
                currentAudioPathDescription = "$src → $detail"
            }
        }
    }
}

// ── Error recovery ──

/**
 * A runtime check that tripped in the audio output path (audio renderer, audio sink or the IEC engine). Video
 * fallbacks such as dropping Dolby Vision cannot cure it.
 */
internal fun isAudioOutputRuntimeFailure(
    errorCode: Int,
    rendererSampleMimeType: String?,
    cause: Throwable?
): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK) return false
    if (rendererSampleMimeType != null) return MimeTypes.isAudio(rendererSampleMimeType)
    return generateSequence(cause) { it.cause }.take(8).any { throwable ->
        throwable.stackTrace.any { frame -> isAudioOutputFrame(frame.className) }
    }
}

private fun isAudioOutputFrame(className: String): Boolean =
    className.startsWith("androidx.media3.exoplayer.audio.") ||
        className.startsWith("com.nuvio.tv.ui.screens.player.iec.")

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlaybackException.isFormatsAudioOutputRuntimeFailure(): Boolean = isAudioOutputRuntimeFailure(
    errorCode = errorCode,
    rendererSampleMimeType = (this as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType,
    cause = cause
)

private fun PlaybackException.isFormatsAudioTrackFailure(): Boolean {
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return isAudioTrackFailure(errorCode, details)
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlaybackException.failingAudioMimeType(): String? =
    (this as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType
        ?: failedAudioTrackInputFormat(this)?.sampleMimeType

/**
 * The first audio rung turns the app's own IEC output off, when the failing player sent the current TrueHD or
 * DTS-HD track through it. A known failing format must be one of those too.
 */
internal fun iecOffRungApplies(audioPathFailure: Boolean, iecInvolved: Boolean, failingMimeType: String?): Boolean =
    audioPathFailure && iecInvolved &&
        (failingMimeType == null || PassthroughWaterLevelPacer.isHbrMime(failingMimeType))

internal const val IEC_FAILURES_BEFORE_OFF = 2

/**
 * Counts an own IEC output failure for [url]. The first one only lets the film carry on through the normal
 * output, the next rebuild tries IEC again; a second one keeps the stream off IEC.
 */
internal fun noteIecFailure(strikes: MutableMap<String, Int>, iecOff: MutableSet<String>, url: String): Int {
    val count = (strikes[url] ?: 0) + 1
    strikes[url] = count
    if (count >= IEC_FAILURES_BEFORE_OFF) iecOff.add(url)
    return count
}

internal fun PlayerRuntimeController.onOwnIecOutputFailed() {
    val url = currentStreamUrl
    val count = noteIecFailure(iecFailureStrikes, iecOffStreamUrls, url)
    Log.i(
        PlayerRuntimeController.TAG,
        if (url in iecOffStreamUrls) {
            "IEC_OFF_FOR_STREAM: own IEC output failed $count times host=${url.formatsSafeHost()}"
        } else {
            "IEC_FAILURE_NOTED: own IEC output failed once, tried again on the next rebuild host=${url.formatsSafeHost()}"
        }
    )
}

internal fun PlayerRuntimeController.retryCurrentStreamWithoutIec(fromPositionMs: Long) {
    iecOffStreamUrls.add(currentStreamUrl)
    Log.i(
        PlayerRuntimeController.TAG,
        "IEC_OFF_AUDIO_FALLBACK: rebuilding without IEC output host=${currentStreamUrl.formatsSafeHost()}"
    )
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

private fun String.formatsSafeHost(): String =
    runCatching { android.net.Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")

/** Bounds the automatic audio recoveries added here, per stream. */
internal fun PlayerRuntimeController.consumeFormatsRecoveryBudget(what: String): Boolean {
    val url = currentStreamUrl
    if (url != formatsRecoveryBudgetUrl) {
        formatsRecoveryBudgetUrl = url
        formatsRecoveryCountForCurrentStream = 0
    }
    formatsRecoveryCountForCurrentStream += 1
    if (formatsRecoveryCountForCurrentStream > MAX_FORMATS_RECOVERIES_PER_STREAM) {
        Log.w(PlayerRuntimeController.TAG, "Audio recovery budget spent for host=${url.formatsSafeHost()}: $what")
        return false
    }
    return true
}

internal fun PlayerRuntimeController.audioTrackPcmFallbackAvailable(): Boolean =
    !hasTriedAudioPcmFallback &&
        cachedDecoderPriority == 1 && // Only for EXTENSION_RENDERER_MODE_ON
        !_uiState.value.tunnelingEnabled

/** Bounded retry of a verified bitstream sink configuration; delays are policy, not a root-cause claim. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryBitstreamAudioTrackSameConfigRetry(error: PlaybackException): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
    if (hasTriedAudioPcmFallback) return false
    val mime = failedAudioTrackInputFormat(error)?.sampleMimeType
    if (AudioTrackRejectionLog.labelForMime(mime) == null ||
        currentAudioPassthroughPolicy?.deniesPassthrough(mime) != false
    ) return false
    val pcmFallbackAvailable = audioTrackPcmFallbackAvailable()
    val reading = if (pcmFallbackAvailable) bitstreamOutputReading(error) else null
    if (reading != null && CompressedOutputCheck.skipSameConfigRetry(
            pcmFallbackAvailable = pcmFallbackAvailable,
            refusedListedLive = reading.refusedListedLive,
            ownTrackMayHold = CompressedOutputCheck.ownTrackMayHoldOutput(
                LiveDirectAudioPlayback.ownTrackOpen(),
                LiveDirectAudioPlayback.msSinceOwnTrackClosed()
            ),
            companionOpened = reading.companionOpened
        )
    ) {
        Log.w(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY skipped: bitstream output unavailable, decoding to PCM")
        return false
    }
    val player = _exoPlayer ?: return false
    if (!playbackRecoveryGate.canRetryAudio()) return false
    if (!consumeFormatsRecoveryBudget("bitstream initialization retry")) return false
    val delayMs = playbackRecoveryGate.nextAudioDelayMs() ?: return false
    Log.w(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY delayMs=$delayMs")
    scheduleOwnedPlaybackRecovery(
        player.currentPosition,
        delayMs,
        awaitOutputFor = PassthroughOutputReturn.encodingOf(failedAudioTrackInputFormat(error))
    )
    return true
}

/**
 * The learned-rejection entry for a refused open, or null when it must not be learned: a same-configuration retry
 * is already scheduled, or the failure is not a bitstream open.
 */
internal fun audioRejectionToStash(
    errorCode: Int,
    retryScheduled: Boolean,
    failingMime: String?,
    routeKey: String?
): String? {
    if (retryScheduled) return null
    if (errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return null
    val group = AudioPassthroughPolicy.groupOf(failingMime) ?: return null
    if (routeKey == null) return null
    return AudioRejectionLedger.entry(routeKey, group)
}

/** Rebuilds the current stream at [positionMs] after [delayMs], unless the playback changed meanwhile. */
internal fun PlayerRuntimeController.scheduleOwnedPlaybackRecovery(
    positionMs: Long,
    delayMs: Long,
    awaitOutputFor: Int? = null
) {
    val url = currentStreamUrl
    val headers = currentHeaders.toMap()
    val generation = playbackRecoveryGate.generation
    var route = runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull()
    val paused = userPausedManually
    val position = _uiState.value.pendingSeekPosition ?: positionMs.coerceAtLeast(0L)
    val preference = rememberedTrackPreference ?: persistedTrackPreference
    cancelFirstFrameWatchdog()
    cancelTunnelAvSyncWatchdog()
    cancelStallWatchdog()
    pendingResumeProgress = null
    showRecoveryOverlay()
    playbackRecoveryJob?.cancel()
    val job = scope.launch(start = CoroutineStart.LAZY) {
        yield()
        if (!playbackRecoveryGate.isCurrent(generation, url, headers)) return@launch
        releasePlayer(flushPlaybackState = false, preserveRecovery = true)
        delay(delayMs)
        if (!playbackRecoveryGate.isCurrent(generation, url, headers) ||
            currentStreamUrl != url || currentHeaders != headers
        ) return@launch
        if (awaitOutputFor != null) {
            val waited = awaitAudioOutputForRetry(awaitOutputFor) {
                playbackRecoveryGate.isCurrent(generation, url, headers)
            } ?: return@launch
            if (waited) route = runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull()
        }
        if (runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull() != route) {
            _uiState.update {
                it.copy(error = context.getString(com.nuvio.tv.R.string.reshaped_formats_output_changed), showLoadingOverlay = false)
            }
            return@launch
        }
        if (preference != null) {
            pendingEngineSwitchTrackPreference = PlayerRuntimeController.PendingEngineSwitchTrackPreference(
                streamUrl = url, preference = preference, sourceEngine = InternalPlayerEngine.EXOPLAYER
            )
        }
        _uiState.update { it.copy(pendingSeekPosition = position, error = null) }
        playbackRecoveryJob = null
        initializePlayer(url, headers, startPaused = paused || userPausedManually)
    }
    playbackRecoveryJob = job
    job.start()
}

/**
 * Waits until the audio output is back before a refused bitstream open of [encoding] is retried. Without this, a
 * refusal while HDMI is unplugged (TV on another input) rebuilds the player with PCM-only capabilities. Returns
 * whether it had to wait, or null when the recovery became stale while waiting.
 */
internal suspend fun PlayerRuntimeController.awaitAudioOutputForRetry(
    encoding: Int,
    stillCurrent: () -> Boolean
): Boolean? {
    val startedMs = SystemClock.elapsedRealtime()
    var logged = false
    while (true) {
        if (!stillCurrent()) return null
        val routeKey = runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull()
        val ready = PassthroughOutputReturn.outputReadyForRetry(
            live = PassthroughOutputReturn.liveEncodings(context),
            routeIsHdmi = PassthroughOutputReturn.isHdmiRoute(routeKey)
        )
        val waitedMs = SystemClock.elapsedRealtime() - startedMs
        if (PassthroughOutputReturn.retryMayProceed(ready, waitedMs)) {
            if (logged) {
                Log.i(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY output back after ${waitedMs}ms ready=$ready route=$routeKey")
            }
            return logged
        }
        if (!logged) {
            Log.w(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY waiting for audio output encoding=$encoding route=$routeKey")
            logged = true
        }
        delay(PassthroughOutputReturn.POLL_MS)
    }
}

/**
 * FFmpeg-preferred rebuild for ERROR_CODE_DECODER_INIT_FAILED (4001) on an audio renderer whose failing format
 * belongs to a policy-denied group. A hybrid track (DTS-HD MA in Matroska) is selected under its base MIME, then
 * upgrades mid-stream to the denied MIME, leaving the selected renderer without a decoder.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryDeniedAudioFfmpegFallback(error: PlaybackException): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    if (currentStreamUrl in preferFfmpegAudioStreamUrls) return false
    if (cachedDecoderPriority == 0) return false // No FFmpeg renderer without extensions.
    val failingMime = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType
    if (failingMime == null || !MimeTypes.isAudio(failingMime)) return false
    val policy = currentAudioPassthroughPolicy ?: return false
    if (!policy.deniesPassthrough(failingMime)) return false
    preferFfmpegAudioStreamUrls.add(currentStreamUrl)
    val paused = userPausedManually
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
    Log.d(
        PlayerRuntimeController.TAG,
        "Decoder init failed (4001) on policy-denied audio $failingMime - retrying with FFmpeg audio preferred, " +
            "position=${savedPosition}ms"
    )
    resetErrorRetryState()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        releasePlayer(flushPlaybackState = false, keepTorrentStream = true)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

/**
 * Notes a refused bitstream open. The session log feeds diagnostics; the stash is persisted as a learned denial
 * only once a fallback has opened audio.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.noteAudioTrackRejection(error: PlaybackException, retryScheduled: Boolean) {
    if (!error.isFormatsAudioTrackFailure()) return
    val mime = failedAudioTrackInputFormat(error)?.sampleMimeType
    val routeKey = currentAudioOutputRoute?.key
    AudioTrackRejectionLog.labelForMime(mime)?.let { label ->
        AudioTrackRejectionLog.record(label, routeKey, System.currentTimeMillis())
        Log.w(PlayerRuntimeController.TAG, "AUDIO_TRACK_REJECTION encoding=$label route=$routeKey retry=$retryScheduled")
    }
    val group = AudioPassthroughPolicy.groupOf(mime)
    if (group != null && routeKey != null && error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) {
        AudioRejectionReverifier.ledger.noteOpenRefused(routeKey, group)
        saveAudioRefusalEvidence()
    }
    if (!retryScheduled && error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED &&
        BusyOutputReturn.startsFor(bitstreamOutputReading(error), retryScheduled)
    ) {
        startBusyOutputChecks()
    }
    if (group != null && !playbackRecoveryGate.shouldLearnAudioRejection(group)) {
        Log.w(PlayerRuntimeController.TAG, "AUDIO_TRACK_REJECTION_NOT_LEARNED group=${group.name} opened earlier in this playback")
        return
    }
    audioRejectionToStash(error.errorCode, retryScheduled, mime, routeKey)?.let { entry ->
        if (bitstreamOutputReading(error)?.outputUnavailable == true) {
            Log.w(
                PlayerRuntimeController.TAG,
                "AUDIO_TRACK_REJECTION_NOT_LEARNED group=${group?.name} route=$routeKey: " +
                    "bitstream output unavailable (a second listed format refused too)"
            )
            return
        }
        AudioRejectionReverifier.ledger.stashPending(currentStreamUrl, entry)
    }
}

internal fun PlayerRuntimeController.bitstreamOutputReading(error: PlaybackException): BitstreamOutputReading? {
    lastBitstreamOutputReading?.let { (readFor, reading) -> if (readFor === error) return reading }
    val reading = readBitstreamOutput(failedAudioTrackInputFormat(error))
    lastBitstreamOutputReading = error to reading
    return reading
}

private fun PlayerRuntimeController.readBitstreamOutput(format: Format?): BitstreamOutputReading? {
    val refused = PassthroughOutputReturn.encodingOf(format) ?: return null
    val live = PassthroughOutputReturn.liveEncodings(context)
    val listed = PassthroughOutputReturn.canPass(live, refused)
    val companionOpened = if (listed) {
        CompressedOutputCheck.companionEncoding(refused, live)?.let { AudioRejectionReverifier.opensNow(it) }
    } else {
        null
    }
    return BitstreamOutputReading(listed, companionOpened)
}

internal fun PlayerRuntimeController.saveAudioRefusalEvidence() {
    ReshapedFormatSettingsStore.saveAudioRefusalEvidence(context, AudioRejectionReverifier.ledger.evidenceSnapshot())
}

internal fun PlayerRuntimeController.recordStashedAudioRejection(openedEncoding: Int) {
    val entry = AudioRejectionReverifier.ledger.takePendingFor(currentStreamUrl) ?: return
    val routeKey = AudioRejectionLedger.routeOf(entry) ?: return
    val group = AudioRejectionLedger.groupOf(entry) ?: return
    val commit = AudioRejectionReverifier.ledger.commit(entry)
    saveAudioRefusalEvidence()
    if (!commit.learn) {
        Log.w(
            PlayerRuntimeController.TAG,
            "AUDIO_REJECTION_NOT_LEARNED group=${group.name} route=$routeKey: " +
                "${commit.refusedOnRoute.joinToString(",") { it.name }} refused with no bitstream open in between, " +
                "AC-3 or E-AC-3 among them: the output is down, not the format" +
                if (commit.forget.isEmpty()) "" else "; forgetting ${commit.forget.joinToString(",")}"
        )
        for (stale in commit.forget) ReshapedFormatSettingsStore.clearAudioRejection(context, stale)
        return
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "AUDIO_REJECTION: ${group.name} refused at open on $routeKey, fallback opened " +
            "encoding=$openedEncoding; recording denial"
    )
    ReshapedFormatSettingsStore.recordAudioRejection(context, routeKey, group.name)
}

/**
 * onPlayerError hook, before NuvioTV's own ladders. Returns true when a recovery was scheduled and the caller
 * must stop. Order follows Arc: a same-configuration retry first (a refusal it cures is never learned), then the
 * IEC-off rung and the FFmpeg rebuild for denied formats.
 */
internal fun PlayerRuntimeController.handleFormatsPlayerError(error: PlaybackException, player: ExoPlayer): Boolean {
    if (!formatsActiveForCurrentPlayback) return false
    val retryScheduled = tryBitstreamAudioTrackSameConfigRetry(error)
    noteAudioTrackRejection(error, retryScheduled)
    if (retryScheduled) return true
    if (iecOffRungApplies(
            audioPathFailure = error.isFormatsAudioOutputRuntimeFailure() || error.isFormatsAudioTrackFailure(),
            iecInvolved = playbackSpeedAwareAudioSink?.iecInvolved() == true,
            failingMimeType = error.failingAudioMimeType()
        ) && consumeFormatsRecoveryBudget("IEC off")
    ) {
        retryCurrentStreamWithoutIec(player.currentPosition)
        return true
    }
    if (tryDeniedAudioFfmpegFallback(error)) return true
    return false
}

/** A tunnelled start stuck without progress is rebuilt once without the tunnel before the audio ladder runs. */
internal fun PlayerRuntimeController.tryTunnelOffForStuckPlayback(player: ExoPlayer): Boolean {
    if (!formatsActiveForCurrentPlayback) return false
    if (!isTunnelingActiveForCurrentPlayback || !player.isTunnelingEnabled) return false
    if (!tunnelingDisabledStreamUrls.add(currentStreamUrl)) return false
    retryCurrentStreamWithoutTunneling(player.currentPosition)
    return true
}

// ── Tunnel A/V sync watchdog ──

internal fun PlayerRuntimeController.retryCurrentStreamWithoutTunneling(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.retryCurrentStreamKeepingTunneling(fromPositionMs: Long) {
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.cancelTunnelAvSyncWatchdog() {
    tunnelAvSyncWatchdogJob?.cancel()
    tunnelAvSyncWatchdogJob = null
}

internal fun PlayerRuntimeController.noteOutputChangeForTunnelWatch() {
    lastOutputChangeForTunnelMs = SystemClock.elapsedRealtime()
}

/** Tunnelled playback whose clock freezes (or never shows a frame) is reloaded, then rebuilt without the tunnel. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.maybeScheduleTunnelAvSyncWatchdog() {
    if (!formatsActiveForCurrentPlayback) return
    if (!isTunnelingActiveForCurrentPlayback) return
    if (_exoPlayer?.isTunnelingEnabled != true) return
    if (!currentStreamHasVideoTrack) return
    if (tunnelingDisabledStreamUrls.contains(currentStreamUrl)) return
    if (tunnelAvSyncWatchdogJob?.isActive == true) return

    tunnelAvSyncWatchdogJob = scope.launch {
        var lastPositionMs: Long? = null
        var stalledMs = 0L
        var readyMs = 0L
        var firstReadyPositionMs: Long? =
            PlayerTunnelAvSyncPolicy.strikeForStream(currentStreamUrl, tunnelStallStrike)?.firstReadyPositionMs
        var pendingDeadClockMemo: Pair<String, String>? = null
        while (isActive) {
            delay(TUNNEL_AV_SYNC_CHECK_MS)
            val livePlayer = _exoPlayer ?: return@launch
            val positionMs = livePlayer.currentPosition
            if (firstReadyPositionMs == null && livePlayer.playbackState == Player.STATE_READY) {
                firstReadyPositionMs = positionMs
            }
            val renderedFrames = livePlayer.videoDecoderCounters
                ?.also { it.ensureUpdated() }
                ?.renderedOutputBufferCount
            val frameReportKey = PlayerTunnelAvSyncPolicy.frameReportKey(Build.FINGERPRINT, formatsVideoDecoderName)
            if (frameReportKey != null && renderedFrames != null && renderedFrames > 0 &&
                livePlayer.isTunnelingEnabled && PlayerTunnelAvSyncPolicy.frameReportDecoders.add(frameReportKey)
            ) {
                Log.i(PlayerRuntimeController.TAG, "TUNNEL_AV_SYNC: tunnelled frames reported by $frameReportKey")
                ReshapedFormatSettingsStore.addTunnelFrameReportDecoder(context, frameReportKey)
            }
            val result = PlayerTunnelAvSyncPolicy.evaluate(
                PlayerTunnelAvSyncPolicy.Input(
                    isTunnelingActive = isTunnelingActiveForCurrentPlayback,
                    isTunnelingApplied = livePlayer.isTunnelingEnabled,
                    hasVideoTrack = currentStreamHasVideoTrack,
                    isReady = livePlayer.playbackState == Player.STATE_READY,
                    playWhenReady = livePlayer.playWhenReady,
                    userPausedManually = userPausedManually,
                    positionMs = positionMs,
                    bufferedPositionMs = livePlayer.bufferedPosition,
                    lastPositionMs = lastPositionMs,
                    stalledMs = stalledMs,
                    intervalMs = TUNNEL_AV_SYNC_CHECK_MS,
                    stallThresholdMs = TUNNEL_AV_SYNC_STALL_MS,
                    renderedOutputBufferCount = renderedFrames,
                    frameReportsTrusted = frameReportKey != null &&
                        frameReportKey in PlayerTunnelAvSyncPolicy.frameReportDecoders,
                    readyMs = readyMs,
                    noFrameThresholdMs = TUNNEL_AV_SYNC_NO_FRAME_MS,
                    tunnelingAlreadyDisarmed = tunnelingDisabledStreamUrls.contains(currentStreamUrl),
                )
            )
            lastPositionMs = positionMs
            stalledMs = result.stalledMs
            readyMs = result.readyMs
            when (result.decision) {
                PlayerTunnelAvSyncPolicy.Decision.Stop -> return@launch
                PlayerTunnelAvSyncPolicy.Decision.None -> Unit
                PlayerTunnelAvSyncPolicy.Decision.DisableTunnelingAndRebuild -> {
                    val audioClass = playbackSpeedAwareAudioSink?.currentTunnelAudioClass
                    val audioLabel = audioClass ?: "unknown"
                    val response = PlayerTunnelAvSyncPolicy.stallResponse(result.reason, currentStreamUrl, tunnelStallStrike)
                    if (response == PlayerTunnelAvSyncPolicy.StallResponse.ReloadKeepingTunnel) {
                        tunnelStallStrike = PlayerTunnelAvSyncPolicy.StallStrike(currentStreamUrl, firstReadyPositionMs)
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "TUNNEL_AV_SYNC: position frozen at ${positionMs}ms for ${stalledMs}ms under " +
                                "tunnelling (audio=$audioLabel); first stall in this play; reloading with tunnelling kept"
                        )
                        queuePlaybackRawEventLine(
                            "tunnel_av_sync_reload_keep_tunneling stalledMs=$stalledMs positionMs=$positionMs " +
                                "audioClass=$audioLabel reason=position-frozen"
                        )
                        retryCurrentStreamKeepingTunneling(positionMs)
                        return@launch
                    }
                    val remembered = PlayerTunnelAvSyncPolicy.remembersStall(
                        SystemClock.elapsedRealtime(),
                        lastOutputChangeForTunnelMs
                    )
                    if (result.reason == PlayerTunnelAvSyncPolicy.Reason.PositionFrozen) {
                        if (!remembered) {
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "TUNNEL_AV_SYNC: freeze right after a display or audio output change; not remembered"
                            )
                        }
                        if (audioClass != null && remembered) {
                            PlayerTunnelAvSyncPolicy.deadAudioClasses.add(audioClass)
                            val signature = tunnelDeadClockSignature
                            if (signature != null && positionMs == firstReadyPositionMs) {
                                pendingDeadClockMemo = audioClass to signature
                            }
                        }
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "TUNNEL_AV_SYNC: position frozen at ${positionMs}ms for ${stalledMs}ms under " +
                                "tunnelling (audio=$audioLabel); disabling tunnelling for this stream"
                        )
                        queuePlaybackRawEventLine(
                            "tunnel_av_sync_disable_tunneling stalledMs=$stalledMs positionMs=$positionMs " +
                                "audioClass=$audioLabel reason=position-frozen"
                        )
                    } else {
                        Log.w(
                            PlayerRuntimeController.TAG,
                            "TUNNEL_AV_SYNC: no tunnelled video frame rendered after ${readyMs}ms at " +
                                "position ${positionMs}ms (audio=$audioLabel); disabling tunnelling for this stream"
                        )
                        queuePlaybackRawEventLine(
                            "tunnel_av_sync_disable_tunneling readyMs=$readyMs positionMs=$positionMs " +
                                "audioClass=$audioLabel reason=no-rendered-frames"
                        )
                    }
                    tunnelingDisabledStreamUrls.add(currentStreamUrl)
                    val rebuiltFrom = livePlayer
                    retryCurrentStreamWithoutTunneling(positionMs)
                    pendingDeadClockMemo?.let { (memoClass, signature) ->
                        persistDeadClockMemoWhenRebuildPlays(memoClass, signature, currentStreamUrl, rebuiltFrom)
                    }
                    return@launch
                }
            }
        }
    }
}

private fun PlayerRuntimeController.persistDeadClockMemoWhenRebuildPlays(
    audioClass: String,
    signature: String,
    streamUrl: String,
    rebuiltFrom: Player
) {
    scope.launch {
        var lastPositionMs: Long? = null
        var playedMs = 0L
        repeat(PlayerTunnelAvSyncPolicy.MEMO_CONFIRM_SAMPLES) {
            delay(TUNNEL_AV_SYNC_CHECK_MS)
            if (isReleasingPlayer || currentStreamUrl != streamUrl) return@launch
            val player = _exoPlayer ?: return@repeat
            if (player === rebuiltFrom || player.playbackState != Player.STATE_READY) return@repeat
            val positionMs = player.currentPosition
            lastPositionMs?.let { last ->
                playedMs += PlayerTunnelAvSyncPolicy.memoAdvanceMs(last, positionMs, TUNNEL_AV_SYNC_CHECK_MS)
            }
            lastPositionMs = positionMs
            if (PlayerTunnelAvSyncPolicy.memoConfirmed(playedMs)) {
                ReshapedFormatSettingsStore.recordTunnelDeadAudioClass(context, audioClass, signature)
                Log.i(
                    PlayerRuntimeController.TAG,
                    "TUNNEL_AV_SYNC: dead-clock memo persisted class=$audioClass (untunnelled rebuild playing)"
                )
                return@launch
            }
        }
        Log.i(
            PlayerRuntimeController.TAG,
            "TUNNEL_AV_SYNC: dead-clock memo not persisted class=$audioClass (untunnelled rebuild did not play long enough)"
        )
    }
}

// ── Output route changes ──

/**
 * Android answers every callback registration with an "added" event listing the outputs already connected. Each
 * new player registers again, so that echo is not a route change and must not reset what was learned about the
 * output during the same playback.
 */
internal fun addsOnlyKnownOutputs(knownOutputIds: Set<Int>, addedOutputIds: Collection<Int>): Boolean =
    addedOutputIds.isNotEmpty() && addedOutputIds.all { it in knownOutputIds }

/** Route-change hook, after the new route is read: forget per-output findings and resolve surround again. */
internal suspend fun PlayerRuntimeController.onFormatsOutputRouteChanged(reason: String) {
    if (!formatsActiveForCurrentPlayback) return
    AudioRejectionReverifier.ledger.invalidate()
    saveAudioRefusalEvidence()
    AudioChainProbe.invalidate()
    PlatformIecAudioTrackFactory.invalidateIec61937ProbeMemo()
    applySurroundResolutionInPlace(reason)
}

/**
 * Rebuilds the player when the HDMI output is back and takes the current track as bitstream, but the player was
 * built while it did not (TV on another input, box asleep). Runs again a little later because a receiver can
 * report its formats a moment after the device appears.
 */
internal fun PlayerRuntimeController.schedulePassthroughReturnCheck(reason: String) {
    if (!formatsActiveForCurrentPlayback) return
    if (_exoPlayer == null || isUsingMpvEngine()) return
    passthroughReturnCheckJob?.cancel()
    passthroughReturnCheckJob = scope.launch {
        if (rebuildForReturnedPassthroughIfNeeded(reason)) return@launch
        delay(PassthroughOutputReturn.SECOND_CHECK_DELAY_MS)
        rebuildForReturnedPassthroughIfNeeded("$reason, second check")
    }
}

private fun PlayerRuntimeController.rebuildForReturnedPassthroughIfNeeded(reason: String): Boolean {
    if (isReleasingPlayer || isInBackground || playbackRecoveryJob?.isActive == true) return false
    if (isUsingMpvEngine()) return false
    val player = _exoPlayer ?: return false
    val sink = playbackSpeedAwareAudioSink ?: return false
    val format = player.audioFormat ?: return false
    val encoding = PassthroughOutputReturn.encodingOf(format) ?: return false
    if (AudioOutputRouteDetector.isBluetoothMediaOutput(context)) return false
    val live = PassthroughOutputReturn.liveEncodings(context)
    val rebuildsSoFar = passthroughReturnRebuilds[currentStreamUrl] ?: 0
    val rebuild = PassthroughOutputReturn.shouldRebuild(
        encoding = encoding,
        pinned = sink.pinnedOutputEncodings,
        live = live,
        alreadyBitstream = sink.isDirectPlaybackActive() || sink.isIecHbrActive(),
        claimedByIec = sink.demandsNonTunnelledVideo(format),
        policyDenies = currentAudioPassthroughPolicy?.deniesPassthrough(format.sampleMimeType) == true,
        forcedPcm = hasTriedAudioPcmFallback ||
            _uiState.value.playbackSpeed != 1f ||
            currentStreamUrl in preferFfmpegAudioStreamUrls,
        rebuildsSoFar = rebuildsSoFar
    )
    if (!rebuild) return false
    passthroughReturnRebuilds[currentStreamUrl] = rebuildsSoFar + 1
    Log.i(
        PlayerRuntimeController.TAG,
        "PASSTHROUGH_RETURN rebuild reason=$reason encoding=$encoding " +
            "pinned=${sink.pinnedOutputEncodings} live=$live route=${currentAudioOutputRoute?.key}"
    )
    scheduleOwnedPlaybackRecovery(player.currentPosition, 0L)
    return true
}

/** After a long stable stretch the bitstream same-configuration retry budget is given back (Arc). */
internal suspend fun PlayerRuntimeController.noteFormatsStablePlayback(player: ExoPlayer, playingSinceMs: Long) {
    if (!formatsActiveForCurrentPlayback) return
    // The caller already waited NuvioTV's 5 s stable-progress delay.
    delay((PlaybackRecoveryGate.AUDIO_RETRY_STABLE_RESET_MS - 5_000L).coerceAtLeast(0L))
    if (_exoPlayer === player && player.playbackState == Player.STATE_READY && player.isPlaying &&
        playbackRecoveryGate.noteStablePlayback(playingSinceMs, SystemClock.elapsedRealtime())
    ) {
        Log.i(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY_RESET after stable playback")
    }
}

// ── Output device callback (Arc) ──

internal fun PlayerRuntimeController.noteFormatsKnownOutputs(audioManager: android.media.AudioManager) {
    knownAudioOutputDeviceIds = runCatching {
        audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).map { it.id }.toSet()
    }.getOrDefault(emptySet())
}

internal fun PlayerRuntimeController.noteFormatsOutputsRemoved(devices: Array<out android.media.AudioDeviceInfo>) {
    knownAudioOutputDeviceIds = knownAudioOutputDeviceIds - devices.map { it.id }.toSet()
}

/**
 * Android answers every callback registration with an "added" event listing the outputs already connected. Each
 * new player registers again, so that echo is not a route change and must not reset what was learned about the
 * output during the same playback.
 */
internal fun PlayerRuntimeController.isFormatsRegistrationEcho(devices: Array<out android.media.AudioDeviceInfo>): Boolean {
    val addedOutputs = devices.filter { it.isSink }.map { it.id }
    val repeat = addsOnlyKnownOutputs(knownAudioOutputDeviceIds, addedOutputs)
    knownAudioOutputDeviceIds = knownAudioOutputDeviceIds + addedOutputs
    if (!repeat || !formatsActiveForCurrentPlayback) return false
    Log.d(
        PlayerRuntimeController.TAG,
        "Audio device added (count=${devices.size}, outputs already connected); output findings kept"
    )
    if (!isReleasingPlayer && _exoPlayer != null && currentAudioOutputRoute?.isBluetooth != true) {
        schedulePassthroughReturnCheck("player start")
    }
    return true
}

/** Input-only devices (a remote's microphone) are not an output change. */
internal fun PlayerRuntimeController.ignoresFormatsNonOutputEvent(
    reason: String,
    devices: Array<out android.media.AudioDeviceInfo>
): Boolean {
    if (!formatsActiveForCurrentPlayback) return false
    if (!SurroundRecheckGuard.eventHasOutput(devices.map { it.isSink })) {
        Log.d(PlayerRuntimeController.TAG, "Audio device $reason ignored (count=${devices.size}, no output device)")
        return true
    }
    noteOutputChangeForTunnelWatch()
    return false
}
