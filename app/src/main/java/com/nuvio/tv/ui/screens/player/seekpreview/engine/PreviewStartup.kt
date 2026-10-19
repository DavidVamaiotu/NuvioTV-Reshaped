package com.nuvio.tv.ui.screens.player.seekpreview.engine

/** A full, deliberately small playback buffer must not starve preview work forever. */
internal fun previewBufferReady(
    playing: Boolean, buffering: Boolean, loading: Boolean, aheadMs: Long,
    toEnd: Boolean, desiredAheadMs: Long,
): Boolean = !buffering && (!playing || toEnd || aheadMs >= desiredAheadMs ||
    (!loading && aheadMs >= 2_000L))

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
