package com.nuvio.tv.ui.screens.player.seekpreview.engine

import kotlinx.coroutines.CancellationException

/** Preview failures are optional-feature failures, including allocation and native linkage failures. */
internal inline fun <T> previewSafely(stage: String, onFailure: (Throwable) -> Unit = {}, block: () -> T): T? =
    try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Throwable) {
        runCatching { PreviewDiagnostics.note("$stage failed: ${failure.javaClass.simpleName}") }
        runCatching { onFailure(failure) }
        null
    }

/** Bounds apply before slot tables or decoders allocate memory. Oversized media keeps ordinary playback. */
internal object PreviewLimits {
    const val MAX_INDEX_ENTRIES = 200_000
    const val MAX_SAMPLES = 1_000_000
    const val MAX_DURATION_MS = 48L * 60 * 60 * 1000
    fun supportedSize(width: Int, height: Int): Boolean = width in 1..4096 && height in 1..2304
    fun slotCount(durationMs: Long, spacingMs: Long): Int? {
        if (durationMs !in 1..MAX_DURATION_MS || spacingMs <= 0) return null
        val count = (durationMs - 1) / spacingMs + 1
        return count.takeIf { it <= 17_280 }?.toInt()
    }
}
