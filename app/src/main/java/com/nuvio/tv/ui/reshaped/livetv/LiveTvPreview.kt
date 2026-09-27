@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.app.ActivityManager
import android.content.Context
import android.view.TextureView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvHttp
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** How long focus rests on a channel before its preview starts, so scrolling opens no streams. */
private const val PREVIEW_DELAY_MS = 700L

/**
 * One small, muted player for the focused channel's preview. Built for weak TVs: created on the
 * first preview and released when the list is left, no audio decoding, the lowest quality an
 * adaptive stream offers and a few seconds of buffer. Loads go through Live TV's own HTTP client,
 * so previews never touch Nuvio's player, its caches or the connection speed learning.
 *
 * The picture is capped at 720p on TVs with little memory (1080p elsewhere). A channel that only
 * comes larger is not decoded at all: the panel keeps its logo and what is on now.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class LiveTvPreviewPlayer(private val context: Context) {
    private val lowMemory = isLowMemoryTv(context)
    private var player: ExoPlayer? = null
    private var surface: TextureView? = null
    private var current: LiveTvChannel? = null
    private var triedHls = false

    /** True once the channel shows a picture. */
    var showingVideo by mutableStateOf(false)
        private set

    /** The picture's width over its height. */
    var aspectRatio by mutableFloatStateOf(16f / 9f)
        private set

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            showingVideo = true
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                aspectRatio = (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height).coerceIn(1f, 2.4f)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            // Only renditions above the cap: stop, so no connection stays open for nothing.
            val hasVideo = tracks.containsType(C.TRACK_TYPE_VIDEO)
            if (hasVideo && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                val channel = current
                stop()
                current = channel // an HLS retry of the same channel would be just as large
                triedHls = true
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            showingVideo = false
            // A link without .m3u8 in it can still be HLS: try that once, then give up quietly.
            val channel = current ?: return
            if (!triedHls) {
                triedHls = true
                start(channel, hls = true)
            }
        }
    }

    fun play(channel: LiveTvChannel) {
        val hls = channel.streamUrl.contains(".m3u8", ignoreCase = true)
        triedHls = hls
        start(channel, hls)
    }

    private fun start(channel: LiveTvChannel, hls: Boolean) {
        current = channel
        showingVideo = false
        val exo = player ?: create().also { created ->
            player = created
            surface?.let(created::setVideoTextureView)
        }
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(channel.streamUrl, channel.headers)
        val dataSource = OkHttpDataSource.Factory(LiveTvHttp.client).apply {
            setDefaultRequestProperties(request.headers)
            if (request.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) setUserAgent(PREVIEW_USER_AGENT)
        }
        val item = MediaItem.Builder()
            .setUri(request.url)
            .apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        runCatching {
            exo.setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(item))
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    /** Stops the preview and closes its connection; the player stays for the next one. */
    fun stop() {
        current = null
        showingVideo = false
        player?.let { exo ->
            runCatching {
                exo.stop()
                exo.clearMediaItems()
            }
        }
    }

    /** Frees the player and its decoder (leaving the list, opening a channel, the app in the background). */
    fun release() {
        current = null
        showingVideo = false
        player?.let { exo -> runCatching { exo.release() } }
        player = null
    }

    fun attach(view: TextureView) {
        surface = view
        player?.setVideoTextureView(view)
    }

    fun detach(view: TextureView) {
        if (surface !== view) return
        player?.clearVideoTextureView(view)
        surface = null
    }

    private fun create(): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setForceLowestBitrate(true)
                    .apply { if (lowMemory) setMaxVideoSize(1280, 720) else setMaxVideoSize(1920, 1080) }
                    .setExceedVideoConstraintsIfNecessary(false)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true),
            )
        }
        val loadControl = DefaultLoadControl.Builder()
            .apply {
                if (lowMemory) setBufferDurationsMs(1_500, 3_000, 800, 1_000) else setBufferDurationsMs(2_000, 5_000, 1_000, 1_500)
            }
            .setTargetBufferBytes(if (lowMemory) 2 * 1024 * 1024 else 4 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(listener)
            }
    }

    private companion object {
        const val PREVIEW_USER_AGENT = "VLC/3.0.0 LibVLC/3.0.0"
        /** Boxes that report under this much memory (2 GB models report less than 2 GB) get the lighter preview. */
        const val LOW_MEMORY_BYTES = 2_560L * 1024 * 1024

        fun isLowMemoryTv(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
            if (manager.isLowRamDevice) return true
            val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            return info.totalMem in 1 until LOW_MEMORY_BYTES
        }
    }
}

