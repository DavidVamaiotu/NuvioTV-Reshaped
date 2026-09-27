package com.nuvio.tv.ui.reshaped.volumeboost

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme

private val BoostRed = Color(0xFFFF453A)

/** Player volume as a percentage: the boost's 0 to 10 dB reads as 100% to 200%. */
internal fun volumeBoostPercent(gainDb: Int, maxDb: Int): Int =
    100 + (gainDb.coerceIn(0, maxDb) * 100) / maxDb.coerceAtLeast(1)

/**
 * Slim 100% to 200% bar under the boost stepper. The right end carries a faint red wash so the
 * loud end reads at a glance, and the fill warms from the accent to red as the boost climbs.
 * One draw pass, no layers, so it stays cheap on low-end TVs.
 */
@Composable
internal fun VolumeBoostBar(gainDb: Int, maxDb: Int, enabled: Boolean, modifier: Modifier = Modifier) {
    val accent = NuvioTheme.colors.Secondary
    val target = if (maxDb > 0) gainDb.coerceIn(0, maxDb).toFloat() / maxDb else 0f
    val fraction by animateFloatAsState(target, tween(160), label = "volumeBoostBar")
    Box(
        modifier
            .width(180.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .drawBehind {
                val alpha = if (enabled) 1f else 0.4f
                drawRect(Color.White.copy(alpha = 0.18f * alpha))
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, BoostRed.copy(alpha = 0.35f * alpha)),
                        startX = size.width * 0.45f,
                        endX = size.width,
                    ),
                )
                val filled = size.width * fraction
                if (filled > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(listOf(accent, BoostRed), startX = 0f, endX = size.width),
                        topLeft = Offset.Zero,
                        size = Size(filled, size.height),
                        alpha = alpha,
                    )
                }
            },
    )
}
