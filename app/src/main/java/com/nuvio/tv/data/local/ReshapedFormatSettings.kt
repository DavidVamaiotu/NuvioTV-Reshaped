package com.nuvio.tv.data.local

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Nuvio Reshaped: sound and picture format handling ported from Nuvio Arc (IEC passthrough for
 * TrueHD / DTS-HD, Auto surround per output, learned receiver refusals, tunnel stall memo, HDR10
 * metadata on Dolby Vision strip, HDR overlay dim). Kept out of PlayerSettingsDataStore so upstream
 * NuvioTV merges stay clean. Device-wide: it describes the box and what is plugged into it.
 */

enum class SurroundFormatMode {
    AUTO,
    MANUAL;

    companion object {
        fun fromStoredString(value: String?): SurroundFormatMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

enum class SurroundChannelTarget {
    AUTO,
    CH_2_0,
    CH_5_1,
    CH_7_1;

    companion object {
        fun fromStoredString(value: String?): SurroundChannelTarget =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

enum class DeniedCodecHandling {
    DECODE_PCM,
    TRANSCODE_AC3;

    companion object {
        fun fromStoredString(value: String?): DeniedCodecHandling =
            entries.firstOrNull { it.name == value } ?: DECODE_PCM
    }
}

internal const val TUNNEL_STALL_MAX_AGE_MS: Long = 14L * 24 * 60 * 60 * 1000

/** A saved tunnel stall list lasts 14 days and ends with the next app update. */
internal fun tunnelStallsExpired(
    savedAtMs: Long?,
    savedAppVersion: Int?,
    nowMs: Long,
    currentAppVersion: Int,
): Boolean {
    if (savedAtMs == null || savedAppVersion == null) return true
    if (savedAppVersion != currentAppVersion) return true
    return kotlin.math.abs(nowMs - savedAtMs) > TUNNEL_STALL_MAX_AGE_MS
}

data class ReshapedFormatSettings(
    /** Master switch. Off by default; plays sound and picture exactly as NuvioTV does until enabled. */
    val enabled: Boolean = false,
    /** Sends TrueHD and DTS-HD through Android's own passthrough instead of the IEC engine. */
    val useSystemPassthrough: Boolean = false,
    val surroundFormatMode: SurroundFormatMode = SurroundFormatMode.AUTO,
    val surroundChannelTarget: SurroundChannelTarget = SurroundChannelTarget.AUTO,
    val surroundTvArcSoundbar: Boolean = false,
    val allowAc3Passthrough: Boolean = true,
    val allowEac3Passthrough: Boolean = true,
    val allowTruehdPassthrough: Boolean = true,
    val allowDtsPassthrough: Boolean = true,
    val allowDtshdPassthrough: Boolean = true,
    val deniedCodecHandling: DeniedCodecHandling = DeniedCodecHandling.DECODE_PCM,
    val audioRejectionsConfirmed: Set<String> = emptySet(),
    val tunnelDeadAudioClasses: Set<String> = emptySet(),
    val tunnelDeadAudioSignature: String? = null,
    val injectHdr10MetadataOnStrip: Boolean = false,
    val dimHdrOverlays: Boolean = false
) {
    /** Arc's IEC engine for TrueHD / DTS-HD runs only with the master switch on. */
    val iecEngineEnabled: Boolean get() = enabled && !useSystemPassthrough
}

object ReshapedFormatSettingsStore {
    private const val PREFS = "nuvio_reshaped_formats"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USE_SYSTEM_PASSTHROUGH = "use_system_passthrough"
    private const val KEY_SURROUND_MODE = "surround_format_mode"
    private const val KEY_CHANNEL_TARGET = "surround_channel_target"
    private const val KEY_TV_ARC_SOUNDBAR = "surround_tv_arc_soundbar"
    private const val KEY_ALLOW_AC3 = "allow_ac3_passthrough"
    private const val KEY_ALLOW_EAC3 = "allow_eac3_passthrough"
    private const val KEY_ALLOW_TRUEHD = "allow_truehd_passthrough"
    private const val KEY_ALLOW_DTS = "allow_dts_passthrough"
    private const val KEY_ALLOW_DTSHD = "allow_dtshd_passthrough"
    private const val KEY_DENIED_HANDLING = "denied_codec_handling"
    private const val KEY_REJECTIONS_CONFIRMED = "audio_rejections_confirmed"
    private const val KEY_REFUSAL_EVIDENCE = "audio_refusal_evidence"
    private const val KEY_REJECTION_RESET_TOKEN = "audio_rejection_reset_token"
    private const val KEY_TUNNEL_CLASSES = "tunnel_dead_audio_classes"
    private const val KEY_TUNNEL_SIGNATURE = "tunnel_dead_audio_signature"
    private const val KEY_TUNNEL_SAVED_AT = "tunnel_dead_audio_saved_at_ms"
    private const val KEY_TUNNEL_APP_VERSION = "tunnel_dead_audio_app_version"
    private const val KEY_TUNNEL_FRAME_REPORTERS = "tunnel_frame_report_decoders"
    private const val KEY_INJECT_HDR10 = "inject_hdr10_metadata_on_strip"
    private const val KEY_DIM_HDR_OVERLAYS = "dim_hdr_overlays"

    private val _settings = MutableStateFlow(ReshapedFormatSettings())

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val created = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = created
            _settings.value = read(created)
            return created
        }
    }

    fun settings(context: Context): StateFlow<ReshapedFormatSettings> {
        prefs(context)
        return _settings.asStateFlow()
    }

    fun current(context: Context): ReshapedFormatSettings {
        prefs(context)
        return _settings.value
    }

    internal fun read(p: SharedPreferences): ReshapedFormatSettings {
        val tunnelExpired = tunnelClassesExpired(p)
        return ReshapedFormatSettings(
            enabled = p.getBoolean(KEY_ENABLED, false),
            useSystemPassthrough = p.getBoolean(KEY_USE_SYSTEM_PASSTHROUGH, false),
            surroundFormatMode = SurroundFormatMode.fromStoredString(p.getString(KEY_SURROUND_MODE, null)),
            surroundChannelTarget = SurroundChannelTarget.fromStoredString(p.getString(KEY_CHANNEL_TARGET, null)),
            surroundTvArcSoundbar = p.getBoolean(KEY_TV_ARC_SOUNDBAR, false),
            allowAc3Passthrough = p.getBoolean(KEY_ALLOW_AC3, true),
            allowEac3Passthrough = p.getBoolean(KEY_ALLOW_EAC3, true),
            allowTruehdPassthrough = p.getBoolean(KEY_ALLOW_TRUEHD, true),
            allowDtsPassthrough = p.getBoolean(KEY_ALLOW_DTS, true),
            allowDtshdPassthrough = p.getBoolean(KEY_ALLOW_DTSHD, true),
            deniedCodecHandling = DeniedCodecHandling.fromStoredString(p.getString(KEY_DENIED_HANDLING, null)),
            audioRejectionsConfirmed = p.getStringSet(KEY_REJECTIONS_CONFIRMED, null)?.toSet() ?: emptySet(),
            tunnelDeadAudioClasses = if (tunnelExpired) emptySet() else
                p.getStringSet(KEY_TUNNEL_CLASSES, null)?.toSet() ?: emptySet(),
            tunnelDeadAudioSignature = if (tunnelExpired) null else p.getString(KEY_TUNNEL_SIGNATURE, null),
            injectHdr10MetadataOnStrip = p.getBoolean(KEY_INJECT_HDR10, false),
            dimHdrOverlays = p.getBoolean(KEY_DIM_HDR_OVERLAYS, false)
        )
    }

    private inline fun edit(context: Context, block: SharedPreferences.Editor.(SharedPreferences) -> Unit) {
        val p = prefs(context)
        synchronized(this) {
            p.edit().apply { block(p) }.apply()
            _settings.value = read(p)
        }
    }

    fun setEnabled(context: Context, value: Boolean) = edit(context) { putBoolean(KEY_ENABLED, value) }
    fun setUseSystemPassthrough(context: Context, value: Boolean) =
        edit(context) { putBoolean(KEY_USE_SYSTEM_PASSTHROUGH, value) }
    fun setSurroundFormatMode(context: Context, mode: SurroundFormatMode) =
        edit(context) { putString(KEY_SURROUND_MODE, mode.name) }
    fun setSurroundChannelTarget(context: Context, target: SurroundChannelTarget) =
        edit(context) { putString(KEY_CHANNEL_TARGET, target.name) }
    fun setSurroundTvArcSoundbar(context: Context, value: Boolean) =
        edit(context) { putBoolean(KEY_TV_ARC_SOUNDBAR, value) }
    fun setAllowAc3Passthrough(context: Context, value: Boolean) = edit(context) { putBoolean(KEY_ALLOW_AC3, value) }
    fun setAllowEac3Passthrough(context: Context, value: Boolean) = edit(context) { putBoolean(KEY_ALLOW_EAC3, value) }
    fun setAllowTruehdPassthrough(context: Context, value: Boolean) =
        edit(context) { putBoolean(KEY_ALLOW_TRUEHD, value) }
    fun setAllowDtsPassthrough(context: Context, value: Boolean) = edit(context) { putBoolean(KEY_ALLOW_DTS, value) }
    fun setAllowDtshdPassthrough(context: Context, value: Boolean) =
        edit(context) { putBoolean(KEY_ALLOW_DTSHD, value) }
    fun setDeniedCodecHandling(context: Context, handling: DeniedCodecHandling) =
        edit(context) { putString(KEY_DENIED_HANDLING, handling.name) }
    fun setInjectHdr10MetadataOnStrip(context: Context, value: Boolean) =
        edit(context) { putBoolean(KEY_INJECT_HDR10, value) }
    fun setDimHdrOverlays(context: Context, value: Boolean) = edit(context) { putBoolean(KEY_DIM_HDR_OVERLAYS, value) }

    // ── Learned receiver refusals ("route::GROUP") ──

    fun recordAudioRejection(context: Context, routeKey: String, formatGroup: String) = edit(context) { p ->
        val confirmed = p.getStringSet(KEY_REJECTIONS_CONFIRMED, null)?.toSet() ?: emptySet()
        putStringSet(KEY_REJECTIONS_CONFIRMED, confirmed + "$routeKey::$formatGroup")
    }

    fun clearAudioRejection(context: Context, entry: String) = edit(context) { p ->
        val confirmed = p.getStringSet(KEY_REJECTIONS_CONFIRMED, null)?.toSet() ?: emptySet()
        if (entry in confirmed) putStringSet(KEY_REJECTIONS_CONFIRMED, confirmed - entry)
    }

    fun clearLearnedAudioRejections(context: Context) = edit(context) { remove(KEY_REJECTIONS_CONFIRMED) }

    fun audioRefusalEvidence(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_REFUSAL_EVIDENCE, null)?.toSet() ?: emptySet()

    fun saveAudioRefusalEvidence(context: Context, records: Set<String>) = edit(context) {
        if (records.isEmpty()) remove(KEY_REFUSAL_EVIDENCE) else putStringSet(KEY_REFUSAL_EVIDENCE, records)
    }

    /** Clears the learned rejections once per token; the token comes from a device setting. */
    fun consumeAudioRejectionReset(context: Context, token: String): Boolean {
        if (token.isBlank() || token == "0" || token.length > 64) return false
        if (prefs(context).getString(KEY_REJECTION_RESET_TOKEN, null) == token) return false
        edit(context) {
            remove(KEY_REJECTIONS_CONFIRMED)
            putString(KEY_REJECTION_RESET_TOKEN, token)
        }
        return true
    }

    // ── Tunnel stall memo: sound types whose tunnelled clock froze on this output chain ──

    fun recordTunnelDeadAudioClass(context: Context, audioClass: String, signature: String) = edit(context) { p ->
        val keep = p.getString(KEY_TUNNEL_SIGNATURE, null) == signature && !tunnelClassesExpired(p)
        val current = if (keep) p.getStringSet(KEY_TUNNEL_CLASSES, null)?.toSet() ?: emptySet() else emptySet()
        putStringSet(KEY_TUNNEL_CLASSES, current + audioClass)
        putString(KEY_TUNNEL_SIGNATURE, signature)
        putLong(KEY_TUNNEL_SAVED_AT, System.currentTimeMillis())
        putInt(KEY_TUNNEL_APP_VERSION, BuildConfig.VERSION_CODE)
    }

    fun clearTunnelDeadAudioClasses(context: Context) = edit(context) { removeTunnelClasses() }

    /** Removes a saved stall list that has run out and returns the sound types it held. */
    fun expireTunnelDeadAudioClasses(context: Context): Set<String> {
        val p = prefs(context)
        if (!tunnelClassesExpired(p)) return emptySet()
        val expired = p.getStringSet(KEY_TUNNEL_CLASSES, null)?.toSet() ?: emptySet()
        edit(context) { removeTunnelClasses() }
        return expired
    }

    /** Decoders ("fingerprint|decoder") seen to report rendered frames while tunnelled. */
    fun tunnelFrameReportDecoders(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_TUNNEL_FRAME_REPORTERS, null)?.toSet() ?: emptySet()

    fun addTunnelFrameReportDecoder(context: Context, key: String) = edit(context) { p ->
        val current = p.getStringSet(KEY_TUNNEL_FRAME_REPORTERS, null)?.toSet() ?: emptySet()
        if (key !in current) putStringSet(KEY_TUNNEL_FRAME_REPORTERS, current + key)
    }

    private fun SharedPreferences.Editor.removeTunnelClasses() {
        remove(KEY_TUNNEL_CLASSES)
        remove(KEY_TUNNEL_SIGNATURE)
        remove(KEY_TUNNEL_SAVED_AT)
        remove(KEY_TUNNEL_APP_VERSION)
    }

    private fun tunnelClassesExpired(p: SharedPreferences): Boolean =
        !p.getStringSet(KEY_TUNNEL_CLASSES, null).isNullOrEmpty() && tunnelStallsExpired(
            savedAtMs = p.getLong(KEY_TUNNEL_SAVED_AT, -1L).takeIf { it >= 0L },
            savedAppVersion = p.getInt(KEY_TUNNEL_APP_VERSION, -1).takeIf { it >= 0 },
            nowMs = System.currentTimeMillis(),
            currentAppVersion = BuildConfig.VERSION_CODE,
        )
}
