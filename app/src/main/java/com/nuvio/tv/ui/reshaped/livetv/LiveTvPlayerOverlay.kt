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
import androidx.compose.foundation.background
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

    /** Whether the player is showing a Live TV channel (read on each key; a memory lookup). */
    fun isActive(): Boolean = LiveTvPlaybackRegistry.isLiveTv(controller.currentStreamUrl)

    internal fun syncCurrent() {
        if (currentListUrl == null && isActive()) {
            currentListUrl = LiveTvPlaybackRegistry.listUrlFor(controller.currentStreamUrl)
            bannerKey++
        }
    }

    /** The list zapping moves through: the one the channel was picked from, else every shown channel. */
    internal fun zapList(): List<LiveTvChannel> = zapTarget().first

    /** The list zapping moves through and the category it is (null for a search). */
    private fun zapTarget(): Pair<List<LiveTvChannel>, String?> {
        val picked = LiveTvRepository.zapList
        if (picked.any { it.streamUrl == currentListUrl }) return picked to LiveTvRepository.zapFolderKey
        // Worked out off the main thread whenever the list or what is hidden changes.
        return LiveTvRepository.uiState.value.shownChannels to FILTER_ALL
    }

    /** The category the panel's list is, so the categories open on it; null for a search. */
    var zappedFolderKey by mutableStateOf<String?>(null)
        private set

    /** Called first by the player's key handler; true when the key was Live TV's. */
    fun onPreviewKey(event: KeyEvent, uiState: PlayerUiState): Boolean {
        if (!isActive()) return false
        val nuvioOverlayOpen = uiState.showEpisodesPanel || uiState.showSourcesPanel ||
            uiState.showAudioOverlay || uiState.showSubtitleOverlay || uiState.showSubtitleStylePanel ||
            uiState.showSpeedDialog || uiState.showSubtitleDelayOverlay || uiState.showSubtitleTimingDialog ||
            uiState.showMoreDialog || uiState.showStreamInfoOverlay
        val down = event.action == KeyEvent.ACTION_DOWN
        if (panelOpen && foldersOpen) {
            return when (event.keyCode) {
                // Back to the channels, which show the category last focused.
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!down) foldersOpen = false
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> true
                else -> false // the column handles the rest
            }
        }
        if (panelOpen) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!down) closePanel()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (down && event.repeatCount == 0) foldersOpen = true
                    true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> true
                else -> false // the list handles the rest
            }
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (nuvioOverlayOpen && uiState.error == null) return false
                if (down) zap(if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) -1 else 1)
                return true
            }
        }
        // ▲▼◀ are Live TV's only on the bare picture: never over controls, panels or errors.
        if (uiState.showControls || nuvioOverlayOpen || uiState.error != null || uiState.showPauseOverlay) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) zap(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down && event.repeatCount == 0) openPanel()
                true // also swallows the release, which would commit a seek
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // A live channel has nothing to seek to: ▶ opens the controls (audio, subtitles).
                if (down && event.repeatCount == 0) controller.onEvent(PlayerEvent.OnToggleControls)
                true
            }
            else -> false
        }
    }

    private fun zap(step: Int) {
        val next = LiveTvRepository.neighbour(zapList(), currentListUrl, step) ?: return
        switchTo(next)
    }

    private fun openPanel() {
        folderJob?.cancel()
        panelFolderKey = null
        val (channels, folderKey) = zapTarget()
        panelChannels = channels
        zappedFolderKey = folderKey
        foldersOpen = false
        panelOpen = true
    }

    /** Shows a category's channels in the panel; zapping follows once one of them is picked. */
    internal fun showFolder(key: String) {
        if (key == panelFolderKey) return
        panelFolderKey = key
        folderJob?.cancel()
        folderJob = scope.launch {
            val state = LiveTvRepository.uiState.value
            panelChannels = withContext(Dispatchers.Default) {
                filterChannels(state.channels, state.favoriteUrls, state.hiddenGroups, state.hiddenChannelKeys, key)
            }
        }
    }

    /** A channel picked from the panel: zapping then stays in the list it was picked from. */
    internal fun pickFromPanel(channel: LiveTvChannel) {
        panelFolderKey?.let { LiveTvRepository.setZapList(panelChannels, it) }
        switchTo(channel)
    }

    internal fun switchTo(channel: LiveTvChannel) {
        if (channel.streamUrl == currentListUrl) {
            closePanel()
            return
        }
        currentListUrl = channel.streamUrl
        bannerKey++
        closePanel()
        // Quick presses land on the last channel only.
        switchJob?.cancel()
        switchJob = scope.launch {
            delay(ZAP_SETTLE_MS)
            val playback = LiveTvRepository.prepareForPlayback(channel)
            LiveTvPlaybackRegistry.register(
                PlayerMediaSourceFactory.normalizePlaybackRequest(playback.streamUrl, playback.headers).url,
                listUrl = channel.streamUrl,
            )
            controller.switchToSourceStream(channel.toStream(playback))
            controller._uiState.update { it.copy(title = channel.name) }
        }
    }

    internal fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        foldersOpen = false
        runCatching { containerFocusRequester.requestFocus() }
    }

    private fun LiveTvChannel.toStream(playback: LiveTvChannel) = Stream(
        name = name,
        title = name,
        description = group.takeIf(String::isNotBlank),
        url = playback.streamUrl,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = ProxyHeaders(request = playback.headers, response = null),
        ),
        addonName = LIVE_TV_ADDON_NAME,
        addonLogo = null,
    )

    private companion object {
        const val ZAP_SETTLE_MS = 350L
    }
}

