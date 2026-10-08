package com.nuvio.tv.ui.reshaped.netflix

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.nuvio.tv.core.util.parseRuntimeMinutes
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
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
    val metaHeight = 100.dp
    val rowEndPadding = 520.dp
    // The pill menu stays in the top 56dp band; the page is clipped below it, and a focused row's
    // top (its header) lands listTopGap under the band, so nothing of the row above shows.
    val pillBand = 56.dp
    val contentTop = pillBand + 14.dp
    val listTopGap = contentTop - pillBand

    val billboardHeight = 368.dp
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

    // Motion. One spring per row drives a fractional focus position; tile widths and the row's
    // scroll are both read from it, so moving between titles is a single continuous morph.
    // Critically damped: no bounce, and it keeps its speed when retargeted (held D-pad).
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val MORPH_STIFFNESS = 380f
    const val OPEN_STIFFNESS = 420f
    const val SCROLL_STIFFNESS = 460f
    /** Beyond this many titles a focus jump (Back to start, restore) snaps instead of sliding. */
    const val MORPH_SNAP_DISTANCE = 4
    // Holding Left/Right: tiles fold to posters and the row glides at a steady speed, then the
    // landing title opens once the key is let go (or steps stop arriving).
    const val HOLD_STEP_MS = 170L
    const val HOLD_RELEASE_MS = 260L
    const val HOLD_FOLD_STIFFNESS = 1400f
    const val HOLD_GLIDE_STIFFNESS = 900f
    const val HOLD_SNAP_DISTANCE = 12
    const val TRAILER_DWELL_MS = 1200L
    const val META_FADE_MS = 140
    const val BILLBOARD_FADE_MS = 500
    const val ACCENT_FADE_MS = 500
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

/**
 * A title's resting width, from the shape its add-on gives it (as Nuvio's own cards do): posters
 * 2:3, square art (TV channels, logos) square, landscape art already 16:9.
 */
internal fun MetaPreview.netflixCollapsedWidth(): Dp = when (posterShape) {
    PosterShape.POSTER -> NetflixTokens.tileWidth
    PosterShape.SQUARE -> NetflixTokens.tileHeight
    PosterShape.LANDSCAPE -> NetflixTokens.tileExpandedWidth
}

/** Wide art to open into; without any, the title keeps its own shape and only its details open. */
internal fun MetaPreview.netflixHasWideArtwork(): Boolean =
    !landscapePoster.isNullOrBlank() || !background.isNullOrBlank()

internal fun MetaPreview.netflixExpandedWidth(): Dp =
    if (netflixHasWideArtwork()) NetflixTokens.tileExpandedWidth else netflixCollapsedWidth()
