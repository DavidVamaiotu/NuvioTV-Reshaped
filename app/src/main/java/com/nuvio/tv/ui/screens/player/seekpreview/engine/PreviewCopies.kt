package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.graphics.Bitmap

/** Immutable input from the integration adapter. Only already-resolved links are accepted. */
internal data class PreviewCopy(
    val url: String,
    val headers: Map<String, String>,
    val label: String,
    val height: Int,
    val h264: Boolean,
    val hdr: Boolean,
    val durationMs: Long?,
)

internal object PreviewCopies {
    private val editions = listOf("extended", "director", "unrated", "uncut", "imax", "remastered", "final cut", "special edition")
    private fun edition(text: String): Set<String> = editions.filterTo(mutableSetOf()) { it in text.lowercase() }
    fun candidates(offers: List<PreviewCopy>, playingLabel: String, durationMs: Long): List<PreviewCopy> = offers
        .filter { it.height in setOf(720, 1080) && !it.hdr && edition(it.label) == edition(playingLabel) &&
            (it.durationMs == null || ReleaseMatch.ratio(durationMs, it.durationMs) != null) }
        .distinctBy { it.url }
        .sortedWith(compareByDescending<PreviewCopy> { it.h264 }.thenByDescending { it.height }).take(2)

    fun signature(bitmap: Bitmap): FloatArray? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val crop = ThumbCrop.measure(bitmap.width, bitmap.height, pixels)
        return ReleaseMatch.signature(pixels, bitmap.width, bitmap.height,
            crop?.left ?: 0, crop?.top ?: 0, crop?.right ?: 0, crop?.bottom ?: 0)
    }
    /** Three independent, distributed anchors. Flat pictures and insufficient evidence never pass. */
    fun verified(matches: List<Float>): Boolean = matches.size >= 3 && matches.all { it >= 0.92f }
}
