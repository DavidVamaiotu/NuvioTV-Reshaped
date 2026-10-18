// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The player's side of a copy pass: its source list, and links to read a copy from. */
internal interface CopySources {
    /** The playing title's source list as copy offers; null while it is not loaded yet. */
    suspend fun offers(): CopyOffers?

    /**
     * A link to read [offer] from, or null. Only a cached TorBox copy may be added to the user's list, and it is
     * removed again through [CopyLink.done].
     */
    suspend fun open(offer: CopyOffer): CopyLink?
}

internal class CopyOffers(val playingText: String, val offers: List<CopyOffer>)

/**
 * Never logged: [url] and [headers] may carry tokens. [via] says how it was reached, for the log. [done] is called once
 * the pass no longer reads it.
 */
internal class CopyLink(val url: String, val headers: Map<String, String>, val via: String, val done: () -> Unit = {})

/** The playing title as the copy pass needs it. */
internal class CopyOwn(
    val identity: String,
    val durationMs: Long,
    val fileLength: Long,
    val pictureW: Int,
    val pictureH: Int,
    /** Keyframe time (us) playback lands on for each own slot, -1 without one. */
    val slotPtsUs: LongArray,
    /** The title's own store, read only here. */
    val store: ThumbStore,
    val anchorSlot: Int,
    /** Height class of the playing picture: no copy above it is used. */
    val maxHeight: Int = 2160,
    /** The title plays from Usenet: its thumbnails come from a copy so the stream itself is spared. */
    val usenet: Boolean = false,
    /** The generating screen counts every [screenStride]th own slot: those are made first. 1: no such order. */
    val screenStride: Int = 1,
)

/** A copy's pictures, ready for the picture check: its store and its timing against the playing title. */
internal class CopySet(val store: ThumbStore, val ratio: Double, val slotCount: Int, val label: String)

/**
 * 4K thumbnails from a smaller copy of the same title in the player's own source list, and a Usenet title's from a
 * copy that is not on Usenet (so the stream is not read for them). The copy's pictures go to a
 * store of their own (same title, keyed by the copy's length and the picture size, never the copy file's own store),
 * fitted to the 4K title's picture size and taken at the keyframes nearest to where a 4K seek lands. The session checks them against its own pictures and copies them over as another release.
 *
 * One request at a time, only while playback can spare it, at background priority; on small boxes behind the
 * dropped-frame [pacer] while playing. Behind the generating screen ([screenUp], playback held) several at once.
 */
