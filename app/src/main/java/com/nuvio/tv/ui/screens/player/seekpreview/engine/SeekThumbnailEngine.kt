// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** What the player hands the engine (ExoPlayer only). */
internal class ThumbSource(
    val url: String,
    /** Playback's request headers; never logged. */
    val headers: Map<String, String>,
    /** videoId (imdb:season:episode for episodes), else contentId or title. */
    val identity: String,
    /** File size if the stream states it; lets a saved store be opened before any network access. */
    val fileSizeHint: Long? = null,
    /** False for an autoplayed next episode: it is never held before play. */
    val holdBeforePlay: Boolean = true,
    /** Connections playback opens to the stream (the Parallel connections setting, 1 without it). */
    val playbackConnections: Int = 2,
    /** [identity] is the videoId, so it names this exact title or episode; other releases are reused only then. */
    val identityExact: Boolean = false,
    /** The player's source list, for a 4K or Usenet title's thumbnails from a copy; null where there is none. */
    val copySources: CopySources? = null,
    /** The stream is an AIOStreams Usenet stream (its link or its add-on says so). */
    val usenet: Boolean = false,
    /** A media server's stream (Silo, Jellyfin, Emby) without thumbnails of its own; see [MediaServerThumbs]. */
    val mediaServer: Boolean = false,
    /** Where the stream comes from and the player settings, for the "gave up" line; no link, host or name. */
    val kindNote: () -> String = { "addon HTTP" },
    val settingsNote: () -> String = { "" },
)

/** Seek thumbnails: one keyframe per slot, fetched by range request, decoded with the in-APK FFmpeg, kept per title. */
internal object SeekThumbnails {
    private const val TAG = "ThumbWorker"
    /** One slot per 10 s, each served by the nearest real keyframe. */
    const val SPACING_MS = 10_000L
    private val housekeepingScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val STARTUP_HOUSEKEEPING_DELAY_MS = 60_000L
    private const val SPOOL_DIR = "spool"
    private const val MB = 1024L * 1024L

    /** Bumped whenever a bitmap lands in memory; the pane keys recomposition on it. */
    val tick = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var session: Session? = null
    /** The media server's own thumbnails; while set, every picture call goes to it and no own session runs. */

    private val sessionLock = Any()

    /** [handBackPlayer] is false when the player screen is closing: never resume a player that is going away. */
    fun stopSession(handBackPlayer: Boolean = true) {
        val s = synchronized(sessionLock) { session.also { session = null } }
        if (s != null) ending = s
        s?.stop(handBackPlayer)
    }

    @Volatile private var ending: Session? = null

    /** Waits (bounded) for an ended session's worker. Reads are cancelled repeatedly: an index read is several in a row. */
    private suspend fun awaitStoppedSession() {
        val s = ending ?: return
        val j = s.workerJob
        val c = s.copyJob
        if (j != null || c != null) {
            withTimeoutOrNull(10_000L) {
                while (j?.isCompleted == false || c?.isCompleted == false) {
                    s.cancelReads()
                    delay(200L)
                }
            }
        }
        if (ending === s && (j == null || j.isCompleted)) ending = null
    }

    /** "Clear saved thumbnails": no session may write while the files are deleted. */
    internal suspend fun stopForClear() {
        stopSession()
        awaitStoppedSession()
    }

    /** The player screen is resumed; playback is never restarted while it is in the background. */
    @Volatile var hostResumed = true
        set(value) {
            if (field && !value) {
                hostPausedAt = SystemClock.elapsedRealtime()
                session?.notePrepareLeft()
            }
            field = value
        }

    /** When the player screen last went to the background (elapsedRealtime), 0 = never. */
    @Volatile var hostPausedAt = 0L
        private set

    /** Player screen closed: an unfinished 4K coverage pass carries on for a few minutes, anything else stops. */
    fun playerClosed() {
        prepareUi.value = PrepareUi.Hidden
        val s = session ?: return
        if (!s.finishAfterPlayback()) stopSession(handBackPlayer = false)
    }

    private fun sessionEnded(s: Session) {
        ending = s
        synchronized(sessionLock) { if (session === s) session = null }
    }

    /**
     * Starts a session for [source] once the player has a format and duration (waits up to 30 s).
     * [skipReason] is asked once the duration is known; non-null means this stream gets no thumbnails.
     */
    internal suspend fun startWhenEligible(
        context: Context,
        source: ThumbSource,
        playerProvider: () -> PreviewPlayer?,
        reserveProvider: () -> PlaybackBufferReserve? = { null },
        pauseForPrepare: () -> Boolean? = { false },
        skipReason: () -> String? = { null },
    ) {
        if (!isThumbnailSource(source.url)) {
            Log.i(TAG, "skip: not a single-file stream")
            return
        }
        // Marks the title as starting, so a next-episode pass cannot begin writing the same store meanwhile.
        pendingIdentity = source.identity
        try {
            startWhenEligibleInner(context, source, playerProvider, reserveProvider, pauseForPrepare, skipReason)
        } finally {
            if (pendingIdentity == source.identity) pendingIdentity = null
        }
    }

    @Volatile private var pendingIdentity: String? = null

    private suspend fun startWhenEligibleInner(
        context: Context,
        source: ThumbSource,
        playerProvider: () -> PreviewPlayer?,
        reserveProvider: () -> PlaybackBufferReserve?,
        pauseForPrepare: () -> Boolean?,
        skipReason: () -> String?,
    ) {
        NextEpisodeThumbs.stopAndJoinFor(source.identity)
        val t0 = SystemClock.elapsedRealtime()
        var width = 0
        var height = 0
        var video = ""
        var waitingLogged = false
        while (true) {
            skipReason()?.let { PreviewDiagnostics.note("startup cancelled: $it"); return }
            val p = playerProvider()
            val fmt = p?.videoFormat
            if (p != null && fmt != null && fmt.width > 0 && fmt.height > 0 &&
                p.duration != C.TIME_UNSET && p.duration > 0) {
                width = fmt.width
                height = fmt.height
                video = GiveUpNotes.videoNote(fmt.width, fmt.height, fmt.sampleMimeType, fmt.codecs,
                    fmt.colorInfo?.colorTransfer ?: Format.NO_VALUE)
                break
            }
            if (!waitingLogged && SystemClock.elapsedRealtime() - t0 >= 30_000L) {
                waitingLogged = true
                PreviewDiagnostics.note("waiting for playback format/duration; generation remains scheduled")
            }
            delay(if (waitingLogged) 1_000L else 500L)
        }
        PreviewDiagnostics.note("playback ready: ${width}x$height")
        fun skipped(): Boolean = skipReason()?.also { Log.i(TAG, "skip: $it") } != null
        if (skipped()) return
        // Decide on 4K before any network access: a 4K remux's index alone can be several MB.
        val fourK = MemoryGovernor.tierFor(width, height) == DecodeTier.SW_4K
        if (fourK && SeekThumbnailPreferences.mode(context) != SeekThumbMode.ALL) {
            Log.i(TAG, "skip: ${width}x$height and the setting is HD titles only")
            return
        }
        // Loading the native library takes 150-440 ms: never on the main thread.
        while (!withContext(Dispatchers.IO) { ThumbNative.load() }) {
            skipReason()?.let { return }
            PreviewDiagnostics.note("native decoder waiting: ${ThumbNative.state()}")
            delay(5_000L) // ThumbNative owns the bounded retry backoff; playback keeps running.
        }
        PreviewDiagnostics.note("native decoder ready")
        stopSession()
        awaitStoppedSession()          // one writer per store
        withContext(Dispatchers.IO) { MemoryGovernor.initForSession(context) }
        // The stream can be rejected while the decoder loads.
        if (skipped()) return
        val s = Session(context.applicationContext, source, playerProvider, reserveProvider, fourK, pauseForPrepare, video)
        synchronized(sessionLock) { session = s }
        PreviewDiagnostics.note("memory limits: lowRam=${MemoryGovernor.isLowRam} strong=${MemoryGovernor.isStrongBox}")
        s.start()
    }

    fun thumbFor(positionMs: Long): Bitmap? = shownFor(positionMs)?.bitmap

    fun pausesPlaybackWhileScrubbing(): Boolean = session != null

    internal fun isCurrentOrStarting(identity: String): Boolean =
        session?.identity == identity || pendingIdentity == identity

    /** A small box is still working on a 4K title's coverage; the next-episode pass waits for it. */
    internal fun currentSmall4KBusy(): Boolean = session?.small4KBusy() == true

    /** Small boxes, 4K: coverage progress (0-99 %) for the paused hint, else null. */
    fun progressPercent(): Int? = session?.progressPercent()

    /** What the player screen shows for "Generate thumbnails before play". Main-thread Compose state. */
    sealed class PrepareUi {
        object Hidden : PrepareUi()
        object Checking : PrepareUi()
        data class Generating(
            val done: Int, val total: Int, val secondsLeft: Int, val anchorMs: Long, val spacingMs: Long,
            /** Positions (ms) of the newest thumbnails, oldest first. */
            val recent: List<Long> = emptyList(),
            /** Coverage slots in time order: made or not. */
            val coverage: List<Boolean> = emptyList(),
            val recentEndSeq: Int = 0,
        ) : PrepareUi()
        data class Ready(val total: Int) : PrepareUi()
    }

    val prepareUi = kotlinx.coroutines.flow.MutableStateFlow<PrepareUi>(PrepareUi.Hidden)

    /** Applied on Main only if [from] is still current and preparing, so a late post cannot re-show a dismissed overlay. */
    private fun postPrepareUi(from: Session, v: PrepareUi) {
        mainScope.launch {
            val current = session
            val stageOk = when (v) {
                is PrepareUi.Generating -> from.prepareStageNow() == 3
                is PrepareUi.Ready -> from.prepareStageNow() == 4
                else -> true
            }
            val ok = if (v is PrepareUi.Hidden) current == null || current === from
            else current === from && from.prepareActive() && stageOk
            if (ok) prepareUi.value = v
        }
    }

    /** Main thread: "Start watching now" (or Back) on the generating screen. */
    fun startWatchingNow() {
        val s = session
        if (s == null) {
            prepareUi.value = PrepareUi.Hidden
            return
        }
        s.startWatchingNow()
    }

    fun exactThumb(positionMs: Long): Bitmap? = shownFor(positionMs)?.takeIf { it.exact }?.bitmap

    /**
     * A picture for the pane. [ptsMs] is its keyframe's time; [exact] is false for a nearby stand-in. [borrowed]: from
     * another release of the title, not confirmed yet.
     */
    class Shown(
        val bitmap: Bitmap, val ptsMs: Long, val exact: Boolean, val crop: ThumbCrop.Bars?, val borrowed: Boolean = false,
    )

    fun shownFor(positionMs: Long): Shown? {
        return session?.shownFor(positionMs)
    }

    /** Spacing of the seek strip's tiles once the index is read, else null (the single picture shows). */
    fun stripSpacingMs(): Long? {
        return session?.stripSpacingMs()
    }

    /** Strip tile for [positionMs]: memory only, a disk hit loads async. */
    fun tileFor(positionMs: Long): Shown? {
        return session?.tileFor(positionMs)
    }

    /** Loader thread, called per video sample: should the next samples be copied? Must be cheap. */
    internal fun tapWants(timeUs: Long): Boolean = session?.tapWants(timeUs) ?: false

    internal fun tapAccepts(timeUs: Long): Boolean = session?.tapAccepts(timeUs) ?: false

    /** Loader thread: a keyframe sample playback just downloaded. Written to the spool off-thread. */
    internal fun tapOffer(timeUs: Long, sample: ByteArray) {
        session?.tapOffer(timeUs, sample)
    }

    /** Keyframe time of [targetMs]'s slot, so a committed seek lands on the frame the pane shows. */
    fun landingFor(targetMs: Long): Long? = session?.landingFor(targetMs)

    /** The next point of the 10 s grid in the direction of [deltaMs], or null without a session. */
    fun gridStep(baseMs: Long, deltaMs: Long): Long? {
        val s = session
        if (s?.hasSlots != true || deltaMs == 0L) return null
        return s.stepPastBase(SeekGrid.gridPoint(baseMs, deltaMs, SPACING_MS), baseMs, forward = deltaMs > 0)
    }

    fun displayMemoryChargeMb(context: Context, mode: SeekThumbMode): Int {
        MemoryGovernor.init(context)
        val rgb565 = runCatching { PreviewPreferences.rgb565(context) }.getOrDefault(true)
        return MemoryGovernor.displayChargeMb(rgb565, fourK = mode == SeekThumbMode.ALL)
    }

    /** Bytes of saved thumbnails on disk. Call on an IO thread. */
    fun storedBytes(context: Context): Long = ThumbStore.totalBytes(context)

