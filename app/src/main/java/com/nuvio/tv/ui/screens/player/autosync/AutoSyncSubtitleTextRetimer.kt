package com.nuvio.tv.ui.screens.player.autosync

/**
 * Applies an AutoSync timeline to a subtitle file's text by rewriting only its timestamps, for
 * players that load the subtitle file themselves (mpv). Styling, positioning and markup stay
 * untouched, so ASS/SSA keeps rendering through libass.
 *
 * Handles SRT, WebVTT and ASS/SSA. Returns null for any other format, or when the text has no
 * timing to rewrite.
 */
internal object AutoSyncSubtitleTextRetimer {
    private val ttmlRegex = Regex("""<tt[\s>]""", RegexOption.IGNORE_CASE)
    private const val ASS_DEFAULT_FIELD_COUNT = 10
    private const val ASS_DEFAULT_START_INDEX = 1
    private const val ASS_DEFAULT_END_INDEX = 2
    private const val ASS_MIN_DURATION_MS = 10L

    fun retime(body: String, timeline: AutoSyncTimelineRetimeResult): String? {
        val text = cleanText(body)
        if (ttmlRegex.containsMatchIn(text.substring(0, minOf(512, text.length)))) return null
        val mapper = AutoSyncTimelineTimeMapper(timeline)
        // Not a prefix sample: embedded [Fonts] or [Graphics] can push [Events] far into the file.
        return if (AutoSyncSubtitleCueParser.hasAssEventsHeader(text)) {
            retimeAss(text, mapper)
        } else {
            retimeTimingLines(text, mapper)
        }
    }

