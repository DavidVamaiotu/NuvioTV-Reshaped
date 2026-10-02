@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.reshaped.livetv

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.tv.material3.Icon
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvCatchupLinks
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import com.nuvio.tv.reshaped.livetv.liveTvGuideSpan
import kotlin.math.roundToInt

private const val MINUTE = 60_000L
private const val SLOT = 30 * MINUTE
/** The rows keep programmes for this much on either side of the view. */
private const val BLOCK = 4 * SLOT
private val MINUTE_WIDTH = 6.dp
private val ROW_HEIGHT = 56.dp
private val CHANNEL_COLUMN = 220.dp
private val GUIDE_BACKGROUND = Color(0xFF0B0B0D)
private val CELL = Color(0xFF1A1A1D)
private val CELL_PAST = Color(0xFF141416)
private val CELL_NOW = Color(0xFF26262A)
private val CHANNEL_SELECTED = Color(0xFF2C2C31)
private val NOW_LINE = Color.White.copy(alpha = 0.55f)

/**
 * The programme guide: channels down, time across, the kept past hours to the next ones.
 * Remote handling is its own (not one focus target per programme), so thousands of channels stay
 * light: only the rows and programmes in view are drawn. ▲▼ move between channels at the same
 * time, ◀▶ between programmes, CH+/CH- by a page, OK plays the channel, Back closes.
 *
 * On the Live TV screen the guide is embedded beside the categories: ◀ from what is on now (or
 * from the oldest programme that can be played again) calls [onExitLeft], ▲ on the first channel
 * [onExitUp], and a long OK [onLongPress].
 */
