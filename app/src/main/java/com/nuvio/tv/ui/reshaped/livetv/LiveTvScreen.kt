@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.nuvio.tv.reshaped.livetv.LiveTvCatchupLinks
import com.nuvio.tv.reshaped.livetv.LIVE_TV_UNGROUPED
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvPreferences
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.reshaped.livetv.rememberLiveTvPreviewSoundEnabled
import com.nuvio.tv.reshaped.livetv.rememberLiveTvPreviewsEnabled
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale
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
    /** One category of one source, picked under that source's heading. */
    data class SourceGroup(val id: String, val name: String) : LiveTvFilter
}

internal const val FILTER_ALL = "\u0000all"
internal const val FILTER_FAVORITES = "\u0000favorites"
internal const val FILTER_SOURCE_PREFIX = "\u0000source:"
internal const val FILTER_SOURCE_GROUP_PREFIX = "\u0000sourcegroup:"

internal fun sourceGroupKey(sourceId: String, group: String): String = "$FILTER_SOURCE_GROUP_PREFIX$sourceId\u0000$group"

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
            is LiveTvFilter.SourceGroup ->
                channel.sourceId == filter.id && channel.group == filter.name && channel.hideKey !in hiddenChannels
        } && (needle.isEmpty() || channel.name.contains(needle, ignoreCase = true))
    }
}

internal fun filterFor(key: String): LiveTvFilter = when {
    key == FILTER_ALL -> LiveTvFilter.All
    key == FILTER_FAVORITES -> LiveTvFilter.Favorites
    key.startsWith(FILTER_SOURCE_PREFIX) -> LiveTvFilter.Source(key.removePrefix(FILTER_SOURCE_PREFIX))
    key.startsWith(FILTER_SOURCE_GROUP_PREFIX) -> key.removePrefix(FILTER_SOURCE_GROUP_PREFIX).let {
        LiveTvFilter.SourceGroup(it.substringBefore('\u0000'), it.substringAfter('\u0000'))
    }
    else -> LiveTvFilter.Group(key)
}