internal class CopyPass(
    private val context: Context,
    private val own: CopyOwn,
    private val sources: CopySources,
    private val playerProvider: () -> PreviewPlayer?,
    private val lastStallAtMs: () -> Long,
    /** A 4K decoder of the session is decoding or holds its memory. */
    private val ownDecoderBusy: () -> Boolean,
    private val pacer: PlaybackDecodePacer?,
    private val screenUp: () -> Boolean = { false },
) {
    private companion object {
        const val TAG = "ThumbCopy"
        const val SPACING_MS = SeekThumbnails.SPACING_MS
        val STRIDES = listOf(30, 6, 3, 1)
        const val LOCAL_BEFORE_SLOTS = 60
        const val LOCAL_AFTER_SLOTS = 120
        const val LIST_WAIT_MS = 5L * 60 * 1000
        const val LIST_POLL_MS = 5_000L
        const val OPEN_TIMEOUT_MS = 45_000L
        const val MAX_OPENS = 4
        const val MAX_BYTES = 200L * 1024 * 1024
        const val READY_PICTURES = 12
        const val FULL_AHEAD_MS = 14_000L
        const val QUIET_AFTER_STALL_MS = 60_000L
        const val TROUBLE_WINDOW_MS = 20_000L
        const val PLAYBACK_429_QUIET_MS = 60_000L
        const val PACE_PAUSED_MS = 150L
        const val PACE_STRONG_PLAYING_MS = 300L
        const val SMALL_MEM_FRAMES = 8
        const val READ_TRIES = 3
        const val NO_SLOT_TRIES = 10
        const val MAX_REJECT_LOGS = 12
        const val PROGRESS_LOG_EVERY = 60
    }

    enum class State { LOOKING, FILLING, WAITING, DONE, STOPPED }

    private enum class Outcome { FILLED, UNUSABLE, REJECTED, STOP }

    private class Unusable(why: String) : Exception(why)
    private class Fetch(
        val m: Int, val kf: Int, val extractor: KeyframeExtractor, val shared: Boolean,
        val result: Deferred<Result<ByteArray?>>,
    )
    private class Halt(why: String) : Exception(why)

    @Volatile var state = State.LOOKING
        private set
    /** Set once enough pictures are made for the check; cleared when the pass moves on to another copy. */
    @Volatile var ready: CopySet? = null
        private set
    /** Copy slots stored so far, all copies tried. */
    @Volatile var made = 0
        private set

    val filling: Boolean get() = state == State.FILLING
    val ended: Boolean get() = state == State.DONE || state == State.STOPPED

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val blocks = CopyHostBlocks.app
    @Volatile private var job: Job? = null
    val workerJob: Job? get() = job
    /** The store the pass writes now; verdicts about any other store are old and ignored. */
    @Volatile private var currentKey: String? = null
    @Volatile private var reader: HttpRangeReader? = null
    @Volatile private var stopping = false
    @Volatile private var rejectedKey: String? = null
    @Volatile private var confirmedKey: String? = null
    @Volatile private var undecidedKey: String? = null
    @Volatile private var verdictWhy = ""
    @Volatile private var requestStartAt = 0L
    @Volatile private var requestEndAt = 0L
    private var seenStallAt = 0L
    private var quietUntil = 0L
    private var lastPlaying = false
    private var stopWhy: String? = null

    fun start() {
        seenStallAt = lastStallAtMs()
        job = scope.launch {
            try {
                run()
            } catch (ce: CancellationException) {
                if (!ended) state = State.STOPPED
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "ended: ${t.javaClass.simpleName}: ${t.message}")
                state = State.STOPPED
            }
        }
    }

    fun stop(why: String) {
        if (stopping) return
        stopping = true
        if (!ended) Log.i(TAG, "stopped: $why ($made thumbnails from copies)")
        reader?.close()
        job?.cancel()
        if (!ended) state = State.STOPPED
    }

    fun cancelReads() {
        reader?.cancel()
    }

    /** The picture check found [key]'s pictures to be another picture: the next copy is tried. */
    fun rejected(key: String, why: String) {
        if (key != currentKey) return
        verdictWhy = why
        rejectedKey = key
        reader?.cancel()
    }

    fun confirmed(key: String) {
        if (key == currentKey) confirmedKey = key
    }

    /** The check could not decide on [key]: no more pictures are made from copies this session. */
    fun undecided(key: String, why: String) {
        if (key != currentKey) return
        verdictWhy = why
        undecidedKey = key
    }

    private fun isRejected(): Boolean = currentKey != null && rejectedKey == currentKey

    private suspend fun run() {
        MemoryGovernor.init(context)
        Log.i(TAG, "looking for ${if (own.usenet) "a copy of this Usenet title" else "a smaller copy of this 4K title"} " +
            "(${own.store.slots().size} own thumbnails stored, ${own.durationMs / 1000} s)")
        val offers = awaitOffers() ?: return end(State.STOPPED, "the player's source list did not load")
        val usable = ArrayList<CopyOffer>()
        var logged = 0
        for (o in offers.offers) {
            val why = CopyChoice.rejection(o, offers.playingText, own.durationMs, own.maxHeight, own.usenet)
            if (why == null) usable += o else if (logged++ < MAX_REJECT_LOGS) Log.i(TAG, "not used: ${o.label} - $why")
        }
        val ranked = CopyChoice.order(usable, own.usenet)
        if (ranked.isEmpty()) return end(State.DONE, "no copy of this title among ${offers.offers.size} sources fits")
        Log.i(TAG, "${ranked.size} possible copies, in order: " + ranked.take(5).joinToString("; ") { describe(it) })
        var checks = 0
        var opens = 0
        for (offer in ranked) {
            if (stopping || checks >= CopyChoice.MAX_TRIES || opens >= MAX_OPENS) break
            var opened: CopyLink? = null
            val link = runCatching { withTimeoutOrNull(OPEN_TIMEOUT_MS) { sources.open(offer).also { opened = it } } }
                .onFailure {
                    if (it is CancellationException) {
                        opened?.let { o -> runCatching { o.done() } }
                        throw it
                    }
                }.getOrNull()
            if (link == null) {
                opened?.let { runCatching { it.done() } }
                Log.i(TAG, "${offer.label}: no link")
                continue
            }
            opens++
            Log.i(TAG, "trying ${describe(offer)} via ${link.via}")
            val outcome = try {
                tryCopy(offer, link)
            } finally {
                runCatching { link.done() }
            }
            when (outcome) {
                Outcome.FILLED -> when (awaitVerdict()) {
                    Outcome.FILLED -> return end(State.DONE, "${offer.label}: confirmed by pictures")
                    Outcome.REJECTED -> checks++
                    else -> return end(State.STOPPED, stopWhy ?: "the picture check could not decide ($verdictWhy)")
                }
                Outcome.REJECTED -> checks++
                Outcome.STOP -> return end(State.STOPPED, stopWhy ?: "stopped")
                Outcome.UNUSABLE -> Unit
            }
            ready = null
            currentKey = null
            state = State.LOOKING
        }
        val ownFile = if (own.usenet) "the Usenet stream" else "the 4K file"
        end(State.DONE, if (checks > 0) "no copy matched the pictures of $ownFile - $ownFile makes the rest" else "no copy could be used")
    }

    private fun end(s: State, why: String) {
        if (ended) return
        Log.i(TAG, "${if (s == State.DONE) "done" else "stopped"}: $why ($made thumbnails from copies)")
        state = s
    }

    private fun describe(o: CopyOffer): String =
        "${o.label} [${o.kind.name.lowercase()}, ${o.height}p ${o.codec.name}${if (o.hdr) " HDR" else ""}" +
            "${if (o.web) " WEB" else ""}${o.sizeBytes?.let { ", ${it / (1024 * 1024)} MB" } ?: ""}" +
            "${o.durationMs?.let { ", ${it / 1000} s" } ?: ""}]"

    private suspend fun awaitOffers(): CopyOffers? {
        val deadline = SystemClock.elapsedRealtime() + LIST_WAIT_MS
        var said = false
        while (!stopping && SystemClock.elapsedRealtime() < deadline) {
            val o = runCatching { sources.offers() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            if (o != null) return o
            if (!said) {
                said = true
                Log.d(TAG, "waiting for the player's source list")
            }
            delay(LIST_POLL_MS)
        }
        return null
    }

    private suspend fun awaitVerdict(): Outcome {
        val key = currentKey ?: return Outcome.STOP
        state = State.WAITING
        Log.i(TAG, "copy made ($made thumbnails), waiting for the picture check")
        while (!stopping) {
            when (key) {
                confirmedKey -> return Outcome.FILLED
                rejectedKey -> {
                    Log.i(TAG, "the picture check turned the copy down ($verdictWhy)")
                    return Outcome.REJECTED
                }
                undecidedKey -> return Outcome.STOP
            }
            delay(2_000L)
        }
        return Outcome.STOP
    }

    private suspend fun tryCopy(offer: CopyOffer, link: CopyLink): Outcome {
        val host = RateLimitedHosts.hostOf(link.url)
        host?.let { h -> blocks.blocked(h, System.currentTimeMillis())?.let { why ->
            Log.i(TAG, "${offer.label}: that server is left alone for now ($why)")
            return Outcome.UNUSABLE
        } }
        if (own.usenet && PreviewSourcePolicy.isUsenet(link.url)) {
            Log.i(TAG, "${offer.label}: not used - a Usenet stream")
            return Outcome.UNUSABLE
        }
        if (!awaitReady(null, host)) return Outcome.STOP
        val r = HttpRangeReader(link.url, link.headers, thumbnailHttpClient(link.headers),
            slotWaitMs = ConnectionLedger.START_SLOT_WAIT_MS)
        reader = r
        try {
            val t0 = SystemClock.elapsedRealtime()
            val head = request(host) { r.read(0, MkvIndexReader.HEAD_BYTES) }
            val fileLength = r.totalLength
            // A Usenet title may borrow the same release from elsewhere, never through another Usenet stream.
            if (own.usenet) {
                if (PreviewSourcePolicy.isUsenet(r.resolvedUrl)) throw Unusable("a Usenet stream")
            } else if (fileLength == own.fileLength) {
                throw Unusable("the same file as playback")
            }
            val mkv = MkvIndexReader.looksLikeMatroska(head)
            if (!mkv && !Mp4IndexReader.looksLikeMp4(head)) throw Unusable("not MKV or MP4")
            var idx: MediaIndex? = null
            val (durationUs, video) = if (mkv) {
                request(host) { MkvIndexReader.peek(r, head) }.let { it.durationUs to it.video }
            } else {
                request(host) { Mp4IndexReader.read(r, head) }.also { idx = it }.let { it.durationUs to it.video }
            }
            val copyMs = durationUs / 1000L
            val ratio = ReleaseMatch.ratio(own.durationMs, copyMs)
                ?: throw Unusable("length ${copyMs / 1000} s against ${own.durationMs / 1000} s")
            if (MemoryGovernor.tierFor(video.width, video.height) != DecodeTier.SW_UP_TO_1080P) {
                throw Unusable("${video.width}x${video.height} is not a smaller copy")
            }
            if (CopyChoice.heightClass(video.width, video.height) > own.maxHeight) {
                throw Unusable("${video.width}x${video.height} is above the playing ${own.maxHeight}p")
            }
            val natural = ThumbGeometry.outputSize(video.width, video.height, 1, 1, video.displayAspect)
            CopyFit.plan(natural[0], natural[1], own.pictureW, own.pictureH)
                ?: throw Unusable("picture shape ${natural[0]}x${natural[1]} against ${own.pictureW}x${own.pictureH}")
            val index = idx ?: request(host) { MkvIndexReader.read(r, head) }
            val timing = if (ratio == 1.0) "same length" else "PAL ratio %.4f".format(ratio)
            Log.i(TAG, "${offer.label}: ${video.codec} ${video.width}x${video.height}, ${copyMs / 1000} s ($timing), " +
                "${index.keyframes.count} keyframes, header and index ${r.bytes / 1024} KB in " +
                "${SystemClock.elapsedRealtime() - t0} ms")
            val rgb565 = runCatching { PreviewPreferences.rgb565(context) }.getOrDefault(true)
            val key = ThumbStore.copyKeyFor(own.identity, fileLength, own.pictureW, own.pictureH, PreviewStore.digest(head))
            if (own.store.verdictOn(key) == false) throw Unusable("its pictures did not match this title before")
            if (ThumbStore.isBusy(key)) throw Unusable("another pass is writing its thumbnails")
            ThumbStore.markBusy(key)
            val store = runCatching { ThumbStore.openKey(context, key, rgb565) }.getOrElse {
                ThumbStore.unmarkBusy(key)
                throw it
            }
            if (store.isMemoryOnly) {
                ThumbStore.unmarkBusy(key)
                stopWhy = "storage nearly full"
                return Outcome.STOP
            }
            r.slotWaitMs = ConnectionLedger.SLOT_WAIT_MS
            currentKey = key
            try {
                store.noteDuration(copyMs)
                if (store.cachedKeyframeIndex() == null) store.setKeyframeIndex(KeyframeIndexCodec.encode(index))
                return fill(offer, r, index, store, ratio, copyMs, host)
            } finally {
                store.flush()
                ThumbStore.unmarkBusy(store.key)
            }
        } catch (e: Unusable) {
            Log.i(TAG, "${offer.label}: not used - ${e.message}")
            return Outcome.UNUSABLE
        } catch (e: Halt) {
            stopWhy = e.message
            return Outcome.STOP
        } catch (e: IOException) {
            if (stopping) return Outcome.STOP
            Log.i(TAG, "${offer.label}: not used - reads failed (${e.javaClass.simpleName})")
            return Outcome.UNUSABLE
        } finally {
            reader = null
            r.close()
        }
    }

    private suspend fun fill(
        offer: CopyOffer, r: HttpRangeReader, idx: MediaIndex, store: ThumbStore, ratio: Double, copyMs: Long, host: String?,
    ): Outcome {
        val copyCount = ReleaseMatch.slotCount(copyMs, SPACING_MS)
        val owners = CopyChoice.ownSlotsByCopySlot(own.slotPtsUs.size, ratio, copyCount)
        val pts = idx.keyframes.ptsUs
        // The copy's keyframe nearest to where a 4K seek lands, not to the slot centre.
        val kfOf = IntArray(copyCount) { -1 }
        for ((m, served) in owners) {
            val s = UsenetCopy.pictureFor(served, own.screenStride)
            val landing = own.slotPtsUs[s].takeIf { it >= 0 } ?: (s * SPACING_MS * 1000L + SPACING_MS * 500L)
            kfOf[m] = idx.keyframes.nearest((landing * ratio).toLong())
        }
        val kfSlots = HashMap<Int, MutableList<Int>>()
        kfOf.forEachIndexed { m, kf -> if (kf >= 0) kfSlots.getOrPut(kf) { ArrayList() }.add(m) }
        val anchor = ReleaseMatch.mapSlot(own.anchorSlot, ratio, copyCount).coerceAtLeast(0)
        val order = UsenetCopy.screenFirst(
            SeekGrid.latticeOrder(copyCount, STRIDES, anchor, LOCAL_BEFORE_SLOTS, LOCAL_AFTER_SLOTS), owners, own.screenStride)
        val coarse = (0 until copyCount step STRIDES.first()).toList()
        val extractors = ArrayList<KeyframeExtractor>()
        val spare = ArrayDeque<KeyframeExtractor>()
        fun bytesRead() = extractors.sumOf { it.bytesRead }
        val decoder = FfmpegThumbDecoder()
        val attempts = HashMap<Int, Int>()
        val dv5 = idx.video.dolbyVision?.profile == 5
        val smallBox = !MemoryGovernor.isStrongBox
        var pictures = 0
        val t0 = SystemClock.elapsedRealtime()
        state = State.FILLING
        Log.i(TAG, "making thumbnails from ${offer.label} (${order.size} slots, ${store.slots().size} stored already)" +
            if (pacer != null) ", behind the dropped-frame pacer while playing" else "")
        try {
            val end: Outcome? = coroutineScope {
                val pending = ArrayDeque<Fetch>()
                val again = ArrayDeque<Int>()
                val underWay = HashSet<Int>()
                val refused = AtomicBoolean(false)
                var next = 0
                var out: Outcome? = null
                try {
                    work@ while (true) {
                        if (stopping) {
                            out = Outcome.STOP
                            break@work
                        }
                        if (isRejected()) {
                            out = Outcome.REJECTED
                            break@work
                        }
                        if (undecidedKey == store.key) {
                            out = Outcome.STOP
                            break@work
                        }
                        if (bytesRead() >= MAX_BYTES) {
                            Log.i(TAG, "byte limit reached (${bytesRead() / (1024 * 1024)} MB)")
                            break@work
                        }
                        val lanes = UsenetCopy.fetches(screenUp(), lastPlaying, ConnectionLedger.app.thumbnailCap(r.resolvedUrl),
                            smallBox, rateLimited = RateLimitedHosts.appSession.contains(r.resolvedUrl))
                        if (lanes == 1 && pending.size > 1 && !refused.get()) {
                            // Back to one at a time (the screen went down): the queued pictures are fetched again later.
                            for (f in pending.asReversed()) {
                                f.result.cancel()
                                attempts[f.kf] = ((attempts[f.kf] ?: 1) - 1).coerceAtLeast(0)
                                underWay -= f.kf
                                again.addFirst(f.m)
                            }
                            pending.clear()
                            r.cancel()
                        }
                        while (!refused.get() && pending.size < lanes && bytesRead() < MAX_BYTES) {
                            val retried = again.isNotEmpty()
                            val m = if (retried) again.first() else order.getOrNull(next) ?: break
                            val kf = kfOf[m]
                            if (kf >= 0 && kf in underWay) break
                            val served = owners[m]
                            if (retried) again.removeFirst() else next++
                            if (kf < 0 || store.has(slotUs(m)) || served == null ||
                                served.all { own.store.has(slotUs(it)) } || (attempts[kf] ?: 0) >= 2) continue
                            if (!awaitReady(decoder, host)) {
                                out = if (isRejected()) Outcome.REJECTED else Outcome.STOP
                                break@work
                            }
                            attempts[kf] = (attempts[kf] ?: 0) + 1
                            val ex = spare.removeFirstOrNull() ?: KeyframeExtractor(idx, r).also { extractors += it }
                            underWay += kf
                            pending += Fetch(m, kf, ex, shared = lanes > 1, async(Dispatchers.IO) {
                                runCatching { fetchKeyframe(ex, kf, host) }.also { if (it.exceptionOrNull() is Halt) refused.set(true) }
                            })
                        }
                        val f = pending.removeFirstOrNull() ?: break@work
                        val got = f.result.await()
                        underWay -= f.kf
                        spare += f.extractor
                        val failure = got.exceptionOrNull()
                        if (failure != null) {
                            if (failure is CancellationException) throw failure
                            if (failure is Halt && isRejected()) {
                                out = Outcome.REJECTED
                                break@work
                            }
                            throw failure
                        }
                        val au = got.getOrNull() ?: continue@work
                        // Fetched next to others, or swap ran low meanwhile: playback, memory and the other decoder
                        // are asked again before decoding.
                        val swapLow = !MemoryGovernor.swapAllows(funded = false).allowed
                        if ((f.shared || swapLow) && !awaitReady(decoder, host)) {
                            out = if (isRejected()) Outcome.REJECTED else Outcome.STOP
                            break@work
                        }
                        if (swapLow && !MemoryGovernor.swapAllows(funded = false).allowed) continue@work
                        val kf = f.kf
                        val playing = lastPlaying
                        val frame = paced(playing) { atBackground { decoder.decode(au, idx.video, threads = 1) } }
                            ?: continue@work
                        if (dv5 && !frame.colour.doviIpt) throw Unusable("Dolby Vision 5 without decoder metadata")
                        val decision = ColourClassifier.classify(idx.video.colour, frame.colour, idx.video.dolbyVision)
                        val size = ThumbGeometry.outputSize(frame.width, frame.height, frame.sarNum, frame.sarDen,
                            idx.video.displayAspect)
                        val plan = CopyFit.plan(size[0], size[1], own.pictureW, own.pictureH) ?: continue@work
                        val rendered = paced(playing) {
                            atBackground { decoder.render(frame, decision, plan.renderW, plan.renderH) }
                        } ?: continue@work
                        val slots = kfSlots[kf].orEmpty().filter { !store.has(slotUs(it)) }
                        if (slots.isEmpty()) continue@work
                        paced(playing) {
                            atBackground { store.put(LongArray(slots.size) { slotUs(slots[it]) }, pts[kf], compose(rendered, plan)) }
                        }
                        made += slots.size
                        pictures++
                        if (!MemoryGovernor.isStrongBox) store.trimMemory(SMALL_MEM_FRAMES)
                        if (ready == null && (pictures >= READY_PICTURES || coarse.all { store.has(slotUs(it)) })) {
                            ready = CopySet(store, ratio, copyCount, offer.label)
                        }
                        if (pictures % PROGRESS_LOG_EVERY == 0) {
                            Log.i(TAG, "$pictures thumbnails from ${offer.label} in ${(SystemClock.elapsedRealtime() - t0) / 1000} s, " +
                                "${bytesRead() / (1024 * 1024)} MB")
                        }
                        delay(pace(playing))
                    }
                } finally {
                    if (pending.isNotEmpty()) {
                        pending.forEach { it.result.cancel() }
                        r.cancel()
                    }
                }
                out
            }
            if (end != null) return end
        } finally {
            runCatching { decoder.close() }
        }
        if (pictures == 0 && store.slots().isEmpty()) {
            Log.i(TAG, "${offer.label}: no thumbnail could be made")
            return Outcome.UNUSABLE
        }
        if (ready == null) ready = CopySet(store, ratio, copyCount, offer.label)
        Log.i(TAG, "${offer.label}: $pictures thumbnails in ${(SystemClock.elapsedRealtime() - t0) / 1000} s, " +
            "${bytesRead() / (1024 * 1024)} MB, stored=${store.slots().size} of $copyCount")
        return Outcome.FILLED
    }

    private fun slotUs(slot: Int): Long = slot.toLong() * SPACING_MS * 1000L

    private fun pace(playing: Boolean): Long = when {
        !playing -> if (screenUp()) 0L else PACE_PAUSED_MS
        pacer != null -> synchronized(pacer) { pacer.paceMs }
        else -> PACE_STRONG_PLAYING_MS
    }

    private fun compose(src: Bitmap, plan: CopyFit.Plan): Bitmap {
        if (plan.top == 0 && src.width == own.pictureW && src.height == own.pictureH) return src
        val out = Bitmap.createBitmap(own.pictureW, own.pictureH, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(src, 0f, plan.top.toFloat(), null)
        }
        src.recycle()
        return out
    }

    /** A copy request; a server that turns it away is left alone ([CopyHostBlocks]) and the pass stops. */
    private fun <T> request(host: String?, block: () -> T): T {
        requestStartAt = SystemClock.elapsedRealtime()
        try {
            return block()
        } catch (e: UnsupportedMediaException) {
            throw Unusable(e.message ?: "unsupported file")
        } catch (e: ConnectionLedger.NoSlotException) {
            throw e
        } catch (e: IOException) {
            if (stopping) throw e
            val status = e as? HttpRangeReader.HttpStatusException
            val refused = e is RateLimitedException || ConnectionLedger.refusalOf(e) != null ||
                status?.tooMany == true || status?.code == 401 || status?.code == 403
            if (refused) {
                (e as? RateLimitedException)?.let { RateLimitedHosts.appSession.note(it) }
                host?.let { blocks.refused(it, System.currentTimeMillis()) }
                throw Halt("the server turned a copy request away (${status?.let { "HTTP ${it.code}" } ?: e.javaClass.simpleName})" +
                    " - left alone for ${CopyHostBlocks.REFUSED_MS / 60_000} min")
            }
            if (status?.code == 404 || status?.code == 410) throw Unusable("link gone (HTTP ${status.code})")
            throw e
        } finally {
            requestEndAt = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun fetchKeyframe(ex: KeyframeExtractor, kf: Int, host: String?): ByteArray? {
        var failures = 0
        var noSlot = 0
        while (true) {
            try {
                return request(host) { ex.extract(kf) }
            } catch (e: ConnectionLedger.NoSlotException) {
                if (stopping) throw Halt("stopped")
                if (++noSlot >= NO_SLOT_TRIES) throw Halt("no connection free on that server")
                delay(2_000L)
            } catch (e: IOException) {
                if (stopping || isRejected()) throw Halt("stopped")
                if (++failures >= READ_TRIES) throw Unusable("reads keep failing (${e.javaClass.simpleName})")
                delay(2_000L * failures)
            }
        }
    }

    private class Sample(
        val playing: Boolean, val buffering: Boolean, val loading: Boolean, val aheadMs: Long, val toEnd: Boolean,
        val failed: Boolean,
    )

    private suspend fun sample(): Sample? = withContext(Dispatchers.Main) {
        val p = playerProvider() ?: return@withContext null
        val duration = if (p.duration == C.TIME_UNSET) 0L else p.duration
        Sample(
            playing = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            loading = p.isLoading,
            aheadMs = p.bufferedPosition - p.currentPosition,
            toEnd = duration > 0 && p.bufferedPosition >= duration - 1_000L,
            failed = ConnectionLedger.sourceTrouble(p.playerError?.errorCode),
        )
    }

    /**
     * Waits until playback can spare a copy request and a decode. False when the pass has to stop: stopped, the copy
     * was turned down, or playback had trouble soon after a copy request.
     */
    private suspend fun awaitReady(decoder: FfmpegThumbDecoder?, host: String?): Boolean {
        var waitingFor: String? = null
        while (true) {
            if (stopping || isRejected()) return false
            val now = SystemClock.elapsedRealtime()
            val s = sample()
            val stall = lastStallAtMs()
            if (s?.failed == true || stall > seenStallAt) {
                val newStall = stall > seenStallAt
                val at = if (newStall) stall else now
                if (newStall) seenStallAt = stall
                if (blocks.blames(requestStartAt, requestEndAt, at, TROUBLE_WINDOW_MS)) {
                    host?.let { blocks.trouble(it, System.currentTimeMillis()) }
                    stopWhy = "playback ${if (newStall) "stalled" else "failed"} " +
                        "${((at - requestEndAt).coerceAtLeast(0L)) / 1000} s after a copy request - " +
                        "that server is left alone for the rest of the day"
                    return false
                }
                if (newStall) quietUntil = at + QUIET_AFTER_STALL_MS
            }
            val playback429 = ThumbRateLimit.playbackClampedNow() ||
                (PreviewHostSignals.hudClampLastHitAtMs > 0 &&
                    SystemClock.uptimeMillis() - PreviewHostSignals.hudClampLastHitAtMs < PLAYBACK_429_QUIET_MS)
            var memory = false
            val why = when {
                now < quietUntil -> "a quiet minute after a playback stall"
                s?.failed == true -> "a playback error"
                playback429 -> "playback was rate limited"
                s == null -> null
                s.buffering -> "playback buffering"
                s.playing && !(s.aheadMs >= ConnectionLedger.HEALTHY_AHEAD_MS || s.toEnd ||
                    (s.aheadMs >= FULL_AHEAD_MS && !s.loading)) -> "a healthy playback buffer"
                pacer != null && s.playing && !synchronized(pacer) { pacer.allows(settleTarget = false) } ->
                    "a pause (dropped frames or a stall stopped copy decodes while playing)"
                decoder != null && !MemoryGovernor.isStrongBox && ownDecoderBusy() -> {
                    decoder.trim()
                    "the 4K thumbnail decoder to be let go"
                }
                decoder != null -> MemoryGovernor.allow(DecodeTier.SW_UP_TO_1080P, decoderWarm = decoder.warm, addressSpace = true)
                    .takeIf { !it.allowed }?.let {
                        memory = true
                        decoder.trim()
                        "memory (${it.reason})"
                    }
                else -> null
            }
            if (why == null) {
                if (waitingFor != null) Log.d(TAG, "carrying on (waited for $waitingFor)")
                lastPlaying = s?.playing == true
                return true
            }
            if (why != waitingFor) {
                Log.d(TAG, "waiting for $why")
                waitingFor = why
            }
            delay(if (memory) 5_000L else 2_000L)
        }
    }

    private inline fun <T> paced(playing: Boolean, block: () -> T): T {
        val p = pacer
        if (p == null || !playing) return block()
        synchronized(p) { p.decodeStarted(SystemClock.elapsedRealtime()) }
        try {
            return block()
        } finally {
            synchronized(p) { p.decodeEnded(SystemClock.elapsedRealtime()) }
        }
    }

    private inline fun <T> atBackground(block: () -> T): T {
        val tid = android.os.Process.myTid()
        val before = runCatching { android.os.Process.getThreadPriority(tid) }.getOrNull()
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            return block()
        } finally {
            if (before != null) runCatching { android.os.Process.setThreadPriority(before) }
        }
    }
}
