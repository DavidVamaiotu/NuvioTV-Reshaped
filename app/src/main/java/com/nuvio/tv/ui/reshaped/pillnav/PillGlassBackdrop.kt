package com.nuvio.tv.ui.reshaped.pillnav

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay

/**
 * The screen behind the pill, recorded once per content frame into a display list the pill replays under its
 * lens. No blur and no pixel copy: the pill only redraws its own small area from the same recording.
 * Only on TVs that can run AGSL (Android 13+) and have memory to spare, and only while Nuvio's blur setting
 * is on; everything else gets the static glass.
 */
@Stable
internal class PillGlassBackdrop(val layer: GraphicsLayer, internal val shader: Any) {
    var contentOrigin by mutableStateOf(Offset.Zero)
        internal set

    /** Bumped after every content recording, and by [refreshWhileShown], so the pill redraws with it. */
    var version by mutableIntStateOf(0)
        private set

    @Volatile
    private var pokeRequested = false

    internal fun onRecorded() {
        // Written from the content's draw pass; never read there, so it can't re-invalidate the content.
        Snapshot.withoutReadObservation { version += 1 }
    }

    /** A key was pressed: the screen is about to animate (focus moves, hero crossfades), so follow it closely. */
    fun poke() {
        pokeRequested = true
    }

    /**
     * Content that animates in its own layer updates the recording without re-running the recorder's draw, and
     * whether the pill's layer then refreshes depends on the renderer. So while the pill is shown it redraws
     * every frame for a moment after each key press, and once a second when idle (auto-rotating heroes).
     */
    suspend fun refreshWhileShown() {
        var activeUntil = 0L
        while (true) {
            val idle = withFrameNanos { now ->
                if (pokeRequested) {
                    pokeRequested = false
                    activeUntil = now + ActiveWindowNanos
                }
                version += 1
                now >= activeUntil
            }
            if (idle) delay(IdleRefreshMillis)
        }
    }

    /** Drops the last recording (and the previous screen's nodes and images it holds) while the pill is gone. */
    fun clear(density: Density, layoutDirection: LayoutDirection) {
        layer.record(density, layoutDirection, IntSize(1, 1)) {}
    }

    /**
     * Draws the recorded content lined up with the node at [coordinates], including any scale its layers
     * currently apply (the pill shrinks slightly while it tucks away).
     */
    fun DrawScope.drawAligned(coordinates: LayoutCoordinates?) {
        val coords = coordinates?.takeIf { it.isAttached } ?: return
        val origin = coords.localToRoot(Offset.Zero)
        val right = coords.localToRoot(Offset(size.width, 0f))
        val rootScale = ((right.x - origin.x) / size.width).takeIf { it > 0.01f } ?: 1f
        val shift = origin - contentOrigin
        scale(1f / rootScale, pivot = Offset.Zero) {
            translate(-shift.x, -shift.y) { drawLayer(layer) }
        }
    }

    private companion object {
        const val ActiveWindowNanos = 1_500_000_000L
        const val IdleRefreshMillis = 1_000L
    }
}

private const val MinLensRamBytes = 2_500_000_000L // 3 GB boxes report about 2.8 GB; 2 GB boxes about 1.9 GB.

/** Android 13+ with at least 3 GB of RAM and not flagged low-RAM: the TVs that can get the refracting pill. */
internal fun pillLensSupported(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    if (manager.isLowRamDevice) return false
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    return info.totalMem >= MinLensRamBytes
}

/** The lens shader as a [android.graphics.RuntimeShader], or null where it fails to compile. */
private fun compilePillGlassShader(): Any? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return runCatching { android.graphics.RuntimeShader(PillGlassShader) }
        .onFailure { Log.w("PillGlass", "Liquid glass shader unavailable, using static glass", it) }
        .getOrNull()
}

/**
 * The backdrop recorder, or null where the pill falls back to static glass: TVs that can't run it, or
 * [blurEnabled] off (Nuvio's blur setting, the same switch that gates the phone's liquid glass).
 */
@Composable
internal fun rememberPillGlassBackdrop(blurEnabled: Boolean): PillGlassBackdrop? {
    val context = LocalContext.current
    val supported = remember { pillLensSupported(context) }
    if (!supported || !blurEnabled) return null
    // Compiled once here: a TV whose GPU driver rejects the shader keeps the static glass instead of crashing.
    val shader = remember { compilePillGlassShader() } ?: return null
    val layer = rememberGraphicsLayer()
    return remember(layer, shader) { PillGlassBackdrop(layer, shader) }
}

/** Put on the content the pill floats over: records it for the lens and draws it as usual. */
internal fun Modifier.pillGlassSource(backdrop: PillGlassBackdrop?): Modifier {
    if (backdrop == null) return this
    return onGloballyPositioned { backdrop.contentOrigin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
            backdrop.onRecorded()
        }
}
