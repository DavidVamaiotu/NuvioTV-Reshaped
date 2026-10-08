package com.nuvio.tv.ui.reshaped.netflix

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 *
 * While Left/Right is held, [held] folds every tile back to a poster so the row glides at an
 * even speed instead of opening each title it passes; the landing title opens on release.
 */
@Stable
internal class NetflixRowMotion(initialIndex: Int) {
    val position = Animatable(initialIndex.toFloat())
    val openness = Animatable(0f)
    val held = Animatable(0f)
    private var positionJob: Job? = null
    private var opennessJob: Job? = null
    private var heldJob: Job? = null
    private var releaseJob: Job? = null
    private var holding = false
    private var lastStepAt = 0L

    fun expansionOf(index: Int): Float {
        val closeness = (1f - abs(index - position.value)).coerceIn(0f, 1f)
        return closeness * openness.value * (1f - held.value)
    }

    fun focus(index: Int, scope: CoroutineScope) {
        val target = index.toFloat()
        // Steps closer together than a deliberate press are a held key, whatever the remote reports.
        val now = SystemClock.uptimeMillis()
        if (target != position.targetValue && now - lastStepAt < NetflixTokens.HOLD_STEP_MS) hold(scope)
        lastStepAt = now
        if (position.targetValue == target && position.isRunning) return
        positionJob?.cancel()
        positionJob = scope.launch {
            val snapDistance = if (holding) NetflixTokens.HOLD_SNAP_DISTANCE else NetflixTokens.MORPH_SNAP_DISTANCE
            if (abs(position.value - target) > snapDistance) {
                position.snapTo(target)
            } else {
                position.animateTo(
                    target,
                    spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = if (holding) NetflixTokens.HOLD_GLIDE_STIFFNESS else NetflixTokens.MORPH_STIFFNESS
                    )
                )
            }
        }
    }

    /** A Left/Right key repeat, or steps arriving faster than presses: fold to posters. */
    fun hold(scope: CoroutineScope) {
        if (!holding) {
            holding = true
            heldJob?.cancel()
            heldJob = scope.launch {
                held.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = NetflixTokens.HOLD_FOLD_STIFFNESS))
            }
        }
        // Remotes that never send the key-up still open the landing title once steps stop.
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(NetflixTokens.HOLD_RELEASE_MS)
            release(scope)
        }
    }

    /** The key was let go: open the title focus landed on. */
    fun release(scope: CoroutineScope) {
        releaseJob?.cancel()
        if (!holding) return
        holding = false
        heldJob?.cancel()
        heldJob = scope.launch {
            held.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = NetflixTokens.OPEN_STIFFNESS))
        }
    }

    fun setOpen(open: Boolean, scope: CoroutineScope) {
        // Leaving the row mid-hold: stay folded while the row closes, then reset quietly.
        if (!open) releaseJob?.cancel()
        opennessJob?.cancel()
        opennessJob = scope.launch {
            openness.animateTo(
                if (open) 1f else 0f,
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = NetflixTokens.OPEN_STIFFNESS)
            )
            if (!open && holding) {
                holding = false
                heldJob?.cancel()
                held.snapTo(0f)
            }
        }
    }
}
