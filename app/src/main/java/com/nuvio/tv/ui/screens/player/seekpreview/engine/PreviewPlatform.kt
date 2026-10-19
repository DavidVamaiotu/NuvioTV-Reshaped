package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Main-thread playback boundary. The engine never retains a Nuvio controller or an ExoPlayer. */
internal interface PreviewPlayer {
    val videoFormat: Format?
    val duration: Long
    val currentPosition: Long
    val bufferedPosition: Long
    val playbackState: Int
    val isLoading: Boolean
    val isPlaying: Boolean
    val playWhenReady: Boolean
    val playerError: PlaybackException?
    val droppedFrames: Int
    val speed: Float
    fun play()
}

/** Values sampled by the adapter, not reads into the playing data source. */
internal object PreviewHostSignals {
    @Volatile var playbackConnections = 1
    @Volatile var hudClampLatched = false
    @Volatile var hudClampLastHitAtMs = 0L
}

internal enum class SeekThumbMode { OFF, HD, ALL }
internal enum class ThumbPrepareMode { OFF, ALWAYS }
internal enum class ThumbsBeforePlay(val gridTimes: Int) { FULL(1), FASTER(2), FASTEST(4) }
internal data class PreviewOptions(val prepare: Boolean = false, val coverage: ThumbsBeforePlay = ThumbsBeforePlay.FASTER,
    val copies: Boolean = true, val nextEpisode: Boolean = true)

/** Owned by this feature; existing local-preview and image-quality choices retain their meaning. */
internal object PreviewPreferences {
    private const val NAME = "nuvio_seek_preview_engine"
    private val values = MutableStateFlow(PreviewOptions())
    private var loaded = false
    @Synchronized fun options(context: Context): StateFlow<PreviewOptions> {
        if (!loaded) {
            val p = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            // Older builds persisted a startup hold. Preview work is now background-only.
            p.edit().remove("prepare").apply()
            values.value = PreviewOptions(false,
                runCatching { ThumbsBeforePlay.valueOf(p.getString("coverage", "FASTER")!!) }.getOrDefault(ThumbsBeforePlay.FASTER),
                p.getBoolean("copies", true), p.getBoolean("next_episode", true))
            loaded = true
        }
        return values
    }
    fun set(context: Context, options: PreviewOptions) {
        this.options(context)
        val background = options.copy(prepare = false)
        values.value = background
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .remove("prepare").putString("coverage", background.coverage.name)
            .putBoolean("copies", options.copies).putBoolean("next_episode", options.nextEpisode).apply()
    }
    fun rgb565(context: Context): Boolean = context.applicationContext
        .getSharedPreferences("image_performance", Context.MODE_PRIVATE).getBoolean("rgb565_enabled", true)
}

/** Compatibility names keep the imported scheduler policies easy to compare with Arc. */
internal object SeekThumbnailPreferences {
    fun mode(context: Context) = SeekThumbMode.ALL // The existing Generate previews switch owns activation.
    fun beforePlay(context: Context) = PreviewPreferences.options(context).value.coverage
    fun prepareMode(context: Context) = ThumbPrepareMode.OFF
    fun copyEnabled(context: Context) = PreviewPreferences.options(context).value.copies
}

internal object PreviewSourcePolicy {
    fun isUsenet(url: String) = CopyChoice.isUsenet(url)
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") ||
            h.startsWith("fe80:") || h.startsWith("fc") && ':' in h || h.startsWith("fd") && ':' in h) return true
        val parts = h.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || parts[0] == 127 || parts[0] == 192 && parts[1] == 168 ||
            parts[0] == 172 && parts[1] in 16..31 || parts[0] == 169 && parts[1] == 254 ||
            parts[0] == 100 && parts[1] in 64..127
    }
}

internal object PreviewRetryAfter {
    fun parseHeaderMs(header: String?, nowEpochMs: Long): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { return it.coerceIn(0, 1800) * 1000 }
        return runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli() - nowEpochMs).coerceAtLeast(0) }.getOrNull()
    }
}

/** Reuses one keep-alive pool across providers, with feature-owned cancellation and limits. */
private val previewHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
internal fun thumbnailHttpClient(headers: Map<String, String>): OkHttpClient = previewHttp
