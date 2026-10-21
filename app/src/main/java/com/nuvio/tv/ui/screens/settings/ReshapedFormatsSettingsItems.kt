@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.ReshapedFormatSettingsStore
import com.nuvio.tv.data.local.SurroundChannelTarget
import com.nuvio.tv.data.local.SurroundFormatMode
import com.nuvio.tv.ui.screens.player.AudioRejectionReverifier
import com.nuvio.tv.ui.screens.player.PlayerTunnelAvSyncPolicy
import com.nuvio.tv.ui.screens.player.iec.PlatformIecAudioTrackFactory

private val LEARNED_GROUP_LABELS = listOf(
    "AC3" to "AC-3", "EAC3" to "E-AC-3", "TRUEHD" to "TrueHD", "DTS" to "DTS", "DTS_HD" to "DTS-HD"
)

/** Format labels of the learned refusals ("route::GROUP" entries). */
internal fun learnedRefusalLabels(entries: Set<String>): String {
    val groups = entries.mapNotNull { it.substringAfterLast("::", "").takeIf { group -> group.isNotEmpty() } }.toSet()
    return LEARNED_GROUP_LABELS.filter { (group, _) -> group in groups }.joinToString(", ") { (_, label) -> label }
}

/**
 * "Sound and picture formats" rows: Nuvio Arc's format handling. State lives in ReshapedFormatSettingsStore
 * (device-wide), never in PlayerSettingsDataStore.
 */