@Composable
internal fun rememberLiveTvPlayer(controller: PlayerRuntimeController, containerFocusRequester: FocusRequester): LiveTvPlayerState {
    val scope = rememberCoroutineScope()
    return remember(controller) { LiveTvPlayerState(controller, containerFocusRequester, scope) }
}

/** The banner and channel panel, drawn over the player. */
@Composable
internal fun LiveTvPlayerOverlay(state: LiveTvPlayerState, uiState: PlayerUiState) {
    if (!state.isActive()) return
    Box(modifier = Modifier.fillMaxSize().zIndex(3f)) {
        LiveTvPlayerOverlayContent(state, uiState)
    }
}

@Composable
private fun BoxScope.LiveTvPlayerOverlayContent(state: LiveTvPlayerState, uiState: PlayerUiState) {
    LaunchedEffect(Unit) { state.syncCurrent() }
    val liveState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    val clock = rememberLiveTvMinuteClock()
    // Numbered within the list being zapped (a category keeps its own 1, 2, 3...).
    val zapList = remember(state.currentListUrl, liveState.shownChannels) { state.zapList() }
    val currentIndex = remember(state.currentListUrl, zapList) { zapList.indexOfFirst { it.streamUrl == state.currentListUrl } }
    val current = zapList.getOrNull(currentIndex)

    var bannerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.bannerKey) {
        if (state.bannerKey == 0) return@LaunchedEffect
        bannerVisible = true
        delay(BANNER_MS)
        bannerVisible = false
    }
    AnimatedVisibility(
        visible = bannerVisible && current != null && !uiState.showControls && !state.panelOpen,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.TopStart).zIndex(3f),
    ) {
        current?.let { channel ->
            LiveTvBanner(
                channel = channel,
                programme = channel.tvgId?.let(liveState.currentProgrammes::get),
                number = currentIndex + 1,
                clock = clock,
            )
        }
    }

    AnimatedVisibility(
        visible = state.panelOpen,
        enter = slideInHorizontally { -it } + fadeIn(),
        exit = slideOutHorizontally { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.CenterStart).zIndex(3f),
    ) {
        LiveTvChannelPanel(state, liveState.currentProgrammes, clock)
    }
}

@Composable
private fun LiveTvBanner(channel: LiveTvChannel, programme: LiveTvProgramme?, number: Int, clock: State<Long>) {
    Row(
        modifier = Modifier
            .padding(start = 48.dp, top = 40.dp)
            .widthIn(max = 640.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number > 0) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.padding(end = 16.dp),
            )
        }
        LiveTvLogo(url = channel.logoUrl, name = channel.name, width = 88.dp, height = 54.dp)
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (programme != null) {
                Text(
                    text = programme.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveTvProgressBar(
                        programme = programme,
                        clock = clock,
                        fill = Color.White,
                        track = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier.width(220.dp),
                    )
                    Text(
                        text = "${programme.timeLabel}  ·  ${liveTvTimeLeft(programme, clock)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.live_tv_player_hint),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
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
                    programme = channel.tvgId?.let(programmes::get),
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
            LiveTvLogo(url = channel.logoUrl, name = channel.name, width = 60.dp, height = 38.dp)
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
