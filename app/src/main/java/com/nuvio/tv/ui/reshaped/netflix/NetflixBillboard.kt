package com.nuvio.tv.ui.reshaped.netflix

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.IntState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview

/**
 * The billboard at the top of Home: one featured title's logo, a one-line summary, a short
 * synopsis and a single "View Details" pill, laid over the page-wide artwork that
 * [NetflixBillboardBackdrop] draws behind the list. Left/Right on the pill steps through Nuvio's
 * hero titles, as the regular hero carousel does.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun NetflixBillboard(
    items: List<MetaPreview>,
    initialIndex: Int,
    focusRequester: FocusRequester,
    showImdbRatings: Boolean,
    onActiveIndexChanged: (Int) -> Unit,
    onFocused: (MetaPreview) -> Unit,
    onClick: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    var index by remember { mutableStateOf(initialIndex.coerceIn(0, items.lastIndex)) }
    if (index > items.lastIndex) index = 0
    val item = items[index]
    var focused by remember { mutableStateOf(false) }
    val latestOnActiveIndexChanged by rememberUpdatedState(onActiveIndexChanged)
    val latestOnFocused by rememberUpdatedState(onFocused)
    LaunchedEffect(index) {
        latestOnActiveIndexChanged(index)
        if (focused) latestOnFocused(items[index])
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NetflixTokens.billboardHeight)
    ) {
        Crossfade(
            targetState = item,
            animationSpec = tween(NetflixTokens.BILLBOARD_FADE_MS),
            label = "netflixBillboard",
            modifier = Modifier
                .align(Alignment.BottomStart)
                // Leaves room for the pill and page dots below.
                .padding(start = NetflixTokens.pageStart, bottom = 84.dp)
        ) { shown ->
            NetflixBillboardText(item = shown, showImdbRatings = showImdbRatings)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = NetflixTokens.pageStart, bottom = 18.dp)
        ) {
            val pillShape = remember { RoundedCornerShape(50) }
            Surface(
                onClick = { onClick(item) },
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        if (it.isFocused != focused) {
                            focused = it.isFocused
                            if (it.isFocused) latestOnFocused(item)
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action != AndroidKeyEvent.ACTION_DOWN || items.size < 2) return@onPreviewKeyEvent false
                        when (native.keyCode) {
                            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { index = (index + 1) % items.size; true }
                            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (index == 0) false else { index -= 1; true }
                            }
                            else -> false
                        }
                    },
                shape = ClickableSurfaceDefaults.shape(shape = pillShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.22f),
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black
                ),
                border = ClickableSurfaceDefaults.border(
                    border = Border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)), shape = pillShape),
                    focusedBorder = Border.None
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f)
            ) {
                Text(
                    text = stringResource(R.string.netflix_ui_view_details),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
                )
            }
            if (items.size > 1) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.indices.forEach { i ->
                        Box(
                            modifier = Modifier
                                .size(width = if (i == index) 18.dp else 6.dp, height = 6.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = if (i == index) 0.95f else 0.35f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetflixBillboardText(item: MetaPreview, showImdbRatings: Boolean) {
    val context = LocalContext.current
    Column(modifier = Modifier.width(NetflixTokens.billboardTextWidth)) {
        var logoFailed by remember(item.logo) { mutableStateOf(false) }
        if (!item.logo.isNullOrBlank() && !logoFailed) {
            AsyncImage(
                model = item.logo,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                onError = { logoFailed = true },
                modifier = Modifier
                    .widthIn(max = NetflixTokens.billboardLogoWidth)
                    .heightIn(max = NetflixTokens.billboardLogoHeight)
            )
        } else {
            Text(
                text = item.name,
                color = NetflixTokens.textPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(14.dp))
        val tokens = remember(item.id, item.genres, item.releaseInfo, item.imdbRating, item.runtime, showImdbRatings) {
            item.netflixMetaLine(context, showImdbRatings)
        }
        NetflixMetaRow(tokens = tokens)
        val synopsis = item.description
        if (!synopsis.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = synopsis,
                color = NetflixTokens.textPrimary.copy(alpha = 0.86f),
                fontSize = NetflixTokens.synopsisSize,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The featured title's artwork, page-wide behind the list: it starts at the top of the screen
 * (under the clear pill), runs past the first row and fades into the page at the left and bottom,
 * tinted by the art's own accent colour. As the page scrolls to the rows it drifts up a little
 * slower than the rows and fades out; scroll is read while drawing only, so it never recomposes.
 *
 * [scrolledPx] is how far the billboard has scrolled up, or null once it is off the page.
 */
@Composable
internal fun NetflixBillboardBackdrop(
    items: List<MetaPreview>,
    activeIndex: IntState,
    scrolledPx: () -> Float?,
    modifier: Modifier = Modifier,
) {
    val item = items.getOrNull(activeIndex.intValue) ?: items.firstOrNull() ?: return
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // Decoded no larger than 1280 px wide (what the old inset card used), however big the screen.
    val sizePx = remember(density, screenWidthDp) {
        val width = with(density) { screenWidthDp.dp.roundToPx() }.coerceIn(1, BACKDROP_MAX_WIDTH_PX)
        val height = (width * NetflixTokens.billboardBackdropHeight.value / screenWidthDp.coerceAtLeast(1)).toInt()
        width to height.coerceAtLeast(1)
    }
    val fadePx = with(density) { NetflixTokens.billboardHeight.toPx() }
    val accent = rememberNetflixAccent(item.netflixBackdropUrl())
    val leftShade = remember {
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.88f),
            0.32f to Color.Black.copy(alpha = 0.6f),
            0.62f to Color.Transparent
        )
    }
    // A light veil at the very top keeps the pill's labels readable over bright art.
    val topShade = remember {
        Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.38f), 0.2f to Color.Transparent)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NetflixTokens.billboardBackdropHeight)
            .graphicsLayer {
                val scrolled = scrolledPx()
                if (scrolled == null) {
                    alpha = 0f
                } else {
                    translationY = -scrolled * BACKDROP_PARALLAX
                    alpha = 1f - (scrolled / fadePx).coerceIn(0f, 1f)
                }
            }
    ) {
        Crossfade(
            targetState = item,
            animationSpec = tween(NetflixTokens.BILLBOARD_FADE_MS),
            label = "netflixBillboardBackdrop"
        ) { shown ->
            val url = shown.netflixBackdropUrl()
            if (url != null) {
                val model = remember(url, sizePx) {
                    ImageRequest.Builder(context).data(url).crossfade(false).size(sizePx.first, sizePx.second).build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(leftShade)
                    drawRect(topShade)
                    val tint = accent.value
                    val glow = if (tint.alpha > 0f) lerp(tint, Color.Black, 0.45f).copy(alpha = 0.6f * tint.alpha) else Color.Transparent
                    drawRect(
                        Brush.verticalGradient(
                            0.42f to Color.Transparent,
                            0.74f to glow,
                            1f to NetflixTokens.page
                        )
                    )
                }
        )
    }
}

private fun MetaPreview.netflixBackdropUrl(): String? =
    background?.takeIf { it.isNotBlank() } ?: landscapePoster?.takeIf { it.isNotBlank() } ?: poster?.takeIf { it.isNotBlank() }

private const val BACKDROP_MAX_WIDTH_PX = 1280

/** The artwork drifts up at this fraction of the page's speed, so the text leaves it gently. */
private const val BACKDROP_PARALLAX = 0.6f