    /** Retention and budget clean-up of stored thumbnails, delayed so it stays off cold start. */
    fun scheduleStartupHousekeeping(context: Context) {
        if (!housekeepingScheduled.compareAndSet(false, true)) return
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            delay(STARTUP_HOUSEKEEPING_DELAY_MS)
            runCatching { ThumbStore.housekeeping(app, activeKey = session?.storeKey) }
            runCatching {   // spools left behind when the process was killed
                val active = session?.spoolDirPath
                File(File(app.cacheDir, ThumbStore.DIR), SPOOL_DIR).listFiles()
                    ?.filter { it.absolutePath != active }?.forEach { it.deleteRecursively() }
            }
                .onFailure { Log.w(TAG, "startup housekeeping failed: ${it.javaClass.simpleName}") }
        }
    }

    /** Width / height of this title's thumbnails while a session runs, else null. Sizes the pane's empty frame. */
    fun frameAspect(): Float? {
        return session?.frameAspect()
    }

    fun notePriority(positionMs: Long) {
        session?.notePriority(positionMs)
    }

    /** Store-key pairs whose pictures did not match in this app run. */
    private val differentReleases = ConcurrentHashMap.newKeySet<String>()

    private fun releasePair(a: String, b: String): String = if (a < b) "$a|$b" else "$b|$a"

    private fun bumpTick() {
        mainScope.launch { tick.value++ }
    }

    /** Snapshot of the player, sampled on the main thread (ExoPlayer is thread-confined). */
    private class PlayerState(
        val isPlaying: Boolean,
        val buffering: Boolean,
        val positionMs: Long,
        val bufferedMs: Long,
        val durationMs: Long,
        val failed: Boolean = false,
        val playWhenReady: Boolean = isPlaying,
        val loading: Boolean = false,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private class Session(
        private val context: Context,
        private val source: ThumbSource,
        private val playerProvider: () -> PreviewPlayer?,
        private val reserveProvider: () -> PlaybackBufferReserve?,
        private val fourK: Boolean,
        /** Main thread: pauses as a user pause, so start-up autoplay leaves it alone. False = not paused. */
        private val pauseForPrepare: () -> Boolean? = { false },
        private val video: String = "",
    ) {
        private companion object {
            const val HEAD_BYTES = MkvIndexReader.HEAD_BYTES
            const val HEAVY_1080P_MIN_BITS_PER_SECOND = 20_000_000L
            /** Above this average bitrate a 4K file is treated as a remux (WEB-DL 4K is ~15-25 Mb/s). */
            const val REMUX_MIN_BITS_PER_SECOND = 35_000_000L
            const val GATE_BUFFER_AHEAD_MS = 14_000L
            const val PLATEAU_WINDOW_MS = 10_000L
            const val PLATEAU_JITTER_MS = 2_000L
            const val PLATEAU_MIN_AHEAD_MS = 8_000L
            /** Coverage-pass byte budget per session. Scrubbing does not count against it. */
            const val LATTICE_MAX_BYTES = 300L * 1024 * 1024
            /** A 2.5 h 4K WEB-DL needs about 500 MB at 10 s, a remux about 320 MB at 30 s. */
            const val LATTICE_4K_MAX_BYTES = 500L * 1024 * 1024
            const val SCRUB_REQUEST_ALLOWANCE = 250
            const val IDLE_TRIM_MS = 10_000L
            const val MAX_ATTEMPTS_PER_KEYFRAME = 2
            /** Network failures in a row before no new thumbnails are fetched; the ones made keep showing. */
            const val MAX_FETCH_FAILURES = 5
            /** Tries of the header and index reads before the session gives up. */
            const val START_READ_TRIES = 4
            /** Header and index reads that found no free connection before the session gives up. */
            const val START_SLOT_TRIES = 4
            const val INDEX_GATE_AHEAD_MS = 10_000L
            const val DEFER_WAIT_PRIORITY_MS = 2_000L
            const val DEFER_WAIT_LATTICE_MS = 5_000L
            /** Arm the tap this long before a wanted keyframe's pts (decode order runs ahead of it). */
            const val TAP_ARM_LEAD_US = 3_000_000L
            /** A tapped keyframe must match an index keyframe this closely, else the timelines differ. */
            const val TAP_MATCH_US = 50_000L
            const val SPOOL_MAX_BYTES = 64L * 1024 * 1024
            const val SPOOL_MAX_PENDING = 3
            const val DEFER_LOG_INTERVAL_MS = 30_000L
            /** A stand-in is at most [fallbackSlots] away: 30 s, or half the finest stride. Farther shows nothing. */
            const val MIN_FALLBACK_SLOTS = 3
            /** Slots decoded either side of the position once the saved pictures are in memory. */
            const val PRELOAD_WARM_SLOTS = 6
            /** A 4K decode needs about 80 MB. Only taken once the read-ahead is [RESERVE_MIN_AHEAD_MS] ahead. */
            const val RESERVE_MIN_BYTES = 96L * 1024 * 1024
            const val RESERVE_MARGIN_MB = 32L
            const val RESERVE_MIN_AHEAD_MS = 10_000L
            const val RESERVE_MAX_MS = 15L * 60 * 1000
            const val RESERVE_PURGE_MIN_BYTES = 8L * 1024 * 1024
            const val RESERVE_PURGE_INTERVAL_MS = 5_000L
            const val RESERVE_LOG_INTERVAL_MS = 20_000L
            const val RESERVE_NO_GAIN_MS = 60_000L
            const val RESERVE_RETRY_MS = 60_000L
            /** A settle target refused this many times in a row yields to the coverage pass until the next scrub. */
            const val P0_MAX_DEFERRALS = 3
            /** Small boxes, 4K coverage while playing: pause after each picture, doubled on dropped frames. */
            const val LATTICE_4K_PLAYING_PACE_MS = 4_000L
            /** Small boxes: a read-ahead at or below this share of its budget leaves room for a 4K decode. */
            const val NATURAL_FUNDING_PERCENT = 40L
            /** Dropped frames this soon after a seek, a resume or a scrub step are not blamed on thumbnails. */
            const val SEEK_QUIET_MS = 5_000L
            const val SEEK_JUMP_MS = 1_500L
            const val MONITOR_INTERVAL_MS = 1_000L
            const val PACE_LOG_INTERVAL_MS = 60_000L
            const val CLOCK_SAMPLE_MS = 5_000L
            const val CLOCK_HOLD_MS = 30_000L
            /** Highest allowed clock seen per core since the app started, so a later session can start capped. */
            val clockPeakKhz = AtomicLongArray(Runtime.getRuntime().availableProcessors())
            const val PREPARE_READY_MS = 1_600L
            /** The generating screen renews playback's yield this often (it lapses after [PlaybackYield.LEASE_MS]). */
            const val YIELD_RENEW_MS = 500L
            const val PREPARE_SECONDS_REMUX = 1.3
            const val PREPARE_SECONDS_WEB = 1.0
            const val PREPARE_SECONDS_HD = 0.6
            const val PREPARE_SECONDS_STRONG_4K = 0.9
            /** A held seek within this long keeps the pause cap after the coverage is done. */
            const val SCRUB_CAP_WINDOW_MS = 5_000L
            const val REANCHOR_JUMP_MS = 2L * 60 * 1000
            /** The coverage pass starts with this window around the playback position. */
            const val LOCAL_BEFORE_MS = 10L * 60 * 1000
            const val LOCAL_AFTER_MS = 20L * 60 * 1000
            const val AFTER_PLAYBACK_MAX_MS = 10L * 60 * 1000
            const val MAX_DISK_LOADS_IN_FLIGHT = 4
            /** Another release's set is tried only with at least this many pictures. */
            const val MIN_BORROW_PICTURES = 12
            /** The generating screen waits this long on the picture check before it starts generating. */
            const val CHECK_HOLD_MS = 30_000L
            const val ADOPT_CHUNK = 64
            const val FITTED_SHOWN_KEPT = 6
            /** A copy's keyframe (WEB, 3-4 s apart) may sit this far from where the 4K seek lands. */
            const val COPY_WIDE_US = 2_000_000L
            const val COPY_ADOPT_EVERY = 24
            const val COPY_CHECK_REFILL_EVERY = 6
            /** Small boxes, copy decodes (1080p) while playing: pause after each picture, doubled on dropped frames. */
            const val COPY_PLAYING_PACE_MS = 1_000L
            const val COPY_TAG = "ThumbCopy"
            /** Strong boxes: the own 4K pass waits for the copy only while the copy makes progress. */
            const val COPY_STALL_HOLD_MS = 3L * 60 * 1000
            /** A Usenet title's generating screen waits this long for a copy to be confirmed before playing. */
            const val USENET_COPY_CHECK_MS = 40_000L
        }

        private enum class Outcome { DONE, FAILED, POSTPONED }

        private class Fetched(
            val au: ThumbAu?, val error: Exception?, val requests: Int, val bytes: Long, val ms: Long, val startedAt: Long,
        )

        private class Decoded(
            val bitmap: Bitmap?, val decodeMs: Long, val renderMs: Long, val cold: Boolean, val width: Int, val height: Int,
            val reason: String, val error: Throwable? = null,
        )

        /** A keyframe handed to a decode lane; the worker stores and logs it once [result] is in. */
        private class DecodeJob(
            val idx: MediaIndex, val st: ThumbStore, val slot: Int, val kf: Int, val priority: Int, val lane: DecoderLane,
            val tapped: Boolean, val auBytes: Int, val requests: Int, val bytes: Long, val fetchMs: Long, val waitedMs: Long,
            val lanes: Int, val concurrent: Int, val result: Deferred<Decoded>,
        )

        private var latticeStrides = PrepareGrid.LATTICE_1080P
        /** The before-play grid in slots: what the generating screen and the paused progress hint count. */
        private var screenStride = 3
        private var beforePlay = ThumbsBeforePlay.FASTER
        private var fallbackSlots = MIN_FALLBACK_SLOTS
        /** 4K keyframes cost about 1 s each on small boxes: fewer settle neighbours. */
        private val priorityRadius = if (fourK) 2 else 3
        /** False while 4K may not decode (playing on a small box); the pane reads it. */
        @Volatile private var decodeOpen = !fourK
        @Volatile private var reserveHeld: PlaybackBufferReserve? = null
        private var reserveBlocked = false
        private var reserveSince = 0L
        private var reservePictures = 0
        private var reserveFreed = 0L
        private var reservePurgedAt = 0L
        private var lastPurgeAt = 0L
        private var lastReserveLogAt = 0L
        private var reserveRetryAt = 0L
        /** The last decode was refused for swap, which a reserve cannot free. */
        @Volatile private var refusedForSwap = false
        private val swapHolds = SwapHolds()
        private var fundedSince = 0L
        private var fundedPictures = 0
        private var p0Deferrals = 0
        private var p0DeferralSlot = -1
        /** Small box, no reserve, read-ahead small enough anyway. Re-checked before every decode. */
        @Volatile private var naturallyFunded = false
        /** Fed on Main by [monitorPlayback], read by the worker: always under synchronized(pacer). */
        private val pacer = PlaybackDecodePacer(LATTICE_4K_PLAYING_PACE_MS)
        /** The same for the copy pass's decodes; under synchronized(copyPacer). */
        private val copyPacer = PlaybackDecodePacer(COPY_PLAYING_PACE_MS)
        @Volatile private var copy: CopyPass? = null
        @Volatile private var copyAllowed = false
        /** Plays from Usenet: thumbnails come from a copy where one fits, and the stream is spared meanwhile. */
        @Volatile private var usenet = source.usenet
        /** A media server away from the home network: only the spot a seek asks for is made. */
        private val onDemand = MediaServerThumbs.onDemandOnly(source.mediaServer, source.url)
        private var copyTried = false
        private var copyStartedAt = 0L
        private var copySetKey: String? = null
        private var copyAdoptedAt = 0
        private var copyChecksAt = 0
        /** Positions (ms) of the newest thumbnails, oldest first. Guarded by itself. */
        private val recentMade = ArrayDeque<Long>()
        /** Guarded by [recentMade]. */
        private var madeSeq = 0
        @Volatile private var prepareLastCoverage: List<Boolean> = emptyList()
        private var monitorJob: Job? = null
        @Volatile private var lastScrubAt = 0L
        /** Where playback jumped to, -1 = no jump pending. */
        @Volatile private var reanchorToMs = -1L
        private var trackPosMs = -1L
        private var trackAtMs = 0L
        private var trackPlaying = false
        @Volatile private var sampledPlaying = true     // true until the first sample, so no early pause cap
        @Volatile private var sampledBuffering = false
        @Volatile private var remux = false
        /** 0 = not used, 1 = checking (player held), 3 = generating, 4 = ready, 5 = finished. 2 is unused. */
        @Volatile private var prepareStage = 0
        private val prepareLock = Any()
        /** The held reserve is the pause cap: no pacer, no time limit. */
        @Volatile private var reserveIsPauseCap = false
        private var pauseCapRetryAt = 0L
        /** Decoder trim asked for by onTrimMemory; done by the worker, never during a decode. */
        @Volatile private var trimRequested = false
        @Volatile private var stoppedExternally = false
        @Volatile private var preparePausedPlayer = false
        @Volatile private var prepareAnchorMs = 0L
        private var prepareDoneAtStart = -1
        private var prepareMadeAtStart = -1
        /** Every coverage slot stored, finer ones included, and what the pass still makes before the screen's grid is done. */
        @Volatile private var latticeMade = 0
        @Volatile private var latticeWorkLeft = 0
        private var prepareStartedAt = 0L
        private var prepareReadyAt = 0L
        private var prepareLastDone = -1
        private var prepareLastProgressAt = 0L
        private var prepareTendedAt = 0L
        private var lastPrepareUi: PrepareUi? = null
        @Volatile private var latticeDone = 0
        /** The coverage is really stored, not just the budget spent. */
        @Volatile private var latticeComplete = false
        @Volatile private var latticeTotal = 0
        /** The coverage pass finished or ran out of budget. On demand there is none. */
        @Volatile private var latticeFinished = onDemand
        @Volatile private var afterPlayback = false
        private var afterPlaybackSince = 0L
        private var lastSampledPlaying = false

        private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        private val rgb565 = runCatching { PreviewPreferences.rgb565(context) }.getOrDefault(true)
        @Volatile private var store: ThumbStore? = null
        @Volatile private var index: MediaIndex? = null
        /** slot -> keyframe index, and keyframe -> all slots it serves (long GOPs). */
        @Volatile private var slotKeyframe: IntArray = IntArray(0)
        private var keyframeSlots: HashMap<Int, IntArray> = HashMap()
        @Volatile private var prioritySlot: Int = -1
        /** Last scrub position (ms), kept even before the index exists; -1 = none. */
        @Volatile private var pendingPriorityMs: Long = -1L
        @Volatile private var lastDurationMs = 0L
        @Volatile private var lastPositionMs = 0L
        @Volatile private var reader: HttpRangeReader? = null
        @Volatile private var makeExtractor: (() -> KeyframeExtractor)? = null
        /** Extractors not in use; each fetch under way has its own. */
        private val spareExtractors = ConcurrentLinkedQueue<KeyframeExtractor>()
        /** Keyframe -> its fetch, under way or done and not decoded yet, in the order started. Worker only. */
        private val fetches = LinkedHashMap<Int, Deferred<Fetched>>()
        private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(ThumbFetchLanes.MAX))
        /** A rate limit or a failed fetch was seen: one fetch at a time for the rest of the session. */
        private var fetchRateLimited = RateLimitedHosts.appSession.contains(source.url)
        /** The server rate limited this session, or answered 429 to an earlier one recently. */
        @Volatile private var sourceRateLimited = fetchRateLimited
        private val fetchRamp = FetchRamp(limitedFromStart = fetchRateLimited)
        private val decoderLanes = Array(ThumbDecodeLanes.MAX) { DecoderLane() }
        private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(ThumbDecodeLanes.MAX))
        /** Keyframe -> its decode under way or done and not stored yet. Worker only. */
        private val decodeJobs = LinkedHashMap<Int, DecodeJob>()
        private val laneCtl = ThumbDecodeLanes(
            MemoryGovernor.isStrongBox, fourK, Runtime.getRuntime().availableProcessors(), FetchPhase.PLAYBACK,
            memoryConstrained = MemoryGovernor.isLowRam,
        )
        /** Raw keyframe reads land here, outside the Java heap; sized to the decode lanes. */
        private val buffers = ThumbBufferPool(ThumbBufferPool.limitFor(laneCtl.lanes, fourK, MemoryGovernor.isStrongBox, MemoryGovernor.isLowRam))
        @Volatile private var laneTrimRequested = false
        private var laneClockAt = 0L
        private var laneHeldUntil = 0L
        private var laneClockLoweredAt = 0L
        private val attempts = HashMap<Int, Int>()
        private var rateLimitedUntil = 0L
        private val fetchFailures = FetchFailureStreak(MAX_FETCH_FAILURES)
        private val giveUp = GiveUpNotes()
        private var fetchesStopped = false
        private val diskLoadsInFlight = HashSet<Pair<String, Int>>()
        /** Last bitmap handed to the pane; shown while the exact frame loads from disk. */
        @Volatile private var lastServed: Bitmap? = null
        @Volatile private var lastServedSlot = -1
        val hasSlots: Boolean get() = !ended && slotKeyframe.isNotEmpty()
        private var lastNotedMs = -1L
        private var lastNotedAt = 0L
        @Volatile private var ended = false
        /** Black bars of this title's pictures; the pane crops them at display time. */
        @Volatile private var crop = ThumbCrop.Estimator()

        // --- playback tap spool, deleted at session end ---
        private val spoolDir = File(File(File(context.cacheDir, ThumbStore.DIR), SPOOL_DIR), "s${System.nanoTime()}")
        val spoolDirPath: String get() = spoolDir.absolutePath
        /** keyframe index -> ready (false while its file is still being written). */
        private val spool = ConcurrentHashMap<Int, Boolean>()
        private val spoolOrder = ConcurrentLinkedDeque<Int>()
        private val spoolBytes = AtomicLong(0)
        private val spoolPending = AtomicInteger(0)
        private val spoolWriter = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
        @Volatile private var tapMismatchLogged = false
        /** The tap copies keyframes only once the index is confirmed for this file. */
        @Volatile private var tapOpen = false
        private var tapHits = 0
        val storeKey: String? get() = store?.key
        private var latticeRequests = 0
        private var latticeBytes = 0L
        private var latticeMaxRequests = 0
        @Volatile private var hardMaxRequests = Int.MAX_VALUE
        private var lastDeferLogAt = 0L
        private var deferredSinceLog = 0
        private val trimListener: (Int) -> Unit = { onTrim() }
        @Volatile private var job: Job? = null
        val workerJob: Job? get() = job
        val copyJob: Job? get() = copy?.workerJob

        fun cancelReads() {
            reader?.close()
            copy?.cancelReads()
        }

        fun start() {
            if (stoppedExternally) return        // stopped between creation and start
            MemoryGovernor.addTrimListener(trimListener)
            if (fourK && !MemoryGovernor.isStrongBox) monitorJob = mainScope.launch { monitorPlayback() }
            job = worker.launch {
                try {
                    run()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    giveUp.ended(t)
                    PreviewDiagnostics.note("generation ended: ${t.javaClass.simpleName}: ${t.message}")
                    Log.w(TAG, "session ended: ${giveUp.endedBy}")
                } finally {
                    withContext(NonCancellable) { finish() }
                }
            }.also { j -> j.invokeOnCompletion { if (ending === this) ending = null } }
        }

        fun stop(handBackPlayer: Boolean = true) {
            stoppedExternally = true
            // Stopped while preparing: give the player back before a newer session can pause it (posts are ordered on Main).
            if (synchronized(prepareLock) { (prepareStage in 1..4).also { if (it) prepareStage = 5 } }) {
                mainScope.launch {
                    if (handBackPlayer) runCatching { resumePlayerAfterPrepare() } else preparePausedPlayer = false
                    if (session == null || session === this@Session) prepareUi.value = PrepareUi.Hidden
                }
            }
            endYield()
            reader?.close()
            copy?.stop("the player stopped this title")
            job?.cancel()
            monitorJob?.cancel()
        }

        private suspend fun run() {
            ConnectionLedger.app.attach(PrefsLedgerMemory(context))
            ConnectionLedger.app.notePlaybackConnections(source.playbackConnections)
            ConnectionLedger.app.notePlayingUrl(source.url)
            val first = sample() ?: return
            lastDurationMs = first.durationMs
            beforePlay = runCatching { SeekThumbnailPreferences.beforePlay(context) }.getOrDefault(ThumbsBeforePlay.FASTER)
            // A saved store shows its thumbnails before any network access; its size is checked against the server's below.
            var known = openKnownStore()
            known?.let {
                applyIndex(it.index, it.store, it.fileLength)
                setUpBorrow(it.index, it.store, confirmedOnly = true)
            }
            if (sourceRateLimited) Log.i(TAG, "source rate limited recently: one thumbnail fetch at a time")
            if (onDemand) Log.i(TAG, "media server away from the home network: only the spot a seek asks for, " +
                "${MediaServerThumbs.ON_DEMAND_FETCHES} request at a time")
            run {
                val mode = runCatching { SeekThumbnailPreferences.prepareMode(context) }.getOrDefault(ThumbPrepareMode.OFF)
                val size = source.fileSizeHint?.takeIf { it > 0 }
                // The marker is written at >= 97 %: a few keyframes that never decode must not bring the screen up again.
                val complete = known?.let { coverageStored(it.store) } == true ||
                    (size != null && runCatching { ThumbStore.isMarkedComplete(context, source.identity, size) }
                        .getOrDefault(false))
                giveUp.beforePlay = when {
                    mode == ThumbPrepareMode.OFF -> "off"
                    complete -> "skipped (already complete)"
                    !source.holdBeforePlay -> "skipped (next episode or late start)"
                    sourceRateLimited -> "skipped (rate limited recently)"
                    onDemand -> "skipped (media server away from home)"
                    else -> "not shown (playback could not be held)"
                }
                if (mode != ThumbPrepareMode.OFF && complete) {
                    Log.i(TAG, "prepare: thumbnails already complete (${if (known != null) "saved" else "marker"}) - playing")
                } else if (mode != ThumbPrepareMode.OFF && !source.holdBeforePlay) {
                    Log.i(TAG, "prepare: autoplayed next episode - not holding")
                } else if (mode != ThumbPrepareMode.OFF && sourceRateLimited) {
                    Log.i(TAG, "prepare: source rate limited recently - not holding")
                } else if (mode != ThumbPrepareMode.OFF && onDemand) {
                    Log.i(TAG, "prepare: media server away from the home network - not holding")
                } else if (mode != ThumbPrepareMode.OFF) {
                    if (beginPrepareOnMain()) {
                        giveUp.beforePlay = if (preparePausedPlayer) "shown (${beforePlayName()})"
                            else "shown, playback already paused (${beforePlayName()})"
                        showPrepare(PrepareUi.Checking)
                        Log.i(TAG, "prepare: generating screen up ($mode, $beforePlay)")
                    }
                }
            }
            PreviewDiagnostics.note("waiting for playback buffer before index")
            awaitIndexGate()
            PreviewDiagnostics.note("starting background header download")
            val r = HttpRangeReader(source.url, source.headers, thumbnailHttpClient(source.headers),
                slotWaitMs = ConnectionLedger.START_SLOT_WAIT_MS)
            reader = r
            val tIndex0 = SystemClock.elapsedRealtime()
            val head = startRead { r.read(0, HEAD_BYTES) }
            val fileLength = r.totalLength
            PreviewDiagnostics.note("header downloaded: ${head.size} bytes")
            ConnectionLedger.app.notePlayingUrl(r.resolvedUrl)
            if (!usenet && PreviewSourcePolicy.isUsenet(r.resolvedUrl)) usenet = true
            if ((fourK || usenet) && source.copySources != null && !onDemand) {
                copyAllowed = runCatching { SeekThumbnailPreferences.copyEnabled(context) }.getOrDefault(false)
                if (usenet) Log.i(COPY_TAG, "Usenet stream: thumbnails from a copy of this title where one fits" +
                    if (copyAllowed) "" else " - off in settings")
            }
            Log.i(TAG, "connections: up to ${ConnectionLedger.app.thumbnailCap(r.resolvedUrl)} thumbnail requests on this " +
                (if (LedgerKey.of(r.resolvedUrl) == LedgerKey.TORBOX) "TorBox account" else "server") +
                " (${ConnectionLedger.app.inFlightNote(r.resolvedUrl)}, playback setting ${source.playbackConnections})")
            if (!sourceRateLimited && RateLimitedHosts.appSession.contains(r.resolvedUrl)) {
                sourceRateLimited = true
                fetchRamp.limitToOne()
                fetchOneAtATime("source rate limited recently")
            }
            if (known != null && known.fileLength != fileLength) {
                Log.i(TAG, "saved thumbnails opened early were for another file size - reopening")
                slotKeyframe = IntArray(0)
                prioritySlot = -1
                store = null
                index = null
                lastServed = null
                lastServedSlot = -1
                crop = ThumbCrop.Estimator()
                dropBorrow()
                known = null
            }
            // A remux is known before its index is read: cap now, before a paused start fills the read-ahead.
            if (fourK && lastDurationMs > 0) {
                remux = fileLength * 8_000L / lastDurationMs >= REMUX_MIN_BITS_PER_SECOND
                tendReserve()
            }
            val s = known?.store ?: ThumbStore.open(context, source.identity, fileLength, rgb565, PreviewStore.digest(head))
            store = s
            ThumbStore.housekeeping(context, activeKey = s.key)
            val cached = known?.index
                ?: s.cachedKeyframeIndex()?.let(KeyframeIndexCodec::decode)?.takeIf { it.fileLength == fileLength }
            // 4K: only read the index (several MB of Cues on a remux) once 4K may decode.
            if (cached == null && fourK) awaitFourKWindow()
            val idx = cached ?: startRead {
                when {
                    MkvIndexReader.looksLikeMatroska(head) -> MkvIndexReader.read(r, head)
                    AviIndexReader.looksLikeAvi(head) -> AviIndexReader.read(r, head)
                    Mp4IndexReader.looksLikeMp4(head) -> Mp4IndexReader.read(r, head)
                    else -> throw UnsupportedMediaException("container not supported")
                }
            }.also { s.setKeyframeIndex(KeyframeIndexCodec.encode(it)) }
            Log.i(TAG, "index ${idx.kind} ${idx.video.codec} ${idx.video.width}x${idx.video.height} " +
                "${idx.video.bitDepth}-bit keyframes=${idx.keyframes.count} cached=${cached != null} " +
                "requests=${r.requests} kb=${r.bytes / 1024} t=${SystemClock.elapsedRealtime() - tIndex0}ms " +
                "stored=${s.slots().size} dv=${idx.video.dolbyVision?.profile}")
            if (!fourK && MemoryGovernor.tierFor(idx.video.width, idx.video.height) == DecodeTier.SW_4K) {
                throw UnsupportedMediaException("container resolution exceeds the playback format; keeping external previews")
            }
            if (known == null) applyIndex(idx, s, fileLength)
            if (fourK) {
                val durationMs = lastDurationMs.takeIf { it > 0 } ?: (idx.durationUs / 1000L)
                val bps = if (durationMs > 0) fileLength * 8_000L / durationMs else 0L
                Log.i(TAG, "4K session: strongBox=${MemoryGovernor.isStrongBox} " +
                    "bitrate=${bps / 1_000_000} Mb/s lattice=${latticeStrides.last() * SPACING_MS / 1000}s")
            }
            PreviewDiagnostics.note("index ready: ${idx.kind} ${idx.video.codec}, ${idx.keyframes.count} keyframes")
            r.slotWaitMs = ConnectionLedger.SLOT_WAIT_MS
            makeExtractor = { KeyframeExtractor(idx, r) }
            tapOpen = true
            // Another release's pictures need own ones fetched to be confirmed: on demand only confirmed ones are used.
            setUpBorrow(idx, s, confirmedOnly = onDemand)
            loop(idx)
        }

        /** Header and index reads: a dropped connection or a rate limit is retried a few times before the session ends. */
        private suspend fun <T> startRead(block: () -> T): T {
            val streak = FetchFailureStreak(START_READ_TRIES)
            var noSlot = 0
            while (true) {
                try {
                    return block()
                } catch (e: UnsupportedMediaException) {
                    throw e
                } catch (e: ConnectionLedger.NoSlotException) {
                    // Playback keeps every connection the server allows for now: not a failure, but no reason to hold it.
                    rethrowIfStopping(e)
                    giveUp.noConnectionFree()
                    releasePrepareHold()
                    if (++noSlot >= START_SLOT_TRIES) throw e
                    delay(1_000L)
                } catch (e: IOException) {
                    rethrowIfStopping(e)
                    if (e is RateLimitedException) {
                        giveUp.rateLimited()
                        noteRateLimited(e)
                    } else {
                        giveUp.fetchFailed(e)
                    }
                    val waitMs = maxOf(streak.failed(), (e as? RateLimitedException)?.retryAfterMs ?: 0L)
                    if (streak.gaveUp) throw e
                    Log.w(TAG, "start read failed (${e.javaClass.simpleName}), retry in ${waitMs / 1000}s")
                    releasePrepareHold()
                    delay(waitMs)
                }
            }
        }

        /** A read cancelled by stop() must end the worker, never count as a network failure. */
        private suspend fun rethrowIfStopping(e: IOException) {
            if (stoppedExternally || ended) throw e
            currentCoroutineContext().ensureActive()
        }

        /** Playback is not held on the checking screen while a slow start waits to retry. */
        private suspend fun releasePrepareHold() {
            val stage = prepareStage
            if (stage in 1..4 && advancePrepare(stage, 5)) {
                showPrepare(PrepareUi.Hidden)
                withContext(Dispatchers.Main) { resumePlayerAfterPrepare() }
            }
        }

        private class KnownStore(val store: ThumbStore, val index: MediaIndex, val fileLength: Long)

        private fun openKnownStore(): KnownStore? {
            val size = source.fileSizeHint?.takeIf { it > 0 } ?: return null
            return runCatching {
                if (!ThumbStore.exists(context, source.identity, size)) return null
                val s = ThumbStore.open(context, source.identity, size, rgb565)
                val idx = s.cachedKeyframeIndex()?.let(KeyframeIndexCodec::decode)?.takeIf { it.fileLength == size }
                    ?: return null
                Log.i(TAG, "saved thumbnails opened at once (stored=${s.slots().size})")
                KnownStore(s, idx, size)
            }.getOrNull()
        }

        private fun applyIndex(idx: MediaIndex, st: ThumbStore, fileLength: Long) {
            val durationMs = lastDurationMs.takeIf { it > 0 } ?: (idx.durationUs / 1000L)
            val bps = if (durationMs > 0) fileLength * 8_000L / durationMs else 0L
            if (fourK) remux = bps >= REMUX_MIN_BITS_PER_SECOND
            val base = PrepareGrid.base(fourK, remux, MemoryGovernor.isStrongBox, bps >= HEAVY_1080P_MIN_BITS_PER_SECOND)
            screenStride = PrepareGrid.screenStride(beforePlay, fourK, remux, base.last())
            latticeStrides = PrepareGrid.strides(base, screenStride)
            fallbackSlots = maxOf(MIN_FALLBACK_SLOTS, latticeStrides.last() / 2)
            st.noteDuration(durationMs)
            index = idx
            store = st
            buildSlots(idx, durationMs)
            seedCropFromStore(st)
            preloadSaved(st)
            pendingPriorityMs.takeIf { it >= 0 }?.let { prioritySlot = slotOf(it) }   // a scrub before the index existed
        }

        /** The generating screen counts this grid (the Thumbnails before play choice); finer slots carry on during playback. */
        private fun prepareStride(): Int = screenStride

        private fun coverageStored(st: ThumbStore): Boolean {
            val lattice = SeekGrid.latticeOrder(slotKeyframe.size, latticeStrides, 0, 0, 0)
            return lattice.isNotEmpty() && lattice.none { needs(st, it) }
        }

        private fun inBackground(): Boolean = ForegroundGate.waits(hostResumed, afterPlayback)

        /** Worker only. Away from the player screen nothing is fetched or decoded, and the decoders' memory is given back. */
        private suspend fun awaitForeground() {
            if (!inBackground()) return
            val now0 = SystemClock.elapsedRealtime()
            val since = hostPausedAt.takeIf { it in 1..now0 } ?: now0
            Log.i(TAG, "app not in front: thumbnails wait")
            while (inBackground()) {
                settleDecodes()
                tendReserve()
                closeDecoders()
                delay(1_000L)
            }
            val back = SystemClock.elapsedRealtime()
            prepareStartedAt = ForegroundGate.shifted(prepareStartedAt, since, back)
            prepareLastProgressAt = ForegroundGate.shifted(prepareLastProgressAt, since, back)
            Log.i(TAG, "app back in front after ${(back - since) / 1000} s: thumbnails carry on")
        }

        /**
         * The index read (up to a few MB) waits for a healthy buffer or a pause, unless the user scrubs first. On demand
         * nothing is read before the first scrub.
         */
        private suspend fun awaitIndexGate() {
            while (true) {
                awaitForeground()
                if (pendingPriorityMs >= 0) return
                if (onDemand) {
                    delay(1_000L)
                    continue
                }
                val ps = sample() ?: return
                if (previewBufferReady(ps.isPlaying, ps.buffering, ps.loading, ps.bufferedMs - ps.positionMs,
                        ps.durationMs > 0 && ps.bufferedMs >= ps.durationMs - 1_000L, INDEX_GATE_AHEAD_MS)) return
                delay(1_000L)
            }
        }

        /** Small boxes: while paused, for the settle target, and for coverage only while the read-ahead leaves room. */
        private fun fourKAllowed(ps: PlayerState, priority: Int = 2): Boolean {
            if (!fourK || MemoryGovernor.isStrongBox || !ps.isPlaying) return true
            // Small box, 4K remux: nothing while playing (it stutters). Made while paused and after the player closes.
            if (remux) return false
            if (!synchronized(pacer) { pacer.allows(settleTarget = priority == 0) }) return false
            return priority == 0 || reserveHeld != null || naturallyFunded || reserveEligible()
        }

        private fun reserveEligible(): Boolean =
            fourK && !afterPlayback && !reserveBlocked && !latticeFinished &&
                SystemClock.elapsedRealtime() >= reserveRetryAt &&
                synchronized(pacer) { pacer.allows(settleTarget = false) } &&
                reserveProvider()?.cancelledByRebuffer == false

        /** A coverage decode was refused for memory: cap playback's read-ahead by the missing amount plus a margin. */
        private suspend fun takeReserve(tier: DecodeTier, reason: String) {
            if (reserveHeld != null || !reserveEligible()) return
            val r = reserveProvider() ?: return
            val ps = sample() ?: return
            val paused = !ps.isPlaying
            if (!paused && ps.bufferedMs - ps.positionMs < RESERVE_MIN_AHEAD_MS) return
            // Small boxes: the read-ahead is small enough already, nothing needs capping. Also while paused.
            if (!MemoryGovernor.isStrongBox && naturalFundingFits(r)) {
                if (!naturallyFunded) {
                    naturallyFunded = true
                    Log.d(TAG, "4K-F natural funding on (read-ahead ${r.footprintBytes / MB} MB of " +
                        "${r.readAheadBudgetBytes / MB} MB, ${(ps.bufferedMs - ps.positionMs) / 1000} s ahead" +
                        (if (paused) ", paused" else "") + ") - $reason")
                }
                return
            }
            // A paused remux is funded by the pause cap (tendPauseCap); the reserve below is for playback.
            if (paused) return
            val shortMb = MemoryGovernor.shortfallMb(tier, decoderLanes[0].decoder.warm, RESERVE_MARGIN_MB) ?: return
            val want = maxOf(RESERVE_MIN_BYTES, shortMb * MB)
            // The player caps a reserve at 60 % of its budget; if that is not enough, tendReserve drops it again.
            val why = when {
                // Strong boxes: a read-ahead already below the cap is not what holds the memory, capping it frees nothing.
                MemoryGovernor.isStrongBox && r.bufferedBytes < r.readAheadBudgetBytes - want ->
                    "read-ahead ${r.bufferedBytes / MB} MB is already below ${(r.readAheadBudgetBytes - want) / MB} MB"
                else -> null
            }
            if (why != null) {
                reserveRetryAt = SystemClock.elapsedRealtime() + RESERVE_RETRY_MS
                Log.d(TAG, "4K-E no reserve: $why - $reason (retry in ${RESERVE_RETRY_MS / 1000} s)")
                return
            }
            holdReserve(r, want, "4K-E reserve on", "${(ps.bufferedMs - ps.positionMs) / 1000} s ahead) - $reason")
        }

        private fun holdReserve(r: PlaybackBufferReserve, want: Long, what: String, detail: String) {
            r.reserve(want)
            if (r.reservedBytes <= 0L) return
            reserveHeld = r
            reserveSince = SystemClock.elapsedRealtime()
            fundedSince = 0L
            reservePictures = 0
            reserveFreed = 0L
            reservePurgedAt = 0L
            Log.d(TAG, "$what: ${r.reservedBytes / MB} MB (wanted ${want / MB}) of ${r.readAheadBudgetBytes / MB} MB read-ahead " +
                "(buffered ${r.bufferedBytes / MB} MB, $detail")
        }

        /** Small box, 4K: while paused the read-ahead is capped like a reserve, which funds the decodes. */
        private fun tendPauseCap() {
            if (!fourK || MemoryGovernor.isStrongBox || ended) return
            if (sampledPlaying || afterPlayback || inBackground() || (latticeFinished && !scrubbingNow())) {
                if (reserveIsPauseCap && reserveHeld != null) {
                    dropReserve(when {
                        sampledPlaying -> "playback resumed"
                        afterPlayback -> "player closed"
                        inBackground() -> "app not in front"
                        else -> "coverage done"
                    })
                }
                return
            }
            // Not tied to the pacer or the reserve time limit: pausing always makes thumbnails.
            if (reserveHeld != null || sampledBuffering || SystemClock.elapsedRealtime() < pauseCapRetryAt) return
            val r = reserveProvider() ?: return
            if (r.cancelledByRebuffer) return
            val shortMb = MemoryGovernor.shortfallMb(DecodeTier.SW_4K, decoderLanes[0].decoder.warm, RESERVE_MARGIN_MB) ?: 0L
            holdReserve(r, maxOf(RESERVE_MIN_BYTES, shortMb * MB), "4K pause cap on", "paused)")
            if (reserveHeld === r) reserveIsPauseCap = true
        }

        private fun dropReserve(why: String, block: Boolean = false) {
            if (block) reserveBlocked = true
            val r = reserveHeld ?: return
            reserveHeld = null
            reserveIsPauseCap = false
            runCatching { r.release() }
            Log.d(TAG, "4K-E reserve off: $why after ${(SystemClock.elapsedRealtime() - reserveSince) / 1000} s, " +
                "$reservePictures pictures, ${reserveFreed / MB} MB given back")
        }

        /** A held seek is under way: its pictures get the pause cap's memory even after the coverage is done. */
        private fun scrubbingNow(): Boolean = SystemClock.elapsedRealtime() - lastScrubAt < SCRUB_CAP_WINDOW_MS

        private fun tendReserve() {
            if (trimRequested) {
                trimRequested = false
                trimDecoders()
            }
            tendPauseCap()
            val r = reserveHeld ?: return
            val now0 = SystemClock.elapsedRealtime()
            if (reserveFunding()) {
                if (fundedSince == 0L) {
                    fundedSince = now0
                    fundedPictures = reservePictures
                }
            } else {
                fundedSince = 0L
            }
            when {
                // Not blocked here: the player decides when the reserve may be taken again.
                r.cancelledByRebuffer -> dropReserve("rebuffer (playback first)")
                !reserveIsPauseCap && !synchronized(pacer) { pacer.allows(settleTarget = false) } ->
                    dropReserve("4K-F stopped playback decodes")
                r.reservedBytes <= 0L -> dropReserve("released by the player (lease)")
                reserveProvider() !== r -> dropReserve("player changed")
                // Not blocked: no picture is made meanwhile, which must not read as a reserve that does not help.
                inBackground() -> dropReserve("app not in front")
                fundedSince > 0L && reservePictures == fundedPictures && now0 - fundedSince > RESERVE_NO_GAIN_MS -> {
                    val swap = refusedForSwap
                    if (reserveIsPauseCap) pauseCapRetryAt = now0 + RESERVE_RETRY_MS
                    else if (swap) reserveRetryAt = now0 + RESERVE_RETRY_MS
                    dropReserve("no picture in ${RESERVE_NO_GAIN_MS / 1000} s with the room reserved" +
                        (if (swap) " (swap low)" else ""), block = !reserveIsPauseCap && !swap)
                }
                latticeFinished && !(reserveIsPauseCap && scrubbingNow()) -> dropReserve("coverage done")
                afterPlayback -> dropReserve("player closed")
                !reserveIsPauseCap && SystemClock.elapsedRealtime() - reserveSince > RESERVE_MAX_MS ->
                    dropReserve("time limit", block = true)
                else -> {
                    val freed = runCatching { r.giveBack() }.getOrDefault(0L)
                    reserveFreed += freed
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReserveLogAt >= RESERVE_LOG_INTERVAL_MS) {
                        lastReserveLogAt = now
                        Log.d(TAG, "4K-E reserve: read-ahead ${r.bufferedBytes / MB}/${r.readAheadBudgetBytes / MB} MB " +
                            "(cap ${(r.readAheadBudgetBytes - r.reservedBytes) / MB}, allocator ${r.footprintBytes / MB} MB), " +
                            "given back ${reserveFreed / MB} MB, pictures $reservePictures")
                    }
                    // Freed segments reach MemAvailable only once the allocator returns their pages. M_PURGE walks the heap.
                    if (reserveFreed - reservePurgedAt >= RESERVE_PURGE_MIN_BYTES && now - lastPurgeAt >= RESERVE_PURGE_INTERVAL_MS) {
                        lastPurgeAt = now
                        reservePurgedAt = reserveFreed
                        runCatching { ThumbNative.nativePurge() }
                    }
                }
            }
        }

        private fun reserveFunding(): Boolean {
            val r = reserveHeld ?: return false
            return r.footprintBytes <= r.readAheadBudgetBytes - r.reservedBytes + 8L * MB
        }

        private fun naturalFundingFits(r: PlaybackBufferReserve): Boolean {
            val budget = r.readAheadBudgetBytes
            return budget > 0L && r.footprintBytes <= budget * NATURAL_FUNDING_PERCENT / 100
        }

        private fun naturalFunding(): Boolean {
            if (!naturallyFunded) return false
            val r = reserveProvider()
            val why = when {
                r == null -> "no player"
                afterPlayback -> "player closed"
                reserveHeld != null -> "reserve taken"
                !naturalFundingFits(r) -> "read-ahead ${r.footprintBytes / MB} MB of ${r.readAheadBudgetBytes / MB} MB"
                else -> return true
            }
            naturallyFunded = false
            Log.d(TAG, "4K-F natural funding off ($why)")
            return false
        }

        private fun afterPlaybackExpired(): Boolean =
            afterPlayback &&
                (SystemClock.elapsedRealtime() - afterPlaybackSince > AFTER_PLAYBACK_MAX_MS || memoryShortAfterClose())

        /** True when this session carries on without the player to finish its coverage pass. */
        fun finishAfterPlayback(): Boolean {
            // No index yet is fine: a small box may still be waiting for a pause, and reads it once the player is gone.
            if (!fourK || !MediaServerThumbs.continuesAfterClose(source.mediaServer) || ended || latticeFinished ||
                afterPlayback) return false
            afterPlaybackSince = SystemClock.elapsedRealtime()
            afterPlayback = true
            if (synchronized(prepareLock) { (prepareStage in 1..4).also { if (it) prepareStage = 5 } }) {
                endYield()
                preparePausedPlayer = false
                prepareUi.value = PrepareUi.Hidden      // called on Main
            }
            prioritySlot = -1
            pendingPriorityMs = -1L
            Log.i(TAG, "4K-C: player closed - finishing the coverage pass (up to ${AFTER_PLAYBACK_MAX_MS / 60_000} min)")
            return true
        }

        private suspend fun awaitFourKWindow() {
            while (true) {
                awaitForeground()
                if (pendingPriorityMs >= 0) {   // scrubbing: the settle target may decode, so read the index
                    decodeOpen = true
                    return
                }
                val ps = sample() ?: return
                if (!ps.buffering && fourKAllowed(ps)) {
                    decodeOpen = true
                    return
                }
                decodeOpen = false
                delay(1_000L)
            }
        }

        private fun buildSlots(idx: MediaIndex, durationMs: Long) {
            val lastSlot = ((durationMs - 1) / SPACING_MS).toInt().coerceAtLeast(0)
            val map = IntArray(lastSlot + 1) { slot ->
                idx.keyframes.nearest(slot * SPACING_MS * 1000L + SPACING_MS * 500L)
            }
            val inverse = HashMap<Int, MutableList<Int>>()
            map.forEachIndexed { slot, kf -> if (kf >= 0) inverse.getOrPut(kf) { ArrayList() }.add(slot) }
            keyframeSlots = HashMap(inverse.mapValues { it.value.toIntArray() })
            slotKeyframe = map
        }

        private fun slotOf(positionMs: Long): Int =
            (positionMs / SPACING_MS).toInt().coerceIn(0, (slotKeyframe.size - 1).coerceAtLeast(0))

        fun notePriority(positionMs: Long) {
            pendingPriorityMs = positionMs
            lastScrubAt = SystemClock.elapsedRealtime()
            if (slotKeyframe.isEmpty()) return
            prioritySlot = slotOf(positionMs)
            // Held scrub: load the frame the next step will land on from disk ahead of time.
            val now = SystemClock.elapsedRealtime()
            val st = store
            if (st != null && lastNotedMs >= 0 && now - lastNotedAt < 1_000L) {
                val next = positionMs + (positionMs - lastNotedMs)
                if (next >= 0) nearestStoredSlot(st, slotOf(next))?.let { requestDiskLoad(st, it) }
            }
            lastNotedMs = positionMs
            lastNotedAt = now
        }

        private suspend fun loop(idx: MediaIndex) {
            val anchorMs = sample()?.positionMs?.takeIf { it > 0 } ?: lastPositionMs
            var anchorSlot = slotOf(anchorMs)
            prepareAnchorMs = anchorSlot * SPACING_MS
            // Full makes the fine detail around the start first; the quicker choices make the screen's grid first
            // while the screen is up, and go back to the detail around the playhead once it is gone.
            fun gridFirst() = if (beforePlay != ThumbsBeforePlay.FULL && prepareStage in 1..4) prepareStride() else 0
            var orderedGridFirst = gridFirst()
            fun order(anchor: Int) = SeekGrid.latticeOrder(slotKeyframe.size, latticeStrides, anchor,
                beforeSlots = (LOCAL_BEFORE_MS / SPACING_MS).toInt(), afterSlots = (LOCAL_AFTER_MS / SPACING_MS).toInt(),
                gridFirst = orderedGridFirst)
            var lattice = order(anchorSlot)
            latticeMaxRequests = lattice.size * 5 / 4 + 10
            hardMaxRequests = (if (onDemand) (reader?.requests ?: 0) else latticeMaxRequests) + SCRUB_REQUEST_ALLOWANCE
            var latticeLogged = false
            var latticePos = 0
            var neighbourCenter = -1
            var neighbours: List<Int> = emptyList()
            var lastWorkAt = SystemClock.elapsedRealtime()
            val st = store ?: return
            laneChange(laneCtl.onPhase(phase()))
            Log.i(TAG, "decode lanes start=${laneCtl.lanes} ceiling=${laneCtl.ceiling()} " +
                "cpus=${Runtime.getRuntime().availableProcessors()}")
            while (true) {
                tendReserve()
                naturalFunding()
                settleFetches(st)
                settleDecodes()
                tendLanes()
                tendCopy()
                if (decodeJobs.size >= laneCtl.lanes) {
                    awaitAnyDecode()
                    continue
                }
                // The screen came up late (held after the first frame) or went away: same slots, new order.
                val wantGridFirst = gridFirst()
                if (wantGridFirst != orderedGridFirst && !latticeFinished) {
                    orderedGridFirst = wantGridFirst
                    lattice = order(anchorSlot)
                    latticePos = 0
                }
                if ((fourK && !MemoryGovernor.isStrongBox) || prepareStage in 1..4) {
                    // Without the screen the paused hint counts the whole pass on this box.
                    val grid = if (prepareStage in 1..4) prepareStride() else latticeStrides.last()
                    val counted = lattice.filter { it % grid == 0 }
                    latticeTotal = counted.size
                    latticeDone = counted.count { !needs(st, it) }
                    if (prepareStage in 1..4) {
                        latticeMade = lattice.count { !needs(st, it) }
                        latticeWorkLeft = PrepareHandover.workLeft(lattice, prepareStride()) { needs(st, it) }
                        prepareLastCoverage = counted.sorted().map { !needs(st, it) }
                        tendPrepare(latticeTotal, latticeDone)
                    }
                }
                // Playback jumped far: re-centre the pass there. Same slots, new order; needs() skips finished ones.
                val jumpTo = reanchorToMs
                if (jumpTo >= 0L) {
                    reanchorToMs = -1L
                    val target = slotOf(jumpTo)
                    if (!latticeFinished && !afterPlayback && kotlin.math.abs(target - anchorSlot) * SPACING_MS > REANCHOR_JUMP_MS) {
                        Log.d(TAG, "coverage re-centred at ${target * SPACING_MS / 1000} s (was ${anchorSlot * SPACING_MS / 1000} s)")
                        anchorSlot = target
                        prepareAnchorMs = target * SPACING_MS
                        lattice = order(target)
                        latticePos = 0
                    }
                }
                if (afterPlayback && (latticeFinished || afterPlaybackExpired() ||
                        (reader?.let { it.requests >= hardMaxRequests } == true))) {
                    while (decodeJobs.isNotEmpty()) awaitAnyDecode()
                    Log.i(TAG, "4K-C: " + (if (latticeFinished) "coverage pass finished" else "time/request limit") +
                        " after the player closed (stored=${st.slots().size})")
                    return
                }
                val copyHolds = copyHoldsLattice()
                val usenetHold = usenet && copyHolds
                // While a Usenet title's copy is tried, only the spot a scrub stops on is read from the stream.
                var landingStop = 0L
                val p0 = when {
                    fetchesStopped -> -1
                    usenetHold -> lastScrubAt.let { at -> usenetLanding(st, at).also { if (it >= 0) landingStop = at } }
                    else -> prioritySlot
                }
                var slot = -1
                var priority = 2
                if (usenetHold) {
                    if (p0 >= 0) {
                        neighbourCenter = -1
                        neighbours = listOf(p0)
                        slot = p0
                        priority = 0
                    }
                } else if (onDemand) {
                    // Only the settle target itself, no neighbours.
                    if (p0 >= 0 && wanted(st, p0)) {
                        neighbourCenter = -1
                        neighbours = listOf(p0)
                        slot = p0
                        priority = 0
                    } else if (p0 >= 0 && prioritySlot == p0) {
                        prioritySlot = -1
                    }
                } else if (p0 >= 0) {
                    // P0: the settle target, then P1: its neighbours, forward first.
                    if (p0 != neighbourCenter) {
                        neighbourCenter = p0
                        neighbours = buildList {
                            add(p0)
                            for (d in 1..priorityRadius) {
                                if (p0 + d < slotKeyframe.size) add(p0 + d)
                                if (p0 - d >= 0) add(p0 - d)
                            }
                        }
                    }
                    val next = neighbours.firstOrNull { wanted(st, it) }
                    if (next != null) {
                        slot = next
                        priority = if (next == p0) 0 else 1
                    } else if (prioritySlot == p0) {
                        prioritySlot = -1
                    }
                }
                if (slot < 0 && !fetchesStopped && !onDemand) {
                    val check = nextCheckSlot(st)
                    if (check >= 0) slot = check
                }
                if (slot < 0) {
                    // P3: keyframes playback already downloaded, no network needed.
                    val tapped = spooledSlot(st)
                    if (tapped >= 0) {
                        slot = tapped
                        priority = 3
                    }
                }
                if (slot < 0 && !fetchesStopped && !copyHolds && !onDemand) {
                    while (latticePos < lattice.size && !wanted(st, lattice[latticePos])) latticePos++
                    if (latticePos < lattice.size) slot = lattice[latticePos]
                }
                if (slot < 0 && decodeJobs.isNotEmpty()) {
                    awaitAnyDecode()
                    continue
                }
                if (slot < 0 && !latticeLogged && latticePos >= lattice.size) {
                    latticeLogged = true
                    latticeFinished = true
                    latticeComplete = lattice.isNotEmpty() &&
                        lattice.count { st.has(it.toLong() * SPACING_MS * 1000L) } >= lattice.size * 97 / 100
                    if (latticeComplete) st.markComplete()
                    bumpTick()                            // hides the paused progress hint
                    Log.i(TAG, "lattice done: slots=${lattice.size} requests=$latticeRequests/$latticeMaxRequests " +
                        "kb=${latticeBytes / 1024} stored=${st.slots().size}")
                }
                if (slot < 0) {
                    // Nothing to do. A 4K context (~80 MB) is closed outright, and at once when 4K may not decode.
                    val idle = SystemClock.elapsedRealtime() - lastWorkAt > IDLE_TRIM_MS
                    if (fourK) {
                        val open = sample()?.let(::fourKAllowed) ?: false
                        decodeOpen = open
                        if (!open || idle) {
                            closeDecoders()
                            buffers.releaseIdle()
                        }
                    } else {
                        if (prepareStage in 1..4) sample()
                        if (idle) {
                            trimDecoders()
                            buffers.releaseIdle()
                        }
                    }
                    delay(500L)
                    continue
                }
                if (!awaitGates(priority)) continue      // new priority while waiting: re-plan
                if (priority == 2 && latticeBudgetSpent()) {
                    latticePos = lattice.size             // coverage ends for this session; P0/P1 continue
                    latticeFinished = true
                    continue
                }
                topUpFetches(st, slot, priority, if (priority <= 1) neighbours else emptyList(), lattice, latticePos)
                if (priority == 2 && lattice.getOrNull(latticePos) == slot && fetchLimit() > 1) {
                    val ahead = (latticePos until minOf(lattice.size, latticePos + ThumbFetchLanes.MAX * 4))
                        .map { lattice[it] }.filter { it == slot || wanted(st, it) }
                    val ready = ThumbFetchLanes.nextReady(ahead) { s -> fetchState(slotKeyframe[s]) }
                    if (ready < 0) {
                        awaitAnyFetch()
                        continue
                    }
                    slot = ready
                }
                // A postponed slot (memory, rate limit) is retried, never skipped.
                val outcome = produce(idx, st, slot, priority)
                if (outcome == Outcome.POSTPONED) continue
                if (landingStop > 0L) {
                    usenetServedStopAt = landingStop
                    Log.i(COPY_TAG, "Usenet: the scrub stop at ${slot * SPACING_MS / 1000} s read from the stream ($outcome)")
                }
                if (priority == 2 && lattice.getOrNull(latticePos) == slot) latticePos++
                lastWorkAt = SystemClock.elapsedRealtime()
                if (outcome == Outcome.DONE && fourK && !MemoryGovernor.isStrongBox && priority != 0 &&
                    lastSampledPlaying) {
                    val pace = synchronized(pacer) { pacer.paceMs }
                    waitUnlessNewPriority(pace)
                }
            }
        }

        private fun needs(st: ThumbStore, slot: Int): Boolean {
            val kf = slotKeyframe.getOrElse(slot) { -1 }
            if (kf < 0) return false
            if ((attempts[kf] ?: 0) >= MAX_ATTEMPTS_PER_KEYFRAME) return false
            return !st.has(slot.toLong() * SPACING_MS * 1000L)
        }

        /** Needed and not being decoded already. */
        private fun wanted(st: ThumbStore, slot: Int): Boolean =
            needs(st, slot) && slotKeyframe.getOrElse(slot) { -1 } !in decodeJobs

        private fun latticeBudgetSpent(): Boolean =
            latticeRequests >= latticeMaxRequests || latticeBytes >= (if (fourK) LATTICE_4K_MAX_BYTES else LATTICE_MAX_BYTES)

        /** P0 only waits out a rebuffer and rate limits; P1/P2 also need a healthy buffer. False = re-plan. */
        private suspend fun awaitGates(priority: Int): Boolean {
            var anchorAhead = -1L
            var anchorSince = 0L
            val entryPriority = prioritySlot
            while (true) {
                tendReserve()                        // waits here can be long
                settleDecodes()
                tendPrepareWhileWaiting()
                tendCopy()
                if (afterPlaybackExpired()) return false
                awaitForeground()
                val now = SystemClock.elapsedRealtime()
                val r = reader
                if (r != null && r.requests >= hardMaxRequests) {
                    if (afterPlayback) return false  // the loop then ends the session
                    delay(5_000L)
                    continue
                }
                if (now < rateLimitedUntil) {
                    delay(1_000L)
                    continue
                }
                val ps = sample()
                if (ps == null) {
                    delay(500L)                      // no player right now (rebuild / engine switch): don't spin
                    return false
                }
                val playback429 = ThumbRateLimit.waitsForPlayback(
                    latched = PreviewHostSignals.hudClampLatched,
                    lastHitAtMs = PreviewHostSignals.hudClampLastHitAtMs,
                    nowMs = SystemClock.uptimeMillis(),
                    playing = ps.isPlaying,
                    playerClosed = afterPlayback,
                )
                if (playback429 || (!afterPlayback && ThumbRateLimit.playbackClampedNow())) {
                    fetchOneAtATime("playback was rate limited")
                }
                if (playback429) {
                    delay(1_000L)
                    continue
                }
                lastPositionMs = ps.positionMs
                lastSampledPlaying = ps.isPlaying
                val small4K = fourK && !MemoryGovernor.isStrongBox
                if (PrepareHandover.waitsForBuffering(ps.buffering, prepareStage in 1..4, ps.playWhenReady, small4K)) {
                    anchorAhead = -1L
                    if (small4K) closeDecoders()
                    delay(500L)
                    continue
                }
                if (fourK && !fourKAllowed(ps, priority)) {
                    decodeOpen = false
                    closeDecoders()
                    delay(500L)
                    if (prioritySlot >= 0 && prioritySlot != entryPriority) return false
                    continue
                }
                decodeOpen = true
                if (priority == 0 || !ps.isPlaying) return true
                if (priority > 0 && prioritySlot >= 0 && prioritySlot != entryPriority) return false
                val ahead = ps.bufferedMs - ps.positionMs
                val toEnd = ps.durationMs > 0 && ps.bufferedMs >= ps.durationMs - 1_000L
                if (anchorAhead < 0 || kotlin.math.abs(ahead - anchorAhead) > PLATEAU_JITTER_MS) {
                    anchorAhead = ahead
                    anchorSince = now
                }
                val plateau = ahead >= PLATEAU_MIN_AHEAD_MS && now - anchorSince >= PLATEAU_WINDOW_MS
                if (previewBufferReady(ps.isPlaying, ps.buffering, ps.loading, ahead, toEnd, GATE_BUFFER_AHEAD_MS) || plateau) return true
                delay(1_000L)
            }
        }

        private suspend fun produce(idx: MediaIndex, st: ThumbStore, slot: Int, priority: Int): Outcome {
            val kf = slotKeyframe[slot]
            val lane = freeLane() ?: run {
                awaitAnyDecode()
                return Outcome.POSTPONED
            }
            val busy = decodeJobs.size
            val tier = MemoryGovernor.tierFor(idx.video.width, idx.video.height)
            // Evaluate both: naturalFunding() also notices its own end.
            val reserveFunded = reserveFunding()
            val naturalFunded = naturalFunding()
            val screenHolds = prepareStage in 1..4 && preparePausedPlayer && !sampledPlaying &&
                reserveProvider()?.yieldHolding == true
            // Playback's held-back read-ahead covers one decode: an extra lane has to fit on its own.
            val funded = MemoryGovernor.funded(busy, tier, reserveFunded, naturalFunded, screenHolds)
            val verdict = MemoryGovernor.allow(tier, decoderWarm = lane.decoder.warm, funded = funded)
            refusedForSwap = verdict.swapLow
            if (!verdict.allowed) return deferForMemory(verdict, tier, slot, priority, busy, funded, afterFetch = false)
            attempts[kf] = (attempts[kf] ?: 0) + 1
            val make = makeExtractor ?: return Outcome.FAILED
            val t0 = SystemClock.elapsedRealtime()
            val tapped = spooledAccessUnit(kf, idx)
            val fetched = if (tapped != null) null else awaitFetch(kf, make)
            val tWaited = SystemClock.elapsedRealtime() - t0
            if (fetched != null && fetched.error != null) {
                attempts[kf] = (attempts[kf] ?: 1) - 1
                fetchFailed(fetched, st, slot)
                return Outcome.POSTPONED
            }
            val au = tapped ?: fetched?.au
            if (fetched != null) {
                fetchFailures.succeeded()
                fetchRamp.succeeded(fetched.startedAt)
            }
            val tFetch = fetched?.ms ?: tWaited
            val reqs = fetched?.requests ?: 0
            val fetchedBytes = fetched?.bytes ?: 0L
            if (priority == 2) {
                latticeRequests += reqs
                latticeBytes += fetchedBytes
            }
            if (au == null) {
                Log.w(TAG, "slot=$slot kf=$kf: keyframe not located")
                return Outcome.FAILED
            }
            // The fetch can take a while: swap is read again before the decode starts. The keyframe is kept for later.
            val late = if (MemoryGovernor.isStrongHardware) null else MemoryGovernor.swapAllows(funded)
            if (late != null && !late.allowed) {
                attempts[kf] = (attempts[kf] ?: 1) - 1
                if (fetched != null) {
                    fetches[kf] = CompletableDeferred(Fetched(fetched.au, null, 0, 0L, fetched.ms, fetched.startedAt))
                } else {
                    au.release()
                }
                refusedForSwap = true
                return deferForMemory(late, tier, slot, priority, decodeJobs.size, funded, afterFetch = true)
            }
            val concurrent = decodeJobs.values.count { !it.result.isCompleted } + 1
            lane.acquire()
            // ATOMIC: the lane is always released, even when the session ends before the decode starts.
            val result = decodeScope.async(start = CoroutineStart.ATOMIC) {
                try {
                    decodeOn(lane, au, idx)
                } catch (t: Throwable) {
                    Decoded(null, 0L, 0L, false, 0, 0, "", error = t)
                } finally {
                    lane.release()
                    au.release()
                }
            }
            val job = DecodeJob(idx, st, slot, kf, priority, lane, tapped != null, au.length, reqs, fetchedBytes, tFetch,
                tWaited, laneCtl.lanes, concurrent, result)
            decodeJobs[kf] = job
            // Every lane busy: wait for one. With one lane this is the decode just started.
            if (decodeJobs.size >= laneCtl.lanes) return awaitAnyDecode(job) ?: Outcome.DONE
            return Outcome.DONE
        }

        /** A decode refused for memory: lanes, reserve, logs and the wait before the slot is tried again. Worker only. */
        private suspend fun deferForMemory(
            verdict: GovernorVerdict, tier: DecodeTier, slot: Int, priority: Int, busy: Int, funded: Boolean,
            afterFetch: Boolean,
        ): Outcome {
            laneChange(laneCtl.onMemoryRefused())
            val close = MemoryGovernor.closesDecodersFor(verdict)
            if (verdict.swapLow) {
                val paused = !sampledPlaying
                if (swapHolds.refused(verdict, paused, funded, afterFetch, close, SystemClock.elapsedRealtime())) {
                    Log.i(TAG, swapHolds.firstLine(verdict, paused, funded, afterFetch, close))
                }
                // A running decode finishes first; its lane's decoder closes as it ends.
                if (close) closeDecoders()
            }
            if (busy > 0) {
                awaitAnyDecode()
                return Outcome.POSTPONED
            }
            if (fourK) takeReserve(tier, verdict.reason)
            if (priority == 0) {
                // A settle target that keeps being refused must not starve the coverage pass.
                if (p0DeferralSlot != slot) {
                    p0DeferralSlot = slot
                    p0Deferrals = 0
                }
                if (++p0Deferrals >= P0_MAX_DEFERRALS && prioritySlot == slot) prioritySlot = -1
            }
            giveUp.memoryRefused(verdict.reason)
            PreviewDiagnostics.note("decode waiting for memory: ${verdict.reason}")
            // Playback first: give our working set back, so the next decode must clear floor + charge again.
            if (!close) trimDecoders()
            buffers.releaseIdle()
            deferredSinceLog++
            val now = SystemClock.elapsedRealtime()
            if (now - lastDeferLogAt >= DEFER_LOG_INTERVAL_MS) {
                Log.d(TAG, "slot=$slot P$priority deferred (x$deferredSinceLog): ${verdict.reason}")
                lastDeferLogAt = now
                deferredSinceLog = 0
            }
            waitUnlessNewPriority(if (priority == 2) DEFER_WAIT_LATTICE_MS else DEFER_WAIT_PRIORITY_MS)
            return Outcome.POSTPONED
        }

        /** Lane thread. Background priority, so playback's threads win the CPU. A 4K decode takes up to 5 s on a Fire TV Stick. */
        private fun decodeOn(lane: DecoderLane, au: ThumbAu, idx: MediaIndex): Decoded {
            val cold = !lane.decoder.warm
            val t1 = SystemClock.elapsedRealtime()
            val frame = pacedDecode {
                atBackgroundPriority { lane.decoder.decode(au, idx.video, threads = 1) }
            } ?: return Decoded(null, SystemClock.elapsedRealtime() - t1, 0L, cold, 0, 0, "")
            val t2 = SystemClock.elapsedRealtime()
            if (idx.video.dolbyVision?.profile == 5 && !frame.colour.doviIpt) {
                // The FFmpeg 6.0 fallback cannot parse current profile-5 RPUs: better no thumbnails than green/purple.
                throw UnsupportedMediaException("Dolby Vision profile 5 without decoder DV metadata (fallback FFmpeg)")
            }
            val decision = ColourClassifier.classify(idx.video.colour, frame.colour, idx.video.dolbyVision)
            val size = ThumbGeometry.outputSize(frame.width, frame.height, frame.sarNum, frame.sarDen, idx.video.displayAspect)
            val bmp = pacedDecode {
                atBackgroundPriority { lane.decoder.render(frame, decision, size[0], size[1]) }
            }
            return Decoded(bmp, t2 - t1, SystemClock.elapsedRealtime() - t2, cold, size[0], size[1], decision.reason)
        }

        /** Stores every finished decode; returns [of]'s outcome if it was one of them. Worker only. */
        private fun settleDecodes(of: DecodeJob? = null): Outcome? {
            if (decodeJobs.isEmpty()) return null
            var outcome: Outcome? = null
            for (job in decodeJobs.values.filter { it.result.isCompleted }) {
                decodeJobs.remove(job.kf)
                val o = finishDecode(job)
                if (job === of) outcome = o
            }
            return outcome
        }

        private suspend fun awaitAnyDecode(of: DecodeJob? = null): Outcome? {
            val running = decodeJobs.values.map { it.result }
            if (running.isEmpty()) return null
            if (running.none { it.isCompleted }) select<Unit> { for (d in running) d.onJoin { } }
            return settleDecodes(of)
        }

        private fun finishDecode(job: DecodeJob): Outcome {
            val d = job.result.getCompleted()
            d.error?.let {
                if (it !is OutOfMemoryError) throw it
                stopForMemory("out of memory decoding")
                return Outcome.FAILED
            }
            val bmp = d.bitmap ?: run {
                giveUp.decodeFailed()
                return Outcome.FAILED
            }
            if (!d.cold) laneChange(laneCtl.onDecode(d.decodeMs + d.renderMs, job.concurrent, laneHeld()))
            measureBars(bmp)
            val kf = job.kf
            val kfPts = job.idx.keyframes.ptsUs[kf]
            val slots = keyframeSlots[kf] ?: intArrayOf(job.slot)
            val checkSlot = if (borrow != null) slots.firstOrNull { it in checkQueue } else null
            job.st.put(LongArray(slots.size) { slots[it].toLong() * SPACING_MS * 1000L }, kfPts, bmp)
            if (checkSlot != null) checkBorrow(job.st, checkSlot, bmp)
            synchronized(recentMade) {
                recentMade.addLast(job.slot.toLong() * SPACING_MS)
                madeSeq++
                if (madeSeq == 1) PreviewDiagnostics.note("first preview ready: ${d.width}x${d.height}")
                while (recentMade.size > 9) recentMade.removeFirst()
            }
            bumpTick()
            if (job.tapped) {
                dropSpooled(kf)
                tapHits++
            }
            Log.d(TAG, "slot=${job.slot} P${job.priority} kf@${kfPts / 1000}ms slots=${slots.size} " +
                (if (job.tapped) "src=tap(${job.auBytes / 1024}KB, #$tapHits) " else "") +
                "req=${job.requests} kb=${job.bytes / 1024} fetch=${job.fetchMs}ms " +
                "decode=${d.decodeMs}ms${if (d.cold) " (cold)" else ""} render=${d.renderMs}ms ${d.width}x${d.height} ${d.reason} " +
                "waited=${job.waitedMs}ms lanes=${job.lanes}")
            if (reserveHeld != null) reservePictures++
            // Small box, 4K settle target without funding: close the ~80 MB decoder at once, playback may be running.
            if (fourK && !MemoryGovernor.isStrongBox && job.priority == 0 && reserveHeld == null && !naturallyFunded) {
                job.lane.close()
            }
            return Outcome.DONE
        }

        private fun freeLane(): DecoderLane? {
            for (i in 0 until laneCtl.lanes) {
                val l = decoderLanes[i]
                if (decodeJobs.values.none { it.lane === l }) return l
            }
            return null
        }

        private fun closeDecoders() = decoderLanes.forEach { it.close() }

        private fun trimDecoders() = decoderLanes.forEach { it.trim() }

        private fun laneChange(c: ThumbDecodeLanes.Change?) {
            c ?: return
            Log.i(TAG, "decode lanes ${c.from} -> ${c.to} (${c.reason}, medianMs=${c.medianMs})")
            for (i in c.to until c.from) decoderLanes[i].close()
            buffers.limit = ThumbBufferPool.limitFor(c.to, fourK, MemoryGovernor.isStrongBox, MemoryGovernor.isLowRam)
        }

        private fun laneHeld(): Boolean = SystemClock.elapsedRealtime() < laneHeldUntil

        /** Phase ceilings, memory trims and the clock-cap hold of the decode lanes. Worker only. */
        private fun tendLanes() {
            if (laneTrimRequested) {
                laneTrimRequested = false
                laneChange(laneCtl.onMemoryTrim())
            }
            laneChange(laneCtl.onPhase(phase()))
            val now = SystemClock.elapsedRealtime()
            if (laneCtl.ceiling() <= 1 || now - laneClockAt < CLOCK_SAMPLE_MS) return
            laneClockAt = now
            if (!clockCapped()) return
            laneHeldUntil = now + CLOCK_HOLD_MS
            if (now - laneClockLoweredAt < CLOCK_HOLD_MS) return
            laneClockLoweredAt = now
            laneChange(laneCtl.onClockCap())
        }

        /** True when a core's highest allowed clock is below the highest seen since the app started. */
        private fun clockCapped(): Boolean {
            var capped = false
            for (cpu in 0 until clockPeakKhz.length()) {
                val khz = runCatching {
                    File("/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq").readText().trim().toLong()
                }.getOrNull() ?: continue
                if (khz < clockPeakKhz.accumulateAndGet(cpu, khz) { a, b -> maxOf(a, b) }) capped = true
            }
            return capped
        }

        private fun phase(): FetchPhase = when {
            afterPlayback -> FetchPhase.PLAYER_CLOSED
            prepareStage in 1..4 -> FetchPhase.GENERATING
            else -> FetchPhase.PLAYBACK
        }

        private fun fetchLimit(): Int = MediaServerThumbs.fetchLimit(onDemand, fetchRamp.limit(ThumbFetchLanes.limit(
            phase = phase(),
            rateLimited = fetchRateLimited,
            smallBox4K = fourK && !MemoryGovernor.isStrongBox,
            ledgerCap = ConnectionLedger.app.thumbnailCap(reader?.resolvedUrl ?: source.url),
            memoryConstrained = MemoryGovernor.isLowRam,
        )))

        /** Remembers the host that answered 429, and lets a held generating screen start playback. */
        private fun noteRateLimited(e: RateLimitedException) {
            sourceRateLimited = true
            RateLimitedHosts.appSession.note(e)
        }

        private fun fetchOneAtATime(why: String) {
            if (fetchRateLimited) return
            fetchRateLimited = true
            Log.i(TAG, "$why: one thumbnail fetch at a time from now on")
        }

        /**
         * Starts fetches for the next keyframes in the worker's own order, up to the lane limit. While playing, a settle
         * target's gate does not cover the others, so only it is started then.
         */
        private fun topUpFetches(
            st: ThumbStore, slot: Int, priority: Int, neighbours: List<Int>, lattice: List<Int>, latticePos: Int,
        ) {
            val make = makeExtractor ?: return
            val plan = ArrayList<Int>()
            fun want(s: Int) {
                if (!wanted(st, s)) return
                val kf = slotKeyframe[s]
                if (spool[kf] != true) plan.add(kf)
            }
            want(slot)
            val ahead = plan.size
            for (s in neighbours) want(s)
            if (!fetchesStopped && !latticeFinished && !latticeBudgetSpent()) {
                for (i in latticePos until minOf(lattice.size, latticePos + ThumbFetchLanes.MAX * 4)) want(lattice[i])
            }
            // Done fetches the plan has moved away from (an earlier settle target's neighbours) give their lane back.
            fetches.entries.removeAll { (kf, d) ->
                (kf !in plan && d.isCompleted && d.getCompleted().error == null).also { if (it) d.getCompleted().au?.release() }
            }
            val limit = fetchLimit()
            if (limit <= 1 || (priority == 0 && lastSampledPlaying)) plan.subList(ahead, plan.size).clear()
            for (kf in ThumbFetchLanes.pick(plan, fetches.keys, minOf(limit - fetches.size, buffers.available))) {
                startFetch(kf, make, buffers.tryAcquire() ?: break)
            }
        }

        private fun startFetch(kf: Int, make: () -> KeyframeExtractor, buf: ThumbBuffer): Deferred<Fetched> {
            val ex = spareExtractors.poll() ?: make()
            val startedAt = SystemClock.elapsedRealtime()
            // ATOMIC: the body always runs, so the buffer always goes back, also when the result is never collected.
            val made = AtomicReference<ThumbAu?>()
            val d = fetchScope.async(start = CoroutineStart.ATOMIC) {
                val req0 = ex.requests
                val bytes0 = ex.bytesRead
                val t0 = SystemClock.elapsedRealtime()
                var au: ThumbAu? = null
                try {
                    au = ex.extractAu(kf, buf)
                    made.set(au)
                    Fetched(au, null, ex.requests - req0, ex.bytesRead - bytes0, SystemClock.elapsedRealtime() - t0, startedAt)
                } catch (e: Exception) {
                    Fetched(null, e, ex.requests - req0, ex.bytesRead - bytes0, SystemClock.elapsedRealtime() - t0, startedAt)
                } catch (e: OutOfMemoryError) {
                    Fetched(null, MemoryShortException("out of memory reading a keyframe"), ex.requests - req0,
                        ex.bytesRead - bytes0, SystemClock.elapsedRealtime() - t0, startedAt)
                } finally {
                    if (au == null) buf.release()
                    spareExtractors.add(ex)
                }
            }
            d.invokeOnCompletion { if (it != null) made.get()?.release() }
            fetches[kf] = d
            return d
        }

        /** Drops the latest fetched keyframe other than [kf], to free its buffer. Worker only. */
        private fun dropReadyFetch(kf: Int): Boolean {
            val ready = fetches.entries.lastOrNull { (k, d) -> k != kf && d.isCompleted && d.getCompleted().error == null }
                ?: return false
            fetches.remove(ready.key)
            ready.value.getCompleted().au?.release()
            return true
        }

        private fun fetchState(kf: Int): ThumbFetchLanes.State {
            val d = fetches[kf] ?: return ThumbFetchLanes.State.NOT_STARTED
            if (!d.isCompleted) return ThumbFetchLanes.State.RUNNING
            return if (d.getCompleted().error == null) ThumbFetchLanes.State.READY else ThumbFetchLanes.State.FAILED
        }

        /** Until a running fetch (or decode) finishes. Worker only. */
        private suspend fun awaitAnyFetch() {
            val running = fetches.values.filter { !it.isCompleted }
            val decoding = decodeJobs.values.map { it.result }.filter { !it.isCompleted }
            if (running.isEmpty() && decoding.isEmpty()) return
            select<Unit> {
                for (j in running) j.onJoin { }
                for (d in decoding) d.onJoin { }
            }
        }

        /** This keyframe's fetch; started now, once a lane is free, if it is not under way yet. */
        private suspend fun awaitFetch(kf: Int, make: () -> KeyframeExtractor): Fetched {
            val d = fetches[kf] ?: startFetch(kf, make, awaitBuffer(kf))
            try {
                return d.await()
            } catch (e: CancellationException) {
                d.invokeOnCompletion { if (it == null) d.getCompleted().au?.release() }
                throw e
            } finally {
                fetches.remove(kf)
            }
        }

        /** A buffer for [kf]'s fetch once a fetch lane is free; a fetched keyframe not needed now gives its own up. */
        private suspend fun awaitBuffer(kf: Int): ThumbBuffer {
            val limit = fetchLimit()
            while (true) {
                val running = fetches.values.filter { !it.isCompleted }
                if (running.size < limit) {
                    buffers.tryAcquire()?.let { return it }
                    if (dropReadyFetch(kf)) continue
                    if (running.isEmpty()) {
                        delay(50L)          // the decodes still hold them
                        continue
                    }
                }
                select<Unit> { for (j in running) j.onJoin { } }
            }
        }

        /** Done fetches no longer needed are dropped, failed ones dealt with at once. */
        private suspend fun settleFetches(st: ThumbStore) {
            if (fetches.isEmpty()) return
            for ((kf, d) in fetches.entries.toList()) {
                if (!d.isCompleted) continue
                val f = d.await()
                val unneeded = fetchesStopped || keyframeSlots[kf]?.none { needs(st, it) } != false
                if (f.error == null && !unneeded) continue
                fetches.remove(kf)
                f.au?.release()
                if (f.error != null && !fetchesStopped) fetchFailed(f, st, keyframeSlots[kf]?.firstOrNull() ?: -1)
            }
        }

        private suspend fun fetchFailed(f: Fetched, st: ThumbStore, slot: Int) {
            when (val e = f.error) {
                null -> Unit
                is RateLimitedException -> {
                    giveUp.rateLimited()
                    noteRateLimited(e)
                    fetchOneAtATime("rate limited")
                    val now = SystemClock.elapsedRealtime()
                    val waitMs = fetchRamp.rateLimited(e.retryAfterMs, f.startedAt, now) ?: return
                    rateLimitedUntil = maxOf(rateLimitedUntil, now + waitMs)
                    val why = if (e.retryAfterMs != null) "server's Retry-After" else "back-off ${fetchRamp.hitsInRow}"
                    Log.w(TAG, "rate limited: pausing thumbnail fetches ${waitMs / 1000}s ($why)")
                }
                is UnsupportedMediaException -> throw e
                is MemoryShortException -> {
                    stopForMemory(e.message ?: "out of memory")
                    releasePrepareHold()
                }
                is ConnectionLedger.NoSlotException -> {
                    rethrowIfStopping(e)
                    giveUp.noConnectionFree()
                    rateLimitedUntil = maxOf(rateLimitedUntil, SystemClock.elapsedRealtime() + 1_000L)
                }
                is IOException -> {
                    rethrowIfStopping(e)
                    giveUp.fetchFailed(e)
                    fetchOneAtATime("a fetch failed")
                    // Fetches under way together fail together: counted once.
                    val waitMs = fetchFailures.failedFetch(f.startedAt, SystemClock.elapsedRealtime()) ?: return
                    if (fetchFailures.gaveUp) {
                        fetchesStopped = true
                        latticeFinished = true
                        releasePrepareHold()
                        bumpTick()
                        Log.w(TAG, "${fetchFailures.count} fetches failed in a row (${e.javaClass.simpleName}): " +
                            "no new thumbnails this session (stored=${st.slots().size})")
                        return
                    }
                    rateLimitedUntil = maxOf(rateLimitedUntil, SystemClock.elapsedRealtime() + waitMs)
                    Log.w(TAG, "slot=$slot fetch failed (${e.javaClass.simpleName}): retry in ${waitMs / 1000}s")
                }
                else -> throw e
            }
        }

        private val memoryStop = MemoryStop()

        /** After the player closed: the box stays short of memory, so the coverage pass stops for this title. */
        private fun memoryShortAfterClose(): Boolean {
            if (memoryStop.stopped) return true
            if (!memoryStop.check(SystemClock.elapsedRealtime()) { MemoryGovernor.pressure() }) return false
            Log.i(TAG, "4K-C: memory short (${memoryStop.reason}) - stopping the coverage pass for this title")
            return true
        }

        /** Out of memory: no new thumbnails for this title, nothing retried. Worker only. */
        private fun stopForMemory(why: String) {
            giveUp.memoryRefused(why)
            if (memoryStop.outOfMemory(why)) {
                Log.w(TAG, "$why: no new thumbnails for this title (stored=${store?.slots()?.size})")
            }
            fetchesStopped = true
            latticeFinished = true
            tapOpen = false
            copy?.stop("out of memory")
            trimDecoders()
            buffers.releaseIdle()
            bumpTick()
        }

        private inline fun <T> pacedDecode(block: () -> T): T {
            // Only decodes while playing can cost playback frames.
            if (!fourK || MemoryGovernor.isStrongBox || !sampledPlaying) return block()
            synchronized(pacer) { pacer.decodeStarted(SystemClock.elapsedRealtime()) }
            try {
                return block()
            } finally {
                synchronized(pacer) { pacer.decodeEnded(SystemClock.elapsedRealtime()) }
            }
        }

        /** Main thread, once a second: feeds [pacer] dropped frames and stalls, except drops around seeks and pauses. */
        private suspend fun monitorPlayback() {
            var quietUntil = 0L
            var lastPos = -1L
            var lastAt = 0L
            var lastLogAt = SystemClock.elapsedRealtime()
            var countedAtLog = 0
            var ignoredAtLog = 0
            while (!ended) {
                val now = SystemClock.elapsedRealtime()
                val p = if (afterPlayback) null else playerProvider()
                if (p != null) {
                    val playing = p.isPlaying
                    val buffering = p.playbackState == Player.STATE_BUFFERING
                    if (playing) {
                        // Playing again by any route. The worker must see it too, or it re-takes the cap from a stale sample.
                        sampledPlaying = true
                        if (prepareActive()) prepareStepAside()
                        if (reserveIsPauseCap) runCatching { reserveHeld?.release() }
                    }
                    val pos = p.currentPosition
                    if (!playing || buffering) {
                        quietUntil = now + SEEK_QUIET_MS
                    } else if (lastPos >= 0) {
                        val expected = lastPos + ((now - lastAt) * p.speed).toLong()
                        if (kotlin.math.abs(pos - expected) > SEEK_JUMP_MS) quietUntil = now + SEEK_QUIET_MS
                    }
                    if (now - lastScrubAt < SEEK_QUIET_MS) quietUntil = maxOf(quietUntil, lastScrubAt + SEEK_QUIET_MS)
                    lastPos = pos
                    lastAt = now
                    val dropped = runCatching {
                        p.droppedFrames
                    }.getOrNull() ?: -1
                    val stallAt = runCatching { reserveProvider()?.lastStallRealtimeMs }.getOrNull() ?: 0L
                    val ticks = synchronized(pacer) {
                        listOf(
                            pacer.observeDrops(now, dropped, excluded = now < quietUntil)
                                .let { PaceTick(it, pacer.lastNote, pacer.paceMs, pacer.levelPaceMs) },
                            pacer.observeStall(now, stallAt).let { PaceTick(it, pacer.lastNote, pacer.paceMs, pacer.levelPaceMs) },
                        )
                    }
                    for (t in ticks) when (t.event) {
                        PlaybackDecodePacer.Event.SLOWER -> Log.d(TAG, "4K pace: ${t.levelPaceMs / 2000} s -> " +
                            "${t.levelPaceMs / 1000} s (${t.note}), now ${t.paceMs / 1000} s")
                        PlaybackDecodePacer.Event.FASTER -> Log.d(TAG, "4K pace: ${t.levelPaceMs * 2 / 1000} s -> " +
                            "${t.levelPaceMs / 1000} s (${t.note}), now ${t.paceMs / 1000} s")
                        PlaybackDecodePacer.Event.STOPPED_DROPS ->
                            Log.i(TAG, "4K playback decodes stopped: drops (${t.note}) - coverage pass only while paused / after exit")
                        PlaybackDecodePacer.Event.STOPPED_STALL ->
                            Log.i(TAG, "4K playback decodes stopped: stall (${t.note})")
                        PlaybackDecodePacer.Event.RESUMED_AFTER_STALL ->
                            Log.i(TAG, "4K playback decodes resumed (${t.note}), pace ${t.paceMs / 1000} s")
                        PlaybackDecodePacer.Event.NONE -> Unit
                    }
                    if (copy != null) {
                        val copyTicks = synchronized(copyPacer) {
                            listOf(
                                copyPacer.observeDrops(now, dropped, excluded = now < quietUntil),
                                copyPacer.observeStall(now, stallAt),
                            ).map { Triple(it, copyPacer.lastNote, copyPacer.paceMs) }
                        }
                        for ((event, note, paceMs) in copyTicks) {
                            if (event != PlaybackDecodePacer.Event.NONE) {
                                Log.i(COPY_TAG, "copy pace: ${event.name.lowercase()} ($note), pace now ${paceMs / 1000.0} s")
                            }
                        }
                    }
                    val pace = ticks.last().paceMs
                    if (now - lastLogAt >= PACE_LOG_INTERVAL_MS) {
                        lastLogAt = now
                        val (counted, ignored, stops) = synchronized(pacer) {
                            Triple(pacer.countedDrops, pacer.ignoredDrops, pacer.stoppedByDrops to pacer.stoppedByStall)
                        }
                        Log.d(TAG, "4K pace status: pace ${pace / 1000} s, drops counted ${counted - countedAtLog} " +
                            "ignored ${ignored - ignoredAtLog} in ${PACE_LOG_INTERVAL_MS / 1000} s (counter $dropped), " +
                            "stopped drops=${stops.first} stall=${stops.second}, natural=$naturallyFunded " +
                            "reserve=${reserveHeld != null}, playing=$playing")
                        countedAtLog = counted
                        ignoredAtLog = ignored
                    }
                } else {
                    lastPos = -1L
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }

        private class PaceTick(val event: PlaybackDecodePacer.Event, val note: String, val paceMs: Long, val levelPaceMs: Long)

        private inline fun <T> atBackgroundPriority(block: () -> T): T {
            val tid = android.os.Process.myTid()
            val before = runCatching { android.os.Process.getThreadPriority(tid) }.getOrNull()
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            try {
                return block()
            } finally {
                if (before != null) runCatching { android.os.Process.setThreadPriority(before) }
            }
        }

        private suspend fun waitUnlessNewPriority(ms: Long) {
            val entry = prioritySlot
            val until = SystemClock.elapsedRealtime() + ms
            while (SystemClock.elapsedRealtime() < until) {
                if (prioritySlot >= 0 && prioritySlot != entry) return
                if (inBackground()) return
                tendReserve()
                settleDecodes()
                tendPrepareWhileWaiting()
                delay(250L)
            }
        }

        /** Gate and deferral waits can last many seconds: keeps the generating screen up to date from them. */
        private suspend fun tendPrepareWhileWaiting() {
            if (prepareStage !in 1..4 || latticeTotal <= 0) return
            val now = SystemClock.elapsedRealtime()
            if (now - prepareTendedAt < 1_000L) return
            prepareTendedAt = now
            samplePlayer()?.let { sampledPlaying = it.isPlaying }
            tendPrepare(latticeTotal, latticeDone)
        }

        /** True when a keyframe due within [TAP_ARM_LEAD_US] after [timeUs] serves a slot that still needs a thumbnail. */
        fun tapWants(timeUs: Long): Boolean {
            if (ended || !tapOpen) return false
            if (index?.video?.codec?.nal != true) return false   // the tap handles H.264/HEVC only
            val map = slotKeyframe
            val pts = index?.keyframes?.ptsUs ?: return false
            val st = store ?: return false
            if (map.isEmpty() || timeUs < 0) return false
            val holdsUntilPause = fourK && remux && !MemoryGovernor.isStrongBox
            val s0 = (timeUs / 1000L / SPACING_MS).toInt()
            for (sl in s0..s0 + 1) {
                if (sl !in map.indices) continue
                val kf = map[sl]
                if (kf !in pts.indices) continue
                val due = pts[kf] - timeUs
                if (due in 1..TAP_ARM_LEAD_US && !spool.containsKey(kf) &&
                    TapPolicy.copies(sl, holdsUntilPause, latticeStrides.last(), latticeFinished,
                        spoolFull = spoolBytes.get() >= SPOOL_MAX_BYTES) &&
                    !st.has(sl.toLong() * SPACING_MS * 1000L)
                ) return true
            }
            return false
        }

        fun tapAccepts(timeUs: Long): Boolean {
            if (ended || !tapOpen || timeUs < 0) return false
            val idx = index ?: return false
            val kf = idx.keyframes.nearest(timeUs)
            if (kf < 0 || kotlin.math.abs(idx.keyframes.ptsUs[kf] - timeUs) > TAP_MATCH_US) return false
            if (spool.containsKey(kf)) return false
            val st = store ?: return false
            val slots = keyframeSlots[kf] ?: return false
            return slots.any { !st.has(it.toLong() * SPACING_MS * 1000L) } &&
                spoolPending.get() < (if (MemoryGovernor.isLowRam) 1 else SPOOL_MAX_PENDING) && spoolBytes.get() < SPOOL_MAX_BYTES
        }

        fun tapOffer(timeUs: Long, sample: ByteArray) {
            if (ended || !tapOpen) return
            if (MemoryGovernor.isLowRam && sample.size > 2 * 1024 * 1024) return
            val idx = index ?: return
            val kf = idx.keyframes.nearest(timeUs)
            if (kf < 0) return
            if (kotlin.math.abs(idx.keyframes.ptsUs[kf] - timeUs) > TAP_MATCH_US) {
                if (!tapMismatchLogged) {
                    tapMismatchLogged = true
                    Log.i(TAG, "tap: player time ${timeUs / 1000}ms matches no index keyframe (nearest " +
                        "${idx.keyframes.ptsUs[kf] / 1000}ms) - tap unused for this title")
                }
                return
            }
            if (spool.putIfAbsent(kf, false) != null) return
            if (spoolPending.incrementAndGet() > (if (MemoryGovernor.isLowRam) 1 else SPOOL_MAX_PENDING)) {
                spoolPending.decrementAndGet()
                spool.remove(kf)
                return
            }
            spoolWriter.launch {
                try {
                    spoolDir.mkdirs()
                    File(spoolDir, "$kf.au").writeBytes(sample)
                    spoolBytes.addAndGet(sample.size.toLong())
                    spoolOrder.addLast(kf)
                    spool[kf] = true
                    while (spoolBytes.get() > SPOOL_MAX_BYTES) {            // FIFO under the cap
                        val old = spoolOrder.pollFirst() ?: break
                        dropSpooled(old)
                    }
                } catch (t: Throwable) {
                    spool.remove(kf)
                } finally {
                    spoolPending.decrementAndGet()
                }
            }
        }

        private fun dropSpooled(kf: Int) {
            if (spool.remove(kf) == null) return
            val f = File(spoolDir, "$kf.au")
            spoolBytes.addAndGet(-f.length())
            f.delete()
        }

        private fun spooledAccessUnit(kf: Int, idx: MediaIndex): ThumbAu? {
            if (spool[kf] != true) return null
            val f = File(spoolDir, "$kf.au")
            if (f.length() > ThumbReads.MAX_READ) return null
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return null
            return tapSampleToAnnexB(bytes, idx.video)?.let { ThumbAu.of(it) }
        }

        private fun spooledSlot(st: ThumbStore): Int {
            if (!memoryStop.allowsSideWork) return -1
            for ((kf, ready) in spool) {
                if (!ready) continue
                val slot = keyframeSlots[kf]?.firstOrNull { wanted(st, it) }
                if (slot != null) return slot
                if (keyframeSlots[kf]?.none { needs(st, it) } != false) dropSpooled(kf)   // nothing left to serve
            }
            return -1
        }

        fun stripSpacingMs(): Long? = if (index != null && slotKeyframe.isNotEmpty()) latticeStrides.last() * SPACING_MS else null

        /** Does not touch [lastServed]. A picture only on disk is loaded and shows on the next tick. */
        fun tileFor(positionMs: Long): Shown? {
            val st = store ?: return null
            if (slotKeyframe.isEmpty()) return null
            val target = slotOf(positionMs)
            val slot = nearestStoredSlot(st, target) ?: return null
            val bmp = st.memGet(slot.toLong() * SPACING_MS * 1000L)
            if (bmp == null) {
                requestDiskLoad(st, slot)
                return null
            }
            return Shown(bmp, slotPtsMs(slot), exact = slot == target, crop = crop.current)
        }

        fun shownFor(positionMs: Long): Shown? {
            val st = store ?: return null
            if (slotKeyframe.isEmpty()) return null
            val target = slotOf(positionMs)
            val nearest = nearestStoredSlot(st, target)
            // A borrowed picture only when it is nearer than our own stand-in.
            val ownGap = nearest?.let { kotlin.math.abs(it - target) } ?: Int.MAX_VALUE
            if (ownGap > 0) borrowedFor(target, within = ownGap)?.let { b ->
                lastServed = b.bitmap
                lastServedSlot = target
                return b
            }
            val slot = nearest ?: return null
            val key = slot.toLong() * SPACING_MS * 1000L
            val bmp = st.fullColourGet(key) ?: st.memGet(key)?.also { requestFullColour(st, slot) }
            if (bmp != null) {
                lastServed = bmp
                lastServedSlot = slot
                return Shown(bmp, slotPtsMs(slot), exact = slot == target, crop = crop.current)
            }
            // On disk only: load it, and keep the previous picture until it lands rather than blanking the pane.
            requestDiskLoad(st, slot)
            val prev = lastServed ?: return null
            return Shown(prev, slotPtsMs(lastServedSlot), exact = false, crop = crop.current)
        }

        fun landingFor(targetMs: Long): Long? {
            if (store == null || slotKeyframe.isEmpty()) return null
            // The slot's keyframe even when its picture is not stored yet: a CLOSEST_SYNC seek to the bare grid point
            // can snap back onto the keyframe playback is already on.
            return slotPtsMs(slotOf(targetMs)).takeIf { it >= 0 }
        }

        fun stepPastBase(gridMs: Long, baseMs: Long, forward: Boolean): Long =
            SeekGrid.stepPastBase(gridMs, baseMs, forward, SPACING_MS, slotKeyframe.size, ::slotPtsMs)

        private fun slotPtsMs(slot: Int): Long {
            val kf = slotKeyframe.getOrElse(slot) { -1 }
            val pts = index?.keyframes?.ptsUs ?: return -1L
            return if (kf in pts.indices) pts[kf] / 1000L else -1L
        }

        /** The stored slot (memory or disk) nearest to [slot] within [fallbackSlots], exact slot first. */
        private fun nearestStoredSlot(st: ThumbStore, slot: Int): Int? {
            if (st.has(slot.toLong() * SPACING_MS * 1000L)) return slot
            for (d in 1..fallbackSlots) {
                if (slot - d >= 0 && st.has((slot - d).toLong() * SPACING_MS * 1000L)) return slot - d
                if (slot + d < slotKeyframe.size && st.has((slot + d).toLong() * SPACING_MS * 1000L)) return slot + d
            }
            return null
        }

        fun frameAspect(): Float? {
            if (ended || slotKeyframe.isEmpty()) return null
            // 4K that cannot decode right now and has nothing stored: no empty frame to fill, so no pane.
            if (!decodeOpen && store?.isEmpty() != false && borrow == null) return null
            val v = index?.video ?: return null
            val size = ThumbGeometry.outputSize(v.width, v.height, 1, 1, v.displayAspect)
            // The crop was measured on stored pictures, whose size can differ from this estimate: apply it as fractions.
            val c = crop.current?.takeIf { it.width > 0 && it.height > 0 }
            val fw = if (c == null) 1f else (c.width - c.left - c.right).toFloat() / c.width
            val fh = if (c == null) 1f else (c.height - c.top - c.bottom).toFloat() / c.height
            return (size[0] * fw) / (size[1] * fh).coerceAtLeast(1f)
        }

        private fun measureBars(bmp: Bitmap) {
            if (crop.sampleCount >= ThumbCrop.Estimator.MAX_SAMPLES) return
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            runCatching { bmp.getPixels(px, 0, w, 0, 0, w, h) }.onFailure { return }
            crop.add(w, h, ThumbCrop.measure(w, h, px))
        }

        /** A reopened store: measure the bars on a few stored pictures, so the first scrub is already cropped. */
        private fun seedCropFromStore(st: ThumbStore) {
            val stored = st.slots().sorted()
            if (stored.isEmpty()) return
            val n = minOf(ThumbCrop.Estimator.MIN_SAMPLES + 3, stored.size)
            for (i in 0 until n) {
                val bmp = st.memGet(stored[i * stored.size / n]) ?: st.load(stored[i * stored.size / n]) ?: continue
                measureBars(bmp)
            }
        }

        /** A title with saved pictures: their bytes go to memory, and the ones around the position are decoded. */
        private fun preloadSaved(st: ThumbStore) {
            if (st.isEmpty()) return
            worker.launch {
                val cap = MemoryGovernor.preloadBytes()
                if (!st.preloadCompressed(cap)) return@launch
                val here = slotOf(lastPositionMs.coerceAtLeast(0L))
                for (d in 0..PRELOAD_WARM_SLOTS) {
                    for (s in intArrayOf(here + d, here - d)) {
                        if (s in slotKeyframe.indices) st.load(s.toLong() * SPACING_MS * 1000L)
                    }
                }
                bumpTick()
                Log.d(TAG, "saved thumbnails in memory (${st.slots().size})")
            }
        }

        /**
         * Another release's store (same title and length), read only; shown until confirmed, then copied over. [copy]:
         * a smaller copy's store that pass is still filling. [wideUs]: keyframes farther apart are not copied over.
         */
        private class Borrow(
            val store: ThumbStore, val ratio: Double, val slotCount: Int,
            val copy: CopyPass? = null, val wideUs: Long = ReleaseMatch.WIDE_US,
            val fit: BorrowFit? = null,
        ) {
            /** Borrowed slot -> (its picture, that picture fitted to ours), for the few shown while scrubbing. */
            val fitted = LruCache<Int, Pair<Bitmap, Bitmap>>(FITTED_SHOWN_KEPT)
            @Volatile var confirmed = false
            /** A clear mismatch was seen: hidden, and one more same-frame pair decides whether it is remembered. */
            @Volatile var failed = false

            fun map(slot: Int): Int = ReleaseMatch.mapSlot(slot, ratio, slotCount)
        }

        /** Another release's pictures are [fromW] x [fromH]: each is scaled to ours and its bars cut or added by [plan]. */
        private class BorrowFit(val plan: CopyFit.Plan, val fromW: Int, val fromH: Int, val toW: Int, val toH: Int)

        private fun fitTo(src: Bitmap, f: BorrowFit): Bitmap {
            val out = Bitmap.createBitmap(f.toW, f.toH, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(Color.BLACK)
                drawBitmap(src, null, Rect(0, f.plan.top, f.plan.renderW, f.plan.top + f.plan.renderH),
                    Paint(Paint.FILTER_BITMAP_FLAG))
            }
            return out
        }

        @Volatile private var borrow: Borrow? = null
        private var borrowSince = 0L
        /** Own slots whose first decode is compared with the borrowed picture, in order. Worker only. */
        private val checkQueue = ArrayList<Int>()
        /** Check slot -> own to borrowed keyframe gap (us). Worker only. */
        private val checkGap = HashMap<Int, Long>()
        private var checkMatches = 0

        private fun slotUs(slot: Int): Long = slot.toLong() * SPACING_MS * 1000L

        /**
         * An incomplete store looks for another release of the title with the same length. [confirmedOnly]: only one
         * this store confirmed before, copied at once. Worker only.
         */
        private fun setUpBorrow(idx: MediaIndex, st: ThumbStore, confirmedOnly: Boolean) {
            if (!source.identityExact || borrow != null || slotKeyframe.isEmpty() || coverageStored(st)) return
            val ownMs = lastDurationMs.takeIf { it > 0 } ?: (idx.durationUs / 1000L)
            val v = idx.video
            val own = ThumbGeometry.outputSize(v.width, v.height, 1, 1, v.displayAspect)
            val candidates = runCatching { ThumbStore.siblings(context, source.identity, st.key) }.getOrDefault(emptyList())
                .filter {
                    it.pictures >= MIN_BORROW_PICTURES && st.verdictOn(it.key) != false &&
                        (!confirmedOnly || st.verdictOn(it.key) == true) && releasePair(st.key, it.key) !in differentReleases
                }
                .mapNotNull { s -> ReleaseMatch.ratio(ownMs, s.durationMs)?.let { s to it } }
                .sortedWith(compareByDescending<Pair<ThumbStore.Sibling, Double>> { st.verdictOn(it.first.key) == true }
                    .thenByDescending { it.first.pictures })
            for ((sib, ratio) in candidates) {
                ThumbStore.markBusy(sib.key)
                val other = runCatching { ThumbStore.openOther(context, sib.key, rgb565) }.getOrNull()
                val size = other?.let { o -> o.slots().minOrNull()?.let(o::compressed)?.let(::pictureSize) }
                val sameSize = size != null && size[0] == own[0] && size[1] == own[1]
                val plan = if (size == null || sameSize) null else CopyFit.plan(size[0], size[1], own[0], own[1])
                if (other == null || size == null || (!sameSize && plan == null)) {
                    ThumbStore.unmarkBusy(sib.key)
                    if (size != null) Log.i(TAG, "reuse: another release has ${size[0]}x${size[1]} pictures " +
                        "(ours ${own[0]}x${own[1]}) - not used")
                    continue
                }
                val fit = plan?.let { BorrowFit(it, size[0], size[1], own[0], own[1]) }
                if (fit != null) Log.i(TAG, "reuse: another release has ${size[0]}x${size[1]} pictures " +
                    "(ours ${own[0]}x${own[1]}) - fitted to ours")
                val b = Borrow(other, ratio, ReleaseMatch.slotCount(sib.durationMs, SPACING_MS), fit = fit)
                borrow = b
                borrowSince = SystemClock.elapsedRealtime()
                val timing = if (ratio == 1.0) "same length" else "PAL ratio %.4f".format(ratio)
                if (st.verdictOn(other.key) == true) {
                    Log.i(TAG, "reuse: another release confirmed earlier ($timing)")
                    adoptBorrow(st, b)
                    return
                }
                val eligible = checkEligible(idx, st, b)
                checkQueue.clear()
                checkGap.clear()
                checkQueue += ReleaseMatch.pickCheckSlots(eligible, slotKeyframe.size)
                for ((s, gap) in eligible) if (s in checkQueue) checkGap[s] = gap
                checkMatches = 0
                if (checkQueue.size < ReleaseMatch.CHECKS_NEEDED) {
                    Log.i(TAG, "reuse: too few borrowed pictures at our keyframes' times - not used")
                    dropBorrow()
                    continue
                }
                Log.i(TAG, "reuse: showing ${other.slots().size} thumbnails of another release ($timing, " +
                    "${sib.durationMs / 1000} s against ${ownMs / 1000} s) until ${ReleaseMatch.CHECKS_NEEDED} own pictures " +
                    "confirm them (check at ${checkQueue.map { "${it * SPACING_MS / 1000} s/${checkGap[it]?.div(1000)} ms" }})")
                bumpTick()
                return
            }
        }

        /** Own slots already compared with a smaller copy's pictures. Worker only. */
        private val checkedSlots = HashSet<Int>()

        /**
         * The session's own coverage pass waits while a smaller copy's pictures are being made. A Usenet stream waits
         * while the copy pass runs within [UsenetCopy]'s limits; only the picture check and scrub stops read it.
         */
        private fun copyHoldsLattice(): Boolean {
            val c = copy ?: return false
            if (usenet) return usenetCopyHolds(c)
            if (!c.filling || prepareStage in 1..4 || borrow?.failed == true) return false
            val now = SystemClock.elapsedRealtime()
            if (c.made != copyHoldMade || copyHoldSince == 0L) {
                copyHoldMade = c.made
                copyHoldSince = now
            }
            if (MemoryGovernor.isStrongBox && now - copyHoldSince > COPY_STALL_HOLD_MS) {
                if (!copyHoldReleased) {
                    copyHoldReleased = true
                    Log.i(COPY_TAG, "no copy progress in ${COPY_STALL_HOLD_MS / 60_000} min - the 4K pass carries on")
                }
                return false
            }
            copyHoldReleased = false
            return true
        }

        private var copyHoldMade = -1
        private var copyHoldSince = 0L
        private var copyHoldReleased = false
        /** When the Usenet title's copy last made a thumbnail, or started making them; 0 while it makes none. */
        private var copyProgressAt = 0L
        /** The scrub stop (its last step's time) already read from the Usenet stream. */
        @Volatile private var usenetServedStopAt = 0L

        private fun usenetCopyHolds(c: CopyPass): Boolean {
            if (c.ended) return false
            val now = SystemClock.elapsedRealtime()
            val confirmed = borrow?.let { it.copy === c && it.confirmed } == true
            if (!c.filling && !confirmed) {
                copyProgressAt = 0L
            } else if (copyProgressAt == 0L || c.made != copyHoldMade) {
                copyHoldMade = c.made
                copyProgressAt = now
            }
            val why = UsenetCopy.giveUp(confirmed, now - copyStartedAt, copyProgressAt.takeIf { it > 0L }?.let { now - it })
                ?: return true
            Log.i(COPY_TAG, "Usenet: $why - the Usenet stream makes the thumbnails")
            c.stop(why)
            return false
        }

        /** Worker only: the scrub stop to read from the Usenet stream while its copy is tried, -1 for none. */
        private fun usenetLanding(st: ThumbStore, stopAt: Long): Int {
            val s = prioritySlot
            val b = borrow
            val copyHas = s >= 0 && b != null && b.confirmed && !b.failed &&
                b.map(s).let { m -> m >= 0 && b.store.has(slotUs(m)) }
            return UsenetCopy.landingSlot(s, SystemClock.elapsedRealtime(), stopAt, usenetServedStopAt,
                needed = s >= 0 && wanted(st, s) && !copyHas)
        }

        /** Worker only: starts the copy pass, sets its pictures up for the check, and copies confirmed ones over. */
        private fun tendCopy() {
            val st = store ?: return
            val idx = index ?: return
            val c = copy
            if (c == null) {
                maybeStartCopy(idx, st)
                return
            }
            val set = c.ready
            borrow?.let { old ->
                if (old.copy === c && !old.confirmed && set?.store?.key != old.store.key) {
                    Log.i(COPY_TAG, "reuse: the copy pass moved on - its earlier copy is no longer shown")
                    dropBorrow()
                }
            }
            if (set != null && borrow == null && set.store.key != copySetKey) setUpCopyBorrow(idx, st, c, set)
            val b = borrow ?: return
            if (b.copy !== c) return
            val made = c.made
            if (b.confirmed) {
                val every = if (prepareStage in 1..4) COPY_CHECK_REFILL_EVERY else COPY_ADOPT_EVERY
                if (made - copyAdoptedAt >= every || (!c.filling && made != copyAdoptedAt)) {
                    copyAdoptedAt = made
                    adoptBorrow(st, b)
                }
            } else if (!b.failed && (made - copyChecksAt >= COPY_CHECK_REFILL_EVERY || (!c.filling && made != copyChecksAt))) {
                copyChecksAt = made
                refillCopyChecks(idx, st, b)
            }
        }

        /**
         * A 4K or Usenet title with an incomplete store, no other release in use and no server thumbnails: try a copy.
         * A Usenet title starts at once, also behind the generating screen.
         */
        private fun maybeStartCopy(idx: MediaIndex, st: ThumbStore) {
            if (!memoryStop.allowsSideWork) return
            if (copyTried || !copyAllowed || !(fourK || usenet) || !source.identityExact) return
            val sources = source.copySources ?: return
            if (afterPlayback || ended || (prepareStage in 1..4 && !usenet) || borrow != null || slotKeyframe.isEmpty()) return
            copyTried = true
            if (st.isMemoryOnly || coverageStored(st)) return
            val v = idx.video
            val picture = ThumbGeometry.outputSize(v.width, v.height, 1, 1, v.displayAspect)
            val pts = idx.keyframes.ptsUs
            val landing = LongArray(slotKeyframe.size) { s -> slotKeyframe[s].let { if (it in pts.indices) pts[it] else -1L } }
            val durationMs = lastDurationMs.takeIf { it > 0 } ?: (idx.durationUs / 1000L)
            val own = CopyOwn(source.identity, durationMs, idx.fileLength, picture[0], picture[1], landing, st,
                slotOf(lastPositionMs.coerceAtLeast(0L)), CopyChoice.heightClass(v.width, v.height), usenet,
                screenStride = if (usenet) prepareStride() else 1)
            copyStartedAt = SystemClock.elapsedRealtime()
            copy = CopyPass(
                context = context,
                own = own,
                sources = sources,
                playerProvider = playerProvider,
                lastStallAtMs = { runCatching { reserveProvider()?.lastStallRealtimeMs }.getOrNull() ?: 0L },
                ownDecoderBusy = { decoderLanes.any { it.inUse || it.decoder.warm } },
                pacer = if (MemoryGovernor.isStrongBox) null else copyPacer,
                screenUp = { usenet && prepareStage in 1..4 },
            ).also { it.start() }
        }

        private fun setUpCopyBorrow(idx: MediaIndex, st: ThumbStore, c: CopyPass, set: CopySet) {
            copySetKey = set.store.key
            copyAdoptedAt = 0
            copyChecksAt = c.made
            if (st.verdictOn(set.store.key) == false || releasePair(st.key, set.store.key) in differentReleases) {
                c.rejected(set.store.key, "its pictures did not match this title before")
                return
            }
            val v = idx.video
            val own = ThumbGeometry.outputSize(v.width, v.height, 1, 1, v.displayAspect)
            val size = set.store.slots().minOrNull()?.let(set.store::compressed)?.let(::pictureSize)
            if (size == null || size[0] != own[0] || size[1] != own[1]) {
                c.rejected(set.store.key, if (size == null) "no picture to measure"
                    else "pictures ${size[0]}x${size[1]} against ${own[0]}x${own[1]}")
                return
            }
            ThumbStore.markBusy(set.store.key)
            val b = Borrow(set.store, set.ratio, set.slotCount, copy = c, wideUs = COPY_WIDE_US)
            checkQueue.clear()
            checkGap.clear()
            checkMatches = 0
            checkedSlots.clear()
            borrow = b
            borrowSince = SystemClock.elapsedRealtime()
            bumpTick()
            if (st.verdictOn(set.store.key) == true) {
                Log.i(COPY_TAG, "reuse: ${set.label} was confirmed for this file before")
                copyAdoptedAt = c.made
                adoptBorrow(st, b)
                return
            }
            Log.i(COPY_TAG, "reuse: showing ${set.store.slots().size} thumbnails of ${set.label} until " +
                "${ReleaseMatch.CHECKS_NEEDED} pictures of this file confirm them")
            refillCopyChecks(idx, st, b)
        }

        /**
         * Own pictures already stored are compared with the copy's at once; then own slots still to make whose copy
         * picture exists are queued for the check, up to the number still needed plus spares.
         */
        private fun refillCopyChecks(idx: MediaIndex, st: ThumbStore, b: Borrow) {
            val stored = b.store.slots()
            val ownStored = slotKeyframe.indices.mapNotNull { s ->
                if (s in checkedSlots || !st.has(slotUs(s))) return@mapNotNull null
                keyframeGap(idx, b, s, stored)?.takeIf { it <= ReleaseMatch.WIDE_US }?.let { s to it }
            }
            for (s in ReleaseMatch.pickCheckSlots(ownStored, slotKeyframe.size)) {
                if (borrow !== b || b.confirmed) return
                val gap = ownStored.first { it.first == s }.second
                if (b.failed && gap > ReleaseMatch.SAME_FRAME_US) continue
                val bmp = st.load(slotUs(s)) ?: continue
                checkGap[s] = gap
                checkBorrow(st, s, bmp)
            }
            if (borrow !== b || b.confirmed) return
            val want = ReleaseMatch.CHECKS_NEEDED + ReleaseMatch.CHECK_SPARES - checkMatches
            if (checkQueue.size >= want) return
            val eligible = checkEligible(idx, st, b).filter { it.first !in checkQueue && it.first !in checkedSlots }
            for (s in ReleaseMatch.pickCheckSlots(eligible, slotKeyframe.size)) {
                if (checkQueue.size >= want) break
                checkQueue += s
                checkGap[s] = eligible.first { it.first == s }.second
            }
        }

        /** Own to borrowed keyframe gap (us) at [slot], on the own timeline; null without both keyframes. */
        private fun keyframeGap(idx: MediaIndex, b: Borrow, slot: Int, stored: Set<Long>): Long? {
            val pts = idx.keyframes.ptsUs
            val kf = slotKeyframe.getOrElse(slot) { -1 }
            val m = b.map(slot)
            if (kf !in pts.indices || m < 0 || slotUs(m) !in stored) return null
            val otherPts = b.store.keyframePtsFor(slotUs(m)) ?: return null
            return kotlin.math.abs(pts[kf] - (otherPts / b.ratio).toLong())
        }

        /** Own slots still to make, with the gap to the borrowed keyframe of the same slot, within [ReleaseMatch.WIDE_US]. */
        private fun checkEligible(idx: MediaIndex, st: ThumbStore, b: Borrow): List<Pair<Int, Long>> {
            val stored = b.store.slots()
            return slotKeyframe.indices.mapNotNull { s ->
                if (!needs(st, s)) return@mapNotNull null
                keyframeGap(idx, b, s, stored)?.takeIf { it <= ReleaseMatch.WIDE_US }?.let { s to it }
            }
        }

        /** The next check slot to make, at most as many under way as matches are still missing. Worker only. */
        private fun nextCheckSlot(st: ThumbStore): Int {
            val b = borrow ?: return -1
            if (b.confirmed) return -1
            // Check slots are fetched like coverage slots: once that budget is spent the check cannot finish.
            if (latticeBudgetSpent()) {
                Log.i(TAG, "reuse: fetch budget spent before the check finished - another release's thumbnails dropped")
                dropBorrow()
                return -1
            }
            checkQueue.removeAll { !needs(st, it) }
            var pool = if (b.failed) checkQueue.filter { sameFrame(it) } else checkQueue.toList()
            if (pool.isEmpty() && b.copy != null) {
                index?.let { refillCopyChecks(it, st, b) }
                if (borrow !== b || b.confirmed) return -1
                checkQueue.removeAll { !needs(st, it) }
                pool = if (b.failed) checkQueue.filter { sameFrame(it) } else checkQueue.toList()
                // The copy is still being made: more pairs to check come with its pictures.
                if (pool.isEmpty() && b.copy.filling) return -1
            }
            if (pool.isEmpty()) {
                Log.i(TAG, if (b.failed) "reuse: a mismatch not seen twice - another release's thumbnails dropped for this session"
                    else "reuse: the check found too few clear pictures - another release's thumbnails dropped")
                dropBorrow()
                return -1
            }
            val window = if (b.failed) 1 else ReleaseMatch.CHECKS_NEEDED - checkMatches
            return pool.take(window).firstOrNull { wanted(st, it) } ?: -1
        }

        private fun sameFrame(slot: Int): Boolean = (checkGap[slot] ?: Long.MAX_VALUE) <= ReleaseMatch.SAME_FRAME_US

        /** Own picture of check [slot] against the borrowed one and its neighbours. Worker only. */
        private fun checkBorrow(st: ThumbStore, slot: Int, bmp: Bitmap) {
            val b = borrow ?: return
            checkQueue.remove(slot)
            checkedSlots += slot
            val gap = checkGap[slot] ?: Long.MAX_VALUE
            val w = bmp.width
            val h = bmp.height
            val m = b.map(slot)
            val otherSize = b.store.compressed(slotUs(m))?.let(::pictureSize)
            val fit = b.fit?.takeIf { it.toW == w && it.toH == h }
            if (b.fit != null && fit == null) {
                rejectBorrow(st, b, "pictures fitted to ${b.fit.toW}x${b.fit.toH} against ${w}x$h")
                return
            }
            val expectW = fit?.fromW ?: w
            val expectH = fit?.fromH ?: h
            if (otherSize != null && (otherSize[0] != expectW || otherSize[1] != expectH)) {
                rejectBorrow(st, b, "pictures ${otherSize[0]}x${otherSize[1]} against ${expectW}x$expectH")
                return
            }
            val px = IntArray(w * h)
            if (runCatching { bmp.getPixels(px, 0, w, 0, 0, w, h) }.isFailure) return
            val bars = ThumbCrop.measure(w, h, px)
            val own = ReleaseMatch.signature(px, w, h, bars?.left ?: 0, bars?.top ?: 0, bars?.right ?: 0, bars?.bottom ?: 0)
            fun scaled(v: Int?, from: Int, to: Int): Int = ((v ?: 0).toLong() * to / from).toInt()
            fun other(at: Int): Float? {
                if (own == null || at !in 0 until b.slotCount) return null
                val bytes = b.store.compressed(slotUs(at)) ?: return null
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = if (fit != null) 1 else 2
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
                val o = fit?.let { f -> fitTo(raw, f).also { raw.recycle() } } ?: raw
                try {
                    val ow = o.width
                    val oh = o.height
                    val opx = IntArray(ow * oh)
                    o.getPixels(opx, 0, ow, 0, 0, ow, oh)
                    val sig = ReleaseMatch.signature(opx, ow, oh, scaled(bars?.left, w, ow), scaled(bars?.top, h, oh),
                        scaled(bars?.right, w, ow), scaled(bars?.bottom, h, oh)) ?: return null
                    return ReleaseMatch.correlation(own, sig)
                } finally {
                    o.recycle()
                }
            }
            val same = other(m)
            val outcome = ReleaseMatch.outcome(same, other(m - 1), other(m + 1), gap)
            Log.d(TAG, "reuse check at ${slot * SPACING_MS / 1000} s (keyframes ${gap / 1000} ms apart): $outcome (" +
                (same?.let { "%.2f".format(it) } ?: "no picture") + ")")
            when {
                outcome == ReleaseMatch.Outcome.SKIP -> Unit
                b.failed -> if (outcome == ReleaseMatch.Outcome.MISMATCH) {
                    rejectBorrow(st, b, "two pictures of the same frame differ")
                } else {
                    Log.i(TAG, "reuse: a mismatch not seen twice - another release's thumbnails dropped for this session")
                    dropBorrow()
                }
                outcome == ReleaseMatch.Outcome.PASS -> if (++checkMatches >= ReleaseMatch.CHECKS_NEEDED) adoptBorrow(st, b)
                outcome == ReleaseMatch.Outcome.FAIL -> {
                    Log.i(TAG, "reuse: a picture too unsure to trust - another release's thumbnails dropped for this session")
                    dropBorrow()
                }
                else -> {
                    b.failed = true
                    bumpTick()
                    Log.i(TAG, "reuse: a picture of the same frame differs - hidden, one more decides")
                }
            }
        }

        /**
         * Copies the borrowed WebP bytes into every slot this store lacks whose keyframe is within
         * [ReleaseMatch.WIDE_US] of the borrowed one; the borrow stays for the other slots. Worker only.
         */
        private fun adoptBorrow(st: ThumbStore, b: Borrow) {
            b.confirmed = true
            st.noteVerdict(b.store.key, true)
            b.copy?.confirmed(b.store.key)
            bumpTick()
            val idx = index ?: return
            if (st.isMemoryOnly) {
                Log.i(TAG, "reuse: confirmed, storage nearly full - shown, not copied")
                return
            }
            val stored = b.store.slots()
            // Slots served by one borrowed picture share one copy.
            val groups = LinkedHashMap<Long, Pair<Long, MutableList<Long>>>()
            var far = 0
            for (s in slotKeyframe.indices) {
                if (st.has(slotUs(s))) continue
                val gap = keyframeGap(idx, b, s, stored) ?: continue
                if (gap > b.wideUs) {
                    far++
                    continue
                }
                val m = b.map(s)
                val otherPts = b.store.keyframePtsFor(slotUs(m)) ?: continue
                groups.getOrPut(otherPts) { slotUs(m) to ArrayList() }.second += slotUs(s)
            }
            var copied = 0
            val fit = b.fit
            if (fit != null) {
                // Pictures of another shape are fitted to ours and stored as our own, one by one.
                for ((otherPts, v) in groups) {
                    val bytes = b.store.compressed(v.first) ?: continue
                    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    val pic = fitTo(raw, fit)
                    raw.recycle()
                    st.put(v.second.toLongArray(), (otherPts / b.ratio).toLong(), pic)
                    copied += v.second.size
                }
                groups.clear()
            }
            for (chunk in groups.entries.chunked(ADOPT_CHUNK)) {
                val items = chunk.mapNotNull { (otherPts, v) ->
                    val bytes = b.store.compressed(v.first) ?: return@mapNotNull null
                    ThumbStore.Compressed(v.second.toLongArray(), (otherPts / b.ratio).toLong(), bytes)
                }
                if (!st.putCompressed(items, indexNow = false)) break
                copied += items.sumOf { it.slotsUs.size }
            }
            st.flush()
            Log.i(if (b.copy != null) COPY_TAG else TAG, "reuse: confirmed - $copied slots copied from " +
                (if (b.copy != null) "the smaller copy" else "another release") + ", $far left for our own " +
                "(keyframes over ${b.wideUs / 1000} ms apart), stored=${st.slots().size}")
        }

        private fun rejectBorrow(st: ThumbStore, b: Borrow, why: String) {
            st.noteVerdict(b.store.key, false)
            differentReleases += releasePair(st.key, b.store.key)
            Log.i(TAG, "reuse: another release's thumbnails do not match ($why) - not used for this file")
            b.copy?.rejected(b.store.key, why)
            dropBorrow()
        }

        private fun dropBorrow() {
            val b = borrow ?: return
            borrow = null
            checkQueue.clear()
            checkGap.clear()
            checkMatches = 0
            checkedSlots.clear()
            if (!b.confirmed) b.copy?.undecided(b.store.key, "see the reuse lines above")
            ThumbStore.unmarkBusy(b.store.key)
            b.store.trimMemory(0)
            bumpTick()
        }

        /**
         * The borrowed picture nearest own [target] slot's mapped one, at most [within] slots away (exclusive), never
         * exact. Its time is the borrowed keyframe's on the own timeline, or the slot's own when it is the mapped one.
         */
        private fun borrowedFor(target: Int, within: Int): Shown? {
            val b = borrow ?: return null
            if (b.failed) return null
            val m = b.map(target)
            if (m < 0) return null
            val other = b.store
            var slot = -1
            for (d in 0..minOf(fallbackSlots, within - 1)) {
                if (m - d >= 0 && other.has(slotUs(m - d))) {
                    slot = m - d
                    break
                }
                if (m + d < b.slotCount && other.has(slotUs(m + d))) {
                    slot = m + d
                    break
                }
            }
            if (slot < 0) return null
            val key = slotUs(slot)
            val raw = other.fullColourGet(key) ?: other.memGet(key)?.also { requestFullColour(other, slot) }
            if (raw == null) {
                requestDiskLoad(other, slot)
                return null
            }
            val bmp = b.fit?.let { f ->
                b.fitted.get(slot)?.takeIf { it.first === raw }?.second
                    ?: fitTo(raw, f).also { b.fitted.put(slot, raw to it) }
            } ?: raw
            val ptsMs = if (slot == m) slotPtsMs(target)
            else other.keyframePtsFor(key)?.let { (it / b.ratio / 1000.0).toLong() } ?: slotPtsMs(target)
            return Shown(bmp, ptsMs, exact = false, crop = crop.current, borrowed = true)
        }

        private fun pictureSize(webp: ByteArray): IntArray? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(webp, 0, webp.size, o)
            return if (o.outWidth > 0 && o.outHeight > 0) intArrayOf(o.outWidth, o.outHeight) else null
        }

        @Volatile private var fullColourLoadSlot = -1

        /** The centre picture in full colour when memory frames are RGB565: one disk decode at a time. */
        private fun requestFullColour(st: ThumbStore, slot: Int) {
            if (fullColourLoadSlot >= 0) return
            fullColourLoadSlot = slot
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (st.loadFullColour(slot.toLong() * SPACING_MS * 1000L) != null) bumpTick()
                } finally {
                    fullColourLoadSlot = -1
                }
            }
        }

        private fun requestDiskLoad(st: ThumbStore, slot: Int) {
            if (synchronized(diskLoadsInFlight) { diskLoadsInFlight.size >= MAX_DISK_LOADS_IN_FLIGHT }) return
            val key = slot.toLong() * SPACING_MS * 1000L
            val token = st.key to slot
            if (!st.has(key) || !synchronized(diskLoadsInFlight) { diskLoadsInFlight.add(token) }) return
            CoroutineScope(Dispatchers.IO).launch {
                val bmp = st.load(key)
                synchronized(diskLoadsInFlight) { diskLoadsInFlight.remove(token) }
                if (bmp != null) {
                    if (st === store) measureBars(bmp)
                    bumpTick()
                }
            }
        }

        private fun onTrim() {
            buffers.releaseIdle()
            store?.trimMemory(4)
            borrow?.store?.trimMemory(0)
            trimRequested = true     // done in tendReserve, never during a decode
            laneTrimRequested = true
        }

        private suspend fun sample(): PlayerState? {
            if (afterPlayback) {
                // The player screen is gone. If a player is still playing anyway, stop: memory is assumed free here.
                val live = samplePlayer()
                if (live != null && (live.isPlaying || live.buffering)) {
                    Log.i(TAG, "4K-C: a player is still active - ending the after-playback phase")
                    throw CancellationException("player still active")
                }
                // Otherwise behave as paused (the gates then only check memory and rate limits).
                ConnectionLedger.app.notePlaybackState(healthy = true, quiet = true)
                return PlayerState(false, false, lastPositionMs, lastPositionMs, lastDurationMs)
            }
            return samplePlayer()?.also(::trackJump)?.also(::noteForLedger)
        }

        /** Worker thread: lets the connection ledger step up only while playback can spare it, and down on trouble. */
        private fun noteForLedger(ps: PlayerState) {
            val ledger = ConnectionLedger.app
            val toEnd = ps.durationMs > 0 && ps.bufferedMs >= ps.durationMs - 1_000L
            ledger.notePlaybackState(
                healthy = ConnectionLedger.healthy(ps.isPlaying, ps.buffering, ps.bufferedMs - ps.positionMs, toEnd),
                quiet = !ps.isPlaying && !ps.buffering,
            )
            val playing = reader?.resolvedUrl ?: source.url
            if (ps.failed) ledger.playbackTrouble(SystemClock.elapsedRealtime(), playing)
            val stallAt = runCatching { reserveProvider()?.lastStallRealtimeMs }.getOrNull() ?: 0L
            if (stallAt > ledgerStallSeen) {
                ledgerStallSeen = stallAt
                ledger.playbackTrouble(stallAt, playing)
            }
        }

        private var ledgerStallSeen = 0L

        /** Worker thread: notes a playback jump of more than [REANCHOR_JUMP_MS] for the coverage pass. */
        private fun trackJump(ps: PlayerState) {
            val now = SystemClock.elapsedRealtime()
            if (trackPosMs >= 0L) {
                val expected = trackPosMs + (if (trackPlaying && ps.isPlaying) now - trackAtMs else 0L)
                if (kotlin.math.abs(ps.positionMs - expected) > REANCHOR_JUMP_MS) reanchorToMs = ps.positionMs
            }
            trackPosMs = ps.positionMs
            trackAtMs = now
            trackPlaying = ps.isPlaying
            sampledPlaying = ps.isPlaying
            sampledBuffering = ps.buffering
        }

        fun prepareActive(): Boolean = prepareStage in 1..4

        fun prepareStageNow(): Int = prepareStage

        /** Resumes from a Main timer, so a worker stuck in a gate wait cannot leave the ready screen up. */
        private fun enterReady(from: Int, made: Int, now: Long): Boolean {
            if (!advancePrepare(from, 4)) return false
            prepareReadyAt = now
            showPrepare(PrepareUi.Ready(made))
            mainScope.launch {
                delay(PREPARE_READY_MS)
                if (advancePrepare(4, 5)) {
                    if (session == null || session === this@Session) prepareUi.value = PrepareUi.Hidden
                    resumePlayerAfterPrepare()
                }
            }
            return true
        }

        val identity: String get() = source.identity

        fun small4KBusy(): Boolean = fourK && !MemoryGovernor.isStrongBox && !ended && !latticeFinished

        /** Worker only. */
        private fun showPrepare(ui: PrepareUi) {
            if (ui == lastPrepareUi) return
            lastPrepareUi = ui
            postPrepareUi(this, ui)
        }

        /** Atomic stage step; false when Main changed it meanwhile. */
        private fun advancePrepare(from: Int, to: Int): Boolean = synchronized(prepareLock) {
            if (prepareStage != from) false else {
                prepareStage = to
                true
            }
        }

        /** Main thread: playback is running although we hold it, so something else resumed it. */
        private fun prepareStepAside() {
            val was = synchronized(prepareLock) { prepareStage.also { if (it in 1..4) prepareStage = 5 } }
            if (was !in 1..4) return
            endYield()
            preparePausedPlayer = false
            prepareUi.value = PrepareUi.Hidden
            Log.i(TAG, "prepare: playback resumed elsewhere - stepping aside (stage $was)")
        }

        /** Stage 1 and the pause in one main-thread step, so the monitor never sees stage 1 while still playing. */
        private suspend fun beginPrepareOnMain(): Boolean = withContext(Dispatchers.Main) {
            val p = playerProvider() ?: return@withContext false
            if (!hostResumed) return@withContext false
            synchronized(prepareLock) { if (prepareStage == 0) prepareStage = 1 else return@withContext false }
            // false = the user already paused: leave it alone and never resume it. null = it cannot be held now.
            val held = runCatching { pauseForPrepare() }.getOrDefault(false)
            if (held == null) {
                // Dolby Vision and tunnelled starts can only be held once their first frame is up.
                synchronized(prepareLock) { if (prepareStage == 1) prepareStage = 0 }
                Log.i(TAG, "prepare: playback cannot be held yet - asking again after the first frame")
                retryHoldAfterFirstFrame(SystemClock.elapsedRealtime(), p.currentPosition)
                return@withContext false
            }
            preparePausedPlayer = held
            startYield()
            // A "playing" sample from before the hold must not read as "the user pressed play" in the next round.
            sampledPlaying = false
            Log.i(TAG, if (held) "prepare: playback held at ${p.currentPosition / 1000} s (before first frame: ${!p.isPlaying})"
                else "prepare: player paused by the user - not holding it")
            true
        }

        /** Main thread. Each refused ask has no side effect, so this is one real ask once the player allows it. */
        private fun retryHoldAfterFirstFrame(refusedAt: Long, refusedAtPosMs: Long) {
            mainScope.launch {
                while (true) {
                    delay(PrepareHandover.HOLD_RETRY_POLL_MS)
                    val p = playerProvider() ?: break
                    val waited = SystemClock.elapsedRealtime() - refusedAt
                    if (stoppedExternally || !hostResumed ||
                        !PrepareHandover.retriesHold(waited, p.currentPosition - refusedAtPosMs)) break
                    val claimed = synchronized(prepareLock) {
                        (!ended && prepareStage == 0).also { if (it) prepareStage = 1 }
                    }
                    if (!claimed) return@launch
                    when (runCatching { pauseForPrepare() }.getOrDefault(false)) {
                        null -> synchronized(prepareLock) { if (prepareStage == 1) prepareStage = 0 }
                        false -> {
                            synchronized(prepareLock) { if (prepareStage == 1) prepareStage = 5 }
                            giveUp.beforePlay = "skipped (paused by the user)"
                            Log.i(TAG, "prepare: paused by the user before the first frame - not holding")
                            return@launch
                        }
                        true -> {
                            preparePausedPlayer = true
                            startYield()
                            sampledPlaying = false
                            giveUp.beforePlay = "shown after the first frame (${beforePlayName()})"
                            postPrepareUi(this@Session, PrepareUi.Checking)
                            Log.i(TAG, "prepare: playback held after the first frame (${waited} ms after the first ask)")
                            return@launch
                        }
                    }
                }
                if (synchronized(prepareLock) { (prepareStage == 0).also { if (it) prepareStage = 5 } }) {
                    Log.i(TAG, "prepare: playback cannot be held at this start - not holding")
                }
            }
        }

        @Volatile private var yieldJob: Job? = null

        /** Main thread. While the generating screen is up, playback keeps its start buffer and leaves us the connections. */
        private fun startYield() {
            if (yieldJob?.isActive == true) return
            yieldJob = mainScope.launch {
                try {
                    while (prepareStage in 1..4 && !ended && !stoppedExternally) {
                        runCatching { reserveProvider()?.yieldLoading(true) }
                        ConnectionLedger.app.noteYield(true)
                        delay(YIELD_RENEW_MS)
                    }
                } finally {
                    runCatching { reserveProvider()?.yieldLoading(false) }
                    ConnectionLedger.app.noteYield(false)
                }
            }
        }

        /** Playback loads normally again at once. Any thread. */
        private fun endYield() {
            yieldJob?.cancel()
            runCatching { reserveProvider()?.yieldLoading(false) }
            ConnectionLedger.app.noteYield(false)
        }

        /** The app was left while the generating screen was up: on return playback waits for the user. */
        @Volatile private var prepareLeft = false

        fun notePrepareLeft() {
            if (prepareStage in 1..4) prepareLeft = true
        }

        /** Main thread. [userAsked]: the user chose to start, so a left-and-returned screen still plays. */
        private fun resumePlayerAfterPrepare(userAsked: Boolean = false) {
            endYield()
            if (!preparePausedPlayer) return
            preparePausedPlayer = false
            if (!hostResumed) {
                Log.i(TAG, "prepare: done while in the background - leaving playback paused")
                return
            }
            if (prepareLeft && !userAsked) {
                Log.i(TAG, "prepare: done after the app was left - paused until play")
                return
            }
            playerProvider()?.takeIf { !it.playWhenReady }?.let {
                it.play()
                Log.i(TAG, "prepare: playback resumed")
            }
        }

        fun startWatchingNow() {
            synchronized(prepareLock) { prepareStage = 5 }
            prepareUi.value = PrepareUi.Hidden
            resumePlayerAfterPrepare(userAsked = true)
            Log.i(TAG, "prepare: start_now - playback resumes")
        }

        private suspend fun tendPrepare(total: Int, done: Int) {
            if (inBackground()) return
            val now = SystemClock.elapsedRealtime()
            if (sampledPlaying && prepareStage in 1..4) {
                // A sample taken just before a hold that came after the first frame is stale: ask the player itself.
                val stepped = withContext(Dispatchers.Main) {
                    if (playerProvider()?.playWhenReady == false) false else true.also { prepareStepAside() }
                }
                if (stepped) return
                sampledPlaying = false
            }
            val made = latticeMade
            val perThumb = if (prepareMadeAtStart in 0 until made - 2 && prepareStartedAt > 0L) {
                (now - prepareStartedAt) / 1000.0 / (made - prepareMadeAtStart)
            } else when {
                !fourK -> PREPARE_SECONDS_HD
                MemoryGovernor.isStrongBox -> PREPARE_SECONDS_STRONG_4K
                remux -> PREPARE_SECONDS_REMUX
                else -> PREPARE_SECONDS_WEB
            }
            val (recent, endSeq) = synchronized(recentMade) { recentMade.toList() to madeSeq }
            val left = (maxOf(latticeWorkLeft, total - done, 0) * perThumb).toInt()
            when (prepareStage) {
                1 -> when {
                    done >= total -> if (advancePrepare(1, 5)) {
                        showPrepare(PrepareUi.Hidden)
                        withContext(Dispatchers.Main) { resumePlayerAfterPrepare() }
                        Log.i(TAG, "prepare: thumbnails already complete ($done/$total) - playing")
                    }
                    // A Usenet title waits for its copy to be confirmed; the stream itself is not read meanwhile.
                    usenet && copy?.ended == false && borrow?.confirmed != true ->
                        if (now - copyStartedAt >= USENET_COPY_CHECK_MS && advancePrepare(1, 5)) {
                            showPrepare(PrepareUi.Hidden)
                            withContext(Dispatchers.Main) { resumePlayerAfterPrepare() }
                            Log.i(TAG, "prepare: no copy confirmed in ${USENET_COPY_CHECK_MS / 1000} s - playing, " +
                                "thumbnails carry on from the copy")
                        }
                    // Another release's set may cover it all: the check comes before any generating.
                    borrow?.let { !it.confirmed && !it.failed } == true && now - borrowSince < CHECK_HOLD_MS -> Unit
                    else -> advancePrepare(1, 3)
                }
                3 -> {
                    if (prepareDoneAtStart < 0) {
                        prepareDoneAtStart = done
                        prepareMadeAtStart = made
                        prepareStartedAt = now
                        Log.i(TAG, "prepare: generating ($done/$total, about $left s)")
                    }
                    if (done != prepareLastDone) {
                        prepareLastDone = done
                        prepareLastProgressAt = now
                    }
                    val handover = PrepareHandover.reason(
                        nowMs = now, startedAtMs = prepareStartedAt, lastProgressAtMs = prepareLastProgressAt, leftS = left,
                        done = done, total = total, finished = latticeFinished, rateLimited = sourceRateLimited,
                    )
                    if (handover != null && advancePrepare(3, 5)) {
                        showPrepare(PrepareUi.Hidden)
                        withContext(Dispatchers.Main) { resumePlayerAfterPrepare() }
                        if (handover == PrepareHandover.Reason.RATE_LIMITED) {
                            Log.i(TAG, "prepare: source rate limited ($done/$total) - playing")
                        } else {
                            Log.i(TAG, "prepare: source too slow ($done/$total, about $left s left, " +
                                "${(now - prepareLastProgressAt) / 1000} s since the last one) - playing")
                        }
                        logGiveUp("generating screen " + when (handover) {
                            PrepareHandover.Reason.RATE_LIMITED -> "rate limited"
                            PrepareHandover.Reason.STALLED -> "stalled"
                            PrepareHandover.Reason.HOPELESS -> "too slow"
                        } + " at $done/$total")
                        return
                    }
                    if (done >= total || latticeFinished) {
                        if (!enterReady(3, done, now)) return
                        Log.i(TAG, "prepare: ready - ${done - prepareDoneAtStart} thumbnails in ${(now - prepareStartedAt) / 1000} s")
                    } else {
                        showPrepare(PrepareUi.Generating(done, total, left, prepareAnchorMs, prepareStride() * SPACING_MS,
                            recent, prepareLastCoverage, endSeq))
                    }
                }
                4 -> if (now - prepareReadyAt >= PREPARE_READY_MS && advancePrepare(4, 5)) {
                    showPrepare(PrepareUi.Hidden)
                    withContext(Dispatchers.Main) { resumePlayerAfterPrepare() }
                }
            }
        }

        fun progressPercent(): Int? {
            if (!fourK || MemoryGovernor.isStrongBox || ended || latticeFinished) return null
            val total = latticeTotal
            if (total <= 0) return null
            return (latticeDone * 100 / total).coerceIn(0, 99)
        }

        private suspend fun samplePlayer(): PlayerState? = withContext(Dispatchers.Main) {
            val p = playerProvider() ?: return@withContext null
            PlayerState(
                isPlaying = p.isPlaying,
                buffering = p.playbackState == Player.STATE_BUFFERING,
                positionMs = p.currentPosition,
                bufferedMs = p.bufferedPosition,
                durationMs = if (p.duration == C.TIME_UNSET) 0L else p.duration,
                failed = ConnectionLedger.sourceTrouble(p.playerError?.errorCode),
                playWhenReady = p.playWhenReady,
                loading = p.isLoading,
            )
        }

        /** One summary of what held the thumbnails back, so a log export tells the cause. */
        private fun logGiveUp(event: String) = runCatching {
            giveUp.logged = true
            Log.i(TAG, giveUp.line(
                event = event,
                made = synchronized(recentMade) { madeSeq },
                stored = store?.slots()?.size ?: 0,
                memory = MemoryGovernor.memoryNote(),
                connections = ConnectionLedger.app.inFlightNote(reader?.resolvedUrl ?: source.url),
                decoder = ThumbNative.state(),
                decoderReady = ThumbNative.available,
                source = if (usenet && !source.mediaServer) "Usenet" else runCatching { source.kindNote() }.getOrDefault("unknown"),
                video = video.ifEmpty { "unknown" },
                settings = runCatching { source.settingsNote() }.getOrDefault(""),
            ))
        }

        private fun beforePlayName() = beforePlay.name.lowercase().replaceFirstChar { it.uppercase() }

        private fun finish() {
            val noneMade = synchronized(recentMade) { madeSeq } == 0
            val complete = store?.let { runCatching { coverageStored(it) }.getOrDefault(false) } == true
            if (noneMade && giveUp.anything && !giveUp.logged && !complete) {
                logGiveUp("session ended with none made")
            }
            ended = true
            endYield()
            runCatching { reader?.close() }
            fetchScope.cancel()
            if (synchronized(prepareLock) { (prepareStage in 1..4).also { if (it) prepareStage = 5 } }) {
                postPrepareUi(this, PrepareUi.Hidden)
                // e.g. an unsupported file: never leave it paused (but never touch a newer session's player)
                if (!stoppedExternally) mainScope.launch { resumePlayerAfterPrepare() }
            }
            monitorJob?.cancel()
            dropReserve("session end")
            copy?.stop("the thumbnail session ended")
            dropBorrow()
            if (fourK && !MemoryGovernor.isStrongBox) synchronized(pacer) {
                Log.i(TAG, "4K pace at session end: pace ${pacer.paceMs / 1000} s, drops counted ${pacer.countedDrops} " +
                    "ignored ${pacer.ignoredDrops}, stopped drops=${pacer.stoppedByDrops} stall=${pacer.stoppedByStall} " +
                    "(stall stops ${pacer.stallStops})")
            }
            swapHolds.summary()?.let { Log.i(TAG, it) }
            sessionEnded(this)
            MemoryGovernor.removeTrimListener(trimListener)
            runCatching { closeDecoders() }
            decodeScope.cancel()
            store?.let { st ->
                if (lastDurationMs > 0) st.watchedFraction = (lastPositionMs.toFloat() / lastDurationMs).coerceIn(0f, 1f)
                st.flush()
                runCatching { ThumbStore.housekeeping(context, activeKey = st.key) }
            }
            for (d in fetches.values) d.invokeOnCompletion { if (it == null) d.getCompleted().au?.release() }
            buffers.close()
            worker.cancel()
            spoolWriter.cancel()
            spool.clear()
            runCatching { spoolDir.deleteRecursively() }
        }
    }
}
