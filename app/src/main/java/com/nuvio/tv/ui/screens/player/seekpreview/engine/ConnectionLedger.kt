// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Which server or service counts a request's connections: TorBox counts per account, anything else per host. */
internal object LedgerKey {
    const val TORBOX = "torbox"

    fun of(url: String?): String? {
        val host = url?.let { RateLimitedHosts.hostOf(it) } ?: return null
        return if (isTorBox(host, url.lowercase())) TORBOX else host
    }

    /** TorBox links, and add-on links that resolve through TorBox (only their path shows it before the redirect). */
    private fun isTorBox(host: String, lower: String): Boolean =
        host.startsWith("tb-cdn.") || ".tb-cdn." in host || host.contains("torbox") || "/torbox/" in lower
}

/** How a server turned a request away. Only [TOO_MANY], or [UNAVAILABLE] twice in a row, may change a kept level. */
internal enum class Refusal { TOO_MANY, UNAVAILABLE, CONNECTION }

/**
 * Thumbnail connections one server allows on top of playback's, kept as a level ([extra]) relative to playback. One
 * more after a run of clean fetches that used what they had while playback could spare it. A refusal soon after a step
 * up goes back to the level before it; a clear one keeps that level as the server's [cap], tried once more after
 * [RETEST_AFTER_MS]. Anything else only slows this session. With a known total playback's count comes off it.
 */
