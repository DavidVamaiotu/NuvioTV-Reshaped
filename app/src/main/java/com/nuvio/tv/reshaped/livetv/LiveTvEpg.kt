package com.nuvio.tv.reshaped.livetv

import android.util.Xml
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import org.xmlpull.v1.XmlPullParser

/**
 * Programme guide for the channels in the list: a few programmes per channel around now, keyed
 * by the channel's [LiveTvChannel.guideKey]. The one on air is picked when it is shown, so "now
 * playing" moves on by itself as programmes end.
 */
internal typealias LiveTvSchedule = Map<String, List<LiveTvProgramme>>

private const val CANCEL_CHECK_EVENTS = 4096
private const val RELAXED_FEATURE = "http://xmlpull.org/v1/doc/features.html#relaxed"

/**
 * How much of the guide is kept per channel: programmes that ended up to [pastMs] ago (at most
 * [maxPast]) and ones starting within [aheadMs] (at most [maxAhead]). Weak TVs keep less.
 */
internal class LiveTvGuideWindow(
    val pastMs: Long,
    val maxPast: Int,
    val aheadMs: Long,
    val maxAhead: Int,
    /** How far back channels with catch-up keep programmes, so past ones can be played again. */
    val catchupPastMs: Long,
    val maxCatchupPast: Int,
) {
    /** How far back [key]'s programmes are kept, given the request's catch-up channels. */
    fun pastMsFor(catchup: Boolean): Long = if (catchup) catchupPastMs else pastMs

    companion object {
        private const val HOUR = 60L * 60 * 1000
        val Regular = LiveTvGuideWindow(
            pastMs = 3 * HOUR, maxPast = 6, aheadMs = 12 * HOUR, maxAhead = 18,
            catchupPastMs = 24 * HOUR, maxCatchupPast = 48,
        )
        val LowMemory = LiveTvGuideWindow(
            pastMs = 2 * HOUR, maxPast = 4, aheadMs = 8 * HOUR, maxAhead = 10,
            catchupPastMs = 12 * HOUR, maxCatchupPast = 24,
        )
    }
}

/** What a guide read looks for, built once per channel list. */
internal class LiveTvGuideRequest(
    /** Every channel's [LiveTvChannel.guideKey]. */
    val keys: Set<String>,
    /** [liveTvNameKey] of each channel's name to the keys of the channels with that name. */
    val keysByName: Map<String, List<String>>,
    /** Keys of channels the playlist gives no logo: the guide's own logo is used for them. */
    val keysWithoutLogo: Set<String>,
    /** Keys of channels with catch-up: they keep more past programmes. */
    val catchupKeys: Set<String> = emptySet(),
) {
    companion object {
        fun from(channels: List<LiveTvChannel>): LiveTvGuideRequest {
            val keys = HashSet<String>(channels.size * 2)
            val byName = HashMap<String, MutableList<String>>(channels.size * 2)
            val withoutLogo = HashSet<String>()
            val catchup = HashSet<String>()
            channels.forEach { channel ->
                if (channel.catchup != null) catchup += channel.guideKey
                if (!keys.add(channel.guideKey)) return@forEach
                val name = liveTvNameKey(channel.name)
                if (name.isNotEmpty()) byName.getOrPut(name) { ArrayList(1) } += channel.guideKey
                // Guides often list a channel by the playlist's tvg-name rather than its shown name.
                channel.tvgName?.let(::liveTvNameKey)?.takeIf { it.isNotEmpty() && it != name }?.let { alias ->
                    byName.getOrPut(alias) { ArrayList(1) } += channel.guideKey
                }
                if (channel.logoUrl.isNullOrBlank()) withoutLogo += channel.guideKey
            }
            return LiveTvGuideRequest(keys, byName, withoutLogo, catchup)
        }
    }
}

/** A read guide: programmes, logos for channels without one, and the channels whose kept programmes were cut short. */
internal class LiveTvGuide(
    val schedule: LiveTvSchedule,
    val logos: Map<String, String>,
    val truncated: Set<String>,
)

/**
 * Reads a saved XMLTV guide (plain or gzip) through a pull parser, keeping only programmes of
 * the requested channels within [window]. Memory stays flat however large the guide is; guides of
 * 100+ MB are common. Channels are matched on the guide's channel id, or, when the playlist's id
 * is missing or not in the guide, on the channel's name (as IPTV players do).
 */
