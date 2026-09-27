package com.nuvio.tv.reshaped.livetv

import android.util.Xml
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import org.xmlpull.v1.XmlPullParser

/**
 * Programme guide for the channels in the list: a few programmes per channel from now on, keyed
 * by the XMLTV channel id in lower case. The one on air is picked when it is shown, so "now
 * playing" moves on by itself as programmes end.
 */
internal typealias LiveTvSchedule = Map<String, List<LiveTvProgramme>>

private const val EPG_LOOKAHEAD_MS = 12L * 60 * 60 * 1000
internal const val EPG_MAX_PER_CHANNEL = 4
private const val CANCEL_CHECK_EVENTS = 4096
private const val RELAXED_FEATURE = "http://xmlpull.org/v1/doc/features.html#relaxed"

/**
 * Reads a saved XMLTV guide (plain or gzip) through a pull parser, keeping only programmes of
 * [channelIds] (lower case) that have not ended and start within the next hours. Memory stays
 * flat however large the guide is; guides of 100+ MB are common.
 */
internal suspend fun readXmlTvSchedule(file: java.io.File, channelIds: Set<String>, nowEpochMs: Long): LiveTvSchedule =
    LiveTvHttp.readFile(file) { input ->
        val builder = LiveTvScheduleBuilder(channelIds, nowEpochMs)
        // A malformed tail (unknown entity, cut download) keeps what was read before it.
        try {
            readProgrammes(input, builder)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
        }
        builder.build()
    }

private fun readProgrammes(input: InputStream, builder: LiveTvScheduleBuilder) {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    runCatching { parser.setFeature(RELAXED_FEATURE, true) }
    parser.setInput(input, null)
    var events = 0
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        // Blocking IO thread: a cancelled load stops at the next check.
        if (++events % CANCEL_CHECK_EVENTS == 0 && Thread.currentThread().isInterrupted) return
        if (event == XmlPullParser.START_TAG && parser.name.equals("programme", ignoreCase = true)) {
            val channelId = parser.getAttributeValue(null, "channel")?.trim()?.lowercase()
            if (channelId == null || !builder.wants(channelId)) {
                parser.skipElement()
            } else {
                val start = parser.getAttributeValue(null, "start")?.let(LiveTvClock::parseXmlTvTimestamp)
                val stop = parser.getAttributeValue(null, "stop")?.let(LiveTvClock::parseXmlTvTimestamp)
                val title = parser.readFirstTitle()
                if (start != null && stop != null && title != null) builder.add(channelId, title, start, stop)
            }
        }
        event = parser.next()
    }
}

/** From a START_TAG: moves to its matching END_TAG. */
private fun XmlPullParser.skipElement() {
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

/** From a programme's START_TAG: its first title, leaving the parser on the programme's END_TAG. */
private fun XmlPullParser.readFirstTitle(): String? {
    var title: String? = null
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> {
                if (title == null && depth == 1 && name.equals("title", ignoreCase = true)) {
                    // nextText() ends on the title's END_TAG, so the depth is unchanged.
                    title = nextText().trim().takeIf(String::isNotBlank)
                } else {
                    depth++
                }
            }
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return title
        }
    }
    return title
}

/** Collects programmes for [channelIds] while a guide is read. */
internal class LiveTvScheduleBuilder(
    private val channelIds: Set<String>,
    private val nowEpochMs: Long,
) {
    private val entries = HashMap<String, MutableList<LiveTvProgramme>>()

    /** [channelId] must already be lower case. */
    fun wants(channelId: String): Boolean = channelId in channelIds

    fun add(channelId: String, title: String, startEpochMs: Long, stopEpochMs: Long) {
        if (stopEpochMs <= startEpochMs || stopEpochMs <= nowEpochMs) return
        if (startEpochMs >= nowEpochMs + EPG_LOOKAHEAD_MS) return
        val list = entries.getOrPut(channelId) { ArrayList(EPG_MAX_PER_CHANNEL) }
        if (list.size >= EPG_MAX_PER_CHANNEL) {
            // Guides are usually in time order; if not, keep the earliest programmes.
            val latest = list.maxBy { it.startEpochMs }
            if (latest.startEpochMs <= startEpochMs) return
            list.remove(latest)
        }
        list += LiveTvProgramme(
            title = title,
            startEpochMs = startEpochMs,
            stopEpochMs = stopEpochMs,
            timeLabel = "${LiveTvClock.formatClock(startEpochMs)} – ${LiveTvClock.formatClock(stopEpochMs)}",
        )
    }

    fun build(): LiveTvSchedule = entries.mapValues { (_, list) -> list.sortedBy { it.startEpochMs } }
}

/**
 * When the guide must be read again: when the first channel whose kept programmes were cut at
 * [EPG_MAX_PER_CHANNEL] reaches the end of them, so "now playing" never runs dry. Channels whose
 * guide simply ends there gain nothing from reading it sooner.
 */
internal fun nextScheduleReadAt(schedule: LiveTvSchedule, nowEpochMs: Long, minGapMs: Long, maxGapMs: Long): Long {
    val runsOut = schedule.values
        .filter { it.size >= EPG_MAX_PER_CHANNEL }
        .minOfOrNull { it.last().stopEpochMs }
        ?: (nowEpochMs + maxGapMs)
    return runsOut.coerceIn(nowEpochMs + minGapMs, nowEpochMs + maxGapMs)
}

/** The programme on air at [nowEpochMs] for each channel id in [tvgIds] (as the playlist spells it). */
internal fun currentProgrammes(
    schedule: LiveTvSchedule,
    tvgIds: Collection<String>,
    nowEpochMs: Long,
): Map<String, LiveTvProgramme> {
    if (schedule.isEmpty()) return emptyMap()
    val current = HashMap<String, LiveTvProgramme>()
    for (tvgId in tvgIds) {
        val programme = schedule[tvgId.lowercase()]
            ?.firstOrNull { nowEpochMs >= it.startEpochMs && nowEpochMs < it.stopEpochMs }
            ?: continue
        current[tvgId] = programme
    }
    return current
}

internal object LiveTvClock {
    private val whitespace = Regex("\\s+")
    private val offsetFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
    private val localFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    private val clockFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun nowEpochMs(): Long = System.currentTimeMillis()

    /** The device's own short time format (13:00 or 1:00 PM) in its time zone. */
    fun formatClock(epochMs: Long): String =
        clockFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** XMLTV `20260927213000 +0200` (or without an offset, then in local time). */
    fun parseXmlTvTimestamp(value: String): Long? {
        val parts = value.trim().split(whitespace, limit = 2)
        val digits = parts.firstOrNull().orEmpty()
        val normalized = when (digits.length) {
            12 -> "${digits}00"
            14 -> digits
            else -> return null
        }
        return runCatching {
            if (parts.size > 1) {
                OffsetDateTime.parse("$normalized ${parts[1]}", offsetFormatter).toInstant().toEpochMilli()
            } else {
                LocalDateTime.parse(normalized, localFormatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }.getOrNull()
    }
}