internal class ConnectionBudget(
    private val knownTotal: Int?,
    remembered: Remembered? = null,
    wallNowMs: Long = 0L,
) {
    data class Remembered(val extra: Int, val capped: Boolean, val savedAtMs: Long)

    companion object {
        const val START_EXTRA = 1
        const val GENERATING_START_EXTRA = 2
        const val MAX_EXTRA = 4
        const val GENERATING_MAX_EXTRA = 6
        const val CLEAN_PER_STEP = 8
        const val GENERATING_CLEAN_PER_STEP = 4
        const val TROUBLE_WINDOW_MS = 20_000L
        const val RETEST_AFTER_MS = 24L * 60 * 60 * 1000
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
        private const val NONE = Long.MIN_VALUE
    }

    private val floor = if (knownTotal != null) 0 else 1

    var extra = remembered?.extra?.coerceIn(floor, MAX_EXTRA) ?: if (knownTotal != null) MAX_EXTRA else START_EXTRA
        private set

    /** The kept cap, null until the server clearly refused a level. */
    var cap: Int? = remembered?.takeIf { it.capped }?.extra?.coerceIn(floor, MAX_EXTRA)
        private set

    private var retestDue = remembered != null && remembered.capped && wallNowMs - remembered.savedAtMs >= RETEST_AFTER_MS
    private var retesting = false
    private var sessionCap: Int? = null
    private var clean = 0
    private var unavailableInRow = 0
    private var stepUpAtMs = NONE
    private var beforeStep = 0
    private var lastDropAtMs = NONE
    private var saveDueAtMs = NONE

    private fun ceiling(generating: Boolean): Int {
        var c = if (generating && cap == null) GENERATING_MAX_EXTRA else MAX_EXTRA
        cap?.let { c = minOf(c, it + if (retestDue) 1 else 0) }
        sessionCap?.let { c = minOf(c, it) }
        return c.coerceAtLeast(floor)
    }

    /** The level in use. On the generating screen a server without a cap starts higher and may go further. */
    fun level(playback: Int, generating: Boolean): Int {
        var x = if (generating && cap == null) maxOf(extra, GENERATING_START_EXTRA) else extra
        x = minOf(x, ceiling(generating))
        if (knownTotal != null) x = minOf(x, knownTotal - playback.coerceAtLeast(1))
        return x.coerceAtLeast(floor)
    }

    /**
     * Thumbnail requests allowed with [live] playback requests in flight. Never none on a server without a known total;
     * with one, never none while playback is [quiet] (paused or yielding), since it is not loading then.
     */
    fun thumbSlots(playback: Int, live: Int, quiet: Boolean, generating: Boolean): Int {
        val room = playback.coerceAtLeast(1) + level(playback, generating) - live.coerceAtLeast(0)
        return maxOf(room, if (knownTotal == null || quiet) 1 else 0)
    }

    /**
     * A thumbnail request started at [startedAtMs] got through. It counts toward a step up only while playback could
     * spare it ([healthy]) and the thumbnails were using what they had ([busy]). True when the level stepped up.
     */
    fun succeeded(startedAtMs: Long, nowMs: Long, healthy: Boolean, busy: Boolean, playback: Int, generating: Boolean): Boolean {
        if (startedAtMs <= lastDropAtMs) return false
        unavailableInRow = 0
        if (!healthy || !busy) return false
        val need = if (generating && cap == null) GENERATING_CLEAN_PER_STEP else CLEAN_PER_STEP
        if (++clean < need) return false
        clean = 0
        val current = level(playback, generating)
        if (current >= ceiling(generating)) return false
        if (knownTotal != null && playback.coerceAtLeast(1) + current >= knownTotal) return false
        beforeStep = current
        extra = current + 1
        stepUpAtMs = nowMs
        val c = cap
        if (c != null && extra > c) {
            cap = extra
            retestDue = false
            retesting = true
        }
        saveDueAtMs = nowMs + TROUBLE_WINDOW_MS
        return true
    }

    /** A thumbnail request started at [startedAtMs] was turned away. Requests under way together count once. */
    fun refused(kind: Refusal, startedAtMs: Long, nowMs: Long, playback: Int, generating: Boolean): Boolean {
        if (startedAtMs <= lastDropAtMs) return false
        val clear = isClear(kind)
        dropBack(nowMs, nowMs, playback, generating, keep = clear, holdSession = clear)
        return true
    }

    /** Playback stalled, failed ([kind] null) or was turned away at [atMs]: only counts soon after a step up. */
    fun playbackTrouble(kind: Refusal?, atMs: Long, nowMs: Long, playback: Int, generating: Boolean): Boolean {
        if (stepUpAtMs == NONE || atMs < stepUpAtMs || atMs - stepUpAtMs > TROUBLE_WINDOW_MS) return false
        if (atMs <= lastDropAtMs) return false
        dropBack(atMs, maxOf(nowMs, atMs), playback, generating, keep = kind != null && isClear(kind), holdSession = true)
        return true
    }

    private fun isClear(kind: Refusal): Boolean = when (kind) {
        Refusal.TOO_MANY -> true
        Refusal.UNAVAILABLE -> ++unavailableInRow >= 2
        Refusal.CONNECTION -> false
    }

    private fun dropBack(atMs: Long, nowMs: Long, playback: Int, generating: Boolean, keep: Boolean, holdSession: Boolean) {
        val afterStep = stepUpAtMs != NONE && atMs - stepUpAtMs in 0..TROUBLE_WINDOW_MS
        if (afterStep) {
            extra = beforeStep
            if (retesting || keep) cap = beforeStep
            if (holdSession) sessionCap = minOf(sessionCap ?: beforeStep, beforeStep)
            saveDueAtMs = if (keep) nowMs else NONE
        } else {
            extra = (level(playback, generating) - 1).coerceAtLeast(floor)
            sessionCap = extra
        }
        retesting = false
        // A second refusal soon after the same step up may still be the clear one (a 503 twice in a row).
        if (!afterStep || keep) stepUpAtMs = NONE
        clean = 0
        lastDropAtMs = nowMs
    }

    /** What the next session starts from, once a change has settled; null when there is nothing new to keep. */
    fun toSave(nowMs: Long, wallNowMs: Long): Remembered? {
        if (saveDueAtMs == NONE || nowMs < saveDueAtMs) return null
        saveDueAtMs = NONE
        return Remembered(extra.coerceIn(floor, MAX_EXTRA), cap != null, wallNowMs)
    }
}

/** A server's learned level as kept between sessions, for [ConnectionBudget.KEEP_MS]. */
internal object ConnectionMemory {
    fun encode(r: ConnectionBudget.Remembered): String = "${r.extra},${if (r.capped) 1 else 0},${r.savedAtMs}"

