@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.tv.ui.screens.player.seekpreview.torbox.TorboxPreviewLibrary
import com.nuvio.tv.ui.screens.player.seekpreview.torbox.torboxPreviewKeys
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.ui.screens.player.seekpreview.engine.PreviewPreferences
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalSeekPreviewSettings

/** One switch for shared movie previews and the existing on-device fallback. */
internal fun LazyListScope.seekPreviewSettingsItems(
    onItemFocused: () -> Unit = {},
) {
    item(key = "seek_preview_torbox_account") {
        TorboxPreviewConnectionItem(onItemFocused)
    }
    item(key = "seek_preview_local") {
        val context = LocalContext.current
        val checked by LocalSeekPreviewSettings.enabled(context).collectAsStateWithLifecycle()
        val keys = remember(context) { torboxPreviewKeys(context) }
        val key by keys.collectAsStateWithLifecycle(initialValue = "")
        LaunchedEffect(checked, key) {
            if (checked && key.isNotBlank()) TorboxPreviewLibrary.ensure(key, context.cacheDir.resolve("torbox-sprites-v1"))
        }

        ToggleSettingsItem(
            icon = Icons.Default.Image,
            title = stringResource(R.string.torbox_preview_title),
            subtitle = stringResource(R.string.torbox_preview_description),
            isChecked = checked,
            onCheckedChange = { LocalSeekPreviewSettings.setEnabled(context, it) },
            onFocused = onItemFocused,
        )
    }
    item(key = "seek_preview_copies") {
        val context = LocalContext.current
        val options by PreviewPreferences.options(context).collectAsStateWithLifecycle()
        ToggleSettingsItem(icon = Icons.Default.Image, title = stringResource(R.string.settings_seek_preview_copies),
            subtitle = stringResource(R.string.settings_seek_preview_copies_description), isChecked = options.copies,
            onCheckedChange = { PreviewPreferences.set(context, options.copy(copies = it)) }, onFocused = onItemFocused)
    }
    item(key = "seek_preview_next_episode") {
        val context = LocalContext.current
        val options by PreviewPreferences.options(context).collectAsStateWithLifecycle()
        ToggleSettingsItem(icon = Icons.Default.Image, title = stringResource(R.string.settings_seek_preview_next),
            subtitle = stringResource(R.string.settings_seek_preview_next_description), isChecked = options.nextEpisode,
            onCheckedChange = { PreviewPreferences.set(context, options.copy(nextEpisode = it)) }, onFocused = onItemFocused)
    }

}

@Composable
private fun TorboxPreviewConnectionItem(onFocused: () -> Unit) {
    val context = LocalContext.current
    val keys = remember(context) { torboxPreviewKeys(context) }
    val key by keys.collectAsStateWithLifecycle(initialValue = "")
    var showDialog by remember { mutableStateOf(false) }
    val connected = key.isNotBlank()

    NavigationSettingsItem(
        icon = Icons.Default.AccountCircle,
        title = stringResource(
            if (connected) R.string.torbox_preview_connected_title else R.string.torbox_preview_connect_title
        ),
        subtitle = stringResource(
            if (connected) R.string.torbox_preview_connected_description else R.string.torbox_preview_connect_description
        ),
        onClick = { showDialog = true },
        onFocused = onFocused,
    )

    if (showDialog) {
        val viewModel: DebridSettingsViewModel = hiltViewModel()
        DebridDeviceAuthDialog(
            provider = DebridProviders.Torbox,
            currentValue = key,
            viewModel = viewModel,
            onConnected = { viewModel.saveProviderCredential(DebridProviders.TORBOX_ID, it) },
            onDisconnect = { viewModel.saveProviderCredential(DebridProviders.TORBOX_ID, "") },
            onDismiss = { showDialog = false },
        )
    }
}
