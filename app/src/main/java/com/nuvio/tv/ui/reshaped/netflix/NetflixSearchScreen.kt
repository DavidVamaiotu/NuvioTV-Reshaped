package com.nuvio.tv.ui.reshaped.netflix

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.screens.search.MIN_SEARCH_QUERY_LENGTH
import com.nuvio.tv.ui.screens.search.SearchEvent
import com.nuvio.tv.ui.screens.search.SearchViewModel
import com.nuvio.tv.ui.screens.search.shouldShowDiscoverInSearch

private val KEYS = ("abcdefghijklmnopqrstuvwxyz1234567890").map { it.toString() }
private const val KEY_COLUMNS = 6
private val keyShape = RoundedCornerShape(6.dp)
private val resultShape = RoundedCornerShape(NetflixTokens.tileCorner)

/** One title in the results grid, with the catalog it came from (for the detail screen). */
private data class NetflixSearchHit(val item: MetaPreview, val addonBaseUrl: String)

/**
 * Nuvio Reshaped: Netflix-style Search. An on-screen letter keyboard on the left, suggestions and
 * recent searches under it, and one grid of wide title cards on the right. It drives Nuvio's own
 * SearchViewModel with the same events as Nuvio's Search screen; only the look is different.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun NetflixSearchScreen(
    viewModel: SearchViewModel,
    onNavigateToDetail: (String, String, String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val query = uiState.query
    val trimmed = query.trim()

    fun setQuery(value: String) = viewModel.onEvent(SearchEvent.QueryChanged(value))
    fun runQuery(value: String) {
        viewModel.onEvent(SearchEvent.QueryChanged(value))
        viewModel.onEvent(SearchEvent.SubmitSearch)
    }

    val voiceFailed = stringResource(R.string.search_voice_unavailable)
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotEmpty()) runQuery(spoken)
        }
    }
    val launchVoice = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, voiceFailed, Toast.LENGTH_SHORT).show()
        }
    }

    val showingResults = trimmed.length >= MIN_SEARCH_QUERY_LENGTH
    val hits = remember(uiState.catalogRows, showingResults) {
        if (!showingResults) emptyList()
        else {
            val seen = HashSet<String>()
            buildList {
                uiState.catalogRows.forEach { row ->
                    row.items.forEach { item ->
                        if (!item.id.startsWith("__placeholder_") && seen.add("${item.apiType}:${item.id}")) {
                            add(NetflixSearchHit(item, row.addonBaseUrl))
                        }
                    }
                }
            }
        }
    }
    val exploreHits = remember(uiState.discoverResults, uiState.discoverCatalogs, uiState.selectedDiscoverCatalogKey) {
        val baseUrl = uiState.discoverCatalogs.firstOrNull { it.key == uiState.selectedDiscoverCatalogKey }?.addonBaseUrl.orEmpty()
        uiState.discoverResults.map { NetflixSearchHit(it, baseUrl) }
    }
    val showExplore = !showingResults &&
        shouldShowDiscoverInSearch(uiState.discoverLocation, uiState.query, uiState.submittedQuery)
    val gridHits = if (showingResults) hits else if (showExplore) exploreHits else emptyList()

    val firstKeyRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var lastArea by rememberSaveable { mutableStateOf("keys") }
    val gridEntryRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(6) {
            withFrameNanos { }
            val target = if (lastArea == "grid" && gridHits.isNotEmpty()) gridEntryRequester else firstKeyRequester
            if (runCatching { target.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixTokens.page)
            .padding(start = NetflixTokens.pageStart, top = NetflixTokens.contentTop, end = 32.dp)
    ) {
        // Left: what you typed, the keyboard, then suggestions or recent searches.
        Column(modifier = Modifier.width(300.dp).fillMaxHeight()) {
            NetflixQueryLine(query = query)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NetflixKey(icon = Icons.Default.SpaceBar, label = "space", modifier = Modifier.weight(2f)) {
                    if (query.isNotEmpty() && !query.endsWith(" ")) setQuery("$query ")
                }
                NetflixKey(icon = Icons.AutoMirrored.Filled.Backspace, label = "delete", modifier = Modifier.weight(2f)) {
                    if (query.isNotEmpty()) setQuery(query.dropLast(1))
                }
                NetflixKey(icon = Icons.Default.Mic, label = "voice", modifier = Modifier.weight(2f)) { launchVoice() }
            }
            Spacer(Modifier.height(4.dp))
            KEYS.chunked(KEY_COLUMNS).forEachIndexed { rowIndex, rowKeys ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    rowKeys.forEachIndexed { colIndex, key ->
                        NetflixKey(
                            text = key,
                            modifier = Modifier
                                .weight(1f)
                                .then(if (rowIndex == 0 && colIndex == 0) Modifier.focusRequester(firstKeyRequester) else Modifier)
                                .onFocusChanged { if (it.isFocused) lastArea = "keys" }
                        ) { setQuery(query + key) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val terms = if (trimmed.isNotEmpty()) uiState.suggestions.take(5) else uiState.recentSearches.take(5)
            terms.forEach { term ->
                NetflixTermRow(term = term) { runQuery(term) }
            }
        }

        Spacer(Modifier.width(28.dp))

        // Right: one grid of wide cards.
        Column(modifier = Modifier.fillMaxSize()) {
            val heading = when {
                showingResults && uiState.isSearching && hits.isEmpty() -> stringResource(R.string.netflix_ui_search_searching)
                showingResults && hits.isEmpty() && uiState.error != null -> uiState.error.orEmpty()
                showingResults && hits.isEmpty() && uiState.submittedQuery.isNotBlank() && !uiState.isSearching ->
                    stringResource(R.string.netflix_ui_search_no_results, trimmed)
                showingResults -> stringResource(R.string.netflix_ui_search_results)
                showExplore && exploreHits.isNotEmpty() -> stringResource(R.string.netflix_ui_search_explore)
                else -> ""
            }
            if (heading.isNotEmpty()) {
                Text(
                    text = heading,
                    color = NetflixTokens.textSecondary,
                    fontSize = NetflixTokens.metaSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp, end = 4.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged {
                        if (it.hasFocus && lastArea != "grid") {
                            lastArea = "grid"
                            if (showingResults) viewModel.onEvent(SearchEvent.RememberSearchFromTextInput)
                        }
                    }
                    .focusRestorer(gridEntryRequester)
            ) {
                itemsIndexed(gridHits, key = { _, hit -> "${hit.item.apiType}:${hit.item.id}" }) { index, hit ->
                    NetflixResultCard(
                        item = hit.item,
                        modifier = if (index == 0) Modifier.focusRequester(gridEntryRequester) else Modifier,
                        onClick = { onNavigateToDetail(hit.item.id, hit.item.apiType, hit.addonBaseUrl) }
                    )
                }
                if (showExplore && uiState.discoverHasMore && exploreHits.isNotEmpty()) {
                    item(key = "explore_more") {
                        LaunchedEffect(exploreHits.size) { viewModel.onEvent(SearchEvent.LoadNextDiscoverResults) }
                        Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NetflixQueryLine(query: String) {
    Column {
        Text(
            text = query.ifEmpty { stringResource(R.string.netflix_ui_search_hint) },
            color = if (query.isEmpty()) NetflixTokens.textSecondary else NetflixTokens.textPrimary,
            fontSize = 22.sp,
            fontWeight = if (query.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.25f)))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetflixKey(
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    label: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = ClickableSurfaceDefaults.shape(shape = keyShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.06f),
            contentColor = NetflixTokens.textPrimary.copy(alpha = 0.86f),
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(20.dp))
            } else if (text != null) {
                Text(text = text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetflixTermRow(term: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        shape = ClickableSurfaceDefaults.shape(shape = keyShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = NetflixTokens.textSecondary,
            focusedContainerColor = Color.White.copy(alpha = 0.12f),
            focusedContentColor = NetflixTokens.textPrimary
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = term,
            fontSize = NetflixTokens.metaSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

/** A wide card: the title's 16:9 art with its logo (or name) over a soft shade. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetflixResultCard(item: MetaPreview, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val art = item.landscapePoster ?: item.background ?: item.poster
    val artHasTitle = !item.landscapePoster.isNullOrBlank()
    val sizePx = remember(density) { with(density) { 200.dp.roundToPx() to 113.dp.roundToPx() } }
    val model = remember(art, sizePx) {
        ImageRequest.Builder(context).data(art).crossfade(true).size(sizePx.first, sizePx.second).build()
    }
    val shade = remember { Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f)) }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
        shape = CardDefaults.shape(shape = resultShape),
        colors = CardDefaults.colors(containerColor = NetflixTokens.tilePlaceholder, focusedContainerColor = NetflixTokens.tilePlaceholder),
        border = CardDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(NetflixTokens.ringWidth, NetflixTokens.ring), shape = resultShape)
        ),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(resultShape)) {
            if (!art.isNullOrBlank()) {
                AsyncImage(model = model, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (!artHasTitle) {
                Box(Modifier.fillMaxSize().background(shade))
                var logoFailed by remember(item.logo) { mutableStateOf(false) }
                if (!item.logo.isNullOrBlank() && !logoFailed) {
                    AsyncImage(
                        model = item.logo,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        onError = { logoFailed = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 10.dp, bottom = 8.dp)
                            .widthIn(max = 120.dp)
                            .heightIn(max = 36.dp)
                    )
                } else {
                    Text(
                        text = item.name,
                        color = NetflixTokens.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
                    )
                }
            }
        }
    }
}