    fun decode(s: String?, wallNowMs: Long): ConnectionBudget.Remembered? {
        val parts = s?.split(',')?.takeIf { it.size == 3 } ?: return null
        val extra = parts[0].toIntOrNull()?.takeIf { it in 0..ConnectionBudget.MAX_EXTRA } ?: return null
        val capped = when (parts[1]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val at = parts[2].toLongOrNull() ?: return null
        if (wallNowMs - at !in 0 until ConnectionBudget.KEEP_MS) return null
        return ConnectionBudget.Remembered(extra, capped, at)
    }
}

/**
 * Requests in flight per server, playback's and the thumbnails'. Playback registers and never waits, and nothing on
 * its side ever throws; a thumbnail request starts only while the server's [ConnectionBudget] leaves room.
 */
@OptIn(UnstableApi::class)
internal class ConnectionLedger(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        val app = ConnectionLedger()
        const val SLOT_WAIT_MS = 30_000L
        /** Header and index reads wait less, and the engine gives up after a few. */
        const val START_SLOT_WAIT_MS = 5_000L
        const val HEALTH_TTL_MS = 10_000L
        const val HEALTHY_AHEAD_MS = 30_000L
        /** A reset of an idle stream as playback resumes is not the server refusing. */
        const val AFTER_YIELD_QUIET_MS = 10_000L
        /** Answer for a request with no host: no limit from the ledger. */
        const val UNLIMITED = Int.MAX_VALUE
        private const val POLL_MS = 200L
        private const val TAG = "ThumbLedger"

        /** Playback can spare a connection: paused, or well buffered ahead and not buffering. */
        fun healthy(playing: Boolean, buffering: Boolean, aheadMs: Long, toEnd: Boolean): Boolean =
            !buffering && (!playing || toEnd || aheadMs >= HEALTHY_AHEAD_MS)

        /** A player error that says something about the server: the IO range, not a sound output or decoder refusal. */
        fun sourceTrouble(errorCode: Int?): Boolean = errorCode != null && errorCode in 2000..2999

        fun saysTooMany(text: String?): Boolean = text?.contains("too many", ignoreCase = true) == true

        /** How the server turned a request away, null when it did not. */
        fun refusalOf(e: Throwable?): Refusal? {
            for (c in generateSequence(e) { it.cause }.take(8)) {
                when {
                    c is RateLimitedException ->
                        return if (c.status == RateLimitedException.TOO_MANY_REQUESTS) Refusal.TOO_MANY else Refusal.UNAVAILABLE
                    c is HttpDataSource.InvalidResponseCodeException -> return when {
                        c.responseCode == 429 || saysTooMany(c.responseMessage) ||
                            saysTooMany(runCatching { String(c.responseBody, Charsets.UTF_8) }.getOrNull()) -> Refusal.TOO_MANY
                        c.responseCode == 503 -> Refusal.UNAVAILABLE
                        c.responseCode == 403 -> Refusal.CONNECTION
                        else -> null
                    }
                    c is ConnectException -> return Refusal.CONNECTION
                    c is SocketException && c.message?.contains("reset", ignoreCase = true) == true -> return Refusal.CONNECTION
                }
            }
            return null
        }
    }

    class NoSlotException : IOException("no connection free")

    interface Memory {
        fun load(): Map<String, String>
        fun save(key: String, value: String)
        fun forget(keys: Collection<String>)
    }

    internal enum class Kind { PLAYBACK, STREAM, THUMBNAIL }

    private class Entry {
        var playback = 0
        /** Playback's single open streams (part of [playback]); idle while playback yields. */
        var streams = 0
        var thumbs = 0
        var playbackPeak = 0
        var budget: ConnectionBudget? = null
    }

    private val lock = ReentrantLock()
    private val freed = lock.newCondition()
    private val entries = HashMap<String, Entry>()
    private var memory: Memory? = null
    private var remembered: Map<String, String> = emptyMap()
    @Volatile private var playbackConnections = 1
    @Volatile private var playingKey: String? = null
    @Volatile private var healthyUntilMs = Long.MIN_VALUE
    @Volatile private var quietUntilMs = Long.MIN_VALUE
    @Volatile private var yieldUntilMs = Long.MIN_VALUE
    @Volatile private var yieldEndAtMs = Long.MIN_VALUE

    /** Once per app run; the learned levels then apply to every server first seen after it. */
    fun attach(m: Memory) {
        if (lock.withLock { memory != null }) return
        val all = runCatching { m.load() }.getOrDefault(emptyMap())
        val now = wallClock()
        val fresh = all.filter { (_, v) -> ConnectionMemory.decode(v, now) != null }
        lock.withLock {
            if (memory != null) return
            memory = m
            remembered = fresh
        }
        val stale = all.keys - fresh.keys
        if (stale.isNotEmpty()) runCatching { m.forget(stale) }
    }

    /** A new playback: its connection setting (1 without parallel connections); what it used before no longer counts. */
    fun notePlaybackConnections(n: Int) {
        playbackConnections = n.coerceAtLeast(1)
        lock.withLock { for (e in entries.values) e.playbackPeak = e.playback }
    }

    /** The stream playback is on; its troubles count against that server only. */
    fun notePlayingUrl(url: String?) {
        LedgerKey.of(url)?.let { playingKey = it }
    }

