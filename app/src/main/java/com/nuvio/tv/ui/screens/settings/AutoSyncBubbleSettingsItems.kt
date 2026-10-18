@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.autosync.bubble.AutoSyncBubbleToasts

/** "Bubble notifications": AutoSync's messages in the frosted bubble instead of plain toasts. On by default. */
internal fun LazyListScope.autoSyncBubbleSettingsItems(enabled: Boolean) {
    item(key = "autosync_bubble_toast") {
        val context = LocalContext.current
        AutoSyncBubbleToasts.ensureLoaded(context)
        val checked by AutoSyncBubbleToasts.enabled.collectAsStateWithLifecycle()
        ToggleSettingsItem(
            icon = Icons.Default.ChatBubble,
            title = stringResource(R.string.settings_autosync_bubble_toast),
            subtitle = stringResource(R.string.settings_autosync_bubble_toast_description),
            isChecked = checked,
            onCheckedChange = { AutoSyncBubbleToasts.setEnabled(context, it) },
            enabled = enabled,
        )
    }
    item(key = "autosync_failures_only") {
        val context = LocalContext.current
        AutoSyncBubbleToasts.ensureLoaded(context)
        val checked by AutoSyncBubbleToasts.failuresOnly.collectAsStateWithLifecycle()
        ToggleSettingsItem(
            icon = Icons.Default.ChatBubble,
            title = stringResource(R.string.settings_autosync_failures_only),
            subtitle = stringResource(R.string.settings_autosync_failures_only_description),
            isChecked = checked,
            onCheckedChange = { AutoSyncBubbleToasts.setFailuresOnly(context, it) },
            enabled = enabled,
        )
    }
}
