@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
package com.nuvio.tv.ui.screens.player.seekpreview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.player.seekpreview.engine.SeekThumbnails

/** Preparation is optional and can always be skipped with OK or Back. */
@Composable
internal fun BoxScope.SeekPreviewPreparationLayer(onDismissed: () -> Unit, visibleAllowed: Boolean = true, onError: () -> Unit = {}) {
    val state by SeekThumbnails.prepareUi.collectAsStateWithLifecycle()
    val visible = visibleAllowed && state !is SeekThumbnails.PrepareUi.Hidden
    LaunchedEffect(visibleAllowed) { if (!visibleAllowed) onError() }
    var wasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (wasVisible && !visible && visibleAllowed) onDismissed()
        wasVisible = visible
    }
    if (!visible) return
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)).zIndex(4f), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(stringResource(R.string.seek_preview_preparing))
            when (val current = state) {
                is SeekThumbnails.PrepareUi.Generating -> Text(stringResource(R.string.seek_preview_preparing_progress, current.done, current.total))
                is SeekThumbnails.PrepareUi.Ready -> Text(stringResource(R.string.seek_preview_preparing_ready))
                else -> Unit
            }
            Button(onClick = SeekThumbnails::startWatchingNow, modifier = Modifier.focusRequester(focus)) {
                Text(stringResource(R.string.seek_preview_start_now))
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
