package com.nuvio.tv.ui.reshaped.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvClock
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import kotlinx.coroutines.delay

/**
 * Rewinding a channel with catch-up, live or in a replay: a time picked on a bar from the start of
 * the show to now, played by asking the provider for a replay from there. Seeking inside the
 * stream would need it to be seekable, and most IPTV streams (TS) are not.
 */
@Stable
internal class LiveTvScrub(val channel: LiveTvChannel, fromMs: Long, targetMs: Long) {
    /** The start of the bar: the show's start (earlier ones are added by going past it). */
    var fromMs by mutableLongStateOf(fromMs)
    /** The time OK plays from. */
    var targetMs by mutableLongStateOf(targetMs)
}

internal object LiveTvScrubSteps {
    private const val MINUTE_MS = 60_000L
    /** Without a guide, how far back the bar reaches at a time. */
    const val NO_GUIDE_SPAN_MS = 2L * 60 * MINUTE_MS
    /** This near to now plays the channel live. */
    const val LIVE_MARGIN_MS = 45_000L

    /** Held ◀▶ repeat about 20 times a second: a minute at a time first, then faster. */
    fun stepMs(repeatCount: Int): Long = when {
        repeatCount < 10 -> MINUTE_MS
        repeatCount < 40 -> 2 * MINUTE_MS
        else -> 5 * MINUTE_MS
    }

    /**
     * Where the bar starts for a time [atMs]: the start of the programme on then in [schedule],
     * else [NO_GUIDE_SPAN_MS] before; never before [earliestMs] (what the provider keeps).
     */
    fun rangeStart(schedule: List<LiveTvProgramme>, atMs: Long, earliestMs: Long): Long {
        val programme = schedule.firstOrNull { atMs >= it.startEpochMs && atMs < it.stopEpochMs }
        val start = programme?.startEpochMs ?: (atMs - NO_GUIDE_SPAN_MS)
        return start.coerceAtLeast(earliestMs)
    }

    /** A replay starts on a whole minute (Xtream panels only take minutes). */
    fun replayStart(targetMs: Long): Long = targetMs - Math.floorMod(targetMs, MINUTE_MS)
}

/** The rewind bar, over the bottom of the picture like the info card. */
@Composable
internal fun LiveTvScrubCard(scrub: LiveTvScrub, logo: String?) {
    // Only while the bar shows: the live edge moves with the clock.
    val now by produceState(LiveTvClock.nowEpochMs()) {
        while (true) {
            delay(1_000L)
            value = LiveTvClock.nowEpochMs()
        }
    }
    val target = scrub.targetMs
    val from = scrub.fromMs
    val live = now - target < LiveTvScrubSteps.LIVE_MARGIN_MS
    val programme = remember(scrub.channel.guideKey, target / 60_000L) {
        LiveTvRepository.schedule(scrub.channel.guideKey).firstOrNull { target >= it.startEpochMs && target < it.stopEpochMs }
    }
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .padding(start = 48.dp, end = 48.dp, bottom = 36.dp)
            .widthIn(max = 880.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(LiveTvCardBackground)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveTvLogo(url = logo, name = scrub.channel.name, width = 104.dp, height = 64.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = programme?.title ?: scrub.channel.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (live) {
                        stringResource(R.string.live_tv_scrub_live)
                    } else {
                        stringResource(R.string.live_tv_scrub_behind, LiveTvClock.formatClock(target), ((now - target) / 60_000L).toInt())
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = if (live) 0.92f else 0.85f),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            ScrubBar(from = from, target = target, now = now, modifier = Modifier.padding(top = 14.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    text = LiveTvClock.formatClock(from),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.live_tv_scrub_live),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            Text(
                text = stringResource(R.string.live_tv_scrub_hint),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** From the bar's start to now, filled up to the picked time, with a knob there. */
@Composable
private fun ScrubBar(from: Long, target: Long, now: Long, modifier: Modifier = Modifier) {
    val fraction = ((target - from).toFloat() / (now - from).coerceAtLeast(1L)).coerceIn(0f, 1f)
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(14.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(LiveTvPillShape)
                .background(Color.White.copy(alpha = 0.18f))
                .drawBehind { drawRect(Color.White, size = Size(size.width * fraction, size.height)) },
        )
        val knob = 14.dp
        Box(
            modifier = Modifier
                .offset(x = (maxWidth - knob) * fraction)
                .size(knob)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