    /** [quiet]: playback is paused and so not loading. Both lapse after [HEALTH_TTL_MS] unless renewed. */
    fun notePlaybackState(healthy: Boolean, quiet: Boolean) {
        val now = clock()
        healthyUntilMs = if (healthy) now + HEALTH_TTL_MS else Long.MIN_VALUE
        quietUntilMs = if (quiet) now + HEALTH_TTL_MS else Long.MIN_VALUE
    }

    /** The generating screen's yield, renewed while it is up (see [PlaybackYield]). */
    fun noteYield(active: Boolean) {
        val now = clock()
        if (active) {
            yieldUntilMs = now + PlaybackYield.LEASE_MS
            yieldEndAtMs = yieldUntilMs
        } else if (now < yieldUntilMs) {
            yieldUntilMs = Long.MIN_VALUE
            yieldEndAtMs = now
        }
        lock.withLock { freed.signalAll() }
    }

    private fun yielding(now: Long): Boolean = now < yieldUntilMs

    private fun troubleIgnored(now: Long): Boolean =
        yielding(now) || (yieldEndAtMs != Long.MIN_VALUE && now - yieldEndAtMs < AFTER_YIELD_QUIET_MS)

    /** A playback stall or error at [atMs] (this ledger's clock) on [url]'s server, else the playing one. */
    fun playbackTrouble(atMs: Long, url: String? = null) {
        val key = LedgerKey.of(url) ?: playingKey ?: return
        val now = clock()
        if (troubleIgnored(now)) return
        lock.withLock {
            val e = entries[key] ?: return
            val b = e.budget ?: return
            if (b.playbackTrouble(null, atMs, now, playbackOf(e), yielding(now))) stepped(key, e, b, "playback trouble soon after a step up")
        }
    }

    /** The most thumbnail requests [url]'s server may carry at once, with playback quiet. */
    fun thumbnailCap(url: String?): Int {
        val key = LedgerKey.of(url) ?: return UNLIMITED
        return lock.withLock {
            val e = entry(key)
            val b = budgetOf(key, e)
            persist(key, b)
            b.thumbSlots(playbackOf(e), 0, true, yielding(clock()))
        }
    }

    fun inFlight(url: String?): Int {
        val key = LedgerKey.of(url) ?: return 0
        return lock.withLock { entries[key]?.let { it.playback + it.thumbs } ?: 0 }
    }

    /** For the log: requests open on [url]'s server now, playback's and the thumbnails'. */
    fun inFlightNote(url: String?): String {
        val key = LedgerKey.of(url) ?: return "not counted"
        return lock.withLock {
            val e = entries[key]
            "in use playback ${e?.playback ?: 0}, thumbnails ${e?.thumbs ?: 0}"
        }
    }

    /** Playback's request: counted at once, never waits. [stream]: a single open stream that may sit idle. */
    fun playback(url: String?, stream: Boolean = false): Slot? = runCatching {
        val key = LedgerKey.of(url)
        val kind = if (stream) Kind.STREAM else Kind.PLAYBACK
        lock.withLock { if (key != null) add(entry(key), kind) }
        Slot(key, kind, clock(), busy = false)
    }.getOrNull()

