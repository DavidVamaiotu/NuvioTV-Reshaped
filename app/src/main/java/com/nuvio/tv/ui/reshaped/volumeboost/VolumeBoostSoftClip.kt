package com.nuvio.tv.ui.reshaped.volumeboost

import kotlin.math.abs
import kotlin.math.tanh

private const val KNEE = 0.8f
private const val HEADROOM = 1f - KNEE

/**
 * Soft clip for boosted samples (full scale = 1). Anything under the knee passes untouched, so
 * normal speech costs one compare; peaks above it bend smoothly toward full scale instead of
 * hard clipping, which is what makes a boosted explosion sound crackly.
 */
internal fun softClipBoosted(sample: Float): Float {
    val magnitude = abs(sample)
    if (magnitude <= KNEE) return sample
    val bent = KNEE + HEADROOM * tanh((magnitude - KNEE) / HEADROOM)
    return if (sample < 0f) -bent else bent
}
