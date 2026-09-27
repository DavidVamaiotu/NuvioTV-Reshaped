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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRecentChannel
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The filter shown in the category column. */
internal sealed interface LiveTvFilter {
    data object All : LiveTvFilter
    data object Favorites : LiveTvFilter
    data class Group(val name: String) : LiveTvFilter
}

private const val FILTER_ALL = "\u0000all"
private const val FILTER_FAVORITES = "\u0000favorites"

/**
 * Live TV: categories on the left, channels with what is on now on the right, the last channel
 * on top. Rows are plain and fixed height (no blur, no images larger than drawn) so thousands of
 * channels scroll smoothly on low-end TVs.
 */
@Composable
fun LiveTvScreen(
    onPlay: (String) -> Unit,
    showBuiltInHeader: Boolean = true,
    viewModel: LiveTvScreenModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var filterKey by rememberSaveable { mutableStateOf(FILTER_ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSourceDialog by remember { mutableStateOf(false) }
    var launching by remember { mutableStateOf(false) }
    val channelListState = rememberLazyListState()
    val channelFocus = remember { FocusRequester() }
    // Under the pill menu the screen starts below it, as Settings does, so the pill never covers the search field.
    val topPadding = if (showBuiltInHeader) NuvioTheme.spacing.xl else 68.dp

    val filter = when (filterKey) {
        FILTER_ALL -> LiveTvFilter.All
        FILTER_FAVORITES -> LiveTvFilter.Favorites
        else -> LiveTvFilter.Group(filterKey)
    }
    // Filtered off the main thread: lists can hold tens of thousands of channels.
    val filterInput = LiveTvFilterInput(uiState.channels, uiState.favoriteUrls, filterKey, query)
    val visibleChannels = viewModel.visibleChannels
    val filtering = !viewModel.isFilteredFor(filterInput)
    LaunchedEffect(uiState.channels, uiState.favoriteUrls, filterKey, query) {
        if (viewModel.isFilteredFor(filterInput)) return@LaunchedEffect
        if (query.isNotEmpty()) delay(200) // typing
        val favorites = filterInput.favoriteUrls
        val needle = query.trim()
        val filtered = withContext(Dispatchers.Default) {
            filterInput.channels.filter { channel ->
                when (filter) {
                    LiveTvFilter.All -> true
                    LiveTvFilter.Favorites -> channel.streamUrl in favorites
                    is LiveTvFilter.Group -> channel.group == filter.name
                } && (needle.isEmpty() || channel.name.contains(needle, ignoreCase = true))
            }
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
    val minuteClock = rememberMinuteClock()

    val play: (LiveTvChannel) -> Unit = { channel ->
        if (!launching) {
            launching = true
            scope.launch {
                try {
                    LiveTvRepository.zapList = visibleChannels.takeIf { list -> list.any { it.streamUrl == channel.streamUrl } }.orEmpty()
                    val route = liveTvPlayerRoute(channel, viewModel.profileId)
                    viewModel.restoreFocusOnReturn = true
                    onPlay(route)
                } finally {
                    launching = false
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
                LiveTvCategoryColumn(
                    showHeader = showBuiltInHeader,
                    channelCount = uiState.channels.size,
                    groups = uiState.groups,
                    selectedKey = filterKey,
                    onSelect = { filterKey = it },
                    modifier = Modifier.width(260.dp).fillMaxHeight(),
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
                            text = stringResource(R.string.live_tv_refresh),
                            onClick = { LiveTvRepository.refresh() },
                            enabled = !uiState.isLoading,
                        )
                        LiveTvPillButton(
                            text = stringResource(R.string.live_tv_source),
                            onClick = { showSourceDialog = true },
                        )
                    }
                    Spacer(Modifier.height(NuvioTheme.spacing.md))
                    val status = when {
                        uiState.isLoading -> stringResource(R.string.live_tv_loading)
                        uiState.error != null -> uiState.error?.message(context)
                        uiState.isEpgLoading -> stringResource(R.string.live_tv_guide_loading)
                        else -> null
                    }
                    if (status != null) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.error != null && !uiState.isLoading) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm),
                        )
                    }
                    LazyColumn(
                        state = channelListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl, top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val recent = uiState.recentChannel
                        if (recent != null && showsRecentRow) {
                            item(key = "recent", contentType = "recent") {
                                LiveTvRecentRow(
                                    recent = recent,
                                    programme = recent.tvgId?.let(uiState.currentProgrammes::get),
                                    clock = minuteClock,
                                    onClick = { play(LiveTvRepository.channelFor(recent)) },
                                )
                            }
                        }
                        items(visibleChannels, key = { it.id }, contentType = { "channel" }) { channel ->
                            LiveTvChannelRow(
                                channel = channel,
                                programme = channel.tvgId?.let(uiState.currentProgrammes::get),
                                clock = minuteClock,
                                isFavorite = channel.streamUrl in uiState.favoriteUrls,
                                onClick = { play(channel) },
                                onLongClick = { LiveTvRepository.toggleFavorite(channel) },
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
                }
            }
        }
    }

    if (showSourceDialog) {
        LiveTvSourceDialog(onDismiss = { showSourceDialog = false })
    }
}

@Composable
private fun LiveTvCategoryColumn(
    showHeader: Boolean,
    channelCount: Int,
    groups: List<String>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                LiveTvCategoryItem(stringResource(R.string.live_tv_all_channels), selectedKey == FILTER_ALL) { onSelect(FILTER_ALL) }
            }
            item(key = FILTER_FAVORITES) {
                LiveTvCategoryItem(stringResource(R.string.live_tv_favorites), selectedKey == FILTER_FAVORITES) { onSelect(FILTER_FAVORITES) }
            }
            items(groups, key = { it }) { group ->
                LiveTvCategoryItem(group, selectedKey == group) { onSelect(group) }
            }
        }
    }
}

