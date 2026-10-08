package com.nuvio.tv.ui.reshaped.netflix

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.nuvio.tv.core.util.parseRuntimeMinutes
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.localizedGenreLabel

/**
 * Sizes, colours and timings of the Netflix-style Home. Netflix publishes no motion numbers;
 * these are tuned by eye to its TV app: short, decisive ease-outs, nothing bouncy.
 */
internal object NetflixTokens {
    // Layout
    val pageStart = 48.dp
    val rowGap = 22.dp
    val headerGap = 10.dp
    val tileGap = 10.dp
    val tileHeight = 228.dp
    val tileWidth = tileHeight * (2f / 3f)
    val tileExpandedWidth = tileHeight * (16f / 9f)
    val tileCorner = 8.dp
    val ringWidth = 2.5.dp
    val metaHeight = 92.dp
    val rowEndPadding = 520.dp
    val focusTopInset = 64.dp

    val billboardHeight = 380.dp
    val billboardCorner = 20.dp
    val billboardTextWidth = 440.dp
    val billboardLogoWidth = 300.dp
    val billboardLogoHeight = 110.dp

    val headerSize = 20.sp
    val metaSize = 15.sp
    val synopsisSize = 14.sp

    // Colour: neutral, the artwork carries the colour.
    val page = Color(0xFF000000)
    val tilePlaceholder = Color(0xFF1A1A1A)
    val ring = Color(0xFFFFFFFF)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFBDBDBD)
    val rating = Color(0xFF46D369)

    // Motion
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val ROW_SLIDE_MS = 220
    const val EXPAND_MS = 280
    const val META_FADE_MS = 180
    const val ROW_META_MS = 260
    const val EXPAND_DWELL_MS = 450L
    const val BILLBOARD_FADE_MS = 600
}

/** "★ 7.6 · 2024 · Action · 2h 29m", Netflix's one-line summary under a title. */
internal fun MetaPreview.netflixMetaLine(context: Context, showRating: Boolean): List<String> = buildList {
    imdbRating?.takeIf { showRating && it > 0f }?.let { add(String.format(java.util.Locale.US, "$NETFLIX_RATING_PREFIX %.1f", it)) }
    releaseInfo?.let { YEAR.find(it)?.value }?.let { add(it) }
    genres.firstOrNull()?.let { add(localizedGenreLabel(context, it)) }
    val isSeries = type == ContentType.SERIES || apiType.equals("series", ignoreCase = true)
    if (isSeries && seasonCount != null && seasonCount > 0) {
        add(if (seasonCount == 1) "1 Season" else "$seasonCount Seasons")
    } else if (runtime != null && (parseRuntimeMinutes(runtime.trim().lowercase()) ?: 0) > 0) {
        formatHeroRuntime(runtime)?.let { add(it) }
    }
    ageRating?.takeIf { it.isNotBlank() }?.let { add(it) }
}

internal const val NETFLIX_RATING_PREFIX = "★"

private val YEAR = Regex("""(19|20)\d{2}""")
