package com.nuvio.tv.reshaped.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.reshaped.livetv.LiveTvRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the Sync settings show. */
internal data class ReshapedSyncStatus(
    val running: Boolean = false,
    val lastSyncedAtMs: Long = 0L,
    val failed: ReshapedSyncFailure? = null,
    /** What went wrong, in Google's words when it said. */
    val failedDetail: String = "",
)

internal enum class ReshapedSyncFailure { Network, SignedOut, NewerVersion }

/**
 * Keeps Reshaped settings and Live TV the same on the viewer's devices, through one small file
 * in their Google Drive. Off until the viewer signs in and turns it on.
 *
 * It syncs when the app comes to the front (at most once a minute), a few seconds after a Live
 * TV change, when the app goes to the back if something changed here, and on "Sync now".
 * Nothing runs in the background or polls; a sync is two or three small requests.
 */
internal object ReshapedSync {
    private const val TAG = "ReshapedSync"
    private const val PREFS = "nuvio_reshaped_sync"
    private const val KEY_SETTINGS = "sync_settings"
    private const val KEY_LIVE_TV = "sync_live_tv"
    private const val KEY_LAST_SYNC = "last_sync_ms"
    /** Per Live TV profile: where in the file it synced last (an earlier version moved some profiles). */
    private const val KEY_SECTION = "live_tv_section_"
    private const val FOREGROUND_MIN_GAP_MS = 60_000L
    private const val CHANGE_DEBOUNCE_MS = 5_000L
    /** The first sync waits until the app has finished starting (lighter on 2 GB TVs). */
    private const val START_DELAY_MS = 8_000L

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncEntryPoint {
        fun profileManager(): ProfileManager
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private lateinit var appContext: Context

    private val _syncSettings = MutableStateFlow(false)
    val syncSettings: StateFlow<Boolean> = _syncSettings.asStateFlow()
    private val _syncLiveTv = MutableStateFlow(false)
    val syncLiveTv: StateFlow<Boolean> = _syncLiveTv.asStateFlow()
    private val _status = MutableStateFlow(ReshapedSyncStatus())
    val status: StateFlow<ReshapedSyncStatus> = _status.asStateFlow()

    @Volatile private var loaded = false
    @Volatile private var lastForegroundSyncMs = 0L
    @Volatile private var driveFileId: String? = null
    private var changeJob: Job? = null

    /** Nuvio RS hook in NuvioApplication.onCreate: syncs as the app comes and goes. */
    fun onAppStart(application: Application) {
        appContext = application.applicationContext
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) onForeground()
            }
            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) onBackground()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = prefs(context)
            _syncSettings.value = prefs.getBoolean(KEY_SETTINGS, false)
            _syncLiveTv.value = prefs.getBoolean(KEY_LIVE_TV, false)
            _status.value = ReshapedSyncStatus(lastSyncedAtMs = prefs.getLong(KEY_LAST_SYNC, 0L))
            loaded = true
        }
    }

    fun setSyncSettings(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _syncSettings.value = enabled
        prefs(context).edit().putBoolean(KEY_SETTINGS, enabled).apply()
        if (enabled) syncNow(context)
    }

    fun setSyncLiveTv(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _syncLiveTv.value = enabled
        prefs(context).edit().putBoolean(KEY_LIVE_TV, enabled).apply()
        if (enabled) syncNow(context)
    }

    /** A new Google account starts from its own file: forget what the last one had. */
    fun onSignedIn(context: Context) {
        driveFileId = null
        baseFile(context).delete()
        syncNow(context)
    }

    fun onSignedOut(context: Context) {
        driveFileId = null
        baseFile(context).delete()
        _status.update { it.copy(failed = null) }
    }

    /** Live TV changed here (a favourite, a category, a source): sync in a few seconds. */
    fun onLocalChange() {
        if (!::appContext.isInitialized || !_syncLiveTv.value) return
        synchronized(this) {
            changeJob?.cancel()
            changeJob = scope.launch {
                delay(CHANGE_DEBOUNCE_MS)
                sync(appContext, onlyIfChanged = true)
            }
        }
    }

    /**
     * Live TV opened (or came back from the player): fetch what other devices changed, so a TV
     * left open for hours shows them. At most once a minute, shared with the foreground sync.
     */
    fun onLiveTvOpened() {
        if (!::appContext.isInitialized || !_syncLiveTv.value) return
        val now = SystemClock.elapsedRealtime()
        if (lastForegroundSyncMs != 0L && now - lastForegroundSyncMs < FOREGROUND_MIN_GAP_MS) return
        lastForegroundSyncMs = now
        scope.launch { sync(appContext, onlyIfChanged = false) }
    }

    fun syncNow(context: Context) {
        scope.launch { sync(context.applicationContext, onlyIfChanged = false) }
    }

    private fun onForeground() {
        val now = SystemClock.elapsedRealtime()
        val firstStart = lastForegroundSyncMs == 0L
        if (!firstStart && now - lastForegroundSyncMs < FOREGROUND_MIN_GAP_MS) return
        lastForegroundSyncMs = now
        scope.launch {
            if (firstStart) delay(START_DELAY_MS)
            sync(appContext, onlyIfChanged = false)
        }
    }

    private fun onBackground() {
        scope.launch { sync(appContext, onlyIfChanged = true) }
    }

    /**
     * One round: read the file, stamp what changed here, merge, apply what came in, and upload
     * when the file lacks something. With [onlyIfChanged], nothing is sent or read unless this
     * device changed something since the last round.
     */
    private suspend fun sync(context: Context, onlyIfChanged: Boolean) {
        ensureLoaded(context)
        val settingsOn = _syncSettings.value
        val liveTvOn = _syncLiveTv.value
        if (!settingsOn && !liveTvOn) return
        if (!GoogleAccount.isConfigured || !GoogleAccount.isSignedIn(context)) return
        mutex.withLock {
            val profileId = if (liveTvOn) activeProfileId(context) else null
            // Each TV profile syncs its own place: two profiles of one TV keep their own Live TV
            // and must never share a place, where each would remove what only the other has.
            val sectionId = profileId
            val lastSection = profileId?.let { prefs(context).getInt(KEY_SECTION + it, it) }
            val savedBase = withContext(Dispatchers.IO) { readBase(context) }
            // A profile syncing into another place than before (it now shares the main profile's)
            // joins what is there: nothing it lacks counts as deleted, what it has is added.
            val base = if (sectionId != null && lastSection != sectionId) {
                savedBase.filterKeys { !it.startsWith(LiveTvSections.prefix(sectionId)) }
            } else {
                savedBase
            }
            // Settings are read and set on the main thread, as their screens do.
            val settings = if (settingsOn) withContext(Dispatchers.Main) { ReshapedSyncedSettings.current(context) } else emptyMap()
            val liveTv = profileId?.let { LiveTvRepository.syncSnapshot(context, it) }
            val removed = profileId?.let { LiveTvRepository.syncRemovedSources(context, it) }.orEmpty()
            // Imported playlists changed here go up first, so the file can name their copies.
            val uploads = if (profileId != null && liveTv != null) SyncedPlaylists.uploaded(context, profileId, liveTv.sources) else null
            val playlists = uploads?.refs.orEmpty()
            val current = settings + (
                if (sectionId != null && liveTv != null) LiveTvSections.toSections(sectionId, liveTv, base, playlists, removed) else emptyMap()
            )
            if (onlyIfChanged && SyncDoc.stamp(base, current, 0L) == base) return
            _status.update { it.copy(running = true) }
            try {
                val remoteFile = DriveAppFolder.read(context)
                val remote = remoteFile.others.fold(SyncDoc.decode(remoteFile.text)) { doc, (_, text) -> SyncDoc.merge(SyncDoc.decode(text), doc) }
                val now = SyncDoc.stampTime(System.currentTimeMillis(), base, remote)
                val local = withRemovals(SyncDoc.stamp(base, current, now), sectionId, removed, now)
                val merged = SyncDoc.prune(
                    keepLocalSources(SyncDoc.merge(local, remote), sectionId, liveTv, base, local, removed),
                    now,
                )

                if (settingsOn) {
                    withContext(Dispatchers.Main) { ReshapedSyncedSettings.apply(context, settings, merged) }
                }
                if (profileId != null && sectionId != null && liveTv != null) {
                    // Sources this device has keep its own ids (the file may give another device's).
                    val localIds = liveTv.sources.associate { it.identity to it.id }
                    val fromFile = LiveTvSections.fromSections(sectionId, merged)
                    val order = liveTv.sources.withIndex().associate { it.value.identity to it.index }
                    val after = fromFile.copy(
                        sources = fromFile.sources
                            .map { source -> localIds[source.identity]?.let { source.copy(id = it) } ?: source }
                            .sortedBy { order[it.identity] ?: Int.MAX_VALUE },
                        // A file without an order (older versions only) leaves this device's as it is.
                        sourceOrder = fromFile.sourceOrder.ifEmpty { liveTv.sourceOrder },
                    )
                    // Imported playlists another device sent or changed are fetched before they load.
                    val refs = LiveTvSections.playlistRefs(sectionId, merged)
                    val reload = ArrayList<String>()
                    val fetched = HashSet<String>()
                    after.sources.forEach { source ->
                        val ref = refs[source.identity] ?: return@forEach
                        if (SyncedPlaylists.fetch(context, profileId, source.id, ref)) {
                            fetched += source.identity
                            if (source.identity in localIds) reload += source.id
                        }
                    }
                    LiveTvRepository.applySync(context, profileId, liveTv, after)
                    reload.forEach { LiveTvRepository.reloadSource(profileId, it) }
                    // A new source given another id here (its id was taken) fetches under that one.
                    after.sources.filter { it.identity in fetched && it.identity !in localIds }.forEach { source ->
                        val id = LiveTvRepository.sourceIdFor(context, profileId, source.identity) ?: return@forEach
                        if (id != source.id && SyncedPlaylists.fetch(context, profileId, id, refs.getValue(source.identity))) {
                            LiveTvRepository.reloadSource(profileId, id)
                        }
                    }
                    // Every profile's copies stay, not only this one's.
                    SyncedPlaylists.deleteUnused(context, LiveTvSections.playlistDriveIds(merged) + playlists.values.map { it.driveId })
                }
                if (merged != remote || remoteFile.id == null || remoteFile.others.isNotEmpty()) {
                    driveFileId = DriveAppFolder.write(context, remoteFile.id ?: driveFileId, SyncDoc.encode(merged))
                } else {
                    driveFileId = remoteFile.id
                }
                remoteFile.others.forEach { (id, _) -> runCatching { DriveAppFolder.delete(context, id) } }
                uploads?.let { SyncedPlaylists.commit(context, it.sent) }
                // Only what this device took in moves its base on: another profile's Live TV (or
                // settings, or Live TV, while their sync is off) stays as this device last had it,
                // so changes it never applied are never taken for deletions here later.
                val nextBase = HashMap<String, Map<String, SyncEntry>>()
                (merged.keys + savedBase.keys).forEach { name ->
                    val applied = if (name.startsWith("live_tv/")) {
                        sectionId != null && liveTv != null && name.startsWith(LiveTvSections.prefix(sectionId))
                    } else {
                        settingsOn
                    }
                    (if (applied) merged[name] else savedBase[name])?.let { nextBase[name] = it }
                }
                withContext(Dispatchers.IO) { writeBase(context, nextBase) }
                val syncedAt = System.currentTimeMillis()
                val edit = prefs(context).edit().putLong(KEY_LAST_SYNC, syncedAt)
                if (profileId != null && sectionId != null) edit.putInt(KEY_SECTION + profileId, sectionId)
                edit.apply()
                // Removals the file now has (no source section lists them any more) are done.
                if (profileId != null && sectionId != null) {
                    val sent = removed.filter { identity ->
                        LiveTvSections.sourceSections(sectionId).none { merged[it]?.get(identity)?.value != null }
                    }
                    LiveTvRepository.clearSyncRemoved(context, profileId, sent)
                }
                _status.value = ReshapedSyncStatus(lastSyncedAtMs = syncedAt)
            } catch (cancel: CancellationException) {
                _status.update { it.copy(running = false) }
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Sync failed", error)
                val failure = when (error) {
                    is DriveAppFolder.SignedOutException -> ReshapedSyncFailure.SignedOut
                    is SyncDoc.NewerFormatException -> ReshapedSyncFailure.NewerVersion
                    else -> ReshapedSyncFailure.Network
                }
                _status.update { it.copy(running = false, failed = failure, failedDetail = error.message.orEmpty()) }
            }
        }
    }

    /**
     * A deletion in the file removes a source here only when this device had already synced
     * that source ([base] has it): a source this device has that the account never had (or had
     * once, deleted long ago) is kept, and goes up as new, rather than being wiped by an old
     * deletion. Sources removed here ([removed]) stay removed.
     */
    private fun keepLocalSources(
        merged: SyncSections,
        sectionId: Int?,
        liveTv: com.nuvio.tv.reshaped.livetv.LiveTvSyncData?,
        base: SyncSections,
        local: SyncSections,
        removed: Set<String>,
    ): SyncSections {
        if (sectionId == null || liveTv == null) return merged
        var result = merged
        LiveTvSections.sourceSections(sectionId).forEach { name ->
            val entries = result[name] ?: return@forEach
            val mine = local[name].orEmpty()
            var kept: MutableMap<String, SyncEntry>? = null
            liveTv.sources.forEach { source ->
                val key = source.identity
                if (key in removed || entries[key]?.value != null) return@forEach
                // Known when the base has it in either section (imported playlists moved section).
                if (LiveTvSections.sourceSections(sectionId).any { base[it]?.get(key)?.value != null }) return@forEach
                val own = mine[key]?.value ?: return@forEach
                val time = maxOf(entries[key]?.time ?: 0L, mine[key]?.time ?: 0L) + 1
                (kept ?: entries.toMutableMap().also { kept = it })[key] = SyncEntry(own, time)
            }
            kept?.let { result = result + (name to it) }
        }
        return result
    }

    /**
     * [local] with a deletion of each source removed here in both source sections, also when the
     * base never had it (the stamp only marks what the base had), unless it is back here.
     */
    private fun withRemovals(local: SyncSections, sectionId: Int?, removed: Set<String>, now: Long): SyncSections {
        if (sectionId == null || removed.isEmpty()) return local
        val result = local.toMutableMap()
        LiveTvSections.sourceSections(sectionId).forEach { name ->
            val entries = result[name].orEmpty().toMutableMap()
            removed.forEach { identity ->
                val entry = entries[identity]
                if (entry == null || (entry.value != null && entry.time < now)) entries[identity] = SyncEntry(null, now)
            }
            result[name] = entries
        }
        return result
    }

    private fun activeProfileId(context: Context): Int =
        EntryPointAccessors.fromApplication(context.applicationContext, SyncEntryPoint::class.java)
            .profileManager().activeProfileId.value

    // The file as last synced, to tell what changed here since.
    private fun baseFile(context: Context) = File(context.applicationContext.filesDir, "reshaped_sync/base.json")

    private fun readBase(context: Context): SyncSections =
        runCatching { SyncDoc.decode(baseFile(context).takeIf(File::isFile)?.readText()) }.getOrDefault(emptyMap())

    private fun writeBase(context: Context, sections: SyncSections) {
        val target = baseFile(context)
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".tmp")
        temp.writeText(SyncDoc.encode(sections))
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
