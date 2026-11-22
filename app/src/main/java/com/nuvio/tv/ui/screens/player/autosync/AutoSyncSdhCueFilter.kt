package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.SubtitleSyncCue

/**
 * Filters pure SDH sound/music description cues (e.g. `[door slams]`, `(sighs)`, `♪ music ♪`)
 * from an external subtitle for AutoSync's timing analysis only.
 *
 * External add-on subtitles are frequently SDH while embedded tracks are clean dialogue (or vice
 * versa). Comparing dialogue-to-dialogue lets AutoSync achieve high coverage and tight alignment,
 * while the rendered/retimed subtitle keeps every original SDH cue untouched.
 */
internal object AutoSyncSdhCueFilter {
    private const val MIN_DIALOGUE_CUES = 20
    private const val MIN_SDH_CUES_TO_FILTER = 10
    private const val MIN_SDH_RATIO_TO_FILTER = 0.05

    /**
     * Returns only dialogue cues when the subtitle contains a meaningful proportion of pure SDH
     * cues and enough dialogue remains for full-film analysis; otherwise returns [cues] unchanged.
     */
    fun dialogueCues(cues: List<SubtitleSyncCue>): List<SubtitleSyncCue> {
        val total = cues.size
        if (total < MIN_DIALOGUE_CUES + MIN_SDH_CUES_TO_FILTER) return cues

        var sdhCount = 0
        val isSdh = BooleanArray(total)
        for (i in 0 until total) {
            if (isPureSdhCueText(cues[i].text)) {
                isSdh[i] = true
                sdhCount++
            }
        }
        val remaining = total - sdhCount
        if (
            sdhCount < MIN_SDH_CUES_TO_FILTER ||
            remaining < MIN_DIALOGUE_CUES ||
            sdhCount.toDouble() / total.toDouble() < MIN_SDH_RATIO_TO_FILTER
        ) {
            return cues
        }

        val filtered = ArrayList<SubtitleSyncCue>(remaining)
        for (i in 0 until total) {
            if (!isSdh[i]) filtered += cues[i]
        }
        return filtered
    }

    /**
     * True only when EVERY non-blank line of [text] is purely a sound/music description
     * (`[...]`, `(...)`, `♪...♪`, `♫...♫`, or music symbols only). Lines containing spoken
     * dialogue alongside a tag (e.g. `(whispering) Come here.`) return false.
     */
    internal fun isPureSdhCueText(text: String): Boolean {
        val len = text.length
        if (len == 0) return false
        var lineStart = 0
        var nonBlankLines = 0

        while (lineStart <= len) {
            var lineEnd = lineStart
            while (lineEnd < len && text[lineEnd] != '\n') lineEnd++

            var s = lineStart
            while (s < lineEnd && text[s].isWhitespace()) s++
            var e = lineEnd
            while (e > s && text[e - 1].isWhitespace()) e--

            if (s < e) {
                nonBlankLines++
                if (!isPureSdhLine(text, s, e)) return false
            }
            if (lineEnd >= len) break
            lineStart = lineEnd + 1
        }
        return nonBlankLines > 0
    }

    private fun isPureSdhLine(text: String, rawStart: Int, rawEnd: Int): Boolean {
        var s = rawStart
        var e = rawEnd

        // Optional speaker dash prefix: "- [groans]" or "— (sighs)"
        if (s < e && (text[s] == '-' || text[s] == '\u2013' || text[s] == '\u2014')) {
            s++
            while (s < e && text[s].isWhitespace()) s++
        }
        if (s >= e) return false

        // Purely musical notes / punctuation / whitespace (e.g. "♪ ♪")
        var hasMusicSymbol = false
        var hasNonMusicLetterOrDigit = false
        for (i in s until e) {
            val ch = text[i]
            if (isMusicSymbol(ch)) {
                hasMusicSymbol = true
            } else if (ch.isLetterOrDigit()) {
                hasNonMusicLetterOrDigit = true
            }
        }
        if (hasMusicSymbol && !hasNonMusicLetterOrDigit) return true

        // Wrapped in music symbols: "♪ some lyric ♪"
        if (isMusicSymbol(text[s]) && isMusicSymbol(text[e - 1]) && e - s >= 2) {
            return true
        }

        // One or more consecutive bracketed/parenthetical groups with only whitespace/punctuation
        // between them (e.g. "[door opens] [footsteps]", "(sighs) (panting)").
        var cursor = s
        var foundGroup = false
        while (cursor < e) {
            val ch = text[cursor]
            if (ch == '[' || ch == '(') {
                val closeChar = if (ch == '[') ']' else ')'
                var depth = 1
                var j = cursor + 1
                while (j < e && depth > 0) {
                    val c = text[j]
                    if (c == ch) depth++ else if (c == closeChar) depth--
                    j++
                }
                if (depth != 0) return false
                foundGroup = true
                cursor = j
            } else if (ch.isWhitespace() || ch == ',' || ch == '.' || ch == ':' || ch == ';' || ch == '-' || isMusicSymbol(ch)) {
                cursor++
            } else {
                return false
            }
        }
        return foundGroup
    }

    private fun isMusicSymbol(ch: Char): Boolean =
        ch == '\u266A' || // ♪
            ch == '\u266B' || // ♫
            ch == '\u2669' || // ♩
            ch == '\u266C' // ♬
}