internal fun LazyListScope.reshapedFormatsSettingsItems(
    playerSettings: PlayerSettings,
    onItemFocused: () -> Unit = {},
) {
    val builtInPlayer = playerSettings.playerPreference != PlayerPreference.EXTERNAL
    val deviceOnly = playerSettings.decoderPriority == 0

    item(key = "reshaped_formats_header") {
        SettingsSectionLabel(
            text = stringResource(R.string.reshaped_formats_header),
            description = stringResource(R.string.reshaped_formats_header_description),
        )
    }
    item(key = "reshaped_formats_enabled") {
        val context = LocalContext.current
        val settings by ReshapedFormatSettingsStore.settings(context).collectAsStateWithLifecycle()
        ToggleSettingsItem(
            icon = Icons.Default.SurroundSound,
            title = stringResource(R.string.reshaped_formats_enabled),
            subtitle = stringResource(R.string.reshaped_formats_enabled_sub),
            isChecked = settings.enabled,
            onCheckedChange = { ReshapedFormatSettingsStore.setEnabled(context, it) },
            onFocused = onItemFocused,
            enabled = builtInPlayer,
        )
    }
    item(key = "reshaped_formats_surround_mode") {
        val context = LocalContext.current
        val settings by ReshapedFormatSettingsStore.settings(context).collectAsStateWithLifecycle()
        val auto = settings.surroundFormatMode == SurroundFormatMode.AUTO
        SettingsActionRow(
            title = stringResource(R.string.reshaped_formats_surround_mode),
            subtitle = stringResource(
                when {
                    deviceOnly -> R.string.reshaped_formats_device_only_note
                    auto -> R.string.reshaped_formats_surround_mode_auto_desc
                    else -> R.string.reshaped_formats_surround_mode_manual_desc
                }
            ),
            value = stringResource(
                if (auto) R.string.reshaped_formats_surround_mode_auto else R.string.reshaped_formats_surround_mode_manual
            ),
            onClick = {
                ReshapedFormatSettingsStore.setSurroundFormatMode(
                    context,
                    if (auto) SurroundFormatMode.MANUAL else SurroundFormatMode.AUTO
                )
            },
            onFocused = onItemFocused,
            enabled = builtInPlayer && settings.enabled,
            leadingIcon = Icons.Default.SurroundSound,
        )
    }
    item(key = "reshaped_formats_surround_rows") {
        val context = LocalContext.current
        val settings by ReshapedFormatSettingsStore.settings(context).collectAsStateWithLifecycle()
        val rowsEnabled = builtInPlayer && settings.enabled && !deviceOnly
        val store = ReshapedFormatSettingsStore
        androidx.compose.foundation.layout.Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                com.nuvio.tv.ui.theme.NuvioTheme.spacing.md
            )
        ) {
            if (settings.surroundFormatMode == SurroundFormatMode.AUTO) {
                FormatToggle(R.string.reshaped_formats_tv_arc_soundbar, R.string.reshaped_formats_tv_arc_soundbar_sub,
                    settings.surroundTvArcSoundbar, rowsEnabled, onItemFocused) { store.setSurroundTvArcSoundbar(context, it) }
            } else {
                FormatToggle(R.string.reshaped_formats_allow_ac3, R.string.reshaped_formats_allow_ac3_sub,
                    settings.allowAc3Passthrough || deviceOnly, rowsEnabled, onItemFocused) { store.setAllowAc3Passthrough(context, it) }
                FormatToggle(R.string.reshaped_formats_allow_eac3, R.string.reshaped_formats_allow_eac3_sub,
                    settings.allowEac3Passthrough || deviceOnly, rowsEnabled, onItemFocused) { store.setAllowEac3Passthrough(context, it) }
                FormatToggle(R.string.reshaped_formats_allow_truehd, R.string.reshaped_formats_allow_truehd_sub,
                    settings.allowTruehdPassthrough || deviceOnly, rowsEnabled, onItemFocused) { store.setAllowTruehdPassthrough(context, it) }
                FormatToggle(R.string.reshaped_formats_allow_dts, R.string.reshaped_formats_allow_dts_sub,
                    settings.allowDtsPassthrough || deviceOnly, rowsEnabled, onItemFocused) { store.setAllowDtsPassthrough(context, it) }
                FormatToggle(R.string.reshaped_formats_allow_dtshd, R.string.reshaped_formats_allow_dtshd_sub,
                    settings.allowDtshdPassthrough || deviceOnly, rowsEnabled, onItemFocused) { store.setAllowDtshdPassthrough(context, it) }
                val transcode = settings.deniedCodecHandling == DeniedCodecHandling.TRANSCODE_AC3
                FormatToggle(R.string.reshaped_formats_transcode_denied, R.string.reshaped_formats_transcode_denied_sub,
                    transcode && !deviceOnly, rowsEnabled, onItemFocused) {
                    store.setDeniedCodecHandling(
                        context,
                        if (it) DeniedCodecHandling.TRANSCODE_AC3 else DeniedCodecHandling.DECODE_PCM
                    )
                }
            }
        }
    }
    item(key = "reshaped_formats_channel_target") {
        val context = LocalContext.current
        val settings by ReshapedFormatSettingsStore.settings(context).collectAsStateWithLifecycle()
        val label = when (settings.surroundChannelTarget) {
            SurroundChannelTarget.AUTO -> R.string.reshaped_formats_channel_auto
            SurroundChannelTarget.CH_2_0 -> R.string.reshaped_formats_channel_2_0
            SurroundChannelTarget.CH_5_1 -> R.string.reshaped_formats_channel_5_1
            SurroundChannelTarget.CH_7_1 -> R.string.reshaped_formats_channel_7_1
        }
        SettingsActionRow(
            title = stringResource(R.string.reshaped_formats_channel_target),
            subtitle = stringResource(R.string.reshaped_formats_channel_target_sub),
            value = stringResource(label),
            onClick = {
                val all = SurroundChannelTarget.entries
                ReshapedFormatSettingsStore.setSurroundChannelTarget(
                    context,
                    all[(all.indexOf(settings.surroundChannelTarget) + 1) % all.size]
                )
            },
            onFocused = onItemFocused,
            enabled = builtInPlayer && settings.enabled && !deviceOnly,
        )
    }
    item(key = "reshaped_formats_system_passthrough") {
        val context = LocalContext.current
        val settings by ReshapedFormatSettingsStore.settings(context).collectAsStateWithLifecycle()
        FormatToggle(R.string.reshaped_formats_use_system_passthrough, R.string.reshaped_formats_use_system_passthrough_sub,
            settings.useSystemPassthrough, builtInPlayer && settings.enabled, onItemFocused) {
            ReshapedFormatSettingsStore.setUseSystemPassthrough(context, it)
        }
    }
    item(key = "reshaped_formats_inject_hdr10") {
        val context = LocalContext.current
        val settings by ReshapedFormatSettingsStore.settings(context).collectAsStateWithLifecycle()
        ToggleSettingsItem(
            icon = Icons.Default.HdrOn,
            title = stringResource(R.string.reshaped_formats_inject_hdr10),
            subtitle = stringResource(R.string.reshaped_formats_inject_hdr10_sub),
            isChecked = settings.injectHdr10MetadataOnStrip,
            onCheckedChange = { ReshapedFormatSettingsStore.setInjectHdr10MetadataOnStrip(context, it) },
            onFocused = onItemFocused,
            enabled = builtInPlayer,
        )
    }
    item(key = "reshaped_formats_iec_probe") {
        val context = LocalContext.current
        val settings by ReshapedFormatSettingsStore.settings(context).collectAsStateWithLifecycle()
        val checking = stringResource(R.string.reshaped_formats_iec_probe_checking)
        val available = stringResource(R.string.reshaped_formats_iec_probe_available)
        val unavailable = stringResource(R.string.reshaped_formats_iec_probe_unavailable)
        SettingsActionRow(
            title = stringResource(R.string.reshaped_formats_reset_iec_probe),
            subtitle = stringResource(R.string.reshaped_formats_reset_iec_probe_sub),
            onClick = {
                Toast.makeText(context, checking, Toast.LENGTH_SHORT).show()
                val appContext = context.applicationContext
                PlatformIecAudioTrackFactory.resetIec61937Probe { usable ->
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(appContext, if (usable) available else unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onFocused = onItemFocused,
            enabled = builtInPlayer && settings.iecEngineEnabled,
        )
    }
    item(key = "reshaped_formats_forget_refusals") {
        val context = LocalContext.current
        val settings by ReshapedFormatSettingsStore.settings(context).collectAsStateWithLifecycle()
        val learned = learnedRefusalLabels(settings.audioRejectionsConfirmed)
        val done = stringResource(R.string.reshaped_formats_forget_rejections_done)
        SettingsActionRow(
            title = stringResource(R.string.reshaped_formats_forget_rejections),
            subtitle = stringResource(R.string.reshaped_formats_forget_rejections_sub),
            value = learned.ifEmpty { stringResource(R.string.reshaped_formats_forget_rejections_none) },
            onClick = {
                ReshapedFormatSettingsStore.clearLearnedAudioRejections(context)
                AudioRejectionReverifier.ledger.invalidate()
                Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
            },
            onFocused = onItemFocused,
            enabled = builtInPlayer && settings.enabled && learned.isNotEmpty(),
        )
    }
    item(key = "reshaped_formats_forget_tunnel_stalls") {
        val context = LocalContext.current
        val settings by ReshapedFormatSettingsStore.settings(context).collectAsStateWithLifecycle()
        var cleared by remember { mutableStateOf(false) }
        val classes = if (cleared) settings.tunnelDeadAudioClasses
            else settings.tunnelDeadAudioClasses + PlayerTunnelAvSyncPolicy.deadAudioClasses
        val labels = PlayerTunnelAvSyncPolicy.memoLabels(classes)
        val done = stringResource(R.string.reshaped_formats_forget_tunnel_stalls_done)
        SettingsActionRow(
            title = stringResource(R.string.reshaped_formats_forget_tunnel_stalls),
            subtitle = if (labels.isEmpty()) stringResource(R.string.reshaped_formats_forget_tunnel_stalls_none)
                else stringResource(R.string.reshaped_formats_forget_tunnel_stalls_sub, labels.joinToString(", ")),
            onClick = {
                cleared = true
                PlayerTunnelAvSyncPolicy.resetMemo()
                ReshapedFormatSettingsStore.clearTunnelDeadAudioClasses(context)
                Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
            },
            onFocused = onItemFocused,
            enabled = builtInPlayer && labels.isNotEmpty(),
        )
    }
}

@androidx.compose.runtime.Composable
private fun FormatToggle(
    titleRes: Int,
    subtitleRes: Int,
    checked: Boolean,
    enabled: Boolean,
    onFocused: () -> Unit,
    onChange: (Boolean) -> Unit,
) {
    ToggleSettingsItem(
        icon = Icons.Default.SurroundSound,
        title = stringResource(titleRes),
        subtitle = stringResource(subtitleRes),
        isChecked = checked,
        onCheckedChange = onChange,
        onFocused = onFocused,
        enabled = enabled,
    )
}
