package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.streams.ranking.StreamRegexPreset

@Composable
internal fun streamRegexPresets(): List<Pair<String, String>> = StreamRegexPreset.entries.map { preset ->
    val label = when (preset) {
        StreamRegexPreset.ANY_1080P -> R.string.autoplay_regex_preset_any_1080p_plus
        StreamRegexPreset.QUALITY_4K_REMUX -> R.string.autoplay_regex_preset_4k_remux
        StreamRegexPreset.FULL_HD_STANDARD -> R.string.autoplay_regex_preset_1080p_standard
        StreamRegexPreset.HD_SMALLER -> R.string.autoplay_regex_preset_720p_smaller
        StreamRegexPreset.WEB_SOURCES -> R.string.autoplay_regex_preset_web_sources
        StreamRegexPreset.BLURAY_QUALITY -> R.string.autoplay_regex_preset_bluray_quality
        StreamRegexPreset.HEVC_X265 -> R.string.autoplay_regex_preset_hevc_x265
        StreamRegexPreset.AVC_X264 -> R.string.autoplay_regex_preset_avc_x264
        StreamRegexPreset.HDR_DV -> R.string.autoplay_regex_preset_hdr_dolby_vision
        StreamRegexPreset.DOLBY_ATMOS_DTS -> R.string.autoplay_regex_preset_dolby_atmos_dts
        StreamRegexPreset.ENGLISH -> R.string.autoplay_regex_preset_english
        StreamRegexPreset.NO_CAM_TS -> R.string.autoplay_regex_preset_no_cam_ts
        StreamRegexPreset.NO_REMUX_HDR -> R.string.autoplay_regex_preset_no_remux_hdr
    }
    stringResource(label) to preset.pattern
}
