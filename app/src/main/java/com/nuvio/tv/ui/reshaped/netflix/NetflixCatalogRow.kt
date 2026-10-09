package com.nuvio.tv.ui.reshaped.netflix

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import kotlin.math.roundToInt
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.animation.Crossfade
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.stableItemKey
import com.nuvio.tv.domain.model.stableItemKeys
import com.nuvio.tv.domain.model.stableKey
import com.nuvio.tv.ui.components.rememberPlaceholderShimmerOffsetState
import com.nuvio.tv.ui.util.localizedContentType

/**
 * One Netflix-style row. The focus logic (placeholder key pinning, index relocation on refresh,
 * restore after returning from details) is carried over unchanged from Nuvio's
 * CatalogRowSection, so the row behaves exactly like Nuvio's; only the look differs:
 * the focused tile stays pinned at the left edge and its details appear underneath the row.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun NetflixCatalogRow(
    catalogRow: CatalogRow,
    onItemClick: (String, String, String) -> Unit,
    onSeeAll: () -> Unit,
    listState: LazyListState,
    rowFocusRequester: FocusRequester,
    showImdbRatings: Boolean,
    showAddonName: Boolean,
    showCatalogTypeSuffix: Boolean,
    trailerEnabled: Boolean,
    trailerMuted: Boolean,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    onItemFocus: (MetaPreview) -> Unit,
    isItemWatched: (MetaPreview) -> Boolean,
    onItemLongPress: (MetaPreview, String) -> Unit,
    focusedItemIndex: Int,
    restorerFocusedIndex: Int,
    onItemFocused: (itemIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    seeAllLabel: String? = null,
    showSeeAll: Boolean = catalogRow.hasMore || catalogRow.items.size >= 15,
) {
    val catalogRowKey = remember(catalogRow) { catalogRow.stableKey() }
    val rowItemIdentities = remember(catalogRow.items) { catalogRow.stableItemKeys() }

    // Same placeholder key pinning as CatalogRowSection: keeps the focused node alive when a
    // lazily loaded row swaps its placeholder for real data.
    val firstCardKey = remember(catalogRowKey) { catalogRowKey + "__first" }
    val firstIsPlaceholder = catalogRow.items.firstOrNull()?.id?.startsWith("__placeholder_") == true
    val pinFirstCard = remember(catalogRowKey) { mutableStateOf(firstIsPlaceholder) }
    val pinSpent = remember(catalogRowKey) { mutableStateOf(false) }
    val pinnedItemKey = remember(catalogRowKey) { mutableStateOf<String?>(null) }
    if (firstIsPlaceholder && !pinSpent.value) pinFirstCard.value = true
    if (pinFirstCard.value && !firstIsPlaceholder && pinnedItemKey.value == null) {
        pinnedItemKey.value = rowItemIdentities.firstOrNull()
    }

    fun rowItemFocusKey(index: Int, item: MetaPreview): String {
        val identity = rowItemIdentities.getOrElse(index) { catalogRow.stableItemKey(item) }
        if (!pinFirstCard.value) return identity
        val pinned = pinnedItemKey.value
        return when {
            pinned != null -> if (identity == pinned) firstCardKey else identity
            index == 0 -> firstCardKey
            else -> identity
        }
    }

    val itemFocusRequestersByKey = remember { mutableMapOf<String, FocusRequester>() }
    var lastRequestedFocusItemKey by remember { mutableStateOf<String?>(null) }
    val lastFocusedItemIndex = remember { mutableIntStateOf(-1) }
    val previousRowItemKeys = remember { mutableStateOf<List<String>>(emptyList()) }
    if (previousRowItemKeys.value !== rowItemIdentities) {
        val storedIndex = lastFocusedItemIndex.intValue
        val previousKeys = previousRowItemKeys.value
        if (storedIndex >= 0 && previousKeys.isNotEmpty()) {
            val wanted = previousKeys.getOrNull(storedIndex)
            val relocated = wanted?.let { rowItemIdentities.indexOf(it) } ?: -1
            if (relocated != storedIndex) lastFocusedItemIndex.intValue = relocated
        }
        previousRowItemKeys.value = rowItemIdentities
    }

    val blockingFocusExit = remember { mutableStateOf(false) }
    val rowHasFocusRef = remember { mutableStateOf(false) }
    var rowHasFocus by remember { mutableStateOf(false) }
    val firstItemId = catalogRow.items.firstOrNull()?.id
    val wasPlaceholderRef = remember { mutableStateOf(firstItemId?.startsWith("__placeholder_") == true) }
    val isNowReal = firstItemId?.startsWith("__placeholder_") != true
    if (wasPlaceholderRef.value && isNowReal && rowHasFocusRef.value) {
        blockingFocusExit.value = true
    }
    wasPlaceholderRef.value = firstItemId?.startsWith("__placeholder_") == true
    if (pinFirstCard.value && !firstIsPlaceholder && !rowHasFocusRef.value) {
        pinFirstCard.value = false
        pinnedItemKey.value = null
        pinSpent.value = true
    }

    LaunchedEffect(blockingFocusExit.value) {
        if (!blockingFocusExit.value) return@LaunchedEffect
        val targetKey = rowItemFocusKey(0, catalogRow.items.firstOrNull() ?: run {
            blockingFocusExit.value = false
            return@LaunchedEffect
        })
        repeat(15) {
            val req = itemFocusRequestersByKey[targetKey]
            if (req != null) {
                val ok = runCatching { req.requestFocus(); true }.getOrDefault(false)
                if (ok) { blockingFocusExit.value = false; return@LaunchedEffect }
            }
            withFrameNanos { }
        }
        blockingFocusExit.value = false
    }

    val latestOnItemClick by rememberUpdatedState(onItemClick)
    val latestOnItemFocus by rememberUpdatedState(onItemFocus)
    val latestIsItemWatched by rememberUpdatedState(isItemWatched)
    val latestOnItemLongPress by rememberUpdatedState(onItemLongPress)
    val latestOnItemFocused by rememberUpdatedState(onItemFocused)

    LaunchedEffect(catalogRow.items) {
        val validKeys = catalogRow.items.mapIndexedTo(mutableSetOf()) { index, item ->
            rowItemFocusKey(index, item)
        }
        itemFocusRequestersByKey.keys.retainAll(validKeys)
        if (lastRequestedFocusItemKey !in validKeys) lastRequestedFocusItemKey = null
    }

    LaunchedEffect(focusedItemIndex, catalogRow.items) {
        if (focusedItemIndex >= 0 && focusedItemIndex < catalogRow.items.size) {
            val targetItem = catalogRow.items[focusedItemIndex]
            val targetItemKey = rowItemFocusKey(focusedItemIndex, targetItem)
            if (lastRequestedFocusItemKey == targetItemKey) return@LaunchedEffect
            val requester = itemFocusRequestersByKey.getOrPut(targetItemKey) { FocusRequester() }
            if (!listState.isScrollInProgress) {
                runCatching { listState.scrollToItem(focusedItemIndex) }
            }
            var focused = false
            for (attempt in 0 until 6) {
                withFrameNanos { }
                runCatching { requester.requestFocus() }
                withFrameNanos { }
                focused = lastFocusedItemIndex.intValue == focusedItemIndex
                if (focused) break
            }
            if (focused) lastRequestedFocusItemKey = targetItemKey
        } else {
            lastRequestedFocusItemKey = null
        }
    }

    val context = LocalContext.current
    val typeLabel = remember(catalogRow.rawType, catalogRow.apiType, context) {
        val raw = catalogRow.rawType.takeIf { it.isNotBlank() } ?: catalogRow.apiType
        localizedContentType(context, raw)
    }
    val catalogTitle = remember(catalogRow.catalogName, typeLabel, showCatalogTypeSuffix) {
        val formattedName = catalogRow.catalogName.replaceFirstChar { it.uppercase() }
        if (formattedName.isBlank()) ""
        else if (showCatalogTypeSuffix && typeLabel.isNotEmpty()) "$formattedName - $typeLabel" else formattedName
    }

    // The focused title; its details sit under the row.
    var focusedItem by remember { mutableStateOf<MetaPreview?>(null) }
    val motionScope = rememberCoroutineScope()
    val motion = remember(catalogRowKey) { NetflixRowMotion(listState.firstVisibleItemIndex) }
    val latestItems by rememberUpdatedState(catalogRow.items)
    val expansionAt: (Int) -> Float = remember(motion) {
        { index ->
            val item = latestItems.getOrNull(index)
            if (item == null || item.id.startsWith("__placeholder_")) 0f else motion.expansionOf(index)
        }
    }
    // The row's scroll follows the motion: the title at the motion's position sits at the left
    // edge, sliding by exactly its own (shrinking) width as focus moves on. Nothing recomposes.
    val motionDensity = LocalDensity.current
    LaunchedEffect(motion, listState, motionDensity) {
        val gapPx = with(motionDensity) { NetflixTokens.tileGap.toPx() }
        val seeAllPx = with(motionDensity) { NetflixTokens.tileWidth.toPx() }
        snapshotFlow { Triple(motion.position.value, motion.openness.value, motion.held.value) }.collect { (p, _, _) ->
            if (p < 0f) return@collect
            val index = kotlin.math.floor(p).toInt()
            val fraction = p - index
            val item = latestItems.getOrNull(index)
            val width = if (item == null) seeAllPx else with(motionDensity) {
                val collapsed = item.netflixCollapsedWidth().toPx()
                collapsed + (item.netflixExpandedWidth().toPx() - collapsed) * expansionAt(index)
            }
            listState.requestScrollToItem(index, (fraction * (width + gapPx)).roundToInt())
        }
    }

    // The wide art of the titles either side of focus is fetched ahead (see netflixPreloadRowArt).
    val preloadContext = LocalContext.current
    LaunchedEffect(motion, preloadContext, motionDensity) {
        netflixPreloadRowArt(
            context = preloadContext,
            density = motionDensity,
            items = { latestItems },
            center = {
                // A held key glides past titles nobody will open: fetch nothing until it lands.
                if (rowHasFocus && motion.held.targetValue == 0f) motion.position.targetValue.roundToInt() else null
            }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth().then(
            if (blockingFocusExit.value) {
                Modifier.focusProperties {
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                }
            } else Modifier
        )
    ) {
        NetflixRowHeader(
            title = catalogTitle,
            subtitle = if (showAddonName && catalogTitle.isNotBlank()) {
                stringResource(R.string.catalog_from_addon, catalogRow.addonName)
            } else null
        )

        val density = LocalDensity.current
        val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
        // The row scrolls only through the motion above, never by focus' bring-into-view.
        val pinnedLeftSpec = remember(defaultBringIntoViewSpec) {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> = defaultBringIntoViewSpec.scrollAnimationSpec
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
            }
        }
        val usesPlaceholderShimmer = catalogRow.isLoading &&
            catalogRow.items.firstOrNull()?.poster == PLACEHOLDER_IMAGE_URL
        val placeholderShimmerOffsetState = if (usesPlaceholderShimmer) {
            rememberPlaceholderShimmerOffsetState(label = "netflixPlaceholderShimmer")
        } else null

        CompositionLocalProvider(LocalBringIntoViewSpec provides pinnedLeftSpec) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NetflixTokens.tileHeight)
                    .onFocusChanged {
                        rowHasFocusRef.value = it.hasFocus
                        if (rowHasFocus != it.hasFocus) {
                            rowHasFocus = it.hasFocus
                            motion.setOpen(it.hasFocus, motionScope)
                        }
                    }
                    // Holding Left/Right folds the row to posters (see NetflixRowMotion); never consumes keys.
                    .onPreviewKeyEvent { event ->
                        val key = event.key
                        if (key == Key.DirectionLeft || key == Key.DirectionRight) {
                            when (event.type) {
                                KeyEventType.KeyDown -> if (event.nativeKeyEvent.repeatCount > 0) motion.hold(motionScope)
                                KeyEventType.KeyUp -> motion.release(motionScope)
                            }
                        }
                        false
                    }
                    .focusRequester(rowFocusRequester)
                    .focusRestorer {
                        val visibleIndices = listState.layoutInfo.visibleItemsInfo
                            .map { it.index }
                            .filter { it in catalogRow.items.indices }
                        val preferredIndex = if (lastFocusedItemIndex.intValue >= 0) {
                            lastFocusedItemIndex.intValue
                        } else {
                            restorerFocusedIndex
                        }
                        val idx = preferredIndex.takeIf { it in visibleIndices } ?: visibleIndices.firstOrNull()
                        idx?.let { visibleIndex ->
                            catalogRow.items.getOrNull(visibleIndex)?.let { item ->
                                itemFocusRequestersByKey[rowItemFocusKey(visibleIndex, item)]
                            }
                        } ?: FocusRequester.Default
                    }
                    .focusGroup(),
                contentPadding = PaddingValues(start = NetflixTokens.pageStart, end = NetflixTokens.rowEndPadding),
                horizontalArrangement = Arrangement.spacedBy(NetflixTokens.tileGap)
            ) {
                itemsIndexed(
                    items = catalogRow.items,
                    key = { index, item -> rowItemFocusKey(index, item) },
                    contentType = { _, _ -> "netflix_tile" }
                ) { index, item ->
                    val requester = itemFocusRequestersByKey.getOrPut(rowItemFocusKey(index, item)) { FocusRequester() }
                    val isPlaceholder = item.id.startsWith("__placeholder_")
                    NetflixTile(
                        item = item,
                        isWatched = latestIsItemWatched(item),
                        focusRequester = requester,
                        expansion = { expansionAt(index) },
                        placeholderShimmerOffsetState = placeholderShimmerOffsetState,
                        trailerEnabled = trailerEnabled,
                        trailerMuted = trailerMuted,
                        trailerUrl = trailerPreviewUrls[item.id],
                        trailerAudioUrl = trailerPreviewAudioUrls[item.id],
                        onFocused = {
                            focusedItem = item.takeUnless { isPlaceholder }
                            motion.focus(index, motionScope)
                            latestOnItemFocus(item)
                            lastFocusedItemIndex.intValue = index
                            latestOnItemFocused(index)
                        },
                        onClick = {
                            if (!isPlaceholder) latestOnItemClick(item.id, item.apiType, catalogRow.addonBaseUrl)
                        },
                        onLongPress = {
                            if (!isPlaceholder) latestOnItemLongPress(item, catalogRow.addonBaseUrl)
                        },
                        modifier = if (isPlaceholder && index > 0) {
                            Modifier.focusProperties { canFocus = false }
                        } else Modifier
                    )
                }
                if (showSeeAll) {
                    item(key = "${catalogRow.type}_${catalogRow.catalogId}_see_all") {
                        NetflixSeeAllTile(
                            label = seeAllLabel ?: stringResource(R.string.action_see_all),
                            onClick = onSeeAll,
                            onFocused = {
                                focusedItem = null
                                motion.focus(catalogRow.items.size, motionScope)
                            }
                        )
                    }
                }
            }
        }

        NetflixRowDetails(motion = motion, item = focusedItem, showImdbRatings = showImdbRatings)
    }
}

@Composable
internal fun NetflixRowHeader(title: String, subtitle: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NetflixTokens.pageStart, end = NetflixTokens.pageStart, bottom = NetflixTokens.headerGap),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text = title.ifBlank { " " },
            color = NetflixTokens.textPrimary,
            fontSize = NetflixTokens.headerSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = NetflixTokens.textSecondary,
                fontSize = NetflixTokens.synopsisSize,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

/**
 * Metadata and synopsis of the focused title, directly under the wide slot. The space opens
 * with the row's openness (same spring as the tiles), so there is never an empty gap, and the
 * text crossfades from title to title as focus slides along the row.
 */
