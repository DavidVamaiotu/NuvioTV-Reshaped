@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRecentChannel
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.reshaped.livetv.LiveTvSource
import com.nuvio.tv.reshaped.livetv.rememberLiveTvPreviewsEnabled
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The filter shown in the category column. */
internal sealed interface LiveTvFilter {
    data object All : LiveTvFilter
    data object Favorites : LiveTvFilter
    data class Source(val id: String) : LiveTvFilter
    data class Group(val name: String) : LiveTvFilter
}

internal const val FILTER_ALL = "\u0000all"
internal const val FILTER_FAVORITES = "\u0000favorites"
internal const val FILTER_SOURCE_PREFIX = "\u0000source:"

/**
 * The channels a filter key shows: hidden categories and hidden channels leave everything but
 * favorites, and a search matches names. Slow for big lists: call off the main thread.
 */
internal fun filterChannels(
    channels: List<LiveTvChannel>,
    favorites: Set<String>,
    hidden: Set<String>,
    hiddenChannels: Set<Long>,
    key: String,
    query: String = "",
): List<LiveTvChannel> {
    val filter = filterFor(key)
    val needle = query.trim()
    return channels.filter { channel ->
        when (filter) {
            LiveTvFilter.All -> channel.group !in hidden && channel.hideKey !in hiddenChannels
            LiveTvFilter.Favorites -> channel.streamUrl in favorites
            is LiveTvFilter.Source -> channel.sourceId == filter.id && channel.group !in hidden && channel.hideKey !in hiddenChannels
            is LiveTvFilter.Group -> channel.group == filter.name && channel.hideKey !in hiddenChannels
        } && (needle.isEmpty() || channel.name.contains(needle, ignoreCase = true))
    }
}

private fun filterFor(key: String): LiveTvFilter = when {
    key == FILTER_ALL -> LiveTvFilter.All
    key == FILTER_FAVORITES -> LiveTvFilter.Favorites
    key.startsWith(FILTER_SOURCE_PREFIX) -> LiveTvFilter.Source(key.removePrefix(FILTER_SOURCE_PREFIX))
    else -> LiveTvFilter.Group(key)
}

/**
 * Live TV: categories on the left, channels with what is on now in the middle, the last channel
 * on top, and a live preview of the focused channel on the right. Rows are plain and fixed
 * height (no blur, no images larger than drawn) so thousands of channels scroll smoothly on
 * low-end TVs.
 */