internal suspend fun readXmlTvGuide(
    file: java.io.File,
    request: LiveTvGuideRequest,
    nowEpochMs: Long,
    window: LiveTvGuideWindow,
): LiveTvGuide =
    LiveTvHttp.readFile(file) { input ->
        val builder = LiveTvScheduleBuilder(request, nowEpochMs, window)
        // A malformed tail (unknown entity, cut download) keeps what was read before it.
        try {
            readGuide(input, builder)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
        }
        builder.build()
    }

private fun readGuide(input: InputStream, builder: LiveTvScheduleBuilder) {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    runCatching { parser.setFeature(RELAXED_FEATURE, true) }
    parser.setInput(input, null)
    var events = 0
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        // Blocking IO thread: a cancelled load stops at the next check.
        if (++events % CANCEL_CHECK_EVENTS == 0 && Thread.currentThread().isInterrupted) return
        if (event == XmlPullParser.START_TAG) {
            when {
                parser.name.equals("programme", ignoreCase = true) -> {
                    val channelId = parser.getAttributeValue(null, "channel")?.trim()?.lowercase()
                    val keys = channelId?.let(builder::keysFor)
                    if (keys == null) {
                        parser.skipElement()
                    } else {
                        val start = parser.getAttributeValue(null, "start")?.let(LiveTvClock::parseXmlTvTimestamp)
                        val stop = parser.getAttributeValue(null, "stop")?.let(LiveTvClock::parseXmlTvTimestamp)
                        val title = parser.readFirstTitle()
                        if (start != null && stop != null && title != null) builder.add(keys, title, start, stop)
                    }
                }
                parser.name.equals("channel", ignoreCase = true) -> {
                    val channelId = parser.getAttributeValue(null, "id")?.trim()?.lowercase()
                    if (channelId == null) parser.skipElement() else parser.readChannel(channelId, builder)
                }
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

/** From a channel's START_TAG: its names and logo, leaving the parser on its END_TAG. */
private fun XmlPullParser.readChannel(channelId: String, builder: LiveTvScheduleBuilder) {
    val names = ArrayList<String>(2)
    var icon: String? = null
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> when {
                depth == 1 && name.equals("display-name", ignoreCase = true) -> {
                    val text = nextText().trim()
                    if (text.isNotEmpty()) names.add(text)
                }
                depth == 1 && name.equals("icon", ignoreCase = true) -> {
                    if (icon == null) icon = getAttributeValue(null, "src")?.trim()?.takeIf(String::isHttpUrl)
                    depth++
                }
                else -> depth++
            }
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
    builder.channel(channelId, names, icon)
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

/** Collects programmes for the requested channels while a guide is read. */
internal class LiveTvScheduleBuilder(
    private val request: LiveTvGuideRequest,
    private val nowEpochMs: Long,
    private val window: LiveTvGuideWindow,
) {
    private val entries = HashMap<String, MutableList<LiveTvProgramme>>()
    private val truncated = HashSet<String>()
    /** Guide channel ids that feed channels matched by name, not by id. */
    private val aliases = HashMap<String, List<String>>()
    /** Keys some guide channel already feeds: one guide channel per list channel. */
    private val claimed = HashSet<String>()
    private val icons = HashMap<String, String>()
    private val logos = HashMap<String, String>()
    /** Repeated titles (news, films shown twice) are kept once. */
    private val titles = HashMap<String, String>()
    private var channelsDone = false

    /** A `<channel>` of the guide ([channelId] lower case). Guides list these before their programmes. */
    fun channel(channelId: String, names: List<String>, icon: String?) {
        if (channelsDone) return
        if (channelId in request.keys) {
            claimed += channelId
            if (icon != null && channelId in request.keysWithoutLogo) logos[channelId] = icon
            return
        }
        for (name in names) {
            val keys = request.keysByName[liveTvNameKey(name)] ?: continue
            aliases[channelId] = keys
            if (icon != null) icons[channelId] = icon
            return
        }
    }

    /** Name matches only feed channels whose own id is not in the guide, each from one guide channel. */
    private fun finishChannels() {
        channelsDone = true
        if (aliases.isEmpty()) return
        val resolved = HashMap<String, List<String>>(aliases.size)
        aliases.forEach { (channelId, keys) ->
            val free = keys.filter { claimed.add(it) }
            if (free.isEmpty()) return@forEach
            resolved[channelId] = free
            icons[channelId]?.let { icon -> free.forEach { if (it in request.keysWithoutLogo) logos.putIfAbsent(it, icon) } }
        }
        aliases.clear()
        aliases.putAll(resolved)
        icons.clear()
    }

    /** The channel keys a programme of guide channel [channelId] (lower case) is kept under, or null. */
    fun keysFor(channelId: String): List<String>? {
        if (!channelsDone) finishChannels()
        aliases[channelId]?.let { return it }
        return if (channelId in request.keys) listOf(channelId) else null
    }

    fun add(keys: List<String>, title: String, startEpochMs: Long, stopEpochMs: Long) {
        keys.forEach { add(it, title, startEpochMs, stopEpochMs) }
    }

    /** [key] must be one of the request's keys. */
    fun add(key: String, title: String, startEpochMs: Long, stopEpochMs: Long) {
        val catchup = key in request.catchupKeys
        if (stopEpochMs <= startEpochMs || stopEpochMs <= nowEpochMs - window.pastMsFor(catchup)) return
        if (startEpochMs >= nowEpochMs + window.aheadMs) {
            truncated += key
            return
        }
        val list = entries.getOrPut(key) { ArrayList(4) }
        val past = stopEpochMs <= nowEpochMs
        var kept = 0
        for (programme in list) {
            // The same slot twice (a guide channel matched by id and by name): keep one.
            if (programme.startEpochMs == startEpochMs) return
            if ((programme.stopEpochMs <= nowEpochMs) == past) kept++
        }
        if (past && kept >= (if (catchup) window.maxCatchupPast else window.maxPast)) {
            // Keep the latest programmes that have ended.
            val earliest = list.filter { it.stopEpochMs <= nowEpochMs }.minBy { it.startEpochMs }
            if (earliest.startEpochMs >= startEpochMs) return
            list.remove(earliest)
        } else if (!past && kept >= window.maxAhead) {
            // Guides are usually in time order; if not, keep the earliest programmes.
            truncated += key
            val latest = list.filter { it.stopEpochMs > nowEpochMs }.maxBy { it.startEpochMs }
            if (latest.startEpochMs <= startEpochMs) return
            list.remove(latest)
        }
        list += LiveTvProgramme(
            title = titles.getOrPut(title) { title },
            startEpochMs = startEpochMs,
            stopEpochMs = stopEpochMs,
        )
    }

    fun build(): LiveTvGuide = LiveTvGuide(
        schedule = entries.mapValues { (_, list) -> list.sortedBy { it.startEpochMs } },
        logos = logos,
        truncated = truncated,
    )
}

/**
 * When the guide must be read again: when the first channel whose kept programmes were cut short
 * reaches the end of them, so "now playing" never runs dry. Channels whose guide simply ends there
 * gain nothing from reading it sooner.
 */
internal fun nextScheduleReadAt(
    schedule: LiveTvSchedule,
    truncated: Set<String>,
    nowEpochMs: Long,
    minGapMs: Long,
    maxGapMs: Long,
): Long {
    val runsOut = truncated
        .mapNotNull { schedule[it]?.lastOrNull()?.stopEpochMs }
        .minOrNull()
        ?: (nowEpochMs + maxGapMs)
    return runsOut.coerceIn(nowEpochMs + minGapMs, nowEpochMs + maxGapMs)
}

/** The programme on air at [nowEpochMs] for each of [keys]. */
internal fun currentProgrammes(
    schedule: LiveTvSchedule,
    keys: Collection<String>,
    nowEpochMs: Long,
): Map<String, LiveTvProgramme> {
    if (schedule.isEmpty()) return emptyMap()
    val current = HashMap<String, LiveTvProgramme>()
    for (key in keys) {
        val programme = schedule[key]
            ?.firstOrNull { nowEpochMs >= it.startEpochMs && nowEpochMs < it.stopEpochMs }
            ?: continue
        current[key] = programme
    }
    return current
}

/** Quality and format words that differ between a playlist's and a guide's name of one channel. */
private val NAME_NOISE = hashSetOf(
    "hd", "fhd", "uhd", "sd", "hq", "4k", "8k", "hevc", "h265", "h264", "1080p", "1080i", "720p", "576p", "50fps", "60fps",
)

/** A leading country tag: "UK:", "UK |", "|UK|", "[UK]", "(UK)". */
private val NAME_TAG = Regex("""^\s*(?:[\[(|]\s*[A-Za-z]{2,3}\s*[\])|]|[A-Za-z]{2,3}\s*[:|])\s*""")

/**
 * A channel name reduced for matching a playlist's name with a guide's: lower case, no country
 * tag, no quality words, letters and digits only ("UK: BBC One HD" and "BBC One" are both "bbcone").
 */
internal fun liveTvNameKey(name: String): String {
    val untagged = NAME_TAG.replaceFirst(name, "")
    val out = StringBuilder(untagged.length)
    var word = StringBuilder()
    fun flush() {
        if (word.isNotEmpty()) {
            val token = word.toString()
            if (token !in NAME_NOISE) out.append(token)
            word = StringBuilder()
        }
    }
    untagged.forEach { char ->
        when {
            char.isLetterOrDigit() -> word.append(char.lowercaseChar())
            // "+1" is a different channel from the one it shifts.
            char == '+' -> word.append(char)
            else -> flush()
        }
    }
    flush()
    return out.toString()
}

/** The key a channel's guide is kept under: its guide id in lower case, else its name. */
internal fun liveTvGuideKey(tvgId: String?, name: String): String =
    tvgId?.trim()?.takeIf(String::isNotEmpty)?.lowercase() ?: (NAME_KEY_PREFIX + liveTvNameKey(name))

/** Never the start of a guide's channel id, so a name key can't meet one. */
private const val NAME_KEY_PREFIX = "\u0001"

internal object LiveTvClock {
    private val clockFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun nowEpochMs(): Long = System.currentTimeMillis()

    /** The device's own short time format (13:00 or 1:00 PM) in its time zone. */
    fun formatClock(epochMs: Long): String =
        clockFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** "21:00 – 22:30". */
    fun formatSpan(programme: LiveTvProgramme): String =
        "${formatClock(programme.startEpochMs)} – ${formatClock(programme.stopEpochMs)}"

    /**
     * XMLTV `20260927213000 +0200`, also written `+02:00`, `+0200` without the space, `Z`/`UTC`/`GMT`,
     * or with no offset (then local time). Seconds may be left out. Parsed by hand: a guide has
     * hundreds of thousands of these.
     */
    fun parseXmlTvTimestamp(value: String): Long? {
        val text = value.trim()
        var digits = 0
        while (digits < text.length && digits < 14 && text[digits].isDigit()) digits++
        if (digits != 12 && digits != 14) return null
        fun number(from: Int, length: Int): Int {
            var result = 0
            for (i in from until from + length) result = result * 10 + (text[i] - '0')
            return result
        }
        val year = number(0, 4)
        val month = number(4, 2)
        val day = number(6, 2)
        val hour = number(8, 2)
        val minute = number(10, 2)
        val second = if (digits == 14) number(12, 2) else 0
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
        val local = runCatching { LocalDateTime.of(year, month, day, hour, minute, minOf(second, 59)) }.getOrNull() ?: return null
        // A fraction of a second (".000") some guides add is skipped.
        var index = digits
        if (index < text.length && text[index] == '.') {
            index++
            while (index < text.length && text[index].isDigit()) index++
        }
        val zone = text.substring(index).trim()
        val offsetSeconds = when {
            zone.isEmpty() -> return local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            zone.equals("Z", true) || zone.equals("UTC", true) || zone.equals("GMT", true) -> 0
            zone[0] == '+' || zone[0] == '-' -> {
                val hhmm = zone.substring(1).replace(":", "")
                if (hhmm.length !in 2..4 || !hhmm.all(Char::isDigit)) return null
                val hours = hhmm.take(2).toInt()
                val minutes = if (hhmm.length >= 4) hhmm.substring(2, 4).toInt() else 0
                val total = hours * 3600 + minutes * 60
                if (zone[0] == '-') -total else total
            }
            // A region ("Europe/London"); abbreviations like "BST" are ambiguous and left out.
            else -> return runCatching { local.atZone(ZoneId.of(zone)).toInstant().toEpochMilli() }.getOrNull()
        }
        return (local.toEpochSecond(ZoneOffset.UTC) - offsetSeconds) * 1000L
    }
}
