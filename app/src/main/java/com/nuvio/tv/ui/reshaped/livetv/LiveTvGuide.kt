@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import kotlin.math.roundToInt

private const val MINUTE = 60_000L
private const val SLOT = 30 * MINUTE
private val MINUTE_WIDTH = 6.dp
private val ROW_HEIGHT = 56.dp
private val CHANNEL_COLUMN = 220.dp

/**
 * The programme guide: channels down, time across, the kept past hours to the next ones.
 * Remote handling is its own (not one focus target per programme), so thousands of channels stay
 * light: only the rows and programmes in view are drawn. ▲▼ move between channels at the same
 * time, ◀▶ between programmes, CH+/CH- by a page, OK plays the channel, Back closes.
 */
@Stable
internal class LiveTvGuideState(
    val channels: List<LiveTvChannel>,
    startIndex: Int,
    private val onPlay: (LiveTvChannel) -> Unit,
    private val onClose: () -> Unit,
) {
    var row by mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0)))
        private set
    /** The time the selection follows between channels. */
    var anchorMs by mutableLongStateOf(LiveTvClock.nowEpochMs())
        private set
    /** The left edge of the timeline in view. */
    var viewStartMs by mutableLongStateOf(floorSlot(LiveTvClock.nowEpochMs() - SLOT))
        private set
    /** How much time fits in view; set once laid out. */
    var viewSpanMs = 3 * 60 * MINUTE

    val channel: LiveTvChannel? get() = channels.getOrNull(row)

    /** The selected programme: the one at [anchorMs] in the selected channel. */
    fun selected(): LiveTvProgramme? =
        channel?.let { LiveTvRepository.schedule(it.guideKey) }?.let { programmes ->
            programmes.firstOrNull { anchorMs >= it.startEpochMs && anchorMs < it.stopEpochMs }
        }

    private var handledDown = -1

    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            val handled = event.keyCode == handledDown
            if (handled) {
                handledDown = -1
                // OK and Back act on release, as Nuvio's own buttons do, so no release is left
                // for the screen underneath (Back would leave Live TV, OK would pause the player).
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> channel?.let(onPlay)
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> onClose()
                }
            }
            return handled || event.keyCode in GUIDE_KEYS
        }
        val acted = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> moveRow(-PAGE)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> moveRow(PAGE)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveProgramme(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveProgramme(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> event.repeatCount == 0 || handledDown == event.keyCode
            else -> false
        }
        if (acted) handledDown = event.keyCode
        return acted
    }

    private fun moveRow(step: Int): Boolean {
        if (channels.isEmpty()) return true
        row = (row + step).coerceIn(0, channels.size - 1)
        return true
    }

    private fun moveProgramme(step: Int): Boolean {
        val now = LiveTvClock.nowEpochMs()
        val first = floorSlot(now - windowPastMs())
        val last = now + windowAheadMs()
        val programmes = channel?.let { LiveTvRepository.schedule(it.guideKey) }.orEmpty()
        val current = programmes.indexOfFirst { anchorMs >= it.startEpochMs && anchorMs < it.stopEpochMs }
        val target = when {
            current >= 0 -> programmes.getOrNull(current + step)
            step > 0 -> programmes.firstOrNull { it.startEpochMs > anchorMs }
            else -> programmes.lastOrNull { it.stopEpochMs <= anchorMs }
        }
        anchorMs = when {
            // Adjacent programme; one that started before the view is anchored where it shows.
            target != null && (current < 0 || target.startEpochMs - programmes[current].stopEpochMs < SLOT) ->
                maxOf(target.startEpochMs, minOf(viewStartMs, target.stopEpochMs - MINUTE))
            // No guide there: move by half an hour.
            else -> (floorSlot(anchorMs) + step * SLOT).coerceIn(first, last)
        }
        keepAnchorInView(first, last)
        return true
    }

    /** Scrolls the timeline so the selected programme's start (or the anchor) is in view. */
    private fun keepAnchorInView(first: Long, last: Long) {
        val start = selected()?.startEpochMs ?: anchorMs
        val latestStart = (last - viewSpanMs).coerceAtLeast(first)
        viewStartMs = when {
            anchorMs < viewStartMs -> floorSlot(maxOf(start, anchorMs - viewSpanMs / 2))
            maxOf(start, anchorMs) >= viewStartMs + viewSpanMs - SLOT -> floorSlot(maxOf(start, anchorMs)) - SLOT
            else -> viewStartMs
        }.coerceIn(first, latestStart)
    }

    private companion object {
        const val PAGE = 6
        val GUIDE_KEYS = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
        )

        fun floorSlot(ms: Long): Long = ms - Math.floorMod(ms, SLOT)
        fun windowPastMs(): Long = LiveTvRepository.guideWindow.pastMs
        fun windowAheadMs(): Long = LiveTvRepository.guideWindow.aheadMs
    }
}