@Stable
internal class LiveTvGuideState(
    channels: List<LiveTvChannel>,
    startIndex: Int,
    private val onPlay: (LiveTvChannel) -> Unit,
    private val onClose: () -> Unit,
    /** A past programme of a channel with catch-up was picked. */
    private val onCatchup: (LiveTvChannel, LiveTvProgramme) -> Unit = { channel, _ -> onPlay(channel) },
    private val onExitLeft: (() -> Unit)? = null,
    private val onExitUp: (() -> Unit)? = null,
    private val onLongPress: ((LiveTvChannel) -> Unit)? = null,
    /** How much of the past the timeline shows when it opens. */
    private val leadMs: Long = SLOT,
) {
    var channels by mutableStateOf(channels)
        private set
    var row by mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0)))
        private set
    /** The time the selection follows between channels. */
    var anchorMs by mutableLongStateOf(LiveTvClock.nowEpochMs())
        private set
    /** The left edge of the timeline in view. */
    var viewStartMs by mutableLongStateOf(floorSlot(LiveTvClock.nowEpochMs() - leadMs))
        private set
    /** How much time fits in view; set once laid out. */
    var viewSpanMs = 3 * 60 * MINUTE

    val channel: LiveTvChannel? get() = channels.getOrNull(row)

    /**
     * Picking several channels at once: OK ticks the selected channel instead of playing it,
     * Back stops picking. [picked] is by stream link, in the order they were ticked.
     */
    var selecting by mutableStateOf(false)
        private set
    val picked = androidx.compose.runtime.mutableStateMapOf<String, LiveTvChannel>()

    fun startSelecting(first: LiveTvChannel?) {
        picked.clear()
        first?.let { picked[it.streamUrl] = it }
        selecting = true
    }

    fun stopSelecting() {
        selecting = false
        picked.clear()
    }

    /**
     * Moving the selected channel within the list (a playlist of the viewer's): ▲▼ call
     * [onReorder] with it and a step, OK or Back put it down.
     */
    var moving by mutableStateOf(false)
        private set
    var onReorder: ((LiveTvChannel, Int) -> Unit)? = null

    fun stopMoving() {
        moving = false
    }

    fun startMoving() {
        if (onReorder != null && channel != null) moving = true
    }

    fun togglePicked(channel: LiveTvChannel) {
        if (picked.remove(channel.streamUrl) == null) picked[channel.streamUrl] = channel
    }

    /** The selected programme: the one at [anchorMs] in the selected channel. */
    fun selected(): LiveTvProgramme? =
        channel?.let { LiveTvRepository.schedule(it.guideKey) }?.let { programmes ->
            programmes.firstOrNull { anchorMs >= it.startEpochMs && anchorMs < it.stopEpochMs }
        }

    /**
     * Shows [list], on [keepUrl]'s channel when it is in it; [toNow] (another category or search)
     * also brings the timeline back to now, while a list only filtered again stays where it was.
     */
    fun showChannels(list: List<LiveTvChannel>, keepUrl: String?, toNow: Boolean) {
        if (list === channels) return
        Snapshot.withMutableSnapshot {
            channels = list
            row = list.indexOfFirst { it.streamUrl == keepUrl }.coerceAtLeast(0)
            if (toNow) backToNow()
        }
    }

    /** Selects the channel at [index] (focus coming back from the player). */
    fun selectRow(index: Int) {
        row = index.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    }

    fun backToNow() {
        val now = LiveTvClock.nowEpochMs()
        anchorMs = now
        viewStartMs = floorSlot(now - leadMs)
        followsNow = true
    }

    /**
     * Until ◀▶ move the selection, it stays on what is on now as time passes (the guide stays
     * open for hours on the Live TV screen); call on each minute.
     */
    fun followNow() {
        if (followsNow) backToNow()
    }

    private var followsNow = true

    private var handledDown = -1
    /** Repeats of a held ◀ while at the left edge, see [moveProgramme]. */
    private var heldAtEdge = 0
    private var longPressed = false

    fun onKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            val handled = event.keyCode == handledDown
            if (handled) {
                handledDown = -1
                // OK and Back act on release, as Nuvio's own buttons do, so no release is left
                // for the screen underneath (Back would leave Live TV, OK would pause the player).
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> channel?.let { picked ->
                        if (longPressed) {
                            longPressed = false
                            return true
                        }
                        if (moving) {
                            moving = false
                            return true
                        }
                        if (selecting) {
                            togglePicked(picked)
                            return true
                        }
                        // A programme that has ended plays again where the provider keeps it; anything else plays live.
                        val programme = selected()
                        if (programme != null && programme.stopEpochMs <= LiveTvClock.nowEpochMs() &&
                            LiveTvCatchupLinks.isPlayable(picked.catchup, programme, LiveTvClock.nowEpochMs())
                        ) {
                            onCatchup(picked, programme)
                        } else {
                            onPlay(picked)
                        }
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> when {
                        moving -> moving = false
                        selecting -> stopSelecting()
                        else -> onClose()
                    }
                }
            }
            return handled || event.keyCode in GUIDE_KEYS
        }
        if (moving) {
            when (event.keyCode) {
                // The list moves the channel; the selection follows it when the list comes back.
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    channel?.let { onReorder?.invoke(it, if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1) }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> return true
            }
        }
        // While picking, ◀ goes to the playlists at once, not back through a catch-up channel's past.
        if (selecting && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && onExitLeft != null) {
            if (event.repeatCount == 0) onExitLeft.invoke()
            return true
        }
        val acted = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (row == 0 && onExitUp != null) {
                // Only a fresh press leaves: a held ▲ stops on the first channel.
                if (event.repeatCount == 0) onExitUp.invoke()
                return true
            } else {
                moveRow(-1)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> moveRow(-PAGE)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> moveRow(PAGE)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveProgramme(-1, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveProgramme(1, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) longPressed = false
                // Held: a long press, once, instead of playing on release.
                if (event.repeatCount > 0 && handledDown == event.keyCode && onLongPress != null && !longPressed) {
                    longPressed = true
                    channel?.let(onLongPress)
                }
                event.repeatCount == 0 || handledDown == event.keyCode
            }
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

    private fun moveProgramme(step: Int, repeating: Boolean = false): Boolean {
        val now = LiveTvClock.nowEpochMs()
        val first = floorSlot(now - windowPastMs(channel?.catchup != null))
        val last = now + windowAheadMs()
        val programmes = channel?.let { LiveTvRepository.schedule(it.guideKey) }.orEmpty()
        val current = programmes.indexOfFirst { anchorMs >= it.startEpochMs && anchorMs < it.stopEpochMs }
        val target = when {
            current >= 0 -> programmes.getOrNull(current + step)
            step > 0 -> programmes.firstOrNull { it.startEpochMs > anchorMs }
            else -> programmes.lastOrNull { it.stopEpochMs <= anchorMs }
        }
        // Embedded: from now (or the past), ◀ goes on into the past only where it can be played again.
        if (step < 0 && onExitLeft != null && anchorMs <= now &&
            (target == null || !LiveTvCatchupLinks.isPlayable(channel?.catchup, target, now))
        ) {
            // A held ◀ pauses on what is on now, then goes on to the categories: it never
            // needs letting go and pressing again, which felt stuck.
            if (!repeating || ++heldAtEdge >= EXIT_AFTER_REPEATS) {
                heldAtEdge = 0
                onExitLeft.invoke()
            }
            return true
        }
        heldAtEdge = 0
        followsNow = false
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

    internal companion object {
        const val PAGE = 6
        const val EXIT_AFTER_REPEATS = 6
        val GUIDE_KEYS = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
        )

        fun floorSlot(ms: Long): Long = ms - Math.floorMod(ms, SLOT)
        fun windowPastMs(catchup: Boolean): Long = LiveTvRepository.guideWindow.pastMsFor(catchup)
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
        LaunchedEffect(Unit) {
            // Until it holds focus, keys would still reach the list underneath.
            repeat(10) {
                withFrameNanos { }
                if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            // Solid, as TV guides are: nothing behind it shows through the grid.
            .background(GUIDE_BACKGROUND)
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
        LiveTvGuideGrid(
            state = state,
            active = true,
            clock = clock,
            modifier = Modifier.fillMaxSize().padding(top = 16.dp),
        )
    }
}

/**
 * The time ruler, the channel rows with their programmes and the line at now. [active] is whether
 * the selection is shown as focused; [corner] fills the space above the channel names.
 */
@Composable
internal fun LiveTvGuideGrid(
    state: LiveTvGuideState,
    active: Boolean,
    clock: State<Long>,
    modifier: Modifier = Modifier,
    channelColumn: Dp = CHANNEL_COLUMN,
    rowHeight: Dp = ROW_HEIGHT,
    corner: @Composable () -> Unit = {},
    rulerHeight: Dp = 28.dp,
) {
    val liveState by LiveTvRepository.uiState.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val timelineWidth = maxWidth - channelColumn
        state.viewSpanMs = (timelineWidth / MINUTE_WIDTH).toLong().coerceAtLeast(60) * MINUTE
        val pxPerMs = with(density) { MINUTE_WIDTH.toPx() } / MINUTE
        // The timeline glides to its new place; programmes are placed at layout, not recomposed per
        // frame. It moves as an offset from a fixed time: epoch milliseconds in a Float would be
        // rounded to two minutes.
        val timeline = remember { GuideTimeline(Snapshot.withoutReadObservation { state.viewStartMs }, pxPerMs) }
        timeline.pxPerMs = pxPerMs
        LaunchedEffect(state.viewStartMs) {
            timeline.scroll.animateTo((state.viewStartMs - timeline.origin).toFloat(), GUIDE_GLIDE)
        }
        Column {
            Row(modifier = Modifier.fillMaxWidth().height(rulerHeight), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(channelColumn).padding(start = 8.dp)) { corner() }
                TimeRuler(state, timeline, clock, modifier = Modifier.weight(1f))
            }
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = remember { Snapshot.withoutReadObservation { (state.row - 2).coerceAtLeast(0) } },
            )
            // The list glides so the selection rests a little above the middle, as Nuvio's rows do.
            // One animation carries on through each step, keeping its speed, so holding ▲▼ is a
            // smooth run rather than a series of starts. Read here, not in composition: moving the
            // selection recomposes only the rows it leaves and enters.
            val rowPx = with(density) { rowHeight.toPx() }
            val glide = remember { Animatable(0f) }
            val glideScope = rememberCoroutineScope()
            LaunchedEffect(state, listState, rowPx) {
                var shownChannels: List<LiveTvChannel>? = null
                snapshotFlow { state.row to state.channels }.collect { (row, channels) ->
                    val changed = shownChannels !== channels
                    shownChannels = channels
                    if (channels.isEmpty()) return@collect
                    val info = listState.layoutInfo
                    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                    val anchorRows = if (viewport > 0f) ((viewport * SELECTION_AT) / rowPx).toInt() else 2
                    val targetIndex = (row - anchorRows).coerceIn(0, channels.lastIndex)
                    val target = targetIndex * rowPx
                    val current = listState.firstVisibleItemIndex * rowPx + listState.firstVisibleItemScrollOffset
                    // A jump (another playlist, a page, back from the player) lands at once.
                    if (changed || info.visibleItemsInfo.isEmpty() || kotlin.math.abs(target - current) > viewport * 1.5f) {
                        glideScope.launch {
                            glide.stop()
                            listState.scrollToItem(targetIndex)
                            glide.snapTo(listState.firstVisibleItemIndex * rowPx + listState.firstVisibleItemScrollOffset)
                        }
                        return@collect
                    }
                    if (!glide.isRunning) glide.snapTo(current)
                    glideScope.launch {
                        glide.animateTo(target, GUIDE_GLIDE) {
                            val now = listState.firstVisibleItemIndex * rowPx + listState.firstVisibleItemScrollOffset
                            listState.dispatchRawDelta(value - now)
                        }
                    }
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState, userScrollEnabled = false, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.channels, key = { _, channel -> channel.id }, contentType = { _, _ -> "guideRow" }) { index, channel ->
                        GuideRow(
                            channel = channel,
                            logo = liveState.logoFor(channel),
                            isFavorite = channel.streamUrl in liveState.favoriteUrls,
                            picked = if (state.selecting) channel.streamUrl in state.picked else null,
                            moving = state.moving && index == state.row,
                            selectedRow = index == state.row,
                            active = active,
                            guideVersion = liveState.guideVersion,
                            state = state,
                            timeline = timeline,
                            clock = clock,
                            channelColumn = channelColumn,
                            rowHeight = rowHeight,
                        )
                    }
                }
                // Now, as a thin line through the rows.
                Box(
                    modifier = Modifier
                        .padding(start = channelColumn)
                        .fillMaxSize()
                        .clipToBounds()
                        .drawBehind {
                            val x = timeline.x(clock.value)
                            if (x in 0f..size.width) {
                                drawLine(NOW_LINE, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                            }
                        },
                )
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
                val catchup = channel?.catchup?.takeIf { programme.startEpochMs < clock.value }
                    ?.takeIf { LiveTvCatchupLinks.isPlayable(it, programme, clock.value) }
                    ?.let { stringResource(R.string.live_tv_catchup) }
                listOfNotNull(LiveTvClock.formatSpan(programme), status, catchup).joinToString("  ·  ")
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

/** Where the timeline is: [scroll] is the left edge's offset from [origin], in ms. */
@Stable
private class GuideTimeline(val origin: Long, var pxPerMs: Float) {
    val scroll: Animatable<Float, AnimationVector1D> = Animatable(0f)

    /** The x position of [epochMs] in the timeline now, in px. Read at layout or draw time. */
    fun x(epochMs: Long): Float = ((epochMs - origin).toFloat() - scroll.value) * pxPerMs
}

@Composable
private fun TimeRuler(
    state: LiveTvGuideState,
    timeline: GuideTimeline,
    clock: State<Long>,
    modifier: Modifier = Modifier,
) {
    val start = state.viewStartMs - SLOT
    val slots = remember(start, state.viewSpanMs) { (0..(state.viewSpanMs / SLOT + 2).toInt()).map { start + it * SLOT } }
    Box(modifier = modifier.fillMaxHeight().clipToBounds(), contentAlignment = Alignment.CenterStart) {
        slots.forEach { slot ->
            Text(
                text = LiveTvClock.formatClock(slot),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = if (slot <= clock.value && clock.value < slot + SLOT) 0.9f else 0.5f),
                modifier = Modifier.offset { IntOffset(timeline.x(slot).roundToInt() + 8, 0) },
            )
        }
    }
}

@Composable
private fun GuideRow(
    channel: LiveTvChannel,
    logo: String?,
    isFavorite: Boolean,
    /** While picking channels: whether this one is ticked; null otherwise. */
    picked: Boolean?,
    /** Being moved within the playlist. */
    moving: Boolean,
    selectedRow: Boolean,
    active: Boolean,
    guideVersion: Int,
    state: LiveTvGuideState,
    timeline: GuideTimeline,
    clock: State<Long>,
    channelColumn: Dp,
    rowHeight: Dp,
) {
    Row(modifier = Modifier.fillMaxWidth().height(rowHeight).padding(vertical = 3.dp)) {
        val rowHighlight by animateColorAsState(if (selectedRow) CHANNEL_SELECTED else Color.Transparent, SELECTION_FADE, label = "guideRow")
        Row(
            modifier = Modifier
                .width(channelColumn)
                .fillMaxHeight()
                .padding(end = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .drawBehind { drawRect(rowHighlight) }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (moving) {
                Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = Color.White, modifier = Modifier.padding(end = 6.dp).size(18.dp))
            }
            if (picked != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (picked) Color.White else Color.White.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picked) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
                }
            }
            LiveTvLogo(url = logo, name = channel.name, width = 48.dp, height = 30.dp)
            Text(
                text = channel.name,
                style = if (rowHeight >= 48.dp) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                fontWeight = if (selectedRow) FontWeight.SemiBold else FontWeight.Normal,
                color = Color.White.copy(alpha = if (selectedRow) 1f else 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
            )
            if (isFavorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = stringResource(R.string.live_tv_favorites),
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 4.dp).size(14.dp),
                )
            }
            if (channel.catchup != null) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = stringResource(R.string.live_tv_catchup),
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 4.dp).size(16.dp),
                )
            }
        }
        // Programmes in view and two hours either side: the rows recompose only when the timeline
        // moves into another two hours, not on each step.
        val block by remember(state) { derivedStateOf { state.viewStartMs / BLOCK } }
        val viewStart = block * BLOCK - BLOCK
        val viewEnd = block * BLOCK + state.viewSpanMs + 2 * BLOCK
        val programmes = remember(channel.guideKey, viewStart, viewEnd, guideVersion) {
            LiveTvRepository.schedule(channel.guideKey).filter { it.stopEpochMs > viewStart && it.startEpochMs < viewEnd }
        }
        val selected = if (selectedRow) programmes.firstOrNull { state.anchorMs >= it.startEpochMs && state.anchorMs < it.stopEpochMs } else null
        Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            if (programmes.isEmpty()) {
                GuideCell(
                    title = stringResource(R.string.live_tv_guide_none),
                    selected = selectedRow && active,
                    state = GuideCellState.Future,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val density = LocalDensity.current
            // Time the guide has nothing for (before its first programme, between two, after the
            // last) shows as "No guide" too, instead of an empty gap.
            val gaps = remember(programmes, viewStart, viewEnd) { guideGaps(programmes, viewStart, viewEnd) }
            val noGuide = stringResource(R.string.live_tv_guide_none)
            gaps.forEach { (gapStart, gapStop) ->
                GuideCell(
                    title = noGuide,
                    selected = selectedRow && active && selected == null && state.anchorMs >= gapStart && state.anchorMs < gapStop,
                    state = GuideCellState.Future,
                    modifier = Modifier
                        .offset { IntOffset(timeline.x(gapStart).roundToInt(), 0) }
                        .width(with(density) { ((gapStop - gapStart) * timeline.pxPerMs).toDp() })
                        .fillMaxHeight()
                        .padding(end = 4.dp),
                    titleShift = { (-timeline.x(gapStart)).coerceAtLeast(0f).roundToInt() },
                )
            }
            programmes.forEach { programme ->
                val (cellStart, cellStop) = liveTvGuideSpan(programme.startEpochMs, programme.stopEpochMs, viewStart, viewEnd)
                    ?: return@forEach
                val widthDp = with(density) { ((cellStop - cellStart) * timeline.pxPerMs).toDp() }
                val cellState = when {
                    // Past programmes the provider keeps can be played again: they stay bright.
                    programme.stopEpochMs <= clock.value ->
                        if (LiveTvCatchupLinks.isPlayable(channel.catchup, programme, clock.value)) GuideCellState.Future else GuideCellState.Past
                    programme.startEpochMs <= clock.value -> GuideCellState.Now
                    else -> GuideCellState.Future
                }
                GuideCell(
                    title = programme.title,
                    selected = programme === selected && active,
                    state = cellState,
                    progress = if (cellState == GuideCellState.Now) programme else null,
                    progressSpan = cellStart to cellStop,
                    clock = clock,
                    modifier = Modifier
                        .offset { IntOffset(timeline.x(cellStart).roundToInt(), 0) }
                        .width(widthDp)
                        .fillMaxHeight()
                        .padding(end = 4.dp),
                    // A programme that began before the view keeps its title in view, marked ‹ as TV guides do.
                    titleShift = { (-timeline.x(cellStart)).coerceAtLeast(0f).roundToInt() },
                )
            }
        }
    }
}

