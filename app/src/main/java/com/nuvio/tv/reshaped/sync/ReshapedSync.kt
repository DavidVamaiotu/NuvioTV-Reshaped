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
)

internal enum class ReshapedSyncFailure { Network, SignedOut, NewerVersion }

/**
 * Keeps Reshaped settings and Live TV the same on the viewer's devices, through one small file
 * in their Google account's hidden app folder. Off until the viewer signs in and turns it on.
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
    private const val FOREGROUND_MIN_GAP_MS = 60_000L
    private const val CHANGE_DEBOUNCE_MS = 5_000L

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
        changeJob?.cancel()
        changeJob = scope.launch {
            delay(CHANGE_DEBOUNCE_MS)
            sync(appContext, onlyIfChanged = true)
        }
    }

    fun syncNow(context: Context) {
        scope.launch { sync(context.applicationContext, onlyIfChanged = false) }
    }

    private fun onForeground() {
        val now = SystemClock.elapsedRealtime()
        if (lastForegroundSyncMs != 0L && now - lastForegroundSyncMs < FOREGROUND_MIN_GAP_MS) return
        lastForegroundSyncMs = now
        scope.launch { sync(appContext, onlyIfChanged = false) }
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
            val base = withContext(Dispatchers.IO) { readBase(context) }
            val settings = if (settingsOn) ReshapedSyncedSettings.current(context) else emptyMap()
            val liveTv = profileId?.let { LiveTvRepository.syncSnapshot(context, it) }
            val current = settings + (if (profileId != null && liveTv != null) LiveTvSections.toSections(profileId, liveTv, base) else emptyMap())
            if (onlyIfChanged && SyncDoc.stamp(base, current, 0L) == base) return
            _status.update { it.copy(running = true) }
            try {
                val remoteFile = DriveAppFolder.read(context)
                val remote = SyncDoc.decode(remoteFile.text)
                val now = SyncDoc.stampTime(System.currentTimeMillis(), base, remote)
                val local = SyncDoc.stamp(base, current, now)
                val merged = SyncDoc.prune(SyncDoc.merge(local, remote), now)

                if (settingsOn) {
                    ReshapedSyncedSettings.apply(context, settings, merged)
                }
                if (profileId != null && liveTv != null) {
                    // Sources this device has keep its own ids (the file may give another device's).
                    val localIds = liveTv.sources.associate { it.identity to it.id }
                    val fromFile = LiveTvSections.fromSections(profileId, merged)
                    val order = liveTv.sources.withIndex().associate { it.value.identity to it.index }
                    val after = fromFile.copy(
                        sources = fromFile.sources
                            .map { source -> localIds[source.identity]?.let { source.copy(id = it) } ?: source }
                            .sortedBy { order[it.identity] ?: Int.MAX_VALUE },
                    )
                    LiveTvRepository.applySync(context, profileId, liveTv, after)
                }
                if (merged != remote || remoteFile.id == null) {
                    driveFileId = DriveAppFolder.write(context, remoteFile.id ?: driveFileId, SyncDoc.encode(merged))
                } else {
                    driveFileId = remoteFile.id
                }
                withContext(Dispatchers.IO) { writeBase(context, merged) }
                val syncedAt = System.currentTimeMillis()
                prefs(context).edit().putLong(KEY_LAST_SYNC, syncedAt).apply()
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
                _status.update { it.copy(running = false, failed = failure) }
            }
        }
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