/**
 * The guide over the whole screen. [takeFocus] is false in the player, whose own key handler
 * passes keys to [LiveTvGuideState.onKey]; on the Live TV screen the guide holds focus itself.
 */
@Composable
internal fun LiveTvGuide(state: LiveTvGuideState, takeFocus: Boolean, modifier: Modifier = Modifier) {
    val liveState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    val clock = rememberLiveTvMinuteClock()
    val focus = remember { FocusRequester() }
    if (takeFocus) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xF20B0B0D))
            .then(
                if (takeFocus) {
                    Modifier
                        .focusRequester(focus)
                        .onPreviewKeyEvent { state.onKey(it.nativeKeyEvent) }
                        .focusable()
                } else {
                    Modifier
                },
            )
            .padding(start = 48.dp, end = 40.dp, top = 32.dp),
    ) {
        GuideHeader(state, liveState.guideVersion, clock)
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            val density = LocalDensity.current
            val timelineWidth = maxWidth - CHANNEL_COLUMN
            state.viewSpanMs = (timelineWidth / MINUTE_WIDTH).toLong().coerceAtLeast(60) * MINUTE
            // The timeline glides to its new place; programmes are placed at layout, not recomposed per frame.
            val scroll = remember { Animatable(state.viewStartMs.toFloat()) }
            LaunchedEffect(state.viewStartMs) {
                scroll.animateTo(state.viewStartMs.toFloat(), spring(dampingRatio = 0.9f, stiffness = 420f))
            }
            val pxPerMs = with(density) { MINUTE_WIDTH.toPx() } / MINUTE
            Column {
                TimeRuler(state, scroll, pxPerMs, clock)
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.row - 3).coerceAtLeast(0))
                LaunchedEffect(state.row) {
                    val visible = listState.layoutInfo.visibleItemsInfo
                    val first = visible.firstOrNull()?.index ?: 0
                    val last = visible.lastOrNull()?.index ?: 0
                    if (state.row <= first || state.row >= last) {
                        listState.animateScrollToItem((state.row - 3).coerceAtLeast(0))
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, userScrollEnabled = false, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(state.channels, key = { _, channel -> channel.id }, contentType = { _, _ -> "guideRow" }) { index, channel ->
                            GuideRow(
                                channel = channel,
                                logo = liveState.logoFor(channel),
                                selectedRow = index == state.row,
                                guideVersion = liveState.guideVersion,
                                state = state,
                                scroll = scroll,
                                pxPerMs = pxPerMs,
                                clock = clock,
                            )
                        }
                    }
                    // Now, as a thin line through the rows.
                    Box(
                        modifier = Modifier
                            .padding(start = CHANNEL_COLUMN)
                            .fillMaxSize()
                            .clipToBounds()
                            .drawBehind {
                                val x = (clock.value - scroll.value) * pxPerMs
                                if (x in 0f..size.width) {
                                    drawLine(Color.White.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                                }
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideHeader(state: LiveTvGuideState, guideVersion: Int, clock: State<Long>) {
    val channel = state.channel
    val selected = remember(state.row, state.anchorMs, guideVersion) { state.selected() }
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().height(96.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.live_tv_guide).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.5.sp,
                color = Color.White.copy(alpha = 0.5f),
            )
            Text(
                text = selected?.title ?: channel?.name.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            val timing = selected?.let { programme ->
                val status = when {
                    programme.stopEpochMs <= clock.value -> stringResource(R.string.live_tv_guide_ended)
                    programme.startEpochMs <= clock.value -> liveTvTimeLeft(programme, clock)
                    else -> null
                }
                listOfNotNull(LiveTvClock.formatSpan(programme), status).joinToString("  ·  ")
            }
            Text(
                text = listOfNotNull(channel?.name?.takeIf { selected != null }, timing).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = stringResource(R.string.live_tv_guide_hint),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.42f),
        )
    }
}

@Composable
private fun TimeRuler(state: LiveTvGuideState, scroll: Animatable<Float, AnimationVector1D>, pxPerMs: Float, clock: State<Long>) {
    val start = state.viewStartMs - SLOT
    val slots = remember(start, state.viewSpanMs) { (0..(state.viewSpanMs / SLOT + 2).toInt()).map { start + it * SLOT } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(start = CHANNEL_COLUMN)
            .clipToBounds(),
    ) {
        slots.forEach { slot ->
            Text(
                text = LiveTvClock.formatClock(slot),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = if (slot <= clock.value && clock.value < slot + SLOT) 0.9f else 0.5f),
                modifier = Modifier.offset { IntOffset(((slot - scroll.value) * pxPerMs).roundToInt() + 8, 0) },
            )
        }
    }
}

@Composable
private fun GuideRow(
    channel: LiveTvChannel,
    logo: String?,
    selectedRow: Boolean,
    guideVersion: Int,
    state: LiveTvGuideState,
    scroll: Animatable<Float, AnimationVector1D>,
    pxPerMs: Float,
    clock: State<Long>,
) {
    Row(modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier
                .width(CHANNEL_COLUMN)
                .fillMaxHeight()
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selectedRow) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveTvLogo(url = logo, name = channel.name, width = 54.dp, height = 34.dp)
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selectedRow) FontWeight.SemiBold else FontWeight.Normal,
                color = Color.White.copy(alpha = if (selectedRow) 1f else 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        // Programmes in view (and a slot either side, for the glide).
        val viewStart = state.viewStartMs - SLOT
        val viewEnd = state.viewStartMs + state.viewSpanMs + SLOT
        val programmes = remember(channel.guideKey, viewStart, viewEnd, guideVersion) {
            LiveTvRepository.schedule(channel.guideKey).filter { it.stopEpochMs > viewStart && it.startEpochMs < viewEnd }
        }
        val selected = if (selectedRow) programmes.firstOrNull { state.anchorMs >= it.startEpochMs && state.anchorMs < it.stopEpochMs } else null
        Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            if (programmes.isEmpty()) {
                GuideCell(
                    title = stringResource(R.string.live_tv_guide_none),
                    selected = selectedRow,
                    state = GuideCellState.Future,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            programmes.forEach { programme ->
                val widthDp = with(LocalDensity.current) { ((programme.stopEpochMs - programme.startEpochMs) * pxPerMs).toDp() }
                GuideCell(
                    title = programme.title,
                    selected = programme === selected,
                    state = when {
                        programme.stopEpochMs <= clock.value -> GuideCellState.Past
                        programme.startEpochMs <= clock.value -> GuideCellState.Now
                        else -> GuideCellState.Future
                    },
                    modifier = Modifier
                        .offset { IntOffset(((programme.startEpochMs - scroll.value) * pxPerMs).roundToInt(), 0) }
                        .width(widthDp)
                        .fillMaxHeight()
                        .padding(end = 4.dp),
                    // A programme that began before the view keeps its title in view.
                    titleShift = { ((scroll.value - programme.startEpochMs) * pxPerMs).coerceAtLeast(0f).roundToInt() },
                )
            }
        }
    }
}

private enum class GuideCellState { Past, Now, Future }

@Composable
private fun GuideCell(
    title: String,
    selected: Boolean,
    state: GuideCellState,
    modifier: Modifier = Modifier,
    titleShift: () -> Int = { 0 },
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    selected -> Color.White
                    state == GuideCellState.Now -> Color.White.copy(alpha = 0.11f)
                    else -> Color.White.copy(alpha = 0.05f)
                },
            )
            .then(if (selected) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.06f), shape)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected || state == GuideCellState.Now) FontWeight.Medium else FontWeight.Normal,
            color = when {
                selected -> Color.Black
                state == GuideCellState.Past -> Color.White.copy(alpha = 0.45f)
                else -> Color.White.copy(alpha = 0.85f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset { IntOffset(titleShift(), 0) }
                .padding(horizontal = 10.dp),
        )
    }
}
