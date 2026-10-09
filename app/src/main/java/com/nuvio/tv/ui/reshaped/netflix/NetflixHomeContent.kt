package com.nuvio.tv.ui.reshaped.netflix

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.R
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.core.poster.withCustomPosterUrls
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.domain.model.stableItemKeys
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.ui.components.CollectionRowSection
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.LocalStartupSplashEnabled
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.HomeEvent
import com.nuvio.tv.ui.screens.home.HomeRow
import com.nuvio.tv.ui.screens.home.HomeScreenFocusState
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.screens.home.HomeViewModel
import com.nuvio.tv.ui.util.dpadVerticalFastScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val HERO_KEY = "hero_carousel"
private const val CW_KEY = "continue_watching"
private const val UPCOMING_KEY = "upcoming_section"

/**
 * Nuvio Reshaped: the Netflix-style Home. Wired exactly like Nuvio's Classic route (same view
 * model calls, same focus save/restore keys), so switching the look changes nothing else.
 */
@Composable
internal fun NetflixHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    // Nuvio's Modern layout leaves its focused backdrop here for the detail screen; this look sets
    // none, so a stale one must not carry over when the look is switched on over Modern.
    LaunchedEffect(Unit) { com.nuvio.tv.ui.screens.home.HeroBackdropState.update(null) }
    NetflixHomeContent(
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        focusState = focusState,
        scrollToTopTrigger = scrollToTopTrigger,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onRemoveContinueWatching = remember(viewModel) {
            { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
                viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
            }
        },
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onRequestTrailerPreview = remember(viewModel) { { item: MetaPreview -> viewModel.requestTrailerPreview(item) } },
        onItemFocus = remember(viewModel) { { item: MetaPreview -> viewModel.onItemFocus(item) } },
        onSaveFocusState = remember(viewModel) {
            { vi: Int, vo: Int, rk: String?, ikm: Map<String, String>, m: Map<String, Int>, ma: Map<String, String>, ri: Int, ii: Int ->
                viewModel.saveFocusState(vi, vo, rk, ikm, m, ma, ri, ii)
                viewModel.setLiveFocusedRowKey(rk)
            }
        },
        onFocusedRowKeyChanged = remember(viewModel) { { key: String? -> viewModel.setLiveFocusedRowKey(key) } },
        onRequestLazyCatalogLoad = remember(viewModel) { { key: String -> viewModel.requestLazyCatalogLoad(key) } },
    )
}

