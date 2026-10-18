// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

internal enum class CopyKind {
    /** Another version on a media server. */
    SERVER,
    /** A ready http(s) link. */
    LINK,
    /** A copy already in the user's TorBox list. */
    DEBRID,
    /** A cached TorBox copy added to the user's list for the pass and removed again. */
    TORBOX_ADD,
    /** A cached Premiumize copy through a direct download link (uses fair-use points). */
    PREMIUMIZE,
    /** A Usenet link streamed by a local AIOStreams (each open may count as a grab at the indexer). */
    USENET,
}

internal enum class CopyCodec { H264, HEVC, OTHER, UNKNOWN }

/** One entry of the player's source list as a possible copy of the playing title. */
internal class CopyOffer(
    val id: String,
    val label: String,
    val kind: CopyKind,
    /** Picture height class (1080, 720, ...), 0 when unknown. */
    val height: Int,
    val codec: CopyCodec,
    val hdr: Boolean,
    val web: Boolean,
    val sizeBytes: Long?,
    /** File extension or the server's container, lower case; null when unknown. */
    val container: String?,
    val durationMs: Long?,
    /** Names, description and file name: the edition words are read from it. */
    val text: String,
)

/** Which copy of a 4K title, or of a title playing from Usenet, its thumbnails are made from. */
internal object CopyChoice {
    /** Copies checked by pictures before falling back to the 4K file. */
    const val MAX_TRIES = 2
    private val CONTAINERS = setOf("mkv", "mp4", "m4v")
    private val HEIGHTS = listOf(1080, 720)

    enum class Edition(val word: Regex) {
        EXTENDED(Regex("(?<![a-z])extended(?![a-z])")),
        DIRECTORS(Regex("(?<![a-z])director'?s?(?![a-z])")),
        UNRATED(Regex("(?<![a-z])unrated(?![a-z])")),
        IMAX(Regex("(?<![a-z])imax(?![a-z])")),
        UNCUT(Regex("(?<![a-z])uncut(?![a-z])")),
        SPECIAL(Regex("(?<![a-z])special[ ._-]?edition(?![a-z])")),
        FINAL_CUT(Regex("(?<![a-z])final[ ._-]?cut(?![a-z])")),
        REMASTERED(Regex("(?<![a-z])remastered(?![a-z])")),
    }

    private val RESOLVE_SEGMENTS = setOf(
        "resolve", "playback", "debrid", "realdebrid", "alldebrid", "torbox", "premiumize", "debridlink",
    )
    private val PROVIDER_TAGS = listOf("[rd", "[tb", "[ad", "[pm", "rd+", "tb+", "download", "⬇")

    /**
     * Why a ready link may not be read for a copy, null when it may. Only a plain file link qualifies, or a Usenet
     * link streamed by a local AIOStreams; a link that may resolve through a debrid service (and add to its account)
     * does not.
     */
    fun linkRejection(url: String, text: String): String? {
        val path = url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').substringBefore('#')
            .lowercase()
        if (isUsenet(url)) return null
        path.split('/').firstOrNull { it in RESOLVE_SEGMENTS }?.let { return "a resolve link (/$it/)" }
        val t = text.lowercase()
        PROVIDER_TAGS.firstOrNull { it in t }?.let { return "a debrid or download entry ($it)" }
        return null
    }

    /** A Usenet link streamed by a local AIOStreams. */
    fun isUsenet(url: String): Boolean {
        val path = url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').substringBefore('#')
            .lowercase()
        return "/usenet/stream/" in "/$path"
    }

    fun editions(text: String): Set<Edition> {
        val t = text.lowercase().replace('’', '\'')
        return Edition.entries.filterTo(LinkedHashSet()) { it.word.containsMatchIn(t) }
    }

    /** A source marked as Usenet by its add-on. */
    fun usenetMarked(type: String?, service: String?): Boolean =
        listOfNotNull(type, service).any { it.trim().lowercase() in USENET_MARKS }