    private fun cleanText(rawText: String): String {
        val hasBom = rawText.indexOf('\uFEFF') >= 0
        val hasCr = rawText.indexOf('\r') >= 0
        if (!hasBom && !hasCr) return rawText
        val len = rawText.length
        val out = StringBuilder(len)
        var i = 0
        while (i < len) {
            val ch = rawText[i]
            if (ch == '\uFEFF') {
                i++
            } else if (ch == '\r') {
                out.append('\n')
                if (i + 1 < len && rawText[i + 1] == '\n') i += 2 else i++
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    /** SRT and WebVTT: every `start --> end` line, keeping cue settings after the end time. */
    private fun retimeTimingLines(text: String, mapper: AutoSyncTimelineTimeMapper): String? {
        val len = text.length
        val entries = ArrayList<TimingEntry>(maxOf(16, len / 80))
        var pos = 0

        while (pos <= len) {
            val lineEnd = text.indexOf('\n', pos).let { if (it < 0) len else it }
            val arrow = indexOfArrow(text, pos, lineEnd)
            if (arrow >= 0) {
                parseTimingLine(text, pos, lineEnd, arrow, mapper)?.let { entries += it }
            }
            if (lineEnd >= len) break
            pos = lineEnd + 1
        }
        if (entries.isEmpty()) return null

        clampIntroducedOverlaps(entries)

        val out = StringBuilder(len + 64)
        var cursor = 0
        for (i in entries.indices) {
            val entry = entries[i]
            // Copy verbatim text up to start timestamp
            out.append(text, cursor, entry.startTsFrom)
            appendFormattedLike(out, entry.startMs, entry.startHasHours, entry.startSeparator)
            // Copy verbatim arrow/spaces between start and end timestamps
            out.append(text, entry.startTsTo, entry.endTsFrom)
            appendFormattedLike(out, entry.endMs, entry.endHasHours, entry.endSeparator)
            cursor = entry.endTsTo
        }
        if (cursor < len) {
            out.append(text, cursor, len)
        }
        return out.toString()
    }

    private fun parseTimingLine(
        text: String,
        lineStart: Int,
        lineEnd: Int,
        arrow: Int,
        mapper: AutoSyncTimelineTimeMapper,
    ): TimingEntry? {
        // Leading whitespace before start timestamp
        var startTsFrom = lineStart
        while (startTsFrom < arrow && text[startTsFrom].isWhitespace()) startTsFrom++

        // Trailing whitespace before "-->"
        var startTsTo = arrow
        while (startTsTo > startTsFrom && text[startTsTo - 1].isWhitespace()) startTsTo--

        val start = parseTimestampRange(text, startTsFrom, startTsTo) ?: return null

        // Whitespace after "-->"
        var endTsFrom = arrow + 3
        while (endTsFrom < lineEnd && text[endTsFrom].isWhitespace()) endTsFrom++

        // End timestamp stops at first non-timestamp character (e.g., whitespace before cue settings)
        var endTsTo = endTsFrom
        while (endTsTo < lineEnd) {
            val ch = text[endTsTo]
            if (ch in '0'..'9' || ch == ':' || ch == '.' || ch == ',') {
                endTsTo++
            } else {
                break
            }
        }

        val end = parseTimestampRange(text, endTsFrom, endTsTo) ?: return null
        val originalEndMs = end.ms.coerceAtLeast(start.ms + 1L)
        val startMs = mapper.mapStartMs(start.ms)
        return TimingEntry(
            startTsFrom = startTsFrom,
            startTsTo = startTsTo,
            endTsFrom = endTsFrom,
            endTsTo = endTsTo,
            startHasHours = start.hasHours,
            startSeparator = start.separator,
            endHasHours = end.hasHours,
            endSeparator = end.separator,
            originalStartMs = start.ms,
            originalEndMs = originalEndMs,
            startMs = startMs,
            endMs = mapper.mapEndMs(originalEndMs).coerceAtLeast(startMs + 1L),
        )
    }

    /** A retimed cue that now runs into the next one is cut at its start, as long as the source did not overlap. */
    private fun clampIntroducedOverlaps(entries: List<TimingEntry>) {
        for (index in 0 until entries.lastIndex) {
            val current = entries[index]
            val next = entries[index + 1]
            if (current.originalEndMs > next.originalStartMs) continue
            if (current.endMs > next.startMs && next.startMs > current.startMs) {
                current.endMs = next.startMs
            }
        }
    }

    /** ASS/SSA: the Start and End fields of each Dialogue line in [Events]. */
    private fun retimeAss(text: String, mapper: AutoSyncTimelineTimeMapper): String? {
        val len = text.length
        val out = StringBuilder(len + 64)
        var inEvents = false
        var fieldCount = ASS_DEFAULT_FIELD_COUNT
        var startIndex = ASS_DEFAULT_START_INDEX
        var endIndex = ASS_DEFAULT_END_INDEX
        var rewritten = 0

        val partStarts = IntArray(32)
        val partEnds = IntArray(32)
        var pos = 0

        while (pos <= len) {
            val lineEnd = text.indexOf('\n', pos).let { if (it < 0) len else it }
            var tStart = pos
            while (tStart < lineEnd && text[tStart].isWhitespace()) tStart++
            var tEnd = lineEnd
            while (tEnd > tStart && text[tEnd - 1].isWhitespace()) tEnd--

            var handled = false
            if (tStart < tEnd) {
                if (text[tStart] == '[' && text[tEnd - 1] == ']') {
                    inEvents = (tEnd - tStart == 8) &&
                        text.regionMatches(tStart, "[Events]", 0, 8, ignoreCase = true)
                    // Bulk-copy multi-MB [Fonts] / [Graphics] sections verbatim instead of
                    // scanning tens of thousands of 80-char UU-encoded lines individually.
                    val isBulkSection = !inEvents && (
                        (tEnd - tStart == 7 && text.regionMatches(tStart, "[Fonts]", 0, 7, ignoreCase = true)) ||
                            (tEnd - tStart == 10 && text.regionMatches(tStart, "[Graphics]", 0, 10, ignoreCase = true))
                        )
                    if (isBulkSection) {
                        var nextSection = len
                        var scan = if (lineEnd < len) lineEnd + 1 else len
                        while (scan < len) {
                            val nlBracket = text.indexOf("\n[", scan)
                            if (nlBracket < 0) break
                            val hStart = nlBracket + 1
                            val hEnd = text.indexOf('\n', hStart).let { if (it < 0) len else it }
                            var hTrimEnd = hEnd
                            while (hTrimEnd > hStart && text[hTrimEnd - 1].isWhitespace()) hTrimEnd--
                            val hLen = hTrimEnd - hStart
                            if (
                                (hLen == 8 && text.regionMatches(hStart, "[Events]", 0, 8, ignoreCase = true)) ||
                                (hLen == 13 && text.regionMatches(hStart, "[Script Info]", 0, 13, ignoreCase = true)) ||
                                (hLen == 11 && text.regionMatches(hStart, "[V4 Styles]", 0, 11, ignoreCase = true)) ||
                                (hLen == 12 && text.regionMatches(hStart, "[V4+ Styles]", 0, 12, ignoreCase = true)) ||
                                (hLen == 7 && text.regionMatches(hStart, "[Fonts]", 0, 7, ignoreCase = true)) ||
                                (hLen == 10 && text.regionMatches(hStart, "[Graphics]", 0, 10, ignoreCase = true)) ||
                                (hLen >= 10 && text.regionMatches(hStart, "[Aegisub ", 0, 9, ignoreCase = true))
                            ) {
                                nextSection = hStart
                                break
                            }
                            scan = hEnd + 1
                        }
                        out.append(text, pos, nextSection)
                        if (nextSection >= len) break
                        pos = nextSection
                        continue
                    }
                } else if (inEvents && tEnd - tStart >= 7 &&
                    text.regionMatches(tStart, "Format:", 0, 7, ignoreCase = true)
                ) {
                    val fields = text.substring(tStart + 7, tEnd).split(',').map { it.trim() }
                    fieldCount = fields.size
                    startIndex = fields.indexOfFirst { it.equals("Start", ignoreCase = true) }
                        .takeIf { it >= 0 } ?: ASS_DEFAULT_START_INDEX
                    endIndex = fields.indexOfFirst { it.equals("End", ignoreCase = true) }
                        .takeIf { it >= 0 } ?: ASS_DEFAULT_END_INDEX
                } else if (inEvents && tEnd - tStart >= 9 &&
                    text.regionMatches(tStart, "Dialogue:", 0, 9, ignoreCase = true)
                ) {
                    val colon = text.indexOf(':', pos)
                    if (colon in pos until lineEnd) {
                        val maxParts = minOf(fieldCount, partStarts.size)
                        var partCount = 0
                        var cursor = colon + 1
                        while (partCount < maxParts - 1) {
                            val comma = text.indexOf(',', cursor)
                            if (comma < 0 || comma >= lineEnd) break
                            partStarts[partCount] = cursor
                            partEnds[partCount] = comma
                            partCount++
                            cursor = comma + 1
                        }
                        partStarts[partCount] = cursor
                        partEnds[partCount] = lineEnd
                        partCount++

                        if (partCount > maxOf(startIndex, endIndex)) {
                            val sRawStart = partStarts[startIndex]
                            val sRawEnd = partEnds[startIndex]
                            var sTsStart = sRawStart
                            while (sTsStart < sRawEnd && text[sTsStart].isWhitespace()) sTsStart++
                            var sTsEnd = sRawEnd
                            while (sTsEnd > sTsStart && text[sTsEnd - 1].isWhitespace()) sTsEnd--

                            val eRawStart = partStarts[endIndex]
                            val eRawEnd = partEnds[endIndex]
                            var eTsStart = eRawStart
                            while (eTsStart < eRawEnd && text[eTsStart].isWhitespace()) eTsStart++
                            var eTsEnd = eRawEnd
                            while (eTsEnd > eTsStart && text[eTsEnd - 1].isWhitespace()) eTsEnd--

                            val start = parseTimestampRange(text, sTsStart, sTsEnd)
                            val end = parseTimestampRange(text, eTsStart, eTsEnd)
                            if (start != null && end != null) {
                                val startMs = mapper.mapStartMs(start.ms)
                                val endMs = mapper.mapEndMs(end.ms.coerceAtLeast(start.ms + 1L))
                                    .coerceAtLeast(startMs + ASS_MIN_DURATION_MS)
                                if (startIndex < endIndex) {
                                    out.append(text, pos, sTsStart)
                                    appendAssTimestamp(out, startMs)
                                    out.append(text, sRawEnd, eTsStart)
                                    appendAssTimestamp(out, endMs)
                                    out.append(text, eRawEnd, lineEnd)
                                } else {
                                    out.append(text, pos, eTsStart)
                                    appendAssTimestamp(out, endMs)
                                    out.append(text, eRawEnd, sTsStart)
                                    appendAssTimestamp(out, startMs)
                                    out.append(text, sRawEnd, lineEnd)
                                }
                                rewritten++
                                handled = true
                            }
                        }
                    }
                }
            }

            if (!handled) {
                out.append(text, pos, lineEnd)
            }
            if (lineEnd >= len) break
            out.append('\n')
            pos = lineEnd + 1
        }
        return if (rewritten == 0) null else out.toString()
    }

    private fun indexOfArrow(text: String, start: Int, end: Int): Int {
        val limit = end - 3
        var i = start
        while (i <= limit) {
            if (text[i] == '-' && text[i + 1] == '-' && text[i + 2] == '>') return i
            i++
        }
        return -1
    }

    private fun parseTimestampRange(text: String, start: Int, end: Int): ParsedTimestamp? {
        if (start >= end) return null
        var i = start

        var p1 = 0L
        var d1 = 0
        while (i < end) {
            val d = text[i] - '0'
            if (d !in 0..9) break
            p1 = p1 * 10L + d
            d1++
            i++
        }
        if (d1 == 0 || i >= end || text[i] != ':') return null
        i++

        var p2 = 0L
        var d2 = 0
        while (i < end) {
            val d = text[i] - '0'
            if (d !in 0..9) break
            p2 = p2 * 10L + d
            d2++
            i++
        }
        if (d2 == 0) return null

        val hours: Long
        val minutes: Long
        val seconds: Long
        val hasHours: Boolean
        if (i < end && text[i] == ':') {
            i++
            if (d2 > 2) return null
            var p3 = 0L
            var d3 = 0
            while (i < end) {
                val d = text[i] - '0'
                if (d !in 0..9) break
                p3 = p3 * 10L + d
                d3++
                i++
            }
            if (d3 != 2) return null
            hours = p1
            minutes = p2
            seconds = p3
            hasHours = true
        } else {
            if (d1 > 2 || d2 != 2) return null
            hours = 0L
            minutes = p1
            seconds = p2
            hasHours = false
        }

        var separator = ','
        var millis = 0L
        if (i < end) {
            val sep = text[i]
            if (sep != '.' && sep != ',') return null
            separator = sep
            i++
            var fracDigits = 0
            while (i < end) {
                val d = text[i] - '0'
                if (d !in 0..9) return null
                if (fracDigits < 3) {
                    millis = millis * 10L + d
                }
                fracDigits++
                i++
            }
            if (fracDigits == 0) return null
            if (fracDigits == 1) millis *= 100L
            else if (fracDigits == 2) millis *= 10L
        }

        if (i != end) return null
        val ms = (hours * 3_600L + minutes * 60L + seconds) * 1_000L + millis
        return ParsedTimestamp(ms = ms, hasHours = hasHours, separator = separator)
    }

    /** `HH:MM:SS,mmm` in the original's style: same decimal separator, hours only if it had them or needs them. */
    private fun appendFormattedLike(
        out: StringBuilder,
        ms: Long,
        hasHours: Boolean,
        separator: Char,
    ) {
        val hours = ms / 3_600_000L
        val minutes = ((ms / 60_000L) % 60L).toInt()
        val seconds = ((ms / 1_000L) % 60L).toInt()
        val millis = (ms % 1_000L).toInt()
        if (hasHours || hours > 0L) {
            if (hours < 10L) out.append('0')
            out.append(hours).append(':')
        }
        appendTwoDigits(out, minutes)
        out.append(':')
        appendTwoDigits(out, seconds)
        out.append(separator)
        appendThreeDigits(out, millis)
    }

    /** ASS time: `H:MM:SS.CC`, rounded to centiseconds. */
    private fun appendAssTimestamp(out: StringBuilder, ms: Long) {
        val centis = (ms + 5L) / 10L
        out.append(centis / 360_000L)
        out.append(':')
        appendTwoDigits(out, ((centis / 6_000L) % 60L).toInt())
        out.append(':')
        appendTwoDigits(out, ((centis / 100L) % 60L).toInt())
        out.append('.')
        appendTwoDigits(out, (centis % 100L).toInt())
    }

    private fun appendTwoDigits(out: StringBuilder, value: Int) {
        out.append(('0'.code + value / 10).toChar())
        out.append(('0'.code + value % 10).toChar())
    }

    private fun appendThreeDigits(out: StringBuilder, value: Int) {
        out.append(('0'.code + value / 100).toChar())
        out.append(('0'.code + (value / 10) % 10).toChar())
        out.append(('0'.code + value % 10).toChar())
    }

    private data class ParsedTimestamp(
        val ms: Long,
        val hasHours: Boolean,
        val separator: Char,
    )

    private class TimingEntry(
        val startTsFrom: Int,
        val startTsTo: Int,
        val endTsFrom: Int,
        val endTsTo: Int,
        val startHasHours: Boolean,
        val startSeparator: Char,
        val endHasHours: Boolean,
        val endSeparator: Char,
        val originalStartMs: Long,
        val originalEndMs: Long,
        val startMs: Long,
        var endMs: Long,
    )
}
