package com.nuvio.tv.reshaped.sync

import android.content.Context
import com.nuvio.tv.core.connection.ConnectionSpeedEstimator
import com.nuvio.tv.reshaped.livetv.LiveTvPreferences
import com.nuvio.tv.ui.reshaped.pillnav.PillNavPreferences
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences
import com.nuvio.tv.ui.screens.player.autosync.bubble.AutoSyncBubbleToasts
import com.nuvio.tv.ui.screens.player.seekpreview.SeekrKeyPreferences
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalSeekPreviewSettings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The Reshaped settings that sync. "settings/shared" holds the few the phone app has too;
 * "settings/tv" syncs between TVs only. Settings tuned to one device stay on it: buffer sizes,
 * channel previews, volume boost, debug logs, the subtitle font file and connection speed history.
 */
internal object ReshapedSyncedSettings {
    const val SHARED = "settings/shared"
    const val TV = "settings/tv"

    private class Setting(
        val section: String,
        val key: String,
        val read: (Context) -> JsonPrimitive,
        val write: (Context, JsonPrimitive) -> Unit,
    )

    private fun bool(section: String, key: String, read: (Context) -> Boolean, write: (Context, Boolean) -> Unit) =
        Setting(section, key, { JsonPrimitive(read(it)) }, { context, value -> value.booleanOrNull?.let { write(context, it) } })

    private val settings = listOf(
        bool(SHARED, "live_tv", { LiveTvPreferences.ensureLoaded(it); LiveTvPreferences.enabled.value }, LiveTvPreferences::setEnabled),
        bool(SHARED, "autosync_bubble", { AutoSyncBubbleToasts.ensureLoaded(it); AutoSyncBubbleToasts.enabled.value }, AutoSyncBubbleToasts::setEnabled),
        Setting(
            SHARED, "autosync_tolerance_ms",
            read = { AutoSyncPreferences.ensureLoaded(it); JsonPrimitive(AutoSyncPreferences.syncToleranceMs.value) },
            write = { context, value ->
                value.intOrNull?.takeIf { it in AutoSyncPreferences.syncToleranceOptionsMs }
                    ?.let { AutoSyncPreferences.setSyncToleranceMs(context, it) }
            },
        ),
        bool(TV, "live_tv_show_favorites", { LiveTvPreferences.ensureLoaded(it); LiveTvPreferences.showFavorites.value }, LiveTvPreferences::setShowFavorites),
        bool(TV, "live_tv_show_all", { LiveTvPreferences.ensureLoaded(it); LiveTvPreferences.showAll.value }, LiveTvPreferences::setShowAll),
        bool(TV, "live_tv_prefer_hls", { LiveTvPreferences.ensureLoaded(it); LiveTvPreferences.preferHls.value }, LiveTvPreferences::setPreferHls),
        Setting(
            TV, "live_tv_guide_refresh_hours",
            read = { LiveTvPreferences.ensureLoaded(it); JsonPrimitive(LiveTvPreferences.guideRefreshHours.value) },
            write = { context, value -> value.intOrNull?.let { LiveTvPreferences.setGuideRefreshHours(context, it) } },
        ),
        bool(TV, "autosync", { AutoSyncPreferences.ensureLoaded(it); AutoSyncPreferences.enabled.value }, AutoSyncPreferences::setEnabled),
        bool(TV, "seek_previews", { LocalSeekPreviewSettings.enabled(it).value }, LocalSeekPreviewSettings::setEnabled),
        bool(TV, "pill_nav", { PillNavPreferences.ensureLoaded(it); PillNavPreferences.enabled.value }, PillNavPreferences::setEnabled),
        bool(TV, "connection_fit", { ConnectionSpeedEstimator.ensureLoaded(it); ConnectionSpeedEstimator.enabled.value }, ConnectionSpeedEstimator::setEnabled),
        Setting(
            TV, "seekr_key",
            read = { SeekrKeyPreferences.ensureLoaded(it); JsonPrimitive(SeekrKeyPreferences.userKey.value) },
            write = { context, value -> value.contentOrNull?.let { SeekrKeyPreferences.setUserKey(context, it) } },
        ),
    )

    /** This device's values, by section. */
    fun current(context: Context): Map<String, Map<String, JsonElement>> =
        settings.groupBy(Setting::section).mapValues { (_, list) -> list.associate { it.key to it.read(context) } }

    /** Sets what [merged] has that differs from [current]. */
    fun apply(context: Context, current: Map<String, Map<String, JsonElement>>, merged: SyncSections) {
        settings.forEach { setting ->
            val value = SyncDoc.values(merged, setting.section)[setting.key] as? JsonPrimitive ?: return@forEach
            if (current[setting.section]?.get(setting.key) != value) setting.write(context, value)
        }
    }
}