/** A category: selecting happens on focus, like Netflix's genre rail, so the list follows the remote. */
@Composable
private fun LiveTvCategoryItem(label: String, selected: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (focused && !selected) {
            delay(250) // passing over a category does not re-filter
            onSelect()
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary.copy(alpha = 0.10f) else Color.Transparent,
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun LiveTvRecentRow(
    recent: LiveTvRecentChannel,
    programme: LiveTvProgramme?,
    clock: State<Long>,
    onClick: () -> Unit,
) {
    LiveTvRowCard(onClick = onClick, onLongClick = null, tall = true) { focused ->
        LiveTvLogo(url = recent.logoUrl, name = recent.name, width = 96.dp, height = 60.dp)
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
    programme: LiveTvProgramme?,
    clock: State<Long>,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveTvRowCard(onClick = onClick, onLongClick = onLongClick, tall = false, modifier = modifier) { focused ->
        LiveTvLogo(url = channel.logoUrl, name = channel.name, width = 72.dp, height = 44.dp)
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

/** "Now: title · 21:00 – 22:00" with a thin progress bar, or the category when there is no guide. */
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
        text = "${programme.title}  ·  ${programme.timeLabel}",
        style = MaterialTheme.typography.bodySmall,
        color = secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    val span = (programme.stopEpochMs - programme.startEpochMs).coerceAtLeast(1L)
    val fill = if (focused) Color.Black else NuvioTheme.colors.TextPrimary
    Box(
        modifier = Modifier
            .padding(top = 5.dp)
            .widthIn(max = 220.dp)
            .fillMaxWidth()
            .height(3.dp)
            .clip(LiveTvPillShape)
            .background(if (focused) Color.Black.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.12f))
            // Read at draw time: the minute tick redraws the bar without recomposing the row.
            .drawBehind {
                val fraction = ((clock.value - programme.startEpochMs).toFloat() / span).coerceIn(0f, 1f)
                drawRect(fill, size = Size(size.width * fraction, size.height))
            },
    )
}

/** The time, updated on each minute. */
@Composable
private fun rememberMinuteClock(): State<Long> = produceState(LiveTvClock.nowEpochMs()) {
    while (true) {
        delay(60_000L - value % 60_000L)
        value = LiveTvClock.nowEpochMs()
    }
}

@Composable
private fun LiveTvRowCard(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    tall: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val container by animateColorAsState(
        if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.55f),
        label = "liveTvRow",
    )
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = container, focusedContainerColor = container),
        scale = CardDefaults.scale(focusedScale = 1.015f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (tall) 84.dp else 64.dp)
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
