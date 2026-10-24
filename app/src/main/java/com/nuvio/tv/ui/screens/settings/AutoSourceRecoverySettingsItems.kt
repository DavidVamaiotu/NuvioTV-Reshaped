@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.player.recovery.AutoSourceRecoverySettings
import com.nuvio.tv.core.player.recovery.shouldRecoverAutoSource
import com.nuvio.tv.data.local.PlayerSettings

internal fun LazyListScope.autoSourceRecoverySettingsItems(settings: PlayerSettings) {
    item(key = "auto_source_recovery") {
        val context = LocalContext.current
        val checked by AutoSourceRecoverySettings.enabled(context).collectAsStateWithLifecycle()
        val available = shouldRecoverAutoSource(true, settings.streamAutoPlayMode, settings.playerPreference)
        ToggleSettingsItem(
            icon = Icons.Default.Refresh,
            title = stringResource(R.string.settings_auto_source_recovery),
            subtitle = stringResource(if (available) R.string.settings_auto_source_recovery_description
                else R.string.settings_auto_source_recovery_unavailable),
            isChecked = checked,
            onCheckedChange = { AutoSourceRecoverySettings.setEnabled(context, it) },
            enabled = available,
        )
    }
}