private class NetflixFocusSnapshot(var rowIndex: Int, var itemIndex: Int, var rowKey: String?)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetflixHomeContent(
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    focusState: HomeScreenFocusState,
    scrollToTopTrigger: Int,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit,
    onRequestTrailerPreview: (MetaPreview) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onSaveFocusState: (Int, Int, String?, Map<String, String>, Map<String, Int>, Map<String, String>, Int, Int) -> Unit,
    onFocusedRowKeyChanged: (String?) -> Unit,
    onRequestLazyCatalogLoad: (String) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val nestedPrefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 2) }
    val columnListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset,
        prefetchStrategy = nestedPrefetchStrategy
    )

    val heroVisible = uiState.heroSectionEnabled && uiState.heroItems.isNotEmpty()
    var billboardFocused by remember { mutableStateOf(false) }

    // The page never scrolls by focus' bring-into-view: the follower below moves the focused row
    // to the top line instead, tracking it live, so rows above shrinking or growing mid-move can't
    // make it overshoot and bounce back.
    val verticalSpec = remember {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = spring()
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
        }
    }
    // Lazy key of the focused row (null: the billboard or nothing). Followed by key, not index:
    // rows that finish loading empty vanish from the page, shifting every index below them.
    val focusedListKey = remember { mutableStateOf<Any?>(null) }
    // The page's item keys as last composed, for finding a followed row that is off screen.
    val listKeys = remember { mutableStateOf<List<Any>>(emptyList()) }

    val isFastScrollingState = remember { mutableStateOf(false) }
    LaunchedEffect(columnListState) {
        // Shared across targets, so a held Down keeps gliding instead of stopping at every row.
        val velocity = floatArrayOf(0f)
        snapshotFlow { focusedListKey.value }.collectLatest { target ->
            if (target == null) {
                velocity[0] = 0f
                return@collectLatest
            }
            try {
                followRowToTop(
                    columnListState,
                    target,
                    indexOf = { listKeys.value.indexOf(target) },
                    velocityHolder = velocity,
                    isFastScrolling = { isFastScrollingState.value }
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Another scroll (held D-pad, scroll to top) took over; wait for the next row.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
            }
        }
    }

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) columnListState.scrollToItem(0, 0)
    }
    LaunchedEffect(billboardFocused) {
        if (billboardFocused && (columnListState.firstVisibleItemIndex != 0 || columnListState.firstVisibleItemScrollOffset != 0)) {
            columnListState.animateScrollToItem(0)
        }
    }

    val snapshot = remember {
        NetflixFocusSnapshot(focusState.focusedRowIndex, focusState.focusedItemIndex, focusState.focusedRowKey)
    }
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val rowFocusedItemIndex = remember { mutableMapOf<String, Int>() }
    val previousRowItemKeys = remember { mutableMapOf<String, List<String>>() }
    val cwItemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val upcomingItemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val cwRowFocusRequester = remember { FocusRequester() }
    val upcomingRowFocusRequester = remember { FocusRequester() }
    val cwListState = rememberLazyListState()
    val upcomingListState = rememberLazyListState()
    val lastFocusedCwIndex = rememberSaveable { mutableIntStateOf(-1) }
    val lastFocusedUpcomingIndex = rememberSaveable { mutableIntStateOf(-1) }
    val contentHasFocus = remember { mutableStateOf(false) }
    val cwFocusedIndex = remember { mutableIntStateOf(-1) }
    val activeRowKeyState = remember { mutableStateOf<String?>(null) }
    // The focused title's place in the active row, kept as state so Back's enabled flag below
    // follows it without the whole page recomposing on every focus step.
    val activeItemIndex = remember { mutableIntStateOf(0) }
    val cwPendingScrollToStart = remember { mutableIntStateOf(0) }
    val upcomingPendingScrollToStart = remember { mutableIntStateOf(0) }

    // Back inside a row returns to its first title before Back leaves Home (Nuvio's behaviour).
    val backReturnsToFirst by remember {
        derivedStateOf { contentHasFocus.value && activeRowKeyState.value != null && activeItemIndex.intValue > 0 }
    }
    BackHandler(enabled = backReturnsToFirst) {
        val rowKey = activeRowKeyState.value ?: return@BackHandler
        activeItemIndex.intValue = 0
        if (rowKey == CW_KEY || rowKey == UPCOMING_KEY) {
            cwFocusedIndex.intValue = 0
            snapshot.itemIndex = 0
            if (rowKey == CW_KEY) cwPendingScrollToStart.intValue++ else upcomingPendingScrollToStart.intValue++
        } else {
            val listState = rowStates[rowKey]
            rowFocusedItemIndex[rowKey] = 0
            snapshot.itemIndex = 0
            scope.launch {
                listState?.scrollToItem(0, 0)
                rowFocusRequesters[rowKey]?.let { runCatching { it.requestFocus() } }
            }
        }
    }

    var restoringFocus by remember { mutableStateOf(focusState.hasSavedFocus) }
    val heroFocusRequester = remember { FocusRequester() }
    val shouldRequestInitialFocus = remember(focusState) {
        !focusState.hasSavedFocus && focusState.verticalScrollIndex == 0 && focusState.verticalScrollOffset == 0
    }
    // catalogRows changes with every enrichment; it only matters while homeRows is still empty.
    val visibleHomeRows = remember(uiState.homeRows, uiState.catalogRows.takeIf { uiState.homeRows.isEmpty() }) {
        netflixHomeRows(uiState.homeRows, uiState.catalogRows)
    }
    // Each row's lazy key: its catalog's own key, numbered only when a catalog repeats, so a row
    // keeps its key (and its focus and scroll) when rows above it come or go.
    val homeRowKeys = remember(visibleHomeRows) { netflixRowKeys(visibleHomeRows) }
    val visibleRowKeys = remember(visibleHomeRows) {
        visibleHomeRows.mapTo(mutableSetOf()) { row ->
            when (row) {
                is HomeRow.Catalog -> row.row.stableKey()
                is HomeRow.CollectionRow -> "collection_${row.collection.id}"
                is HomeRow.PlaceholderCatalog -> row.stableCatalogKey
            }
        }
    }
    LaunchedEffect(visibleRowKeys) {
        rowStates.keys.retainAll(visibleRowKeys)
        rowFocusRequesters.keys.retainAll(visibleRowKeys)
    }

    DisposableEffect(Unit) {
        onDispose {
            onSaveFocusState(
                columnListState.firstVisibleItemIndex,
                columnListState.firstVisibleItemScrollOffset,
                snapshot.rowKey,
                emptyMap(),
                focusState.catalogRowScrollStates + rowStates.mapValues { it.value.firstVisibleItemIndex },
                focusState.catalogRowScrollAnchors,
                snapshot.rowIndex,
                snapshot.itemIndex
            )
        }
    }

    LaunchedEffect(shouldRequestInitialFocus, heroVisible) {
        if (!shouldRequestInitialFocus || !heroVisible) return@LaunchedEffect
        columnListState.scrollToItem(0)
        repeat(8) {
            withFrameNanos { }
            if (runCatching { heroFocusRequester.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    val shouldRestoreHeroFocus = restoringFocus && heroVisible && focusState.focusedRowKey == HERO_KEY
    LaunchedEffect(shouldRestoreHeroFocus) {
        if (!shouldRestoreHeroFocus) return@LaunchedEffect
        columnListState.scrollToItem(0)
        repeat(8) {
            withFrameNanos { }
            if (runCatching { heroFocusRequester.requestFocus(); true }.getOrDefault(false)) {
                restoringFocus = false
                return@LaunchedEffect
            }
        }
    }

    val stableTrailerPreviewUrls = remember { mutableStateOf(trailerPreviewUrls) }.apply { value = trailerPreviewUrls }
    val stableTrailerPreviewAudioUrls = remember { mutableStateOf(trailerPreviewAudioUrls) }.apply { value = trailerPreviewAudioUrls }
    val savedHeroIndex = rememberSaveable { mutableIntStateOf(0) }
    val latestOnItemFocus by rememberUpdatedState(onItemFocus)
    val latestOnRequestTrailerPreview by rememberUpdatedState(onRequestTrailerPreview)

    // Read in a flow, not in composition: a focus step must not recompose the whole page.
    val focusedCatalogItem = remember { mutableStateOf<MetaPreview?>(null) }
    val latestTrailerPreviewUrls = rememberUpdatedState(trailerPreviewUrls)
    if (uiState.focusedPosterBackdropTrailerEnabled) {
        LaunchedEffect(Unit) {
            snapshotFlow { focusedCatalogItem.value }.collectLatest { item ->
                if (item == null || latestTrailerPreviewUrls.value.containsKey(item.id)) return@collectLatest
                delay(150)
                latestOnRequestTrailerPreview(item)
            }
        }
    }
    val handleMetaFocus: (MetaPreview) -> Unit = remember(uiState.focusedPosterBackdropTrailerEnabled) {
        { item ->
            billboardFocused = false
            if (uiState.focusedPosterBackdropTrailerEnabled) focusedCatalogItem.value = item
            latestOnItemFocus(item)
        }
    }

    // Like Classic: on a fresh start, wait briefly for the hero so first focus lands on it.
    val heroExpected = uiState.heroSectionEnabled
    var heroDeferTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(shouldRequestInitialFocus, heroExpected) {
        if (!shouldRequestInitialFocus || !heroExpected) return@LaunchedEffect
        delay(2000)
        heroDeferTimedOut = true
    }
    if (shouldRequestInitialFocus && heroExpected && !heroVisible && !heroDeferTimedOut) {
        Box(modifier = Modifier.fillMaxSize().background(NetflixTokens.page), contentAlignment = Alignment.Center) {
            if (!LocalStartupSplashEnabled.current) LoadingIndicator()
        }
        return
    }

    // Lazy catalog loading, as in Nuvio's Classic layout, reading the sections above the rows as
    // they are now (Continue Watching often arrives after the first rows).
    val latestOnRequestLazyCatalogLoad = rememberUpdatedState(onRequestLazyCatalogLoad)
    val latestVisibleHomeRows = rememberUpdatedState(visibleHomeRows)
    val latestRowsOffset = rememberUpdatedState(
        (if (heroVisible) 1 else 0) +
            (if (uiState.continueWatchingEnabled && uiState.continueWatchingItems.isNotEmpty()) 1 else 0) +
            (if (uiState.continueWatchingEnabled && uiState.upcomingItems.isNotEmpty()) 1 else 0)
    )
    LaunchedEffect(columnListState) {
        snapshotFlow {
            val info = columnListState.layoutInfo
            (info.visibleItemsInfo.firstOrNull()?.index ?: -1) to (info.visibleItemsInfo.lastOrNull()?.index ?: -1)
        }.collectLatest { (firstVisible, lastVisible) ->
            if (lastVisible < 0) return@collectLatest
            delay(240)
            val rows = latestVisibleHomeRows.value
            val rowsOffset = latestRowsOffset.value
            for (idx in firstVisible.coerceAtLeast(0)..(lastVisible + 1)) {
                val row = rows.getOrNull(idx - rowsOffset) ?: continue
                if (row is HomeRow.Catalog && row.row.isLoading &&
                    row.row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true
                ) {
                    latestOnRequestLazyCatalogLoad.value(row.row.legacyKey())
                }
            }
        }
    }

    val (cwCardWidth, cwImageHeight) = remember(uiState.continueWatchingCardStyle) {
        when (uiState.continueWatchingCardStyle) {
            ContinueWatchingCardStyle.POSTER -> NetflixTokens.tileWidth to NetflixTokens.tileHeight
            ContinueWatchingCardStyle.WIDE -> 380.dp to 152.dp
            ContinueWatchingCardStyle.CARD -> 300.dp to 169.dp
        }
    }
    val cwPosterPattern = patternForScreen(uiState.customPosterUrlPattern, CustomPosterScreen.CONTINUE_WATCHING, uiState.customPosterEnabledScreens)
    val contentFocusRequester = LocalContentFocusRequester.current

    // Accent glow behind the billboard (the colour of the featured title), fading away as the
    // page scrolls to the rows. Colour and fade are read while drawing only: no recomposition.
    val accentItem = if (heroVisible) uiState.heroItems.getOrNull(savedHeroIndex.intValue) else null
    val accent = rememberNetflixAccent(accentItem?.let { it.background ?: it.landscapePoster ?: it.poster })
    val glowFadePx = with(density) { NetflixTokens.billboardHeight.toPx() }
    Box(modifier = Modifier.fillMaxSize().background(NetflixTokens.page)) {
    if (heroVisible) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(NetflixTokens.billboardHeight + 220.dp)
                .drawBehind {
                    val fade = if (columnListState.firstVisibleItemIndex > 0) 0f
                    else 1f - (columnListState.firstVisibleItemScrollOffset / glowFadePx).coerceIn(0f, 1f)
                    val color = accent.value
                    if (fade <= 0f || color.alpha <= 0f) return@drawBehind
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to color.copy(alpha = 0.78f * fade),
                            0.55f to color.copy(alpha = 0.42f * fade),
                            1f to Color.Transparent
                        )
                    )
                }
        )
    }
    val heroCount = if (heroVisible) 1 else 0
    val cwCount = if (uiState.continueWatchingEnabled && uiState.continueWatchingItems.isNotEmpty()) 1 else 0
    val upcomingCount = if (uiState.continueWatchingEnabled && uiState.upcomingItems.isNotEmpty()) 1 else 0
    val pageKeys = buildList<Any> {
        if (heroVisible) add(HERO_KEY)
        if (cwCount > 0) add(CW_KEY)
        if (upcomingCount > 0) add(UPCOMING_KEY)
        addAll(homeRowKeys)
    }
    SideEffect { listKeys.value = pageKeys }
    CompositionLocalProvider(LocalBringIntoViewSpec provides verticalSpec) {
        LazyColumn(
            state = columnListState,
            modifier = Modifier
                .fillMaxSize()
                // The page starts under the pill's band and is clipped there (lazy lists clip to their
                // bounds), so rows scroll away under a clear band instead of behind the pill.
                .padding(top = NetflixTokens.pillBand)
                .onFocusChanged { contentHasFocus.value = it.hasFocus }
                .focusRequester(contentFocusRequester)
                .focusRestorer()
                .dpadVerticalFastScroll(
                    scrollableState = columnListState,
                    onFastScrollingChanged = { isFastScrollingState.value = it },
                    resolveVerticalLanding = { sign ->
                        val layoutInfo = columnListState.layoutInfo
                        val visibleItems = layoutInfo.visibleItemsInfo
                        val lastIdx = layoutInfo.totalItemsCount - 1
                        val lastItemAtBottom = lastIdx >= 0 &&
                            visibleItems.lastOrNull { it.index == lastIdx }?.let {
                                it.offset + it.size <= layoutInfo.viewportEndOffset
                            } == true
                        val upwardTopItem = if (sign < 0) visibleItems.firstOrNull()?.takeIf { it.offset > -it.size / 2 } else null
                        val target = when {
                            lastItemAtBottom -> visibleItems.lastOrNull { it.index == lastIdx }
                            upwardTopItem != null -> upwardTopItem
                            else -> visibleItems.firstOrNull { it.offset >= 0 } ?: visibleItems.firstOrNull()
                        }
                        fun requesterForKey(k: String?): FocusRequester? = when {
                            k == null -> null
                            k == HERO_KEY -> heroFocusRequester
                            rowFocusRequesters.containsKey(k) -> rowFocusRequesters[k]
                            else -> rowFocusRequesters[k.substringBeforeLast('#')]
                        }
                        val requester = target?.let { requesterForKey(it.key as? String) }
                            ?: visibleItems.firstNotNullOfOrNull { requesterForKey(it.key as? String) }
                        requester?.let { req ->
                            scope.launch {
                                repeat(6) {
                                    if (runCatching { req.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return@launch
                                    withFrameNanos { }
                                }
                            }
                        }
                        null
                    },
                ),
            contentPadding = PaddingValues(top = NetflixTokens.listTopGap, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(NetflixTokens.rowGap)
        ) {
            if (heroVisible) {
                item(key = HERO_KEY, contentType = "netflix_billboard") {
                    NetflixBillboard(
                        items = uiState.heroItems,
                        initialIndex = savedHeroIndex.intValue,
                        focusRequester = heroFocusRequester,
                        showImdbRatings = uiState.homeImdbRatingsVisibility.showRatings,
                        onActiveIndexChanged = { savedHeroIndex.intValue = it },
                        onFocused = { item ->
                            billboardFocused = true
                            focusedListKey.value = null
                            snapshot.rowIndex = -2
                            snapshot.itemIndex = 0
                            snapshot.rowKey = HERO_KEY
                            activeRowKeyState.value = null
                            latestOnItemFocus(item)
                        },
                        onClick = { item -> onNavigateToDetail(item.id, item.apiType, "") }
                    )
                }
            }

            if (uiState.continueWatchingEnabled && uiState.continueWatchingItems.isNotEmpty()) {
                item(key = CW_KEY, contentType = CW_KEY) {
                    LaunchedEffect(cwPendingScrollToStart.intValue) {
                        if (cwPendingScrollToStart.intValue > 0) {
                            cwListState.scrollToItem(0, 0)
                            runCatching { cwRowFocusRequester.requestFocus() }
                            cwPendingScrollToStart.intValue = 0
                        }
                    }
                    ContinueWatchingSection(
                        items = uiState.continueWatchingItems.withCustomPosterUrls(cwPosterPattern),
                        onItemClick = onContinueWatchingClick,
                        onStartFromBeginning = onContinueWatchingStartFromBeginning,
                        showManualPlayOption = showContinueWatchingManualPlayOption,
                        onPlayManually = onContinueWatchingPlayManually,
                        onDetailsClick = { item -> onNavigateToDetail(item.contentIdForDetail(), item.contentTypeForDetail(), "") },
                        onRemoveItem = { item -> item.remove(onRemoveContinueWatching) },
                        focusedItemIndex = when {
                            focusState.hasSavedFocus && focusState.focusedRowIndex == -1 -> focusState.focusedItemIndex
                            shouldRequestInitialFocus && !heroVisible -> 0
                            else -> -1
                        },
                        onItemFocused = { itemIndex ->
                            billboardFocused = false
                            snapshot.rowIndex = -1
                            snapshot.itemIndex = itemIndex
                            snapshot.rowKey = CW_KEY
                            focusedListKey.value = CW_KEY
                            activeRowKeyState.value = CW_KEY
                            cwFocusedIndex.intValue = itemIndex
                            activeItemIndex.intValue = itemIndex
                            onFocusedRowKeyChanged(null)
                        },
                        blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                        useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                        focusRequesters = cwItemFocusRequesters,
                        rowFocusRequester = cwRowFocusRequester,
                        lastFocusedIndexState = lastFocusedCwIndex,
                        cardWidth = cwCardWidth,
                        imageHeight = cwImageHeight,
                        cardStyle = uiState.continueWatchingCardStyle,
                        cornerRadius = NetflixTokens.tileCorner,
                        listState = cwListState
                    )
                }
            }

            if (uiState.continueWatchingEnabled && uiState.upcomingItems.isNotEmpty()) {
                item(key = UPCOMING_KEY, contentType = UPCOMING_KEY) {
                    LaunchedEffect(upcomingPendingScrollToStart.intValue) {
                        if (upcomingPendingScrollToStart.intValue > 0) {
                            upcomingListState.scrollToItem(0, 0)
                            runCatching { upcomingRowFocusRequester.requestFocus() }
                            upcomingPendingScrollToStart.intValue = 0
                        }
                    }
                    ContinueWatchingSection(
                        items = uiState.upcomingItems.withCustomPosterUrls(cwPosterPattern),
                        title = stringResource(R.string.upcoming_section_title),
                        onItemClick = onContinueWatchingClick,
                        onStartFromBeginning = onContinueWatchingStartFromBeginning,
                        showManualPlayOption = showContinueWatchingManualPlayOption,
                        onPlayManually = onContinueWatchingPlayManually,
                        onDetailsClick = { item -> onNavigateToDetail(item.contentIdForDetail(), item.contentTypeForDetail(), "") },
                        onRemoveItem = { item -> item.remove(onRemoveContinueWatching) },
                        blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                        useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                        focusRequesters = upcomingItemFocusRequesters,
                        rowFocusRequester = upcomingRowFocusRequester,
                        lastFocusedIndexState = lastFocusedUpcomingIndex,
                        onItemFocused = { itemIndex ->
                            billboardFocused = false
                            snapshot.rowIndex = -1
                            snapshot.itemIndex = itemIndex
                            snapshot.rowKey = UPCOMING_KEY
                            focusedListKey.value = UPCOMING_KEY
                            activeRowKeyState.value = UPCOMING_KEY
                            cwFocusedIndex.intValue = itemIndex
                            activeItemIndex.intValue = itemIndex
                            onFocusedRowKeyChanged(null)
                        },
                        cardWidth = cwCardWidth,
                        imageHeight = cwImageHeight,
                        cardStyle = uiState.continueWatchingCardStyle,
                        cornerRadius = NetflixTokens.tileCorner,
                        listState = upcomingListState
                    )
                }
            }

            itemsIndexed(
                items = visibleHomeRows,
                key = { index, _ -> homeRowKeys[index] },
                contentType = { _, item ->
                    when (item) {
                        is HomeRow.Catalog, is HomeRow.PlaceholderCatalog -> "netflix_row"
                        is HomeRow.CollectionRow -> "collection_row"
                    }
                }
            ) { index, homeRow ->
                when (homeRow) {
                    is HomeRow.Catalog -> {
                        val catalogRow = homeRow.row
                        val catalogKey = catalogRow.stableKey()
                        val currentItemKeys = remember(catalogRow.items) { catalogRow.stableItemKeys() }
                        rowFocusedItemIndex[catalogKey]?.let { storedIdx ->
                            previousRowItemKeys[catalogKey]
                                ?.getOrNull(storedIdx)
                                ?.let { currentItemKeys.indexOf(it) }
                                ?.takeIf { it >= 0 && it != storedIdx }
                                ?.let {
                                    rowFocusedItemIndex[catalogKey] = it
                                    if (activeRowKeyState.value == catalogKey) activeItemIndex.intValue = it
                                }
                        }
                        previousRowItemKeys[catalogKey] = currentItemKeys
                        val shouldRestoreFocus = restoringFocus &&
                            (snapshot.rowKey == catalogKey || index == focusState.focusedRowIndex)
                        val shouldInitialFocusFirstRow = shouldRequestInitialFocus && !heroVisible &&
                            (!uiState.continueWatchingEnabled || uiState.continueWatchingItems.isEmpty()) && index == 0
                        val focusedItemIndex = when {
                            shouldRestoreFocus -> focusState.focusedItemIndex
                            shouldInitialFocusFirstRow -> 0
                            else -> -1
                        }
                        val listState = rowStates.getOrPut(catalogKey) {
                            LazyListState(firstVisibleItemIndex = focusState.catalogRowScrollStates[catalogKey] ?: 0)
                        }
                        NetflixCatalogRow(
                            catalogRow = catalogRow,
                            showImdbRatings = uiState.homeImdbRatingsVisibility.showRatings,
                            showAddonName = uiState.catalogAddonNameEnabled,
                            showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                            trailerEnabled = uiState.focusedPosterBackdropTrailerEnabled,
                            trailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                            trailerPreviewUrls = stableTrailerPreviewUrls.value,
                            trailerPreviewAudioUrls = stableTrailerPreviewAudioUrls.value,
                            onItemFocus = handleMetaFocus,
                            isItemWatched = isCatalogItemWatched,
                            onItemLongPress = onCatalogItemLongPress,
                            onItemClick = onNavigateToDetail,
                            onSeeAll = {
                                onNavigateToCatalogSeeAll(catalogRow.catalogId, catalogRow.addonId, catalogRow.apiType)
                            },
                            rowFocusRequester = rowFocusRequesters.getOrPut(catalogKey) { FocusRequester() },
                            listState = listState,
                            focusedItemIndex = focusedItemIndex,
                            restorerFocusedIndex = rowFocusedItemIndex[catalogKey] ?: focusedItemIndex,
                            onItemFocused = { itemIndex ->
                                if (!shouldRestoreFocus || itemIndex == focusedItemIndex) {
                                    if (restoringFocus) restoringFocus = false
                                    snapshot.rowIndex = index
                                    snapshot.itemIndex = itemIndex
                                    snapshot.rowKey = catalogKey
                                    billboardFocused = false
                                    focusedListKey.value = homeRowKeys.getOrNull(index)
                                    activeRowKeyState.value = catalogKey
                                    activeItemIndex.intValue = itemIndex
                                    onFocusedRowKeyChanged(catalogKey)
                                    rowFocusedItemIndex[catalogKey] = itemIndex
                                }
                            }
                        )
                    }

                    is HomeRow.CollectionRow -> {
                        val collectionKey = "collection_${homeRow.collection.id}"
                        val shouldRestoreCollectionFocus = restoringFocus &&
                            (snapshot.rowKey == collectionKey || index == focusState.focusedRowIndex)
                        val listState = rowStates.getOrPut(collectionKey) {
                            LazyListState(firstVisibleItemIndex = focusState.catalogRowScrollStates[collectionKey] ?: 0)
                        }
                        CollectionRowSection(
                            collection = homeRow.collection,
                            onFolderClick = onNavigateToFolderDetail,
                            listState = listState,
                            posterCardStyle = posterCardStyle,
                            focusedItemIndex = if (shouldRestoreCollectionFocus) focusState.focusedItemIndex else -1,
                            rowFocusRequester = rowFocusRequesters.getOrPut(collectionKey) { FocusRequester() },
                            onItemFocused = { itemIndex ->
                                billboardFocused = false
                                if (restoringFocus) restoringFocus = false
                                snapshot.rowIndex = index
                                snapshot.itemIndex = itemIndex
                                snapshot.rowKey = collectionKey
                                focusedListKey.value = homeRowKeys.getOrNull(index)
                                activeRowKeyState.value = collectionKey
                                activeItemIndex.intValue = itemIndex
                                onFocusedRowKeyChanged(null)
                                rowFocusedItemIndex[collectionKey] = itemIndex
                            }
                        )
                    }

                    is HomeRow.PlaceholderCatalog -> Unit
                }
            }
        }
    }
    }
}

private fun ContinueWatchingItem.contentIdForDetail(): String = when (this) {
    is ContinueWatchingItem.InProgress -> progress.contentId
    is ContinueWatchingItem.NextUp -> info.contentId
}

private fun ContinueWatchingItem.contentTypeForDetail(): String = when (this) {
    is ContinueWatchingItem.InProgress -> progress.contentType
    is ContinueWatchingItem.NextUp -> info.contentType
}

private fun ContinueWatchingItem.remove(onRemove: (String, Int?, Int?, Boolean) -> Unit) = when (this) {
    is ContinueWatchingItem.InProgress -> onRemove(progress.contentId, progress.season, progress.episode, false)
    is ContinueWatchingItem.NextUp -> onRemove(info.contentId, info.seedSeason, info.seedEpisode, true)
}

/**
 * Moves the page so the row at [index] sits at the top line (offset 0, just under the pill band)
 * on a critically damped spring that follows the row's live position every frame: if rows above
 * fold their details while it moves, it never overshoots and bounces back.
 */
private suspend fun followRowToTop(
    listState: androidx.compose.foundation.lazy.LazyListState,
    key: Any,
    indexOf: () -> Int,
    velocityHolder: FloatArray,
    isFastScrolling: () -> Boolean,
) {
    val omega = kotlin.math.sqrt(NetflixTokens.SCROLL_STIFFNESS)
    var velocity by FloatHolderDelegate(velocityHolder)
    var lastFrame = 0L
    var elapsedNanos = 0L
    var jumps = 0
    while (true) {
        val frame = withFrameNanos { it }
        val dt = if (lastFrame == 0L) 1f / 60f else ((frame - lastFrame) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        if (lastFrame != 0L) elapsedNanos += frame - lastFrame
        lastFrame = frame
        if (isFastScrolling()) {
            velocity = 0f
            continue
        }
        val layoutInfo = listState.layoutInfo
        val visible = layoutInfo.visibleItemsInfo
        val info = visible.firstOrNull { it.key == key }
        val error = if (info != null) {
            info.offset.toFloat()
        } else {
            val index = indexOf()
            if (index < 0 || index >= layoutInfo.totalItemsCount) return // the row went away
            // Just off screen (the row above sits beyond the band): head for it with an estimate,
            // so it glides in like any other row; real offsets take over once it shows.
            val first = visible.firstOrNull()
            val last = visible.lastOrNull()
            if (first == null || last == null) return
            val spacing = layoutInfo.mainAxisItemSpacing
            val averageStep = visible.sumOf { it.size + spacing }.toFloat() / visible.size
            val estimate = when {
                index < first.index -> first.offset - (first.index - index) * averageStep
                else -> last.offset + (index - last.index) * averageStep
            }
            if (kotlin.math.abs(index - (if (index < first.index) first.index else last.index)) > FOLLOW_MAX_GLIDE_ROWS) {
                // Far away (restored focus): jump there, then settle.
                if (++jumps > 2) return
                listState.scrollToItem(index)
                velocity = 0f
                continue
            }
            estimate
        }
        if (elapsedNanos > FOLLOW_MIN_NANOS && kotlin.math.abs(error) < 0.5f && kotlin.math.abs(velocity) < 8f) {
            if (error != 0f) listState.scrollBy(error)
            return
        }
        // Exact step of the critically damped spring over dt. A plain Euler step here went
        // unstable once frames took longer than about 40 ms (a busy TV): every step overshot
        // further than the last, so the page swung up and down with growing jumps.
        val decay = kotlin.math.exp(-omega * dt)
        val errorRate = -velocity
        val b = errorRate + omega * error
        val nextError = (error + b * dt) * decay
        velocity = -(errorRate - omega * b * dt) * decay
        val delta = error - nextError
        val consumed = listState.scrollBy(delta)
        // Top or bottom of the page: nothing left to move.
        if (kotlin.math.abs(delta) > 0.5f && kotlin.math.abs(consumed) < 0.01f && elapsedNanos > FOLLOW_MIN_NANOS) return
    }
}

/** Beyond this many rows off screen, the follower jumps instead of gliding. */
private const val FOLLOW_MAX_GLIDE_ROWS = 3

/** The rows' details open and fold over about this long; keep following until then. */
private const val FOLLOW_MIN_NANOS = 450_000_000L

/** Lazy keys for [rows]: a catalog's own key, with "#n" added only to its repeats. */
internal fun netflixRowKeys(rows: List<HomeRow>): List<String> {
    val seen = HashMap<String, Int>()
    return rows.map { row ->
        val base = when (row) {
            is HomeRow.Catalog -> row.row.stableKey()
            is HomeRow.CollectionRow -> "collection_${row.collection.id}"
            is HomeRow.PlaceholderCatalog -> row.stableCatalogKey
        }
        val repeat = seen.getOrDefault(base, 0)
        seen[base] = repeat + 1
        if (repeat == 0) base else "$base#$repeat"
    }
}

private class FloatHolderDelegate(private val holder: FloatArray) {
    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Float = holder[0]
    operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Float) { holder[0] = value }
}

/**
 * Nuvio's home rows, shaped the way this look draws them whatever Nuvio layout is set underneath.
 * Under Modern (Nuvio's default) catalogs not loaded yet arrive as placeholders; they become the
 * same loading rows Classic gets, which load as they near the screen.
 */
internal fun netflixHomeRows(homeRows: List<HomeRow>, catalogRows: List<CatalogRow>): List<HomeRow> {
    if (homeRows.isEmpty()) return catalogRows.filter { it.items.isNotEmpty() }.map { HomeRow.Catalog(it) }
    if (homeRows.none { it is HomeRow.PlaceholderCatalog }) return homeRows
    return homeRows.map { row -> if (row is HomeRow.PlaceholderCatalog) HomeRow.Catalog(row.toLoadingRow()) else row }
}

/** The same eight shimmering placeholders Nuvio's pipeline builds for Classic. */
private fun HomeRow.PlaceholderCatalog.toLoadingRow(): CatalogRow {
    val type = com.nuvio.tv.domain.model.ContentType.fromString(apiType)
    return CatalogRow(
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = addonBaseUrl,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        rawType = apiType,
        items = (0 until 8).map { i ->
            MetaPreview(
                id = "__placeholder_${catalogKey}_$i",
                type = type,
                rawType = apiType,
                name = " ",
                poster = PLACEHOLDER_IMAGE_URL,
                posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                background = null,
                logo = null,
                description = null,
                releaseInfo = " ",
                imdbRating = null,
                genres = emptyList()
            )
        },
        isLoading = true,
        hasMore = false
    )
}