private enum class GuideCellState { Past, Now, Future }

/** Stretches of [from]..[to] that none of [programmes] (sorted by start) covers, of a minute or more. */
private fun guideGaps(programmes: List<LiveTvProgramme>, from: Long, to: Long): List<Pair<Long, Long>> {
    if (programmes.isEmpty()) return emptyList()
    val gaps = ArrayList<Pair<Long, Long>>(2)
    var covered = from
    programmes.forEach { programme ->
        if (programme.startEpochMs - covered >= GAP_MIN_MS) gaps += covered to programme.startEpochMs
        covered = maxOf(covered, programme.stopEpochMs)
    }
    if (to - covered >= GAP_MIN_MS) gaps += covered to to
    return gaps
}

private const val GAP_MIN_MS = 60_000L

/** How far down the guide the selected row rests. */
private const val SELECTION_AT = 0.4f
/** The guide's glide, for rows and the timeline: quick, and settling without a bounce. */
private val GUIDE_GLIDE = spring<Float>(dampingRatio = 1f, stiffness = 320f)
private val SELECTION_FADE = tween<Color>(durationMillis = 140, easing = FastOutSlowInEasing)

@Composable
private fun GuideCell(
    title: String,
    selected: Boolean,
    state: GuideCellState,
    modifier: Modifier = Modifier,
    /** The programme on now, whose progress shows as a line along the bottom. */
    progress: LiveTvProgramme? = null,
    /** The time the cell spans when it is cut to the view: the line runs along that part only. */
    progressSpan: Pair<Long, Long>? = null,
    clock: State<Long>? = null,
    titleShift: () -> Int = { 0 },
) {
    val shape = RoundedCornerShape(8.dp)
    // The selection fades from cell to cell; drawn at draw time, so the fade recomposes nothing.
    val fill by animateColorAsState(
        when {
            selected -> Color.White
            state == GuideCellState.Now -> CELL_NOW
            state == GuideCellState.Past -> CELL_PAST
            else -> CELL
        },
        SELECTION_FADE,
        label = "guideCell",
    )
    val textColor by animateColorAsState(
        when {
            selected -> Color.Black
            state == GuideCellState.Past -> Color.White.copy(alpha = 0.5f)
            else -> Color.White.copy(alpha = 0.9f)
        },
        SELECTION_FADE,
        label = "guideCellText",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .drawBehind { drawRect(fill) }
            .then(
                if (progress != null && clock != null) {
                    val from = progressSpan?.first ?: progress.startEpochMs
                    val span = ((progressSpan?.second ?: progress.stopEpochMs) - from).coerceAtLeast(1L)
                    val line = if (selected) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.7f)
                    // Read at draw time: the minute tick redraws it without recomposing.
                    Modifier.drawBehind {
                        val fraction = ((clock.value - from).toFloat() / span).coerceIn(0f, 1f)
                        val height = 3.dp.toPx()
                        drawRect(line, topLeft = Offset(0f, size.height - height), size = Size(size.width * fraction, height))
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            // Drawn only while the title is pushed in from the cell's start (it began earlier).
            text = "‹",
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) Color.Black else Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .offset { IntOffset(titleShift(), 0) }
                .padding(start = 6.dp)
                .graphicsLayer { alpha = if (titleShift() > 0) 1f else 0f },
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected || state == GuideCellState.Now) FontWeight.Medium else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset { IntOffset(titleShift(), 0) }
                .padding(start = 16.dp, end = 10.dp),
        )
    }
}