/**
 * The focused channel, the way some TVs show it in their channel list: a small live picture
 * (after focus rests a moment, only while [playVideo]) above the channel and what is on now.
 */
@Composable
internal fun LiveTvPreviewPanel(
    preview: LiveTvPreviewPlayer,
    channel: LiveTvChannel?,
    programme: LiveTvProgramme?,
    clock: State<Long>,
    sourceLabel: String?,
    playVideo: Boolean,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(channel?.id, playVideo) {
        preview.stop()
        if (!playVideo || channel == null) return@LaunchedEffect
        // Stalker links are created per play, and portals flag a device that asks for many: those
        // channels show their logo and what is on now, without a picture.
        if (LiveTvRepository.isStalker(channel)) return@LaunchedEffect
        delay(PREVIEW_DELAY_MS)
        preview.play(channel)
    }
    // Turning previews off removes the panel: nothing may keep playing unseen.
    DisposableEffect(preview) { onDispose { preview.stop() } }

    Column(modifier = modifier) {
        val shape = RoundedCornerShape(16.dp)
        val videoAlpha by animateFloatAsState(if (preview.showingVideo) 1f else 0f, tween(260), label = "liveTvPreviewAlpha")
        val videoScale by animateFloatAsState(
            if (preview.showingVideo) 1f else 0.96f,
            spring(dampingRatio = 0.65f, stiffness = 380f),
            label = "liveTvPreviewScale",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(NuvioTheme.colors.BackgroundElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (channel != null) {
                LiveTvLogo(url = channel.logoUrl, name = channel.name, width = 128.dp, height = 76.dp)
            }
            val context = LocalContext.current
            val textureView = remember { TextureView(context) }
            DisposableEffect(textureView) {
                preview.attach(textureView)
                onDispose { preview.detach(textureView) }
            }
            AndroidView(
                factory = { textureView },
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(preview.aspectRatio, matchHeightConstraintsFirst = true)
                    .graphicsLayer {
                        alpha = videoAlpha
                        scaleX = videoScale
                        scaleY = videoScale
                    },
            )
            Text(
                text = stringResource(R.string.live_tv_live_badge),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = Color.Black,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(LiveTvPillShape)
                    .background(Color.White.copy(alpha = 0.9f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        Crossfade(targetState = channel to programme, animationSpec = tween(180), label = "liveTvPreviewInfo") { (shown, programme) ->
            if (shown == null) return@Crossfade
            Column(modifier = Modifier.fillMaxWidth().padding(top = NuvioTheme.spacing.md, start = 4.dp, end = 4.dp)) {
                Text(
                    text = shown.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (programme != null) {
                    Text(
                        text = programme.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = "${programme.timeLabel}  ·  ${liveTvTimeLeft(programme, clock)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    LiveTvProgressBar(
                        programme = programme,
                        clock = clock,
                        fill = NuvioTheme.colors.TextPrimary,
                        track = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    )
                }
                val details = listOfNotNull(shown.group.takeIf(String::isNotBlank), sourceLabel).joinToString("  ·  ")
                if (details.isNotEmpty()) {
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** The preview player for a screen: released when the screen leaves or the app goes to the background. */
@Composable
internal fun rememberLiveTvPreviewPlayer(): LiveTvPreviewPlayer {
    val context = LocalContext.current
    val preview = remember { LiveTvPreviewPlayer(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, preview) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) preview.release()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            preview.release()
        }
    }
    return preview
}
