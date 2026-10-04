@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry
import com.nuvio.tv.reshaped.livetv.LiveTvRecorder
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.PlayerUiState
import com.nuvio.tv.ui.screens.player.isUsingMpvEngine
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/** Whether [url] is a live channel of Live TV, which can be recorded (a past programme or a recording can't). */
internal fun isRecordableLiveTv(url: String?): Boolean =
    LiveTvPlaybackRegistry.isLiveTv(url) && !LiveTvPlaybackRegistry.isCatchup(url)

/** Whether [url] is being recorded now, following the recorder. */
@Composable
internal fun rememberLiveTvRecordingOf(url: String?): Boolean {
    val recording by LiveTvRecorder.active.collectAsStateWithLifecycle()
    return url != null && recording?.playbackUrl == url
}

/**
 * The REC button in the player's controls (and a remote's REC key): starts recording the channel
 * playing, or saves the recording in progress.
 */
internal fun PlayerRuntimeController.toggleLiveTvRecording() {
    val url = currentStreamUrl
    if (LiveTvRecorder.isRecording(url)) {
        LiveTvRecorder.stop()
        return
    }
    if (!isRecordableLiveTv(url)) return
    val listUrl = LiveTvPlaybackRegistry.listUrlFor(url)
    val name = LiveTvRepository.uiState.value.channels.firstOrNull { it.streamUrl == listUrl }?.name ?: _uiState.value.title
    // mpv reads the stream itself, so nothing passes through the recorder.
    val message = when {
        isUsingMpvEngine() -> R.string.live_tv_recording_needs_exoplayer
        LiveTvRecorder.start(context, url, name) != null -> R.string.live_tv_recording_started
        else -> R.string.live_tv_recording_unavailable
    }
    Toast.makeText(context, context.getString(message, name), Toast.LENGTH_LONG).show()
}

/**
 * A recording keeps the channel it was started on: switching to another (or to a past programme)
 * or leaving the player saves it.
 */
@Composable
internal fun LiveTvRecordingFollow(state: LiveTvPlayerState, uiState: PlayerUiState) {
    LaunchedEffect(uiState.currentStreamUrl) { state.stopOtherRecording() }
    DisposableEffect(state) { onDispose { LiveTvRecorder.stop() } }
    // The rate the display is matched to live goes with the recording (see LiveTvRecorder.noteFrameRate).
    val recordingThis = rememberLiveTvRecordingOf(uiState.currentStreamUrl)
    LaunchedEffect(recordingThis, uiState.detectedFrameRate) {
        if (!recordingThis || uiState.detectedFrameRate <= 0f) return@LaunchedEffect
        val player = state.player
        LiveTvRecorder.noteFrameRate(uiState.detectedFrameRateRaw, uiState.detectedFrameRate, player.currentVideoWidth, player.currentVideoHeight)
    }
}

/** While recording: a blinking dot, the time recorded and the file's size, top right. */
@Composable
internal fun BoxScope.LiveTvRecordingBadge(showControls: Boolean) {
    val recording by LiveTvRecorder.active.collectAsStateWithLifecycle()
    val current = recording ?: return
    if (showControls) return
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis(), current) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val seconds = ((now - current.startedAtMs) / 1_000L).coerceAtLeast(0L)
    val size = remember(now) { Formatter.formatShortFileSize(context, LiveTvRecorder.bytesWritten()) }
    Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .zIndex(4f)
            .padding(top = 28.dp, end = 36.dp)
            .background(Color(0xE6121214), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Color(0xFFE50914).copy(alpha = if (seconds % 2L == 0L) 1f else 0.35f)),
        )
        Text(
            text = stringResource(R.string.live_tv_recording_badge, formatElapsed(seconds), size),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private fun formatElapsed(seconds: Long): String {
    val hours = seconds / 3_600L
    val minutes = seconds / 60L % 60L
    val secs = seconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%02d:%02d".format(minutes, secs)
}

/**
 * The player route for a recording: a file on this device, played and sought like a film, with
 * the display matched to the rate the channel had live.
 */
internal fun liveTvRecordingRoute(file: File, profileId: Int): String = Screen.Player.createRoute(
    streamUrl = Uri.fromFile(file).toString(),
    title = file.nameWithoutExtension,
    streamName = file.nameWithoutExtension,
    filename = file.name,
    profileId = profileId,
)

/** The recordings on this device, newest first: OK plays one, held OK and OK again deletes it. */
@Composable
internal fun LiveTvRecordingsDialog(onPlay: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val recording by LiveTvRecorder.active.collectAsStateWithLifecycle()
    var reload by remember { mutableIntStateOf(0) }
    val files by produceState<List<File>?>(null, reload) {
        value = withContext(Dispatchers.IO) { LiveTvRecorder.recordings(context) }
    }
    // A recording held OK on: OK now deletes it, moving away cancels.
    var deleting by remember { mutableStateOf<File?>(null) }
    val doneFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(files) {
        val list = files ?: return@LaunchedEffect
        repeat(5) {
            withFrameNanos { }
            val target = if (list.isEmpty()) doneFocus else firstFocus
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_tv_recordings),
        subtitle = stringResource(R.string.live_tv_recordings_description),
        width = 640.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = NuvioTheme.spacing.md,
    ) {
        val list = files
        if (list != null && list.isEmpty()) {
            Text(
                text = stringResource(R.string.live_tv_recordings_none),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
            )
        } else if (list != null) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(list, key = { it.path }) { file ->
                    val inProgress = recording?.file == file
                    LiveTvRecordingRow(
                        file = file,
                        inProgress = inProgress,
                        deleting = deleting == file,
                        onClick = {
                            when {
                                deleting == file -> {
                                    deleting = null
                                    if (LiveTvRecorder.delete(file)) reload++
                                }
                                !inProgress -> onPlay(file)
                            }
                        },
                        onLongClick = { if (!inProgress) deleting = file },
                        onFocusLost = { if (deleting == file) deleting = null },
                        modifier = if (file == list.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            LiveTvPillButton(
                text = stringResource(R.string.live_tv_done),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(doneFocus),
            )
        }
    }
}

@Composable
private fun LiveTvRecordingRow(
    file: File,
    inProgress: Boolean,
    deleting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocusLost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val details = remember(file, inProgress) {
        val date = Date(file.lastModified())
        listOf(
            DateFormat.getMediumDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date),
            Formatter.formatShortFileSize(context, file.length()),
        ).joinToString("  ·  ")
    }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) onFocusLost()
        },
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.TextPrimary.copy(alpha = 0.06f),
            focusedContainerColor = if (deleting) Color(0xFFE50914) else NuvioTheme.colors.TextPrimary,
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = file.nameWithoutExtension,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    deleting && focused -> Color.White
                    focused -> Color.Black
                    else -> NuvioTheme.colors.TextPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    deleting -> stringResource(R.string.live_tv_playlist_delete_confirm)
                    inProgress -> stringResource(R.string.live_tv_recording_in_progress)
                    else -> details
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    deleting && focused -> Color.White.copy(alpha = 0.85f)
                    focused -> Color.Black.copy(alpha = 0.65f)
                    else -> NuvioTheme.colors.TextSecondary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
