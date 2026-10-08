package com.nuvio.tv.ui.reshaped.netflix

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The single moving value behind a Netflix-style row.
 *
 * [position] is the focused title as a fractional index; it springs toward the title that has
 * focus. A title's expansion is how close [position] is to it, times [openness] (whether the
 * row holds focus). Mid-move, the title focus is leaving and the one it is reaching share the
 * wide slot between them, which is what makes the next card open before focus lands on it.
 * The row's scroll is computed from the same value, keeping the wide slot pinned at the left.
 */
@Stable
internal class NetflixRowMotion(initialIndex: Int) {
    val position = Animatable(initialIndex.toFloat())
    val openness = Animatable(0f)
    private var positionJob: Job? = null
    private var opennessJob: Job? = null

    fun expansionOf(index: Int): Float {
        val closeness = (1f - abs(index - position.value)).coerceIn(0f, 1f)
        return closeness * openness.value
    }

    fun focus(index: Int, scope: CoroutineScope) {
        val target = index.toFloat()
        if (position.targetValue == target && position.isRunning) return
        positionJob?.cancel()
        positionJob = scope.launch {
            if (abs(position.value - target) > NetflixTokens.MORPH_SNAP_DISTANCE) {
                position.snapTo(target)
            } else {
                position.animateTo(
                    target,
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = NetflixTokens.MORPH_STIFFNESS)
                )
            }
        }
    }

    fun setOpen(open: Boolean, scope: CoroutineScope) {
        opennessJob?.cancel()
        opennessJob = scope.launch {
            openness.animateTo(
                if (open) 1f else 0f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = NetflixTokens.OPEN_STIFFNESS)
            )
        }
    }
}