@Composable
fun LiveTvScreen(
    onPlay: (String) -> Unit,
    showBuiltInHeader: Boolean = true,
    viewModel: LiveTvScreenModel = hiltViewModel(),
) {
    val context = LocalContext.current
    // Before the state is read, so a list let go while unused shows as loading, never as empty.
    remember(viewModel) { viewModel.ensureLoaded() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        // Back from the background after a long while: the list may have been let go meanwhile.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) viewModel.ensureLoaded() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val uiState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    // A release that raced the screen coming back leaves an empty, idle state: load again.
    LaunchedEffect(uiState.isLoaded, uiState.isLoading, uiState.hasSource) {
        if (!uiState.isLoaded && !uiState.isLoading && !uiState.hasSource) viewModel.ensureLoaded()
    }
    val scope = rememberCoroutineScope()
    var filterKey by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf<LiveTvGuideState?>(null) }
    var launching by remember { mutableStateOf(false) }
    val channelListState = rememberLazyListState()
    val channelFocus = remember { FocusRequester() }
    val previewsEnabled = rememberLiveTvPreviewsEnabled()
    val preview = rememberLiveTvPreviewPlayer()
    var focusedChannel by remember { mutableStateOf<LiveTvChannel?>(null) }
    var listFocused by remember { mutableStateOf(false) }
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    // Under the pill menu the screen starts below it, as Settings does, so the pill never covers the search field.
    val topPadding = if (showBuiltInHeader) NuvioTheme.spacing.xl else 68.dp

    // A category that was hidden, or a source that was removed, falls back to all channels.
    LaunchedEffect(uiState.hiddenGroups, uiState.sources) {
        val stale = when (val current = filterFor(filterKey)) {
            is LiveTvFilter.Group -> current.name in uiState.hiddenGroups
            is LiveTvFilter.Source -> uiState.sources.none { it.id == current.id }
            else -> false
        }
        if (stale) filterKey = FILTER_ALL
    }

    val filter = filterFor(filterKey)
    // Filtered off the main thread: lists can hold tens of thousands of channels.
    val filterInput = LiveTvFilterInput(uiState.channels, uiState.favoriteUrls, uiState.hiddenGroups, uiState.hiddenChannelKeys, filterKey, query)
    val visibleChannels = viewModel.visibleChannels
    val filtering = !viewModel.isFilteredFor(filterInput)
    LaunchedEffect(uiState.channels, uiState.favoriteUrls, uiState.hiddenGroups, uiState.hiddenChannelKeys, filterKey, query) {
        if (viewModel.isFilteredFor(filterInput)) return@LaunchedEffect
        if (query.isNotEmpty()) delay(200) // typing
        val filtered = withContext(Dispatchers.Default) {
            filterChannels(
                filterInput.channels, filterInput.favoriteUrls, filterInput.hiddenGroups, filterInput.hiddenChannels,
                filterInput.filterKey, filterInput.query,
            )
        }
        viewModel.setVisible(filterInput, filtered)
    }
    // Back to the top only when the category changes, not each time the screen comes back.
    var scrolledForKey by rememberSaveable { mutableStateOf(filterKey) }
    LaunchedEffect(filterKey) {
        if (scrolledForKey != filterKey) {
            scrolledForKey = filterKey
            channelListState.scrollToItem(0)
        }
    }

    // Back from the player: focus the channel last watched (or the first one), scrolled into view.
    val showsRecentRow = uiState.recentChannel != null && filter == LiveTvFilter.All && query.isEmpty()
    var focusTargetUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(visibleChannels, filtering) {
        if (!viewModel.restoreFocusOnReturn || filtering || visibleChannels.isEmpty()) return@LaunchedEffect
        viewModel.restoreFocusOnReturn = false
        val index = visibleChannels.indexOfFirst { it.streamUrl == uiState.recentChannel?.streamUrl }.coerceAtLeast(0)
        focusTargetUrl = visibleChannels[index].streamUrl
        val itemIndex = index + if (showsRecentRow) 1 else 0
        val shown = channelListState.layoutInfo.visibleItemsInfo
        if (shown.none { it.index == itemIndex }) {
            channelListState.scrollToItem((itemIndex - 2).coerceAtLeast(0))
        }
        withFrameNanos { }
        withFrameNanos { }
        runCatching { channelFocus.requestFocus() }
    }
    val minuteClock = rememberLiveTvMinuteClock()
    // Stays set until the player has taken over the screen, so the preview can't start again
    // beside it during the navigation.
    LaunchedEffect(started) { if (!started) launching = false }

    // Unfavouriting a channel in Favorites removes its row: focus moves to the next one (or the
    // category when none is left) instead of falling back to the top of the categories, whose
    // focus would switch the list to All channels.
    var refocusAfterRemoval by remember { mutableStateOf(false) }
    val categoryFocus = remember { FocusRequester() }
    LaunchedEffect(visibleChannels, filtering) {
        if (!refocusAfterRemoval || filtering) return@LaunchedEffect
        refocusAfterRemoval = false
        val target = if (visibleChannels.any { it.streamUrl == focusTargetUrl }) channelFocus else categoryFocus
        repeat(10) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    var refocusAfterGuide by remember { mutableStateOf(false) }
    LaunchedEffect(refocusAfterGuide) {
        if (!refocusAfterGuide) return@LaunchedEffect
        refocusAfterGuide = false
        val index = visibleChannels.indexOfFirst { it.streamUrl == focusTargetUrl }
        if (index >= 0 && channelListState.layoutInfo.visibleItemsInfo.none { it.key == visibleChannels[index].id }) {
            channelListState.scrollToItem((index - 2).coerceAtLeast(0))
        }
        repeat(10) {
            withFrameNanos { }
            if (runCatching { channelFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    val toggleFavorite: (LiveTvChannel) -> Unit = { channel ->
        if (filter == LiveTvFilter.Favorites && channel.streamUrl in uiState.favoriteUrls) {
            val index = visibleChannels.indexOfFirst { it.streamUrl == channel.streamUrl }
            focusTargetUrl = (visibleChannels.getOrNull(index + 1) ?: visibleChannels.getOrNull(index - 1))?.streamUrl
            refocusAfterRemoval = true
        }
        LiveTvRepository.toggleFavorite(channel)
    }

    val play: (LiveTvChannel, Boolean) -> Unit = { channel, fromRecent ->
        if (!launching) {
            launching = true
            // The player needs the decoder and, with one-connection providers, the connection.
            preview.release()
            scope.launch {
                try {
                    if (fromRecent) {
                        // The last channel is listed above every category: in the player, the
                        // categories open on its own, and zapping stays in it, as when it was picked there.
                        val state = uiState
                        val group = channel.group
                        val shown = group !in state.hiddenGroups && channel.hideKey !in state.hiddenChannelKeys
                        val list = if (!shown) {
                            emptyList()
                        } else {
                            withContext(Dispatchers.Default) {
                                filterChannels(state.channels, state.favoriteUrls, state.hiddenGroups, state.hiddenChannelKeys, group)
                            }
                        }
                        LiveTvRepository.setZapList(list, folderKey = group.takeIf { list.isNotEmpty() })
                    } else {
                        val list = visibleChannels.takeIf { list -> list.any { it.streamUrl == channel.streamUrl } }.orEmpty()
                        LiveTvRepository.setZapList(list, folderKey = filterKey.takeIf { query.isBlank() })
                    }
                    val route = liveTvPlayerRoute(channel, viewModel.profileId)
                    viewModel.restoreFocusOnReturn = true
                    onPlay(route)
                } catch (error: Exception) {
                    launching = false
                    throw error
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background),
    ) {
        if (!uiState.isLoaded && !uiState.isLoading && !uiState.hasSource) {
            LiveTvEmptyState(onAddSource = { showSourceDialog = true })
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xl, top = topPadding),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
            ) {
                val visibleGroups = remember(uiState.groups, uiState.hiddenGroups) { uiState.visibleGroups }
                LiveTvCategoryColumn(
                    showHeader = showBuiltInHeader,
                    channelCount = uiState.channels.size,
                    sources = if (uiState.sources.size > 1) uiState.sources else emptyList(),
                    groups = visibleGroups,
                    selectedKey = filterKey,
                    selectedFocus = categoryFocus,
                    onSelect = { filterKey = it },
                    modifier = Modifier.width(if (previewsEnabled) 220.dp else 260.dp).fillMaxHeight(),
                )
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    ) {
                        LiveTvTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = stringResource(R.string.live_tv_search),
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                            modifier = Modifier.weight(1f),
                        )
                        LiveTvPillButton(
                            text = stringResource(R.string.live_tv_guide),
                            onClick = {
                                val channels = visibleChannels
                                if (channels.isNotEmpty()) {
                                    val start = channels.indexOfFirst { it.streamUrl == focusedChannel?.streamUrl }.coerceAtLeast(0)
                                    guide = LiveTvGuideState(
                                        channels = channels,
                                        startIndex = start,
                                        onPlay = { channel ->
                                            guide = null
                                            play(channel, false)
                                        },
                                        onClose = {
                                            // Back on the list, on the channel the guide ended on.
                                            focusTargetUrl = guide?.channel?.streamUrl
                                            guide = null
                                            refocusAfterGuide = true
                                        },
                                    )
                                }
                            },
                        )
                        LiveTvPillButton(
                            text = stringResource(R.string.live_tv_refresh),
                            onClick = { LiveTvRepository.refresh() },
                            enabled = !uiState.isLoading,
                        )
                        LiveTvPillButton(
                            text = stringResource(R.string.live_tv_categories),
                            onClick = { showCategoryDialog = true },
                        )
                        LiveTvPillButton(
                            text = stringResource(R.string.live_tv_sources),
                            onClick = { showSourceDialog = true },
                        )
                    }
                    Spacer(Modifier.height(NuvioTheme.spacing.md))
                    val failedSource = uiState.sources.firstOrNull { it.id in uiState.sourceErrors }
                    val status = when {
                        uiState.isLoading -> stringResource(R.string.live_tv_loading)
                        failedSource != null -> stringResource(
                            R.string.live_tv_source_error,
                            failedSource.label,
                            uiState.sourceErrors[failedSource.id]?.message(context).orEmpty(),
                        )
                        uiState.error != null -> uiState.error?.message(context)
                        uiState.isEpgLoading -> stringResource(R.string.live_tv_guide_loading)
                        else -> null
                    }
                    if (status != null) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if ((failedSource != null || uiState.error != null) && !uiState.isLoading) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
                    ) {
                        LazyColumn(
                            state = channelListState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .onFocusChanged { listFocused = it.hasFocus },
                            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl, top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val recent = uiState.recentChannel
                            if (recent != null && showsRecentRow) {
                                item(key = "recent", contentType = "recent") {
                                    LiveTvRecentRow(
                                        recent = recent,
                                        logo = recent.logoUrl ?: uiState.guideLogos[recent.guideKey],
                                        programme = uiState.currentProgrammes[recent.guideKey],
                                        clock = minuteClock,
                                        onClick = { play(LiveTvRepository.channelFor(recent), true) },
                                        onFocused = { focusedChannel = LiveTvRepository.channelFor(recent) },
                                    )
                                }
                            }
                            items(visibleChannels, key = { it.id }, contentType = { "channel" }) { channel ->
                                LiveTvChannelRow(
                                    channel = channel,
                                    logo = uiState.logoFor(channel),
                                    programme = uiState.currentProgrammes[channel.guideKey],
                                    clock = minuteClock,
                                    isFavorite = channel.streamUrl in uiState.favoriteUrls,
                                    onClick = { play(channel, false) },
                                    onLongClick = { toggleFavorite(channel) },
                                    onFocused = { focusedChannel = channel },
                                    modifier = if (channel.streamUrl == focusTargetUrl) Modifier.focusRequester(channelFocus) else Modifier,
                                )
                            }
                            if (visibleChannels.isEmpty() && !filtering && uiState.isLoaded && !uiState.isLoading) {
                                item(key = "empty") {
                                    Text(
                                        text = stringResource(
                                            if (filter == LiveTvFilter.Favorites) R.string.live_tv_no_favorites else R.string.live_tv_no_channels_found,
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NuvioTheme.colors.TextSecondary,
                                        modifier = Modifier.padding(vertical = NuvioTheme.spacing.lg),
                                    )
                                }
                            }
                        }
                        if (previewsEnabled) {
                            val shownChannel = focusedChannel
                            LiveTvPreviewPanel(
                                preview = preview,
                                channel = shownChannel,
                                logo = shownChannel?.let(uiState::logoFor),
                                programme = shownChannel?.guideKey?.let(uiState.currentProgrammes::get),
                                clock = minuteClock,
                                sourceLabel = if (uiState.sources.size > 1) {
                                    uiState.sources.firstOrNull { it.id == shownChannel?.sourceId }?.label
                                } else {
                                    null
                                },
                                playVideo = listFocused && started && !launching && !showSourceDialog && !showCategoryDialog && guide == null,
                                modifier = Modifier.width(300.dp).padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        // Over the whole screen, the pill menu included, as Nuvio's own full-screen pages are.
        guide?.let { LiveTvGuide(it, takeFocus = true, modifier = Modifier.zIndex(10f)) }
    }

    if (showSourceDialog) {
        LiveTvSourceDialog(onDismiss = { showSourceDialog = false })
    }
    if (showCategoryDialog) {
        LiveTvCategoryDialog(onDismiss = { showCategoryDialog = false })
    }
}

@Composable
private fun LiveTvCategoryColumn(
    showHeader: Boolean,
    channelCount: Int,
    sources: List<LiveTvSource>,
    groups: List<String>,
    selectedKey: String,
    selectedFocus: FocusRequester,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedModifier = Modifier.focusRequester(selectedFocus)
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.live_tv_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = if (showHeader) NuvioTheme.colors.TextPrimary else Color.Transparent,
        )
        Text(
            text = stringResource(R.string.live_tv_channel_count, channelCount),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextTertiary,
            modifier = Modifier.padding(top = 2.dp, bottom = NuvioTheme.spacing.md),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = FILTER_ALL) {
                LiveTvCategoryItem(stringResource(R.string.live_tv_all_channels), selectedKey == FILTER_ALL, selectedModifier) { onSelect(FILTER_ALL) }
            }
            item(key = FILTER_FAVORITES) {
                LiveTvCategoryItem(stringResource(R.string.live_tv_favorites), selectedKey == FILTER_FAVORITES, selectedModifier) { onSelect(FILTER_FAVORITES) }
            }
            // With several sources, each can be browsed on its own.
            items(sources, key = { FILTER_SOURCE_PREFIX + it.id }) { source ->
                val key = FILTER_SOURCE_PREFIX + source.id
                LiveTvCategoryItem(source.label, selectedKey == key, selectedModifier) { onSelect(key) }
            }
            if (sources.isNotEmpty()) {
                item(key = "\u0000divider") {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(NuvioTheme.colors.TextPrimary.copy(alpha = 0.08f)),
                    )
                }
            }
            items(groups, key = { it }) { group ->
                LiveTvCategoryItem(liveTvGroupLabel(group), selectedKey == group, selectedModifier) { onSelect(group) }
            }
        }
    }
}

/** A category: selecting happens on focus, like Netflix's genre rail, so the list follows the remote. */
@Composable
private fun LiveTvCategoryItem(label: String, selected: Boolean, selectedModifier: Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused && !selected) {
            delay(250) // passing over a category does not re-filter
            onSelect()
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onSelect,
        modifier = (if (selected) selectedModifier else Modifier)
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary.copy(alpha = 0.08f) else Color.Transparent,
            focusedContainerColor = NuvioTheme.colors.TextPrimary,
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) Color.Black else if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LiveTvRecentRow(
    recent: LiveTvRecentChannel,
    logo: String?,
    programme: LiveTvProgramme?,
    clock: State<Long>,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    LiveTvRowCard(onClick = onClick, onLongClick = null, tall = true, onFocused = onFocused) { focused ->
        LiveTvLogo(url = logo, name = recent.name, width = 88.dp, height = 54.dp)
        Column(modifier = Modifier.weight(1f).padding(start = NuvioTheme.spacing.md)) {
            Text(
                text = stringResource(R.string.live_tv_continue).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.5.sp,
                color = if (focused) Color.Black.copy(alpha = 0.6f) else NuvioTheme.colors.TextTertiary,
            )
            Text(
                text = recent.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (focused) Color.Black else NuvioTheme.colors.TextPrimary,
            )
            ProgrammeLine(programme, clock, focused)
        }
    }
}

@Composable
private fun LiveTvChannelRow(
    channel: LiveTvChannel,
    logo: String?,
    programme: LiveTvProgramme?,
    clock: State<Long>,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveTvRowCard(onClick = onClick, onLongClick = onLongClick, tall = false, onFocused = onFocused, modifier = modifier) { focused ->
        LiveTvLogo(url = logo, name = channel.name, width = 66.dp, height = 40.dp)
        Column(modifier = Modifier.weight(1f).padding(start = NuvioTheme.spacing.md)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (focused) Color.Black else NuvioTheme.colors.TextPrimary,
            )
            ProgrammeLine(programme, clock, focused, fallback = channel.group)
        }
        if (isFavorite) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = stringResource(R.string.live_tv_favorites),
                tint = if (focused) Color.Black else NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(start = NuvioTheme.spacing.sm).size(18.dp),
            )
        }
    }
}

/** What is on now, a thin progress bar and the time left, or the category when there is no guide. */
@Composable
private fun ProgrammeLine(programme: LiveTvProgramme?, clock: State<Long>, focused: Boolean, fallback: String = "") {
    val secondary = if (focused) Color.Black.copy(alpha = 0.65f) else NuvioTheme.colors.TextSecondary
    if (programme == null) {
        if (fallback.isNotBlank()) {
            Text(text = fallback, style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    Text(
        text = programme.title,
        style = MaterialTheme.typography.bodySmall,
        color = secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveTvProgressBar(
            programme = programme,
            clock = clock,
            fill = if (focused) Color.Black else NuvioTheme.colors.TextPrimary,
            track = if (focused) Color.Black.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.12f),
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 160.dp).fillMaxWidth(),
        )
        Text(
            text = liveTvTimeLeft(programme, clock),
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) Color.Black.copy(alpha = 0.55f) else NuvioTheme.colors.TextTertiary,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun LiveTvRowCard(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    tall: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val container by animateColorAsState(
        if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextPrimary.copy(alpha = 0.035f),
        label = "liveTvRow",
    )
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = container, focusedContainerColor = container),
        scale = CardDefaults.scale(focusedScale = 1.015f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (tall) 76.dp else 58.dp)
                .padding(horizontal = NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content(focused)
        }
    }
}

@Composable
private fun LiveTvEmptyState(onAddSource: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.xxxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.live_tv_empty_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = NuvioTheme.colors.TextPrimary,
        )
        Text(
            text = stringResource(R.string.live_tv_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.widthIn(max = 520.dp).padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.lg),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        LiveTvPillButton(
            text = stringResource(R.string.live_tv_add_source),
            onClick = onAddSource,
            modifier = Modifier.focusRequester(focus),
        )
    }
}
