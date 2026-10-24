@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.reshaped.netflix.NetflixUiPreferences

/** "Netflix-style Home" row: a purely visual Home look. Off by default. */
internal fun LazyListScope.netflixUiSettingsItems(
    onItemFocused: () -> Unit = {},
) {
    item(key = "netflix_ui_enabled") {
        val context = LocalContext.current
        NetflixUiPreferences.ensureLoaded(context)
        val checked by NetflixUiPreferences.enabled.collectAsStateWithLifecycle()

        ToggleSettingsItem(
            icon = Icons.Default.Movie,
            title = stringResource(R.string.settings_netflix_ui_title),
            subtitle = stringResource(R.string.settings_netflix_ui_description),
            isChecked = checked,
            onCheckedChange = { NetflixUiPreferences.setEnabled(context, it) },
            onFocused = onItemFocused,
        )
    }
}
