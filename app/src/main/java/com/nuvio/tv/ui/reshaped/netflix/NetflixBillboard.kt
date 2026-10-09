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
 * The billboard at the top of Home: one featured title in a large rounded inset card with its
 * logo, a one-line summary, a short synopsis and a single "View Details" pill. Left/Right on the
 * pill steps through Nuvio's hero titles, as the regular hero carousel does.
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

    val shape = remember { RoundedCornerShape(NetflixTokens.billboardCorner) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NetflixTokens.pageStart)
            .height(NetflixTokens.billboardHeight)
            .clip(shape)
            .background(NetflixTokens.tilePlaceholder)
    ) {
        Crossfade(
            targetState = item,
            animationSpec = tween(NetflixTokens.BILLBOARD_FADE_MS),
            label = "netflixBillboard"
        ) { shown ->
            NetflixBillboardPage(item = shown, showImdbRatings = showImdbRatings)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 36.dp, bottom = 30.dp)
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
private fun NetflixBillboardPage(item: MetaPreview, showImdbRatings: Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sizePx = remember(density) {
        with(density) { (NetflixTokens.billboardHeight * (16f / 9f)).roundToPx() to NetflixTokens.billboardHeight.roundToPx() }
    }
    val backdrop = item.background ?: item.landscapePoster ?: item.poster
    val model = remember(backdrop, sizePx) {
        ImageRequest.Builder(context).data(backdrop).crossfade(false).size(sizePx.first, sizePx.second).build()
    }
    val leftShade = remember {
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.82f),
            0.45f to Color.Black.copy(alpha = 0.45f),
            0.75f to Color.Transparent
        )
    }
    val bottomShade = remember {
        Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (!backdrop.isNullOrBlank()) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(Modifier.fillMaxSize().background(leftShade))
        Box(Modifier.fillMaxSize().background(bottomShade))

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                // Leaves room for the pill and page dots drawn over this page.
                .padding(start = 36.dp, bottom = 96.dp)
                .width(NetflixTokens.billboardTextWidth)
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
}