    private val USENET_MARKS = setOf("usenet", "nzb")

    /** Picture height class of a [w] x [h] picture: a wide film has fewer lines than its class. */
    fun heightClass(w: Int, h: Int): Int = when {
        w >= 3000 || h >= 1500 -> 2160
        w >= 1600 || h >= 900 -> 1080
        w >= 1100 || h >= 600 -> 720
        else -> 480
    }

    /** AIOStreams' parsed duration: seconds for a film-length value, else milliseconds. */
    fun parsedDurationMs(raw: Long?): Long? = when {
        raw == null || raw <= 0L -> null
        raw < 100_000L -> raw * 1000L
        else -> raw
    }

    fun containerOf(fileName: String?): String? =
        fileName?.substringBefore('?')?.substringAfterLast('/')?.substringAfterLast('.', "")
            ?.lowercase()?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }

    /**
     * Why [offer] cannot stand in for the playing title, null when it can. Nothing is opened for this. [maxHeight]: the
     * playing picture's height class. [usenet]: the title plays from Usenet, so another Usenet link (another grab) is
     * never used.
     */
    fun rejection(offer: CopyOffer, playingText: String, ownMs: Long, maxHeight: Int = 2160, usenet: Boolean = false): String? {
        if (usenet && offer.kind == CopyKind.USENET) return "another Usenet link"
        offer.container?.let { if (it !in CONTAINERS) return "container $it" }
        when {
            offer.height == 0 -> return "resolution not stated"
            offer.height > maxHeight -> return "${offer.height}p is above the playing ${maxHeight}p"
            offer.height > HEIGHTS.first() -> return "${offer.height}p is not a smaller copy"
            offer.height !in HEIGHTS -> return "${offer.height}p is too small"
        }
        val own = editions(playingText)
        val theirs = editions(offer.text)
        if (own != theirs) {
            val diff = (own - theirs) + (theirs - own)
            return "another edition (${diff.joinToString { it.name.lowercase() }})"
        }
        offer.durationMs?.let { ms ->
            if (ReleaseMatch.ratio(ownMs, ms) == null) return "length ${ms / 1000} s against ${ownMs / 1000} s"
        }
        return null
    }

    /**
     * Copies with no account side effects first (TorBox list, server versions, ready links), then a TorBox copy added
     * and removed again, then Premiumize, then Usenet (an indexer grab); within that SDR before HDR, H.264 before HEVC
     * (a thumbnail is 480 wide, so a 720p H.264 copy gives the same picture faster than a 1080p HEVC one), 1080p before
     * 720p, WEB first, smaller files first, and by source cost last (TorBox list before server versions and links). For a title playing from Usenet ([usenet]) the source comes first: server versions, the TorBox list,
     * Premiumize, ready links, then a TorBox copy added and removed again.
     */
    fun order(offers: List<CopyOffer>, usenet: Boolean = false): List<CopyOffer> = offers.sortedWith(
        compareBy<CopyOffer> { if (usenet) usenetRank(it.kind) else sideEffects(it.kind) }
            .thenBy { if (it.hdr) 1 else 0 }
            .thenBy { codecRank(it.codec) }
            .thenBy { HEIGHTS.indexOf(it.height).let { i -> if (i < 0) HEIGHTS.size else i } }
            .thenBy { if (it.web) 0 else 1 }
            .thenBy { it.sizeBytes ?: Long.MAX_VALUE }
            .thenBy { if (it.kind == CopyKind.DEBRID) 0 else 1 }
    )

    private fun sideEffects(k: CopyKind): Int = when (k) {
        CopyKind.DEBRID, CopyKind.SERVER, CopyKind.LINK -> 0
        CopyKind.TORBOX_ADD -> 1
        CopyKind.PREMIUMIZE -> 2
        CopyKind.USENET -> 3
    }

    private fun usenetRank(k: CopyKind): Int = when (k) {
        CopyKind.SERVER -> 0
        CopyKind.DEBRID -> 1
        CopyKind.PREMIUMIZE -> 2
        CopyKind.LINK -> 3
        CopyKind.TORBOX_ADD -> 4
        CopyKind.USENET -> 5
    }

    private fun codecRank(c: CopyCodec): Int = when (c) {
        CopyCodec.H264 -> 0
        CopyCodec.HEVC -> 1
        CopyCodec.UNKNOWN -> 2
        CopyCodec.OTHER -> 3
    }

    /** Copy slot -> the own slots it serves (same moment, slot centres), for a copy of [ratio] (copy / own time). */
    fun ownSlotsByCopySlot(ownSlotCount: Int, ratio: Double, copySlotCount: Int): Map<Int, IntArray> {
        val m = LinkedHashMap<Int, MutableList<Int>>()
        for (s in 0 until ownSlotCount) {
            val c = ReleaseMatch.mapSlot(s, ratio, copySlotCount)
            if (c >= 0) m.getOrPut(c) { ArrayList() }.add(s)
        }
        return m.mapValues { it.value.toIntArray() }
    }
}