@Composable
private fun NetflixRowDetails(motion: NetflixRowMotion, item: MetaPreview?, showImdbRatings: Boolean) {
    val openness = { motion.openness.value }
    // The space stays while a key is held (no rows jumping up and down); only the text fades.
    val textAlpha = { motion.openness.value * (1f - motion.held.value) }
    val isOpen by remember(motion) { derivedStateOf { motion.openness.value > 0.001f } }
    if (!isOpen) return
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val full = NetflixTokens.metaHeight.roundToPx()
                val h = (full * openness()).roundToInt()
                val placeable = measurable.measure(constraints.copy(minHeight = full, maxHeight = full))
                layout(placeable.width, h) { placeable.place(0, 0) }
            }
            .clipToBounds()
            .graphicsLayer { alpha = textAlpha() }
    ) {
        Crossfade(
            targetState = item,
            animationSpec = tween(NetflixTokens.META_FADE_MS),
            label = "netflixRowDetails"
        ) { current ->
            if (current == null) return@Crossfade
            val context = LocalContext.current
            val tokens = remember(current.id, current.genres, current.releaseInfo, current.imdbRating, current.runtime, showImdbRatings) {
                current.netflixMetaLine(context, showImdbRatings)
            }
            Column(
                modifier = Modifier
                    .padding(start = NetflixTokens.pageStart + 4.dp, top = 12.dp)
                    .width(NetflixTokens.tileExpandedWidth)
            ) {
                NetflixMetaRow(tokens = tokens)
                val synopsis = current.description
                if (!synopsis.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = synopsis,
                        color = NetflixTokens.textSecondary,
                        fontSize = NetflixTokens.synopsisSize,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun NetflixMetaRow(tokens: List<String>) {
    val ratingFirst = tokens.firstOrNull()?.startsWith(NETFLIX_RATING_PREFIX) == true
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        tokens.forEachIndexed { index, token ->
            Text(
                text = token,
                color = if (index == 0 && ratingFirst) NetflixTokens.rating else NetflixTokens.textPrimary,
                fontSize = NetflixTokens.metaSize,
                fontWeight = if (index == 0 && ratingFirst) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetflixSeeAllTile(label: String, onClick: () -> Unit, onFocused: () -> Unit) {
    val shape = remember { RoundedCornerShape(NetflixTokens.tileCorner) }
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(NetflixTokens.tileWidth)
            .height(NetflixTokens.tileHeight)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(shape = shape),
        colors = CardDefaults.colors(
            containerColor = NetflixTokens.tilePlaceholder,
            focusedContainerColor = NetflixTokens.tilePlaceholder
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(NetflixTokens.ringWidth, NetflixTokens.ring),
                shape = shape
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = label,
                    tint = NetflixTokens.textPrimary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(text = label, color = NetflixTokens.textSecondary, fontSize = NetflixTokens.synopsisSize)
            }
        }
    }
}
