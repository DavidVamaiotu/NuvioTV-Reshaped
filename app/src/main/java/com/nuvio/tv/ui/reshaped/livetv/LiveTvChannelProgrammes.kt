@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvCatchupLinks
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.reshaped.livetv.LiveTvUiState
import kotlinx.coroutines.delay

/**
 * One channel's programmes, top to bottom (▶ on a channel in the player's channel list): the
 * kept guide as it is (no copy), opening on what is on now. OK plays the channel, or an ended
 * programme again where the provider keeps it; held OK on the one on now starts it over.
 */
@Composable
internal fun LiveTvProgrammeColumn(
    state: LiveTvPlayerState,
    channel: LiveTvChannel,
    liveState: LiveTvUiState,
    clock: State<Long>,
) {
    // Read again only when the guide is read again.
    val programmes = remember(channel.guideKey, liveState.guideVersion) { LiveTvRepository.schedule(channel.guideKey) }
    val startIndex = remember(programmes) {
        val now = LiveTvClock.nowEpochMs()
        programmes.indexOfFirst { it.stopEpochMs > now }.let { if (it < 0) programmes.lastIndex.coerceAtLeast(0) else it }
    }
    // A couple of what came before stay in view above what is on now.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 2).coerceAtLeast(0))
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The row must be composed before it can take focus.
        repeat(10) {
            if (runCatching { startFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(460.dp)
            .padding(start = 32.dp, end = 40.dp, top = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveTvLogo(url = liveState.logoFor(channel), name = channel.name, width = 64.dp, height = 40.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = liveTvGroupLabel(channel.group, liveState.groupNames),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = stringResource(R.string.live_tv_player_programmes_hint),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        if (programmes.isEmpty()) {
            // Focusable, so the remote stays on the panel: OK plays the channel, ◀ and Back go back.
            Card(
                onClick = { state.pickFromPanel(channel) },
                modifier = Modifier.fillMaxWidth().focusRequester(startFocus),
                shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White.copy(alpha = 0.14f)),
                scale = CardDefaults.scale(focusedScale = 1f),
            ) {
                Text(
                    text = stringResource(R.string.live_tv_info_no_guide),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
            return@Column
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            itemsIndexed(programmes, contentType = { _, _ -> "programme" }) { index, programme ->
                // Read here, so the minute tick recomposes only the rows in view.
                val now = clock.value
                val live = programme.startEpochMs <= now && now < programme.stopEpochMs
                val ended = programme.stopEpochMs <= now
                val replayable = ended && LiveTvCatchupLinks.isPlayable(channel.catchup, programme, now)
                val startOver = live && LiveTvCatchupLinks.isPlayable(channel.catchup, programme, now)
                ProgrammeRow(
                    programme = programme,
                    live = live,
                    dimmed = ended && !replayable,
                    replayable = replayable,
                    clock = clock,
                    onClick = { state.playProgramme(channel, programme) },
                    onLongClick = if (startOver) ({ state.playCatchup(channel, programme) }) else null,
                    modifier = if (index == startIndex) Modifier.focusRequester(startFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ProgrammeRow(
    programme: LiveTvProgramme,
    live: Boolean,
    dimmed: Boolean,
    replayable: Boolean,
    clock: State<Long>,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val text = if (focused) Color.Black else Color.White
    val alpha = if (dimmed && !focused) 0.5f else 1f
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = if (live) Color.White.copy(alpha = 0.10f) else Color.Transparent,
            focusedContainerColor = Color.White,
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(spring<IntSize>(stiffness = Spring.StiffnessMediumLow))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = LiveTvClock.formatClock(programme.startEpochMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = text.copy(alpha = (if (focused) 0.65f else 0.6f) * alpha),
                    maxLines = 1,
                    modifier = Modifier.width(TIME_WIDTH),
                )
                Text(
                    text = programme.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                    color = text.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (replayable) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = stringResource(R.string.live_tv_catchup),
                        tint = text.copy(alpha = 0.55f),
                        modifier = Modifier.padding(start = 8.dp).size(16.dp),
                    )
                }
                if (live) {
                    Text(
                        text = stringResource(R.string.live_tv_live_badge),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = if (focused) Color.White else Color.Black,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(LiveTvPillShape)
                            .background(if (focused) Color.Black else Color.White.copy(alpha = 0.9f))
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            if (live) {
                Row(modifier = Modifier.padding(start = TIME_WIDTH, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveTvProgressBar(
                        programme = programme,
                        clock = clock,
                        fill = text,
                        track = text.copy(alpha = 0.15f),
                        modifier = Modifier.width(140.dp),
                    )
                    Text(
                        text = liveTvTimeLeft(programme, clock),
                        style = MaterialTheme.typography.labelSmall,
                        color = text.copy(alpha = 0.55f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            // The description, where the guide kept one, only on the focused row.
            if (focused) {
                programme.description?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Black.copy(alpha = 0.65f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = TIME_WIDTH, top = 4.dp),
                    )
                }
            }
        }
    }
}

private val TIME_WIDTH = 96.dp
