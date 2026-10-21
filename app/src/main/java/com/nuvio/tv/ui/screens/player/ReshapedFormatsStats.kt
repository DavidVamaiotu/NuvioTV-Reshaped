package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.DolbyVisionConversionStats
import com.nuvio.tv.core.player.DoviBridge
import java.util.Locale

/**
 * Nuvio RS: Arc's "Audio" and "DV" rows for the stats overlay. Read on the main thread (controller state).
 * The DV text follows Arc's PlaybackStatsDv.profileRowText without the Amlogic native FEL route.
 */
internal fun PlayerRuntimeController.formatsDebugStats(): List<DebugStat> {
    if (!formatSettings().enabled) return emptyList()
    val rows = ArrayList<DebugStat>(2)
    currentAudioPathDescription?.takeIf { it.isNotBlank() }?.let { rows += DebugStat("audio", it) }
    val diagnostics = lastPlaybackDiagnosticsForReport
    diagnostics.dvSourceProfile?.takeIf { it.isNotBlank() }?.let { sourceProfile ->
        // The first-frame snapshot can race the converter's first RPU; the live counters are authoritative.
        val converting = DoviBridge.getConversionSuccessCount() > 0L ||
            DolbyVisionConversionStats.getCodecStringRewriteCount() > 0L ||
            diagnostics.dv7DoviSignalRewrites > 0 || diagnostics.dv7DoviSuccess > 0
        rows += DebugStat(
            "dv",
            dvProfileRowText(
                sourceProfile = sourceProfile,
                modeEffective = diagnostics.dv7ModeEffective,
                sourceCodecs = currentVideoTrackCodecs ?: _exoPlayer?.videoFormat?.codecs,
                converting = converting
            )
        )
    }
    return rows
}

/** Level digits of a Dolby Vision codecs string ("dvhe.07.06" -> 6), or null. */
internal fun dvLevelFromCodecs(codecs: String?): Int? {
    val c = codecs?.lowercase(Locale.ROOT) ?: return null
    val m = Regex("(?:^|,)\\s*(?:dvhe|dvh1|dvav|dva1|dav1)\\.(\\d+)\\.(\\d+)").find(c) ?: return null
    return m.groupValues[2].toIntOrNull()
}

/** "Profile 7.6 → 8.1" on the libdovi conversion path, "Profile 8 · native" for single-layer sources. */
internal fun dvProfileRowText(
    sourceProfile: String,
    modeEffective: String?,
    sourceCodecs: String?,
    converting: Boolean
): String {
    val profile = sourceProfile.trim().toIntOrNull()
    val name = buildString {
        append("Profile ").append(profile ?: sourceProfile.trim())
        if (profile == 7) dvLevelFromCodecs(sourceCodecs)?.let { append('.').append(it) }
    }
    if (profile != null && profile != 7) return "$name · native"
    return when (modeEffective) {
        "DV81_LIBDOVI" -> if (converting) "$name → 8.1" else "$name → 8.1 (not converting)"
        "HDR10_BASE_LAYER" -> "$name → HDR10 base layer"
        "STRIP_DV" -> "$name → HDR10 (RPU stripped)"
        "OFF" -> "$name · MediaCodec"
        else -> name
    }
}
