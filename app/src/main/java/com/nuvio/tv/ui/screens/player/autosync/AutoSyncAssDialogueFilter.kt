package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.SubtitleSyncCue

/**
 * Keeps only the dialogue of an ASS/SSA subtitle for AutoSync's analysis.
 *
 * Fansub ASS files carry much more than dialogue: karaoke for the opening and ending, signs that
 * translate on-screen text, titles and notes. Another release of the same episode (the embedded
 * track, or another add-on subtitle) often has a different set of those, or none, so matching them
 * cue by cue only adds noise. Dialogue is what every release shares.
 *
 * Styles are classified first: a style is not dialogue when its name says so (sign, song, OP, ...),
 * when it is placed in the middle of the screen, or when most of its events are typeset (positioned,
 * animated, clipped, karaoke or drawings). Within dialogue styles, typeset events and events with an
 * Effect are dropped too. The filter only narrows what AutoSync compares; the subtitle that is shown
 * and retimed keeps every event.
 */
internal object AutoSyncAssDialogueFilter {
    private const val MIN_DIALOGUE_EVENTS = 20
    private const val TYPESET_STYLE_RATIO = 0.5
    private const val MIN_DIALOGUE_DURATION_MS = 300L
    private const val MAX_DIALOGUE_DURATION_MS = 12_000L
    private const val BURST_WINDOW_MS = 1_000L
    private const val BURST_MIN_STARTS = 5

    private val nonDialogueName = Regex(
        """(?:^|[^a-z])(?:signs?|songs?|lyrics?|karaoke|kara|kfx|fx|op|ed|opening|ending|insert|""" +
            """romaji|kanji|title|eyecatch|preview|next|logo|credits?|staff|notes?|tl ?note|""" +
            """typeset|ts|ruby|furigana|screen|text)(?:$|[^a-z])""",
    )
    private val dialogueName = Regex(
        """(?:^|[^a-z])(?:default|main|dialog|dialogue|dial|italics?|flashback|thoughts?|""" +
            """internal|narrat\w*|overlap|top|alt|sub|subs|subtitles?)(?:$|[^a-z])""",
    )

    internal data class Style(val name: String, val alignment: Int?)

    internal data class Event(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val style: String,
        val name: String,
        val effect: String,
        val rawText: String,
    )

    /**
     * The dialogue events among [events]. Falls back to every event when the classification leaves
     * too few to be the dialogue of a whole episode, so an unusual file is still analysed as before.
     */
    fun dialogueEvents(events: List<Event>, styles: List<Style>): List<Event> {
        if (events.size < MIN_DIALOGUE_EVENTS) return events
        val stylesByName = HashMap<String, Style>(styles.size * 2)
        for (i in styles.indices) {
            val s = styles[i]
            stylesByName[s.name.lowercase()] = s
        }

        // Compute lowered style name and typeset flag ONCE per event instead of twice.
        val styleNames = arrayOfNulls<String>(events.size)
        val isTypeset = BooleanArray(events.size)
        val styleTotals = HashMap<String, Int>(16)
        val styleTypesetCounts = HashMap<String, Int>(16)
        val styleNameCache = HashMap<String, String>(16)

        for (i in events.indices) {
            val ev = events[i]
            val lowered = styleNameCache.getOrPut(ev.style) { ev.style.lowercase() }
            styleNames[i] = lowered
            val ts = hasTypesetTag(ev.rawText)
            isTypeset[i] = ts
            styleTotals[lowered] = (styleTotals[lowered] ?: 0) + 1
            if (ts) {
                styleTypesetCounts[lowered] = (styleTypesetCounts[lowered] ?: 0) + 1
            }
        }

        val dialogueStyles = HashSet<String>(styleTotals.size * 2)
        for ((styleName, total) in styleTotals) {
            val typesetCount = styleTypesetCounts[styleName] ?: 0
            if (isDialogueStyleCounts(styleName, stylesByName[styleName], total, typesetCount)) {
                dialogueStyles += styleName
            }
        }

        val dialogue = ArrayList<Event>(events.size)
        for (i in events.indices) {
            val ev = events[i]
            if (styleNames[i] in dialogueStyles && !isTypeset[i] && isDialogueEventWithoutTypeset(ev)) {
                dialogue += ev
            }
        }
        return if (dialogue.size < MIN_DIALOGUE_EVENTS) events else dialogue
    }

    /**
     * The likely dialogue of an embedded ASS/SSA track known only by its timing (the Matroska Cues
     * index has no styles or text). Karaoke and effects show up as runs of very short events or as
     * many events starting within a moment of each other, and signs or titles as events held far
     * longer than a line of dialogue; those are dropped. [estimatedEndStartsMs] are the starts whose
     * end was estimated rather than indexed, whose duration says nothing about the event. Falls back
     * to [cues] when too little would be left.
     */
    fun indexedDialogueCues(
        cues: List<SubtitleSyncCue>,
        estimatedEndStartsMs: Set<Long>,
    ): List<SubtitleSyncCue> {
        if (cues.size < MIN_DIALOGUE_EVENTS) return cues
        var alreadySorted = true
        for (i in 1 until cues.size) {
            if (cues[i].startTimeMs < cues[i - 1].startTimeMs) {
                alreadySorted = false
                break
            }
        }
        val sorted = if (alreadySorted) cues else cues.sortedBy { it.startTimeMs }
        val n = sorted.size
        val dialogue = ArrayList<SubtitleSyncCue>(n)
        var left = 0
        var right = 0
        for (index in 0 until n) {
            val cue = sorted[index]
            val center = cue.startTimeMs
            while (left < index && center - sorted[left].startTimeMs > BURST_WINDOW_MS) left++
            while (right + 1 < n && sorted[right + 1].startTimeMs - center <= BURST_WINDOW_MS) right++
            val durationMs = cue.endTimeMs - center
            val explicit = center !in estimatedEndStartsMs
            if (explicit && durationMs !in MIN_DIALOGUE_DURATION_MS..MAX_DIALOGUE_DURATION_MS) {
                continue
            }
            if (right - left + 1 < BURST_MIN_STARTS) {
                dialogue += cue
            }
        }
        return if (dialogue.size < MIN_DIALOGUE_EVENTS) cues else dialogue
    }

