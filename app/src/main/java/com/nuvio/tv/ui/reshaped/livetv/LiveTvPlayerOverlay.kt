@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.reshaped.livetv.LIVE_TV_UNGROUPED
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.reshaped.livetv.LiveTvUiState
import com.nuvio.tv.ui.screens.player.PlayerEvent
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.PlayerUiState
import com.nuvio.tv.ui.screens.player.onEvent
import com.nuvio.tv.ui.screens.player.switchToSourceStream
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live TV inside Nuvio's player: CH+/CH- (and ▲▼ while the controls are hidden) switch channel,
 * ◀ opens the channel list and ◀ again its categories, ▶ the player's controls, and a banner shows what is on after each switch. Everything is
 * inert unless the player is playing a Live TV channel.
 */
@Stable
internal class LiveTvPlayerState(
    private val controller: PlayerRuntimeController,
    private val containerFocusRequester: FocusRequester,
    private val scope: CoroutineScope,
) {
    var panelOpen by mutableStateOf(false)
        private set
    /** The categories column beside the channel list (◀ from the list). */
    var foldersOpen by mutableStateOf(false)
        private set
    /** The category the panel lists, or null for the list being zapped. */
    var panelFolderKey by mutableStateOf<String?>(null)
        private set
    /** The channels the panel lists. */
    var panelChannels by mutableStateOf<List<LiveTvChannel>>(emptyList())
        private set
    private var folderJob: Job? = null
    /** The list entry of the channel playing now (a Stalker link differs from its list URL). */
    var currentListUrl by mutableStateOf<String?>(null)
        private set
    /** Bumped on each switch so the banner shows again. */
    var bannerKey by mutableIntStateOf(0)
        private set

    private var switchJob: Job? = null

    /**
 * The Now/Next card OK shows: the channel with its picture and sound (resolution, frame rate,
 * codecs), what is on with how far it has got, and what follows.
 */
@Composable
private fun LiveTvInfoCard(
    channel: LiveTvChannel,
    logo: String?,
    details: LiveTvStreamDetails,
    now: LiveTvProgramme?,
    number: Int,
    clock: State<Long>,
) {
    // Read once per minute tick: the kept guide is a map lookup.
    val next = remember(channel.guideKey, now, clock.value / 60_000L) { LiveTvRepository.nextProgramme(channel.guideKey) }
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .padding(start = 48.dp, end = 48.dp, bottom = 36.dp)
            .widthIn(max = 880.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.66f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveTvLogo(url = logo, name = channel.name, width = 104.dp, height = 64.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (number > 0) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(end = 10.dp),
                    )
                }
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                details.labels.forEach { label ->
                    LiveTvDetailChip(label, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (now == null) {
                Text(
                    text = stringResource(R.string.live_tv_info_no_guide),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.live_tv_info_now).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black,
                        modifier = Modifier
                            .clip(LiveTvPillShape)
                            .background(Color.White.copy(alpha = 0.92f))
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                    Text(
                        text = now.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveTvProgressBar(
                        programme = now,
                        clock = clock,
                        fill = Color.White,
                        track = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier.width(240.dp),
                    )
                    Text(
                        text = "${LiveTvClock.formatSpan(now)}  ·  ${liveTvTimeLeft(now, clock)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                next?.let {
                    Text(
                        text = stringResource(R.string.live_tv_info_next, LiveTvClock.formatClock(it.startEpochMs)) + "  " + it.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.live_tv_info_hint),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.42f),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** A quiet outlined tag: "1080p", "50 fps". */
@Composable
private fun LiveTvDetailChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.82f),
        maxLines = 1,
        modifier = modifier
            .border(1.dp, Color.White.copy(alpha = 0.24f), LiveTvPillShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun LiveTvChannelPanel(state: LiveTvPlayerState, programmes: Map<String, LiveTvProgramme>, clock: State<Long>) {
    val liveState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.92f),
                    0.85f to Color.Black.copy(alpha = 0.82f),
                    1f to Color.Black.copy(alpha = 0f),
                ),
            ),
    ) {
        AnimatedVisibility(
            visible = state.foldersOpen,
            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
        ) {
            LiveTvFolderColumn(state, liveState)
        }
        LiveTvChannelColumn(state, programmes, liveState, clock)
    }
}

/** The categories: focusing one lists its channels beside it (after a short rest, so passing over is cheap). */
@Composable
private fun LiveTvFolderColumn(state: LiveTvPlayerState, liveState: LiveTvUiState) {
    val allLabel = stringResource(R.string.live_tv_all_channels)
    val favoritesLabel = stringResource(R.string.live_tv_favorites)
    val uncategorisedLabel = liveTvGroupLabel(LIVE_TV_UNGROUPED)
    val folders = remember(liveState.sources, liveState.groups, liveState.hiddenGroups, allLabel, favoritesLabel, uncategorisedLabel) {
        buildList {
            add(FILTER_ALL to allLabel)
            add(FILTER_FAVORITES to favoritesLabel)
            if (liveState.sources.size > 1) liveState.sources.forEach { add(FILTER_SOURCE_PREFIX + it.id to it.label) }
            liveState.visibleGroups.forEach { add(it to if (it == LIVE_TV_UNGROUPED) uncategorisedLabel else it) }
        }
    }
    // The panel opens on the zapped list's category (All channels for a search or one gone since).
    val zappedFolder = state.zappedFolderKey?.takeIf { key -> folders.any { it.first == key } } ?: FILTER_ALL
    val startKey = state.panelFolderKey ?: zappedFolder
    val startIndex = folders.indexOfFirst { it.first == startKey }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { startFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    Column(modifier = Modifier.fillMaxHeight().width(250.dp).padding(start = 32.dp, top = 32.dp, end = 8.dp)) {
        Text(
            text = stringResource(R.string.live_tv_categories),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            itemsIndexed(folders, key = { _, folder -> folder.first }) { index, (key, label) ->
                FolderRow(
                    label = label,
                    selected = key == (state.panelFolderKey ?: startKey),
                    onFocused = { state.showFolder(key) },
                    onClick = { state.showFolder(key) },
                    modifier = if (index == startIndex) Modifier.focusRequester(startFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    label: String,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused && !selected) {
            delay(250) // passing over a category does not re-filter
            onFocused()
        }
    }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = Color.White,
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) Color.Black else if (selected) Color.White else Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun LiveTvChannelColumn(
    state: LiveTvPlayerState,
    programmes: Map<String, LiveTvProgramme>,
    liveState: LiveTvUiState,
    clock: State<Long>,
) {
    val channels = state.panelChannels
    val startIndex = remember(channels) { channels.indexOfFirst { it.streamUrl == state.currentListUrl }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    // A new category starts at its top (or at the channel playing, when it has it).
    LaunchedEffect(channels) {
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == startIndex }) {
            listState.scrollToItem((startIndex - 3).coerceAtLeast(0))
        }
    }
    val currentFocus = remember { FocusRequester() }
    // On opening, and when the categories close, focus goes to the channel playing (or the first).
    LaunchedEffect(state.foldersOpen) {
        if (state.foldersOpen) return@LaunchedEffect
        // The row must be composed before it can take focus.
        repeat(10) {
            if (runCatching { currentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    val folderKey = state.panelFolderKey
    val folderLabel = when {
        folderKey == null -> null
        folderKey == FILTER_ALL -> stringResource(R.string.live_tv_all_channels)
        folderKey == FILTER_FAVORITES -> stringResource(R.string.live_tv_favorites)
        folderKey.startsWith(FILTER_SOURCE_PREFIX) ->
            liveState.sources.firstOrNull { FILTER_SOURCE_PREFIX + it.id == folderKey }?.label
        else -> folderKey
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(460.dp)
            .padding(start = if (state.foldersOpen) 8.dp else 32.dp, end = 40.dp, top = 32.dp),
    ) {
        Text(
            text = folderLabel ?: stringResource(R.string.live_tv_player_channels),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.live_tv_player_categories_hint),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = if (state.foldersOpen) 0f else 0.45f),
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            itemsIndexed(channels, key = { _, channel -> channel.id }, contentType = { _, _ -> "channel" }) { index, channel ->
                PanelRow(
                    channel = channel,
                    logo = liveState.logoFor(channel),
                    programme = programmes[channel.guideKey],
                    playing = channel.streamUrl == state.currentListUrl,
                    clock = clock,
                    onClick = { state.pickFromPanel(channel) },
                    modifier = if (index == startIndex) Modifier.focusRequester(currentFocus) else Modifier,
                )
            }
            if (channels.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(
                            if (folderKey == FILTER_FAVORITES) R.string.live_tv_no_favorites else R.string.live_tv_no_channels_found,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelRow(
    channel: LiveTvChannel,
    logo: String?,
    programme: LiveTvProgramme?,
    playing: Boolean,
    clock: State<Long>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick,
        onLongClick = { LiveTvRepository.toggleFavorite(channel) },
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveTvLogo(url = logo, name = channel.name, width = 60.dp, height = 38.dp)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (focused) Color.Black else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                programme?.let {
                    Text(
                        text = it.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (focused) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(modifier = Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        LiveTvProgressBar(
                            programme = it,
                            clock = clock,
                            fill = if (focused) Color.Black else Color.White,
                            track = if (focused) Color.Black.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.15f),
                            modifier = Modifier.width(120.dp),
                        )
                        Text(
                            text = liveTvTimeLeft(it, clock),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (focused) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.5f),
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            if (playing) {
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (focused) Color.Black else Color(0xFFE50914)),
                )
            }
        }
    }
}

private const val BANNER_MS = 4_000L