    /**
     * A thumbnail request: waits while the server has no room. Throws [NoSlotException] after [maxWaitMs], or an
     * IOException once [cancelled].
     */
    fun acquire(url: String?, maxWaitMs: Long = SLOT_WAIT_MS, cancelled: () -> Boolean = { false }): Slot {
        val key = LedgerKey.of(url) ?: return Slot(null, Kind.THUMBNAIL, clock(), busy = false)
        val deadline = clock() + maxWaitMs
        var busy: Boolean
        lock.withLock {
            val e = entry(key)
            var allowed = slotsLocked(key, e)
            while (e.thumbs >= allowed) {
                if (cancelled()) throw IOException("closed")
                val left = deadline - clock()
                if (left <= 0L) throw NoSlotException()
                try {
                    freed.await(minOf(left, POLL_MS), TimeUnit.MILLISECONDS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("interrupted while waiting for a connection")
                }
                allowed = slotsLocked(key, e)
            }
            add(e, Kind.THUMBNAIL)
            busy = e.thumbs >= allowed - 1
        }
        return Slot(key, Kind.THUMBNAIL, clock(), busy)
    }

    private fun entry(key: String): Entry = entries.getOrPut(key) { Entry() }

    private fun playbackOf(e: Entry): Int = maxOf(playbackConnections, e.playbackPeak)

    private fun budgetOf(key: String, e: Entry): ConnectionBudget = e.budget ?: run {
        val wall = wallClock()
        ConnectionBudget(null, ConnectionMemory.decode(remembered[key], wall), wall)
    }.also { e.budget = it }

    private fun slotsLocked(key: String, e: Entry): Int {
        val now = clock()
        val b = budgetOf(key, e)
        persist(key, b)
        val yielding = yielding(now)
        val live = e.playback - if (yielding) e.streams else 0
        return b.thumbSlots(playbackOf(e), live, yielding || now < quietUntilMs, yielding)
    }

    private fun add(e: Entry, kind: Kind) {
        when (kind) {
            Kind.THUMBNAIL -> e.thumbs++
            Kind.PLAYBACK, Kind.STREAM -> {
                e.playback++
                if (kind == Kind.STREAM) e.streams++
                e.playbackPeak = maxOf(e.playbackPeak, e.playback)
            }
        }
    }

    private fun remove(e: Entry, kind: Kind) {
        when (kind) {
            Kind.THUMBNAIL -> e.thumbs = (e.thumbs - 1).coerceAtLeast(0)
            Kind.PLAYBACK, Kind.STREAM -> {
                e.playback = (e.playback - 1).coerceAtLeast(0)
                if (kind == Kind.STREAM) e.streams = (e.streams - 1).coerceAtLeast(0)
            }
        }
        freed.signalAll()
    }

    private fun persist(key: String, b: ConnectionBudget) {
        val m = memory ?: return
        val r = b.toSave(clock(), wallClock()) ?: return
        runCatching { m.save(key, ConnectionMemory.encode(r)) }
    }

    private fun stepped(key: String, e: Entry, b: ConnectionBudget, why: String) {
        val label = if (key == LedgerKey.TORBOX) "TorBox" else "server"
        Log.i(TAG, "$label: thumbnail level ${b.level(playbackOf(e), yielding(clock()))} above playback ($why)" +
            (b.cap?.let { ", cap $it" } ?: ""))
        persist(key, b)
    }

    inner class Slot internal constructor(
        key: String?,
        private val kind: Kind,
        val startedAtMs: Long,
        private var busy: Boolean,
    ) {
        private var key: String? = key
        private var closed = false

        /** The request was redirected: it counts for the server that answers. Never a step down for the overshoot. */
        fun moveTo(url: String?) {
            runCatching {
                val to = LedgerKey.of(url ?: return) ?: return
                lock.withLock {
                    val from = key
                    if (closed || to == from) return
                    from?.let { entries[it] }?.let { remove(it, kind) }
                    add(entry(to), kind)
                    key = to
                    busy = false
                }
            }
        }

        fun succeeded() {
            if (kind != Kind.THUMBNAIL) return
            lock.withLock {
                val k = key ?: return
                val e = entries[k] ?: return
                val b = budgetOf(k, e)
                val now = clock()
                val healthy = now < healthyUntilMs || yielding(now)
                if (b.succeeded(startedAtMs, now, healthy, busy, playbackOf(e), yielding(now))) stepped(k, e, b, "clean fetches")
                else persist(k, b)
            }
        }

        /** Counts [e] if the server turned the request away. Never throws. */
        fun failed(e: Throwable) {
            runCatching { refusalOf(e)?.let { refused(it) } }
        }

        fun refused(refusal: Refusal) {
            runCatching {
                lock.withLock {
                    val k = key ?: return
                    val e = entries[k] ?: return
                    val now = clock()
                    if (kind == Kind.THUMBNAIL) {
                        val b = budgetOf(k, e)
                        if (b.refused(refusal, startedAtMs, now, playbackOf(e), yielding(now))) {
                            stepped(k, e, b, "thumbnail request turned away (${refusal.name.lowercase()})")
                        }
                    } else if (!troubleIgnored(now)) {
                        val b = e.budget ?: return
                        if (b.playbackTrouble(refusal, now, now, playbackOf(e), yielding(now))) {
                            stepped(k, e, b, "playback turned away soon after a step up")
                        }
                    }
                }
            }
        }

        fun close() {
            runCatching {
                lock.withLock {
                    if (closed) return
                    closed = true
                    key?.let { entries[it] }?.let { remove(it, kind) }
                }
            }
        }
    }
}

internal class PrefsLedgerMemory(context: Context) : ConnectionLedger.Memory {
    private val prefs = context.getSharedPreferences("thumb_connections", Context.MODE_PRIVATE)

    override fun load(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun save(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun forget(keys: Collection<String>) {
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
    }
}
