package com.nuvio.tv.ui.screens.player.seekpreview.engine

/** A full, deliberately small playback buffer must not starve preview work forever. */
internal fun previewBufferReady(
    playing: Boolean, buffering: Boolean, loading: Boolean, aheadMs: Long,
    toEnd: Boolean, desiredAheadMs: Long, fullAheadMs: Long = 2_000L,
): Boolean = !buffering && (!playing || toEnd || aheadMs >= desiredAheadMs ||
    (!loading && aheadMs >= fullAheadMs))

/** Some loaders keep isLoading true even when their small buffer has reached its steady limit. */
internal class PreviewBufferGate(private val desiredAheadMs: Long, private val fullAheadMs: Long = 2_000L) {
    private var anchorAheadMs = -1L
    private var stableSinceMs = 0L
    fun reset() { anchorAheadMs = -1L }
    fun ready(nowMs: Long, playing: Boolean, buffering: Boolean, loading: Boolean, aheadMs: Long, toEnd: Boolean): Boolean {
        if (previewBufferReady(playing, buffering, loading, aheadMs, toEnd, desiredAheadMs, fullAheadMs)) {
            reset()
            return true
        }
        if (buffering || aheadMs < 4_000L) { reset(); return false }
        if (anchorAheadMs < 0 || kotlin.math.abs(aheadMs - anchorAheadMs) > 1_000L) {
            anchorAheadMs = aheadMs
            stableSinceMs = nowMs
        }
        return nowMs - stableSinceMs >= 5_000L
    }
}

/** Small, in-memory report; no stream URLs, credentials, media bytes or persistent file writes. */
internal object PreviewDiagnostics {
    private val lines = ArrayDeque<String>()
    private val startedAt = System.nanoTime()
    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    @Synchronized fun note(message: String) {
        val safe = url.replace(message, "<url>").replace('\n', ' ').take(240)
        if (lines.lastOrNull()?.substringAfter("s ") == safe) return
        lines.addLast("${(System.nanoTime() - startedAt) / 1_000_000_000}s $safe")
        while (lines.size > 96) lines.removeFirst()
    }
    @Synchronized fun text(): String = "Seek preview diagnostics\n" +
        lines.joinToString("\n").ifEmpty { "No preview session observed in this app run." }
}