/**
 * Live TV, laid out as TV channel guides are: what is selected on top (picture, channel, programme,
 * time left, description) with the live preview on the right; below, the categories on the left
 * (search, favorites, each source's categories under its own heading) and the programme guide.
 * The guide draws only the rows and programmes in view, so thousands of channels stay light.
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
    var launching by remember { mutableStateOf(false) }
    val previewsEnabled = rememberLiveTvPreviewsEnabled()
    val preview = rememberLiveTvPreviewPlayer()
    var gridFocused by remember { mutableStateOf(false) }
    var settingsFocused by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // The categories and search slide in over the channel names (◀ from now, or Back), as in TV guides.
    var categoriesOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    // Under the pill menu the screen starts below it, as Settings does, so the pill never covers the header.
    val topPadding = if (showBuiltInHeader) NuvioTheme.spacing.xl else 68.dp
    val gridFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }

    // A category that was hidden, or a source that was removed, falls back to all channels.
    // Categories show under their source's heading only with several sources: a category picked
    // the other way follows (or falls back to all channels when that is not possible).
    LaunchedEffect(uiState.hiddenGroups, uiState.sources) {
        val multiSource = uiState.sources.size > 1
        filterKey = when (val current = filterFor(filterKey)) {
            is LiveTvFilter.Group -> when {
                current.name in uiState.hiddenGroups -> FILTER_ALL
                multiSource -> FILTER_ALL
                else -> filterKey
            }
            is LiveTvFilter.Source -> if (uiState.sources.none { it.id == current.id }) FILTER_ALL else filterKey
            is LiveTvFilter.SourceGroup -> when {
                current.name in uiState.hiddenGroups || uiState.sources.none { it.id == current.id } -> FILTER_ALL
                !multiSource -> current.name
                else -> filterKey
            }
            else -> filterKey
        }
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

    val minuteClock = rememberLiveTvMinuteClock()
    // Stays set until the player has taken over the screen, so the preview can't start again
    // beside it during the navigation.
    LaunchedEffect(started) { if (!started) launching = false }

    /** Plays [channel] live, or its past [programme] (catch-up) when one is given. */
    val launchPlay: (LiveTvChannel, LiveTvProgramme?) -> Unit = { channel, programme ->
        if (!launching) {
            launching = true
            // The player needs the decoder and, with one-connection providers, the connection.
            preview.release()
            scope.launch {
                try {
                    val list = visibleChannels.takeIf { list -> list.any { it.streamUrl == channel.streamUrl } }.orEmpty()
                    // The player's categories list each category once: a source's category opens as that category.
                    val folder = (filterFor(filterKey) as? LiveTvFilter.SourceGroup)?.name ?: filterKey
                    LiveTvRepository.setZapList(list, folderKey = folder.takeIf { query.isBlank() })
                    val route = if (programme != null) {
                        liveTvCatchupRoute(channel, programme, viewModel.profileId)
                    } else {
                        liveTvPlayerRoute(channel, viewModel.profileId)
                    }
                    if (route == null) {
                        launching = false
                        android.widget.Toast.makeText(context, R.string.live_tv_catchup_failed, android.widget.Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    viewModel.restoreFocusOnReturn = true
                    onPlay(route)
                } catch (error: Exception) {
                    launching = false
                    throw error
                }
            }
        }
    }
    val currentLaunchPlay by rememberUpdatedState(launchPlay)

    val categoryList = rememberLazyListState()
    // Focus put on a category by the guide (Back, ◀) does not pick it: only the viewer's moves do.
    val holdCategory = remember { mutableStateOf(false) }
    val toCategories: () -> Unit = {
        holdCategory.value = true
        categoriesOpen = true
        // The panel is composed on the next frame, and the selected category may need scrolling to.
        scope.launch {
            repeat(3) {
                withFrameNanos { }
                if (runCatching { categoryFocus.requestFocus() }.isSuccess) return@launch
            }
            val index = viewModel.categoryKeys.indexOf(filterKey)
            if (index >= 0) categoryList.scrollToItem((index - 3).coerceAtLeast(0))
            repeat(5) {
                withFrameNanos { }
                if (runCatching { categoryFocus.requestFocus() }.isSuccess) return@launch
            }
            focusManager.moveFocus(FocusDirection.Left)
        }
    }
    val toGuide: () -> Boolean = {
        visibleChannels.isNotEmpty() && runCatching { gridFocus.requestFocus() }.isSuccess
    }
    val currentToCategories by rememberUpdatedState(toCategories)
    // Unfavouriting a channel in Favorites removes its row: the guide moves to the next one, or
    // to the categories when none is left.
    var keepAfterRefilter by remember { mutableStateOf<String?>(null) }
    val toggleFavorite: (LiveTvChannel) -> Unit = { channel ->
        if (filterFor(filterKey) == LiveTvFilter.Favorites && channel.streamUrl in uiState.favoriteUrls) {
            val index = visibleChannels.indexOfFirst { it.streamUrl == channel.streamUrl }
            keepAfterRefilter = (visibleChannels.getOrNull(index + 1) ?: visibleChannels.getOrNull(index - 1))?.streamUrl
        }
        LiveTvRepository.toggleFavorite(channel)
    }
    val currentToggleFavorite by rememberUpdatedState(toggleFavorite)
    val guide = remember {
        LiveTvGuideState(
            channels = emptyList(),
            startIndex = 0,
            onPlay = { channel -> currentLaunchPlay(channel, null) },
            onCatchup = { channel, programme -> currentLaunchPlay(channel, programme) },
            // Back, and ◀ from what is on now, go to the categories, as in TV guides: to the one
            // selected, or (scrolled out of view) the nearest one.
            onClose = { currentToCategories() },
            onExitLeft = { currentToCategories() },
            onExitUp = { runCatching { settingsFocus.requestFocus() } },
            onLongPress = { channel -> currentToggleFavorite(channel) },
            leadMs = 0L,
        )
    }
    // Another category or search brings the guide back to now; the same one filtered again
    // (a favourite, a hidden channel) stays where it was.
    var shownFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(visibleChannels) {
        val shownKey = filterKey + "\u0000" + query
        val keep = keepAfterRefilter ?: guide.channel?.streamUrl ?: uiState.recentChannel?.streamUrl
        keepAfterRefilter = null
        guide.showChannels(visibleChannels, keepUrl = keep, toNow = shownFor != shownKey)
        shownFor = shownKey
        if (visibleChannels.isEmpty() && gridFocused) currentToCategories()
    }
    // What is on now stays selected as time passes, and again after the app comes back.
    LaunchedEffect(guide) { snapshotFlow { minuteClock.value }.collect { guide.followNow() } }
    LaunchedEffect(started) { if (started) guide.backToNow() }

    // Back from the player: the guide on the channel last watched, focused.
    LaunchedEffect(visibleChannels, filtering) {
        if (!viewModel.restoreFocusOnReturn || filtering || visibleChannels.isEmpty()) return@LaunchedEffect
        viewModel.restoreFocusOnReturn = false
        guide.selectRow(visibleChannels.indexOfFirst { it.streamUrl == uiState.recentChannel?.streamUrl }.coerceAtLeast(0))
        guide.backToNow()
        repeat(10) {
            withFrameNanos { }
            if (runCatching { gridFocus.requestFocus() }.isSuccess) return@LaunchedEffect
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = NuvioTheme.spacing.xxl, end = NuvioTheme.spacing.xl, top = topPadding),
            ) {
                LiveTvHeader(
                    guide = guide,
                    uiState = uiState,
                    gridFocused = gridFocused,
                    clock = minuteClock,
                    showTitle = showBuiltInHeader,
                    preview = if (previewsEnabled) preview else null,
                    playVideo = (gridFocused || settingsFocused || categoriesOpen) && started && !launching &&
                        !showSourceDialog && !showCategoryDialog && !showMenu,
                )
                Spacer(Modifier.height(NuvioTheme.spacing.md))
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(gridFocus)
                            .onFocusChanged { gridFocused = it.isFocused }
                            .onPreviewKeyEvent { guide.onKey(it.nativeKeyEvent) }
                            .focusable(enabled = visibleChannels.isNotEmpty()),
                    ) {
                        LiveTvGuideGrid(
                            state = guide,
                            active = gridFocused,
                            clock = minuteClock,
                            channelColumn = 220.dp,
                            rowHeight = 46.dp,
                            rulerHeight = 32.dp,
                            modifier = Modifier.fillMaxSize(),
                            // The settings button sits in front of the date, outside the guide's own keys.
                            corner = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(SETTINGS_SLOT))
                                    LiveTvGuideDate(guide.viewStartMs, minuteClock)
                                }
                            },
                        )
                        if (visibleChannels.isEmpty() && !filtering && uiState.isLoaded && !uiState.isLoading) {
                            Text(
                                text = stringResource(
                                    if (filter == LiveTvFilter.Favorites) R.string.live_tv_no_favorites else R.string.live_tv_no_channels_found,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.padding(top = 40.dp, start = 8.dp),
                            )
                        }
                    }
                    LiveTvPillButton(
                        text = "",
                        icon = Icons.Filled.Settings,
                        iconDescription = stringResource(R.string.live_tv_settings),
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .focusRequester(settingsFocus)
                            .onFocusChanged { settingsFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                // ▼ back to the guide, ◀ to the categories.
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> toGuide()
                                    Key.DirectionLeft -> {
                                        toCategories()
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                    if (categoriesOpen) {
                        var panelHadFocus by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .zIndex(2f)
                                .width(280.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(16.dp))
                                .background(NuvioTheme.colors.Background.copy(alpha = 0.97f))
                                .padding(horizontal = 8.dp)
                                // It closes once focus leaves it.
                                .onFocusChanged {
                                    if (it.hasFocus) panelHadFocus = true else if (panelHadFocus) categoriesOpen = false
                                }
                                .onPreviewKeyEvent { event ->
                                    val right = event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown
                                    right && toGuide()
                                },
                        ) {
                            LiveTvCategoryColumn(
                                uiState = uiState,
                                listState = categoryList,
                                holdSelection = holdCategory,
                                viewModel = viewModel,
                                query = query,
                                onQueryChange = { query = it },
                                selectedKey = filterKey,
                                selectedFocus = categoryFocus,
                                onSelect = { filterKey = it },
                                // OK on a category shows its guide.
                                onPicked = { toGuide() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSourceDialog) {
        LiveTvSourceDialog(onDismiss = { showSourceDialog = false })
    }
    if (showCategoryDialog) {
        LiveTvCategoryDialog(onDismiss = { showCategoryDialog = false })
    }
    if (showMenu) {
        LiveTvMenuDialog(
            previews = previewsEnabled,
            loading = uiState.isLoading,
            onCategories = {
                showMenu = false
                toCategories()
            },
            onEditCategories = {
                showMenu = false
                showCategoryDialog = true
            },
            onSources = {
                showMenu = false
                showSourceDialog = true
            },
            onDismiss = { showMenu = false },
        )
    }
}

/** Room for the settings button in front of the date. */
private val SETTINGS_SLOT = 46.dp

/** Everything that used to sit above the guide: one button opens it. */
@Composable
private fun LiveTvMenuDialog(
    previews: Boolean,
    loading: Boolean,
    onCategories: () -> Unit,
    onEditCategories: () -> Unit,
    onSources: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val previewSound = rememberLiveTvPreviewSoundEnabled()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    com.nuvio.tv.ui.components.NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.live_tv_settings),
        width = 420.dp,
        usePlatformDefaultWidth = false,
        contentSpacing = NuvioTheme.spacing.sm,
    ) {
        val wide = Modifier.fillMaxWidth()
        LiveTvPillButton(text = stringResource(R.string.live_tv_categories_and_search), onClick = onCategories, modifier = wide.focusRequester(first))
        LiveTvPillButton(text = stringResource(R.string.live_tv_edit_categories), onClick = onEditCategories, modifier = wide)
        LiveTvPillButton(text = stringResource(R.string.live_tv_sources), onClick = onSources, modifier = wide)
        LiveTvPillButton(
            text = stringResource(R.string.live_tv_refresh),
            onClick = {
                LiveTvRepository.refresh()
                onDismiss()
            },
            enabled = !loading,
            modifier = wide,
        )
        if (previews) {
            LiveTvPillButton(
                text = stringResource(if (previewSound) R.string.live_tv_preview_mute else R.string.live_tv_preview_unmute),
                icon = if (previewSound) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                onClick = { LiveTvPreferences.setPreviewSound(context, !previewSound) },
                modifier = wide,
            )
        }
    }
}

/** The header above the guide: what is selected, and the live preview on the right. */
@Composable
private fun LiveTvHeader(
    guide: LiveTvGuideState,
    uiState: com.nuvio.tv.reshaped.livetv.LiveTvUiState,
    gridFocused: Boolean,
    clock: State<Long>,
    showTitle: Boolean,
    preview: LiveTvPreviewPlayer?,
    playVideo: Boolean,
) {
    val context = LocalContext.current
    val shownChannel = guide.channel
    val shownProgramme = remember(shownChannel, guide.anchorMs, uiState.guideVersion, uiState.currentProgrammes, gridFocused) {
        // The programme selected in the guide, or what is on now on the selected channel.
        (if (gridFocused) guide.selected() else null) ?: shownChannel?.guideKey?.let(uiState.currentProgrammes::get)
    }
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
    Row(modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
        LiveTvGuideInfo(
            channel = shownChannel,
            logo = shownChannel?.let(uiState::logoFor),
            programme = shownProgramme,
            clock = clock,
            status = status,
            statusIsError = (failedSource != null || uiState.error != null) && !uiState.isLoading,
            showTitle = showTitle && shownChannel == null,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        if (preview != null) {
            LiveTvPreviewVideo(
                preview = preview,
                channel = shownChannel,
                logo = shownChannel?.let(uiState::logoFor),
                playVideo = playVideo,
                modifier = Modifier.padding(start = NuvioTheme.spacing.xl).fillMaxHeight(),
            )
        }
    }
}

private val HEADER_HEIGHT = 140.dp

private const val POSTER_DELAY_MS = 250L
private val POSTER_WIDTH = HEADER_HEIGHT * 2 / 3

/** "Wed, Sep 30 · 9:43 AM": the day the guide shows, and the time now. */
@Composable
private fun LiveTvGuideDate(viewStartMs: Long, clock: State<Long>) {
    val pattern = remember { DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd") }
    val day = remember(pattern, viewStartMs / 3_600_000L) {
        java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(java.util.Date(maxOf(viewStartMs, LiveTvClock.nowEpochMs())))
    }
    Text(
        text = "$day  ·  ${LiveTvClock.formatClock(clock.value)}",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = NuvioTheme.colors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * What is selected: the programme's picture when the guide has one, the channel, the title, its
 * times, time left and catch-up, how far it has got and its description. [actions] sit at the bottom.
 */
@Composable
private fun LiveTvGuideInfo(
    channel: LiveTvChannel?,
    logo: String?,
    programme: LiveTvProgramme?,
    clock: State<Long>,
    status: String?,
    statusIsError: Boolean,
    showTitle: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        // Loaded once the selection rests, so holding ▼ through channels starts no image loads;
        // once a guide has given a picture its place stays, so the text does not jump between rows.
        val image = programme?.image
        val poster by produceState<String?>(null, image) {
            value = null
            if (image == null) return@produceState
            delay(POSTER_DELAY_MS)
            value = image
        }
        var posterSlot by remember { mutableStateOf(false) }
        LaunchedEffect(image != null) { if (image != null) posterSlot = true }
        if (posterSlot) {
            Box(modifier = Modifier.padding(end = NuvioTheme.spacing.lg).size(POSTER_WIDTH, HEADER_HEIGHT)) {
                LiveTvPoster(url = poster, width = POSTER_WIDTH, height = HEADER_HEIGHT)
            }
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (channel == null) {
                if (showTitle) {
                    Text(
                        text = stringResource(R.string.live_tv_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NuvioTheme.colors.TextPrimary,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveTvLogo(url = logo, name = channel.name, width = 46.dp, height = 28.dp)
                    Text(
                        text = channel.name.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.sp,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Text(
                    text = programme?.title ?: channel.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (programme != null) {
                    val now = clock.value
                    val timing = listOfNotNull(
                        LiveTvClock.formatSpan(programme),
                        when {
                            programme.stopEpochMs <= now -> stringResource(R.string.live_tv_guide_ended)
                            programme.startEpochMs <= now -> liveTvTimeLeft(programme, clock)
                            else -> null
                        },
                        stringResource(R.string.live_tv_catchup).takeIf {
                            programme.startEpochMs < now && LiveTvCatchupLinks.isPlayable(channel.catchup, programme, now)
                        },
                    ).joinToString("  ·  ")
                    Text(
                        text = timing,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (programme.startEpochMs <= now && now < programme.stopEpochMs) {
                        LiveTvProgressBar(
                            programme = programme,
                            clock = clock,
                            fill = NuvioTheme.colors.TextPrimary,
                            track = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.padding(top = 8.dp).widthIn(max = 420.dp).fillMaxWidth(),
                        )
                    }
                    programme.description?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).widthIn(max = 640.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One row of the category column. */
private class LiveTvCategoryEntry(
    val key: String,
    val label: String,
    val count: Int? = null,
    val heading: Boolean = false,
    val folded: Boolean = false,
    val indent: Boolean = false,
    val sourceId: String? = null,
)

/**
 * Search, Favorites, All channels, then the categories; with several sources, each source's
 * categories sit under its own heading (with its channel count), which OK folds away.
 */
@Composable
private fun LiveTvCategoryColumn(
    uiState: com.nuvio.tv.reshaped.livetv.LiveTvUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    holdSelection: androidx.compose.runtime.MutableState<Boolean>,
    viewModel: LiveTvScreenModel,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedKey: String,
    selectedFocus: FocusRequester,
    onSelect: (String) -> Unit,
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedModifier = Modifier.focusRequester(selectedFocus)
    val visibleGroups = remember(uiState.groups, uiState.hiddenGroups) { uiState.visibleGroups }
    // Which categories each source has and the channels shown: one pass over the channels, off
    // the main thread, kept in the model so coming back from the player shows them at once.
    val sections by produceState(viewModel.sourceSections(uiState, visibleGroups), uiState.channels, visibleGroups, uiState.hiddenChannelKeys, uiState.sources) {
        value = viewModel.sourceSections(uiState, visibleGroups)
            ?: withContext(Dispatchers.Default) { viewModel.computeSourceSections(uiState, visibleGroups) }
    }
    val collapsed = viewModel.collapsedSources
    val allLabel = stringResource(R.string.live_tv_all_channels)
    val favoritesLabel = stringResource(R.string.live_tv_favorites)
    val uncategorised = liveTvGroupLabel(LIVE_TV_UNGROUPED)
    val entries = remember(sections, visibleGroups, uiState.groupNames, collapsed.toMap(), allLabel, favoritesLabel, uncategorised) {
        fun label(group: String) = liveTvGroupName(group, uiState.groupNames) ?: if (group == LIVE_TV_UNGROUPED) uncategorised else group
        buildList {
            add(LiveTvCategoryEntry(FILTER_FAVORITES, favoritesLabel))
            add(LiveTvCategoryEntry(FILTER_ALL, allLabel, count = sections?.total))
            val bySource = sections?.sources
            if (bySource != null && bySource.size > 1) {
                bySource.forEach { section ->
                    val folded = collapsed[section.source.id] == true
                    add(
                        LiveTvCategoryEntry(
                            key = FILTER_SOURCE_PREFIX + section.source.id,
                            label = section.source.label.uppercase(),
                            count = section.channelCount,
                            heading = true,
                            folded = folded,
                            sourceId = section.source.id,
                        ),
                    )
                    if (!folded) section.groups.forEach { add(LiveTvCategoryEntry(sourceGroupKey(section.source.id, it), label(it), indent = true)) }
                }
            } else if (uiState.sources.size <= 1) {
                visibleGroups.forEach { add(LiveTvCategoryEntry(it, label(it))) }
            }
        }
    }
    // Item 0 is the search field: the guide's ◀ and Back find a category by this list.
    SideEffect { viewModel.categoryKeys = listOf("\u0000search") + entries.map { it.key } }
    // The selected category inside a folded source opens again, so it can take focus.
    LaunchedEffect(selectedKey) {
        (filterFor(selectedKey) as? LiveTvFilter.SourceGroup)?.let { collapsed.remove(it.id) }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(top = 2.dp, bottom = NuvioTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "\u0000search") {
            LiveTvTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.live_tv_search),
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        items(entries, key = { it.key }, contentType = { if (it.heading) "heading" else "category" }) { entry ->
            LiveTvCategoryItem(
                label = entry.label,
                selected = selectedKey == entry.key,
                selectedModifier = selectedModifier,
                holdSelection = holdSelection,
                count = entry.count,
                heading = entry.heading,
                folded = entry.folded,
                indent = entry.indent,
                onClick = entry.sourceId?.let { id -> { collapsed[id] = !entry.folded } } ?: {
                    onSelect(entry.key)
                    onPicked()
                },
            ) { onSelect(entry.key) }
        }
    }
}

/**
 * A category: selecting happens on focus, like Netflix's genre rail, so the guide follows the
 * remote. A source heading ([heading]) folds its categories on OK ([onClick]).
 */
@Composable
private fun LiveTvCategoryItem(
    label: String,
    selected: Boolean,
    selectedModifier: Modifier,
    count: Int? = null,
    heading: Boolean = false,
    folded: Boolean = false,
    indent: Boolean = false,
    holdSelection: androidx.compose.runtime.MutableState<Boolean>? = null,
    onClick: (() -> Unit)? = null,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        // Focus the guide put here (Back, ◀) only shows where the viewer is.
        if (holdSelection?.value == true) {
            holdSelection.value = false
            return@LaunchedEffect
        }
        if (!selected) {
            delay(250) // passing over a category does not re-filter
            onSelect()
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onClick ?: onSelect,
        modifier = (if (selected) selectedModifier else Modifier)
            .fillMaxWidth()
            .padding(top = if (heading) 8.dp else 0.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.TextPrimary.copy(alpha = 0.08f) else Color.Transparent,
            focusedContainerColor = NuvioTheme.colors.TextPrimary,
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        val content = if (focused) Color.Black else if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
        Row(
            modifier = Modifier.padding(start = if (indent) 22.dp else 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (heading) {
                Icon(
                    imageVector = if (folded) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.padding(end = 6.dp).size(18.dp),
                )
            }
            Text(
                text = label,
                style = if (heading) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected || heading) FontWeight.SemiBold else FontWeight.Normal,
                letterSpacing = if (heading) 1.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (focused) Color.Black.copy(alpha = 0.6f) else NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
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
