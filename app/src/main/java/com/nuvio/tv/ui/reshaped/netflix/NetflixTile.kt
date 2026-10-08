package com.nuvio.tv.ui.reshaped.netflix

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.components.WatchedMarker
import com.nuvio.tv.ui.components.placeholderCardShimmer
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import kotlinx.coroutines.delay

/**
 * A Netflix-style title tile: a poster that, after a short dwell with focus, widens into the
 * title's 16:9 artwork. Only the focused tile ever animates; every other tile is a static image.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun NetflixTile(
    item: MetaPreview,
    isWatched: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onExpandedChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    placeholderShimmerOffsetState: State<Float>? = null,
    trailerEnabled: Boolean = false,
    trailerMuted: Boolean = true,
    trailerUrl: String? = null,
    trailerAudioUrl: String? = null,
) {
    val isPlaceholder = item.poster == PLACEHOLDER_IMAGE_URL
    val shape = remember { RoundedCornerShape(NetflixTokens.tileCorner) }
    var isFocused by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnExpandedChanged by rememberUpdatedState(onExpandedChanged)

    // Dwell before widening, so holding the D-pad glides along posters instead of
    // expanding every one it passes.
    LaunchedEffect(isFocused, item.id) {
        if (!isFocused || isPlaceholder) {
            expanded = false
            return@LaunchedEffect
        }
        delay(NetflixTokens.EXPAND_DWELL_MS)
        if (isFocused && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            expanded = true
        }
    }
    LaunchedEffect(expanded) { latestOnExpandedChanged(expanded) }

    val width by animateDpAsState(
        targetValue = if (expanded) NetflixTokens.tileExpandedWidth else NetflixTokens.tileWidth,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = NetflixTokens.EXPAND_STIFFNESS),
        label = "netflixTileWidth"
    )
    // The artwork layer is composed as soon as the tile takes focus (invisible), so the wide
    // image is already decoded when the dwell ends and the expansion never waits on it. It stays
    // while the tile is wider than a poster, so collapsing fades it out instead of popping.
    val showArtwork = isFocused || expanded || width > NetflixTokens.tileWidth + 1.dp
    val artworkAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(NetflixTokens.META_FADE_MS + 60),
        label = "netflixTileArtwork"
    )

    val context = LocalContext.current
    val density = LocalDensity.current
    val posterSize = remember(density) {
        with(density) { NetflixTokens.tileWidth.roundToPx() to NetflixTokens.tileHeight.roundToPx() }
    }
    val artworkSize = remember(density) {
        with(density) { NetflixTokens.tileExpandedWidth.roundToPx() to NetflixTokens.tileHeight.roundToPx() }
    }
    val posterModel = remember(item.poster, posterSize) {
        ImageRequest.Builder(context)
            .data(item.poster)
            .crossfade(true)
            .size(posterSize.first, posterSize.second)
            .build()
    }
    // landscapePoster usually carries the title baked into the art; a plain backdrop gets the logo on top.
    val artworkUrl = item.landscapePoster ?: item.background
    val artworkHasTitle = !item.landscapePoster.isNullOrBlank()

    Card(
        onClick = {
            if (longPressTriggered) longPressTriggered = false else onClick()
        },
        modifier = modifier
            .width(width)
            .height(NetflixTokens.tileHeight)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused != isFocused) {
                    isFocused = state.isFocused
                    if (state.isFocused) onFocused()
                }
            }
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                    longPressTriggered = true
                    onLongPress()
                    return@onPreviewKeyEvent true
                }
                if (longPressKeyTracker.handle(native, ::isSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                    }
                ) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) longPressTriggered = false
                    return@onPreviewKeyEvent true
                }
                if (native.action == AndroidKeyEvent.ACTION_UP && longPressTriggered &&
                    (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
                ) {
                    longPressTriggered = false
                    return@onPreviewKeyEvent true
                }
                false
            },
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
        scale = CardDefaults.scale(focusedScale = 1f),
        glow = CardDefaults.glow()
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(shape)) {
            if (isPlaceholder) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (placeholderShimmerOffsetState != null) {
                                Modifier.placeholderCardShimmer(
                                    shimmerOffsetState = placeholderShimmerOffsetState,
                                    backgroundColor = NetflixTokens.tilePlaceholder
                                )
                            } else Modifier
                        )
                )
                return@Box
            }
            if (!item.poster.isNullOrBlank()) {
                AsyncImage(
                    model = posterModel,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = item.name,
                    color = NetflixTokens.textPrimary,
                    fontSize = NetflixTokens.metaSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp)
                )
            }

            if (showArtwork) {
                Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = artworkAlpha }) {
                    if (!artworkUrl.isNullOrBlank()) {
                        val artworkModel = remember(artworkUrl, artworkSize) {
                            ImageRequest.Builder(context)
                                .data(artworkUrl)
                                .crossfade(false)
                                .size(artworkSize.first, artworkSize.second)
                                .build()
                        }
                        AsyncImage(
                            model = artworkModel,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().background(NetflixTokens.tilePlaceholder)
                        )
                    }
                    if (!artworkHasTitle) {
                        NetflixTileTitle(item = item, modifier = Modifier.align(Alignment.BottomStart))
                    }
                    if (trailerEnabled && expanded && isFocused && trailerUrl != null) {
                        NetflixTileTrailer(
                            trailerUrl = trailerUrl,
                            trailerAudioUrl = trailerAudioUrl,
                            muted = trailerMuted,
                            onEnded = { expanded = false }
                        )
                    }
                }
            }

            if (isWatched) {
                WatchedMarker(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
    }
}

/** Logo (or the name when there is none) over a soft bottom shade, as on Netflix's wide tiles. */
@Composable
private fun NetflixTileTitle(item: MetaPreview, modifier: Modifier = Modifier) {
    val shade = remember {
        Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NetflixTokens.tileHeight * 0.5f)
            .background(shade)
    ) {
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
                    .padding(start = 14.dp, bottom = 12.dp)
                    .widthIn(max = 200.dp)
                    .heightIn(max = 64.dp)
            )
        } else {
            Text(
                text = item.name,
                color = NetflixTokens.textPrimary,
                fontSize = NetflixTokens.headerSize,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun NetflixTileTrailer(
    trailerUrl: String,
    trailerAudioUrl: String?,
    muted: Boolean,
    onEnded: () -> Unit,
) {
    var firstFrame by remember(trailerUrl) { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (firstFrame) 1f else 0f }) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        TrailerPlayer(
            trailerUrl = trailerUrl,
            trailerAudioUrl = trailerAudioUrl,
            isPlaying = true,
            muted = muted,
            cropToFill = true,
            onEnded = onEnded,
            onFirstFrameRendered = { firstFrame = true },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun isSelectKey(keyCode: Int): Boolean =
    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
