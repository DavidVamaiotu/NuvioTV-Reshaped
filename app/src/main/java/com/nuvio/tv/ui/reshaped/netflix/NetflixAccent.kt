package com.nuvio.tv.ui.reshaped.netflix

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The accent colour of a piece of artwork, for the glow behind the billboard. Sampled from a
 * 48 px copy of the image off the main thread and cached, so it costs one tiny decode per title.
 * Returns an animated state; read it in a draw lambda so colour changes only redraw.
 */
@Composable
internal fun rememberNetflixAccent(imageUrl: String?): State<Color> {
    val context = LocalContext.current
    val target = remember { mutableStateOf(Color.Transparent) }
    LaunchedEffect(imageUrl) {
        val url = imageUrl?.takeIf { it.isNotBlank() } ?: run {
            target.value = Color.Transparent
            return@LaunchedEffect
        }
        target.value = NetflixAccentCache.get(url) ?: resolveAccent(context, url)?.also {
            NetflixAccentCache.put(url, it)
        } ?: Color.Transparent
    }
    return animateColorAsState(
        targetValue = target.value,
        animationSpec = tween(NetflixTokens.ACCENT_FADE_MS),
        label = "netflixAccent"
    )
}

private object NetflixAccentCache {
    private val map = object : LinkedHashMap<String, Color>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Color>?) = size > 64
    }

    @Synchronized fun get(url: String): Color? = map[url]
    @Synchronized fun put(url: String, color: Color) { map[url] = color }
}

private suspend fun resolveAccent(context: Context, url: String): Color? = withContext(Dispatchers.IO) {
    val request = ImageRequest.Builder(context)
        .data(url)
        .allowHardware(false)
        .size(Size(48, 48))
        .build()
    val result = try {
        context.imageLoader.execute(request)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
    val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: return@withContext null
    accentOf(bitmap)
}

/** Saturation-weighted average, so the dominant colourful area wins over grey and black. */
private fun accentOf(bitmap: Bitmap): Color? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val stepX = max(1, bitmap.width / 16)
    val stepY = max(1, bitmap.height / 16)
    val hsv = FloatArray(3)
    var r = 0f
    var g = 0f
    var b = 0f
    var total = 0f
    for (y in 0 until bitmap.height step stepY) {
        for (x in 0 until bitmap.width step stepX) {
            val pixel = bitmap.getPixel(x, y)
            android.graphics.Color.colorToHSV(pixel, hsv)
            if (hsv[2] < 0.1f) continue
            val weight = (0.2f + hsv[1] * 2f) * (0.4f + hsv[2])
            r += android.graphics.Color.red(pixel) * weight
            g += android.graphics.Color.green(pixel) * weight
            b += android.graphics.Color.blue(pixel) * weight
            total += weight
        }
    }
    if (total <= 0f) return null
    val raw = Color(r / total / 255f, g / total / 255f, b / total / 255f)
    // Calm it down: deep enough for white text on top, never neon.
    android.graphics.Color.colorToHSV(
        android.graphics.Color.rgb((raw.red * 255).toInt(), (raw.green * 255).toInt(), (raw.blue * 255).toInt()),
        hsv
    )
    hsv[1] = hsv[1].coerceIn(0f, 0.7f)
    hsv[2] = hsv[2].coerceIn(0.35f, 0.62f)
    val calm = Color(android.graphics.Color.HSVToColor(hsv))
    return if (calm.luminance() > 0.3f) lerp(calm, Color.Black, 0.25f) else calm
}