    private fun isDialogueStyleCounts(
        name: String,
        style: Style?,
        totalEvents: Int,
        typesetEvents: Int,
    ): Boolean {
        val namedDialogue = dialogueName.containsMatchIn(name)
        if (!namedDialogue && nonDialogueName.containsMatchIn(name)) return false
        // Numpad alignment: 4-6 is the middle row, where dialogue is not placed.
        if (!namedDialogue && style?.alignment in 4..6) return false
        return typesetEvents < totalEvents * TYPESET_STYLE_RATIO
    }

    private fun isDialogueEventWithoutTypeset(event: Event): Boolean {
        if (event.effect.isNotBlank()) return false
        val rawActor = event.name
        if (rawActor.isNotEmpty()) {
            val actor = rawActor.lowercase()
            if (!dialogueName.containsMatchIn(actor) && nonDialogueName.containsMatchIn(actor)) {
                return false
            }
        }
        return true
    }

    /**
     * Exact zero-regex match inside ASS `{...}` override blocks for:
     * `\\(?:pos|move|org|i?clip)\s*\(|\\t\s*\(|\\p[1-9]|\\[kK][fo]?\d|\\fr[xyz]?-?\d|\\fa[xy]`
     */
    internal fun hasTypesetTag(rawText: String): Boolean {
        val len = rawText.length
        var blockStart = rawText.indexOf('{')
        while (blockStart >= 0 && blockStart + 2 < len) {
            val blockEnd = rawText.indexOf('}', blockStart + 1)
            val limit = if (blockEnd >= 0) blockEnd else len
            var i = rawText.indexOf('\\', blockStart + 1)
            while (i in (blockStart + 1) until (limit - 1)) {
                val c1 = rawText[i + 1]
                when (c1) {
                    'p', 'P' -> {
                        if (i + 2 < limit) {
                            val c2 = rawText[i + 2]
                            if (c1 == 'p' && c2 in '1'..'9') return true
                            if ((c2 == 'o' || c2 == 'O') && i + 3 < limit &&
                                (rawText[i + 3] == 's' || rawText[i + 3] == 'S') &&
                                hasOptionalSpacesThenOpenParen(rawText, i + 4, limit)
                            ) {
                                return true
                            }
                        }
                    }
                    'm', 'M' -> {
                        if (i + 4 < limit &&
                            rawText.regionMatches(i + 1, "move", 0, 4, ignoreCase = true) &&
                            hasOptionalSpacesThenOpenParen(rawText, i + 5, limit)
                        ) {
                            return true
                        }
                    }
                    'o', 'O' -> {
                        if (i + 3 < limit &&
                            rawText.regionMatches(i + 1, "org", 0, 3, ignoreCase = true) &&
                            hasOptionalSpacesThenOpenParen(rawText, i + 4, limit)
                        ) {
                            return true
                        }
                    }
                    'c', 'C' -> {
                        if (i + 4 < limit &&
                            rawText.regionMatches(i + 1, "clip", 0, 4, ignoreCase = true) &&
                            hasOptionalSpacesThenOpenParen(rawText, i + 5, limit)
                        ) {
                            return true
                        }
                    }
                    'i', 'I' -> {
                        if (i + 5 < limit &&
                            rawText.regionMatches(i + 1, "iclip", 0, 5, ignoreCase = true) &&
                            hasOptionalSpacesThenOpenParen(rawText, i + 6, limit)
                        ) {
                            return true
                        }
                    }
                    't', 'T' -> {
                        if (hasOptionalSpacesThenOpenParen(rawText, i + 2, limit)) return true
                    }
                    'k', 'K' -> {
                        var k = i + 2
                        if (k < limit && (rawText[k] == 'f' || rawText[k] == 'o' || rawText[k] == 'F' || rawText[k] == 'O')) k++
                        if (k < limit && rawText[k] in '0'..'9') return true
                    }
                    'f', 'F' -> {
                        if (i + 2 < limit) {
                            val c2 = rawText[i + 2]
                            if (c2 == 'r' || c2 == 'R') {
                                var k = i + 3
                                if (k < limit && rawText[k].lowercaseChar() in "xyz") k++
                                if (k < limit && rawText[k] == '-') k++
                                if (k < limit && rawText[k] in '0'..'9') return true
                            } else if (c2 == 'a' || c2 == 'A') {
                                if (i + 3 < limit && rawText[i + 3].lowercaseChar() in "xy") return true
                            }
                        }
                    }
                }
                i = rawText.indexOf('\\', i + 1)
            }
            if (blockEnd < 0) break
            blockStart = rawText.indexOf('{', blockEnd + 1)
        }
        return false
    }

    private fun hasOptionalSpacesThenOpenParen(text: String, start: Int, len: Int): Boolean {
        var k = start
        while (k < len && text[k].isWhitespace()) k++
        return k < len && text[k] == '('
    }
}
