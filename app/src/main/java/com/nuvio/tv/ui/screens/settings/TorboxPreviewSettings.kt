package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.seekpreview.torbox.TorboxPreviewLibrary
import com.nuvio.tv.ui.screens.player.seekpreview.torbox.torboxPreviewKeys
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun LazyListScope.torboxPreviewSettingsItems() {
    item(key = "torbox_preview_library") { TorboxPreviewLibraryRow() }
}

@Composable
private fun TorboxPreviewLibraryRow() {
    val context = LocalContext.current
    val keys = remember(context) { torboxPreviewKeys(context) }
    val key by keys.collectAsStateWithLifecycle(initialValue = "")
    val scope = rememberCoroutineScope()
    var busy by remember(key) { mutableStateOf(false) }
    var result by remember(key) { mutableStateOf<TorboxPreviewLibrary.Result?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(key) { onDispose { job?.cancel() } }
    val message = when {
        key.isBlank() -> stringResource(R.string.torbox_preview_connect)
        busy -> stringResource(R.string.torbox_preview_adding)
        result == TorboxPreviewLibrary.Result.READY -> stringResource(R.string.torbox_preview_ready)
        result == TorboxPreviewLibrary.Result.UNAVAILABLE -> stringResource(R.string.torbox_preview_unavailable)
        result == TorboxPreviewLibrary.Result.RATE_LIMITED -> stringResource(R.string.torbox_preview_limited)
        result == TorboxPreviewLibrary.Result.FAILED -> stringResource(R.string.torbox_preview_failed)
        else -> stringResource(R.string.torbox_preview_description)
    }
    SettingsActionRow(
        title = stringResource(R.string.torbox_preview_add),
        subtitle = message,
        enabled = key.isNotBlank() && !busy,
        onClick = {
            if (busy || key.isBlank()) return@SettingsActionRow
            busy = true
            result = null
            job = scope.launch {
                try { result = TorboxPreviewLibrary.add(key) }
                finally { busy = false }
            }
        }
    )
}
