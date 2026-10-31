@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The Nuvio Reshaped settings category: everything the fork adds on top of NuvioTV, in one place,
 * split into groups that open in place like Nuvio's Playback page. A closed group composes nothing.
 */
@Composable
internal fun NuvioReshapedSettingsContent(
    initialFocusRequester: FocusRequester?,
    viewModel: PlaybackSettingsViewModel = hiltViewModel(),
) {
    val playerSettings by viewModel.playerSettings.collectAsStateWithLifecycle(initialValue = PlayerSettings())
    val builtInPlayer = playerSettings.playerPreference != PlayerPreference.EXTERNAL
    var openGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
    ) {
        item(key = "nuvio_reshaped_header") {
            SettingsDetailHeader(
                title = stringResource(R.string.settings_nuvio_reshaped),
                subtitle = stringResource(R.string.settings_nuvio_reshaped_description),
            )
        }

        fun group(
            id: String,
            @StringRes title: Int,
            @StringRes description: Int,
            icon: ImageVector,
            focusRequester: FocusRequester? = null,
            rows: LazyListScope.() -> Unit,
        ) {
            val open = id in openGroups
            item(key = "reshaped_group_$id") {
                SettingsActionRow(
                    title = stringResource(title),
                    subtitle = stringResource(description),
                    value = stringResource(if (open) R.string.layout_open else R.string.layout_closed),
                    onClick = { openGroups = if (open) openGroups - id else openGroups + id },
                    leadingIcon = icon,
                    trailingIcon = if (open) Icons.Default.ExpandMore else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
                )
            }
            if (open) IndentedRows(this).rows()
        }

        group("subtitles", R.string.reshaped_group_subtitles, R.string.reshaped_group_subtitles_description,
            Icons.Default.Subtitles, focusRequester = initialFocusRequester) {
            autoSyncSettingsItems(enabled = builtInPlayer)
            subtitleFontSettingsItems()
            autoSyncBubbleSettingsItems(enabled = builtInPlayer) // Nuvio RS hook: AutoSync bubble
        }
        group("seek_previews", R.string.reshaped_group_seek_previews, R.string.reshaped_group_seek_previews_description,
            Icons.Default.Slideshow) {
            seekPreviewSettingsItems()
            seekrKeySettingsItems()
        }
        group("formats", R.string.reshaped_formats_header, R.string.reshaped_group_formats_description,
            Icons.Default.SurroundSound) {
            reshapedFormatsSettingsItems(playerSettings) // Nuvio RS: Arc sound and picture formats
        }
        group("streams", R.string.reshaped_group_streams, R.string.reshaped_group_streams_description,
            Icons.Default.Speed) {
            connectionSpeedSettingsItems()
            autoSourceRecoverySettingsItems(playerSettings)
        }
        group("home", R.string.reshaped_group_home, R.string.reshaped_group_home_description,
            Icons.Default.Home) {
            netflixUiSettingsItems()
            pillNavSettingsItems()
        }
        group("live_tv", R.string.settings_live_tv_title, R.string.reshaped_group_live_tv_description,
            Icons.Default.LiveTv) {
            liveTvSettingsItems()
        }
        group("sync", R.string.reshaped_sync_sign_in_title, R.string.reshaped_group_sync_description,
            Icons.Default.CloudSync) {
            reshapedSyncSettingsItems()
        }
        group("diagnostics", R.string.reshaped_group_diagnostics, R.string.reshaped_group_diagnostics_description,
            Icons.Default.BugReport) {
            autoSyncDiagnosticsSettingsItems(enabled = builtInPlayer)
            audioSyncDiagnosticsSettingsItems(enabled = builtInPlayer)
        }
    }
}

/** Indents the rows of an open group under its header, as Nuvio's collapsible sections do. */
private class IndentedRows(private val scope: LazyListScope) : LazyListScope by scope {
    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        scope.item(key, contentType) {
            val itemScope = this
            Box(Modifier.padding(start = NuvioTheme.spacing.lg)) { itemScope.content() }
        }
    }
}
