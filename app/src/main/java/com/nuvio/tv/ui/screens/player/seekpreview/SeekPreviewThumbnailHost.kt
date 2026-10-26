package com.nuvio.tv.ui.screens.player.seekpreview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Frame width as a share of the width available (the progress bar's), clamped for a 10-foot
 * screen: about 260 dp on a typical 960 dp wide TV, never smaller than 240 dp nor wider than
 * 400 dp on large layouts.
 */
private const val FrameWidthFraction = 0.30f
private val MinFrameWidth = 240.dp
private val MaxFrameWidth = 400.dp
private val FrameGapAboveBar = 10.dp
private const val LingerAfterScrubMs = 1500L

/**
 * The scrub-time preview, Netflix style, above the progress bar: the frame of the cue the scrub
 * lands on in the middle, outlined, with the frames before and after it either side (see
 * [SeekPreviewFilmstripRow]).
 *
 * Grid-locked scrubbing (see [SeekPreviewCueStepper]) parks the playhead on the centre frame's
 * own timestamp, so the frame outlined is the frame playback resumes on, and the controls' own
 * time readout stays the single, honest position label.
 */
@Composable
fun SeekPreviewThumbnailHost(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val seekPreview = viewModel.seekPreview
    val track by seekPreview.previewTrack.collectAsStateWithLifecycle()
    val offsetState by seekPreview.offsetMs.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val timeline by viewModel.playbackTimeline.collectAsStateWithLifecycle()
    val activeTrack = track

    val previewTs = uiState.pendingPreviewSeekPosition
    val scrubActive = previewTs != null || uiState.showSeekOverlay

    // Prevent flicker between repeated seek inputs.
    var lingerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(scrubActive) {
        if (scrubActive) {
            lingerVisible = true
        } else {
            delay(LingerAfterScrubMs)
            lingerVisible = false
        }
    }

    val displayTs = previewTs ?: timeline.currentPosition
    val offsetMs = offsetState.toLong()
    // On-device tracks fill in while playing; a new revision means the frame may have sharpened.
    val revision by (activeTrack?.revision ?: NoRevision).collectAsStateWithLifecycle()
    var strip by remember(activeTrack) { mutableStateOf<Filmstrip?>(null) }
    // In frames: how far the strip still has to slide to settle on its centre.
    val slide = remember { Animatable(0f) }
    // Conflate rapid scrub/nudge changes: a lookup that finishes takes the latest request next.
    val requestFlow = remember(activeTrack) {
        MutableStateFlow(PreviewRequest(displayTs, offsetMs, revision, lingerVisible))
    }
    LaunchedEffect(activeTrack, displayTs, offsetMs, revision, lingerVisible) {
        requestFlow.value = PreviewRequest(displayTs, offsetMs, revision, lingerVisible)
    }
    LaunchedEffect(activeTrack) {
        if (activeTrack == null) {
            seekPreview.onPreviewCueResolved(null)
            return@LaunchedEffect
        }
        coroutineScope {
            var cachedCovering: SeekPreviewCue? = null
            var cachedPrefersSuccessor = false
            var cachedOffsetMs: Long? = null
            var cachedRevision: Int? = null
            var slideJob: Job? = null
            var lastPaintAt = 0L
            val fills = MutableStateFlow<Filmstrip?>(null)

            // A neighbor lookup finishes before taking the latest strip. Key repeats do not
            // cancel its decode or discard all four neighbors until the key is released.
            launch {
                fills.filterNotNull().collect { seed ->
                    fun stillCurrent(): Boolean {
                        val current = strip
                        return current != null && current.matches(seed.revision, seed.offsetMs) &&
                            current.center?.cueStartMs == seed.center?.cueStartMs &&
                            requestFlow.value.offsetMs == seed.offsetMs &&
                            requestFlow.value.revision == seed.revision && requestFlow.value.visible
                    }
                    activeTrack.fillFilmstrip(seed, refreshAll = false,
                        stillCurrent = ::stillCurrent,
                        onProgress = { filled -> if (stillCurrent()) strip = filled })
                }
            }

            // StateFlow conflates inputs, while completed useful frames are painted even when
            // a held key has already moved again. Only an up-to-date result may align the seek.
            requestFlow.collect { (positionMs, offset, rev, visible) ->
                val covering = cachedCovering
                if (offset == cachedOffsetMs && (rev == cachedRevision || !visible) &&
                    covering != null && covering.canAlignSeek && covering.contains(positionMs) &&
                    covering.prefersSuccessorFor(positionMs) == cachedPrefersSuccessor
                ) {
                    strip?.takeIf { visible && it.frames.any { frame -> frame == null } }?.let { fills.value = it }
                    return@collect
                }
                activeTrack.offsetMs = offset
                val coveringThumbnail = activeTrack.thumbnailFor(positionMs)
                var latest = requestFlow.value
                if (coveringThumbnail == null) {
                    // A miss for an obsolete request must not erase a newer visible strip.
                    if (latest.positionMs != positionMs || latest.offsetMs != offset) return@collect
                    val center = strip?.center
                    if (center == null || !SeekPreviewCue(center.cueStartMs - offset, center.cueEndMs - offset).represents(positionMs)) {
                        fills.value = null
                        strip = null
                        cachedCovering = null
                        seekPreview.onPreviewCueResolved(null)
                    }
                    return@collect
                }
                if (!PreviewScrubPublication.mayPaint(positionMs, latest.positionMs,
                        coveringThumbnail.cueEndMs - coveringThumbnail.cueStartMs,
                        latest.offsetMs == offset && latest.revision == rev,
                        strip?.center?.cueStartMs?.minus(offset))) return@collect

                val coveringCue = SeekPreviewCue(
                    coveringThumbnail.cueStartMs - offset, coveringThumbnail.cueEndMs - offset,
                    canAlignSeek = !coveringThumbnail.approximate && coveringThumbnail.actualFrameMs == null)
                val prefersSuccessor = coveringCue.canAlignSeek && coveringCue.prefersSuccessorFor(positionMs)
                val successor = if (prefersSuccessor) activeTrack.sideThumbnailFor(coveringCue.endMs)
                    ?.takeIf { it.cueStartMs != coveringThumbnail.cueStartMs } else null
                latest = requestFlow.value
                if (!PreviewScrubPublication.mayPaint(positionMs, latest.positionMs, coveringCue.durationMs,
                        latest.offsetMs == offset && latest.revision == rev,
                        strip?.center?.cueStartMs?.minus(offset))) return@collect

                cachedCovering = coveringCue
                cachedPrefersSuccessor = prefersSuccessor
                cachedOffsetMs = offset
                cachedRevision = rev
                val center = successor ?: coveringThumbnail
                val previous = strip?.takeIf { it.matches(rev, offset) }
                val steps = previous?.stepsTo(center.cueStartMs)
                val seeded = Filmstrip.around(center, previous, steps, rev, offset)
                strip = seeded
                if (latest.positionMs == positionMs) {
                    seekPreview.onPreviewCueResolved(SeekPreviewCue(
                        center.cueStartMs - offset, center.cueEndMs - offset,
                        canAlignSeek = !center.approximate && center.actualFrameMs == null))
                }
                if (steps != 0) {
                    slideJob?.cancel()
                    val now = SystemClock.elapsedRealtime()
                    val animate = steps != null && kotlin.math.abs(steps) == 1 && now - lastPaintAt >= 55
                    slideJob = launch {
                        slide.snapTo(if (animate) steps!!.toFloat() else 0f)
                        if (animate) slide.animateTo(0f, tween(40))
                    }
                    lastPaintAt = now
                }
                fills.value = if (visible) seeded else null
            }
        }
    }

    AnimatedVisibility(
        visible = lingerVisible && activeTrack != null,
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val frameWidth = (maxWidth * FrameWidthFraction)
                .coerceIn(MinFrameWidth, MaxFrameWidth)
                .coerceAtMost(maxWidth)
            strip?.let { current ->
                SeekPreviewFilmstripRow(
                    strip = current,
                    slide = slide,
                    frameWidth = frameWidth,
                    modifier = Modifier.padding(bottom = FrameGapAboveBar)
                )
            }
        }
    }
}

private data class PreviewRequest(
    val positionMs: Long,
    val offsetMs: Long,
    val revision: Int,
    val visible: Boolean
)

private val NoRevision: StateFlow<Int> = MutableStateFlow(0)