/** Fits a copy's picture to the playing title's picture size, so both can be compared and shown alike. */
internal object CopyFit {
    /** [renderW] x [renderH] is drawn at [top] inside the own picture: bars added when positive, the copy's bars cut when negative. */
    class Plan(val renderW: Int, val renderH: Int, val top: Int)

    private const val MAX_SHAPE_CHANGE = 1.6

    /** The copy's [w] x [h] picture scaled to the own width [ow] and centred in [ow] x [oh]; null when the shapes are too far apart. */
    fun plan(w: Int, h: Int, ow: Int, oh: Int): Plan? {
        if (w <= 0 || h <= 0 || ow <= 0 || oh <= 0) return null
        if (w == ow && h == oh) return Plan(ow, oh, 0)
        val rh = ((ow.toDouble() * h / w).roundToInt() and 1.inv()).coerceAtLeast(2)
        val change = maxOf(rh, oh).toDouble() / minOf(rh, oh)
        if (change > MAX_SHAPE_CHANGE) return null
        return Plan(ow, rh, (oh - rh) / 2)
    }
}

/**
 * Servers a copy is not read from for now: one that turned a copy request away ([refused], for [REFUSED_MS]), and
 * one whose playback stalled or failed soon after a copy request ([trouble], for the rest of the day).
 */
internal class CopyHostBlocks {
    companion object {
        const val REFUSED_MS = 30L * 60 * 1000

        val app = CopyHostBlocks()

        /** Local midnight after [nowMs]. */
        fun endOfDayMs(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long =
            Calendar.getInstance(zone).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
    }

    private class Block(val untilMs: Long, val why: String)

    private val blocks = java.util.concurrent.ConcurrentHashMap<String, Block>()

    fun refused(host: String, nowMs: Long) {
        blocks.merge(host, Block(nowMs + REFUSED_MS, "turned a copy request away")) { a, b -> if (a.untilMs >= b.untilMs) a else b }
    }

    fun trouble(host: String, nowMs: Long) {
        blocks[host] = Block(maxOf(endOfDayMs(nowMs), blocks[host]?.untilMs ?: 0L), "playback had trouble soon after a copy request")
    }

    /** Why [host] is left alone at [nowMs], null when it is not. */
    fun blocked(host: String, nowMs: Long): String? {
        val b = blocks[host] ?: return null
        if (nowMs < b.untilMs) return b.why
        blocks.remove(host, b)
        return null
    }

    /** Playback trouble at [troubleAtMs] during a copy request or within [windowMs] after it: the copy is blamed. */
    fun blames(requestStartMs: Long, requestEndMs: Long, troubleAtMs: Long, windowMs: Long): Boolean =
        requestStartMs > 0L && troubleAtMs >= requestStartMs && troubleAtMs <= maxOf(requestStartMs, requestEndMs) + windowMs
}
