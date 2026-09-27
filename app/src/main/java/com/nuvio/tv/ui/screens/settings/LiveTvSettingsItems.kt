@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvPreferences

/** "Live TV" row: shows Live TV (IPTV channel lists) in the menu. Off by default. */
internal fun LazyListScope.liveTvSettingsItems(
    onItemFocused: () -> Unit = {},
) {
    item(key = "live_tv_enabled") {
        val context = LocalContext.current
        LiveTvPreferences.ensureLoaded(context)
        val checked by LiveTvPreferences.enabled.collectAsStateWithLifecycle()

        ToggleSettingsItem(
            icon = Icons.Default.LiveTv,
            title = stringResource(R.string.settings_live_tv_title),
            subtitle = stringResource(R.string.settings_live_tv_description),
            isChecked = checked,
            onCheckedChange = { LiveTvPreferences.setEnabled(context, it) },
            onFocused = onItemFocused,
        )
    }
}
