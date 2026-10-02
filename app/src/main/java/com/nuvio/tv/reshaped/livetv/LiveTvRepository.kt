package com.nuvio.tv.reshaped.livetv

import android.content.Context
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.nuvio.tv.reshaped.sync.ReshapedSync

/**
 * Live TV's channel list, guide, favorites and last channel for the active profile. It lives for
 * the app's process, so coming back from the player shows the list as it was, without a reload.
 * Loads run in its own scope: leaving the screen does not cancel them.
 *
 * Several sources can be saved; their channels show as one list, in the order the sources were
 * added. A source that fails to load keeps the channels it had.
 */
object LiveTvRepository {
    private const val TAG = "LiveTv"
    private const val EPG_TICK_MS = 60_000L
    /** How long a downloaded guide is used before it is downloaded again. */
    private const val EPG_DOWNLOAD_MS = 10L * 60 * 60 * 1000
    /** The saved guide is read again when channels run out of kept programmes, at most this often. */
    private const val EPG_MIN_READ_GAP_MS = 60L * 60 * 1000
    /** A guide that could not be read is tried again sooner. */
    private const val EPG_RETRY_MS = 30L * 60 * 1000
    /** A held ▲ moving a playlist channel is saved once it pauses this long. */
    private const val LIST_SAVE_DELAY_MS = 600L
    /**
     * How long Live TV may go unseen (no list, no Live TV channel in the player) before its
     * channels and guide are let go. Coming back after that loads them again.
     */
    private const val IDLE_RELEASE_MS = 5L * 60 * 1000
    /** How long a guide download or read carries on once nothing shows Live TV. */
    private const val UNSEEN_GRACE_MS = 10_000L
    /** Sources loaded at once on a refresh: each holds a connection and a parse buffer. */
    private const val PARALLEL_SOURCES = 2
    /** Marks a Stalker portal's guide in the guide list; see [stalkerGuideLink]. */
    private const val STALKER_GUIDE_PREFIX = "stalker-guide:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(LiveTvUiState())
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    /**
     * Goes up each time Live TV lets go of its channels while unused, so a screen model kept on a
     * saved back stack entry drops its copy of the list too (it is rebuilt when the screen opens).
     */
    private val _releases = MutableStateFlow(0)
    val releases: StateFlow<Int> = _releases.asStateFlow()

    /** The list the last channel was picked from (a category, favorites, a search): zapping stays in it. */
    @Volatile var zapList: List<LiveTvChannel> = emptyList()
        private set
    /** The category key [zapList] is, or null for a search. */
    @Volatile var zapFolderKey: String? = null
        private set

    fun setZapList(channels: List<LiveTvChannel>, folderKey: String?) {
        zapList = channels
        zapFolderKey = folderKey
    }

    private lateinit var appContext: Context
    @Volatile private var storage: LiveTvStorage? = null
    @Volatile private var loadedProfileId: Int? = null
    /** The profile last loaded, kept after a release so the player can load it again. */
    @Volatile private var lastProfileId: Int? = null
    private var profileJob: Job? = null
    private var idleJob: Job? = null
    private var epgJob: Job? = null
    @Volatile private var epgGeneration = 0
    /** The guide programmes kept for each [LiveTvChannel.guideKey], for "up next" and the guide. */
    @Volatile private var keptSchedule: LiveTvSchedule = emptyMap()
    private var epgKey: Triple<List<String>, Set<String>, Long>? = null
    /** How much guide is kept per channel; less on low-memory TVs. */
    @Volatile internal var guideWindow: LiveTvGuideWindow = LiveTvGuideWindow.Regular
        private set
    /** Set by Refresh: the next guide read downloads every guide again, however recent its saved copy. */
    @Volatile private var forceGuideDownload = false
    /** The viewer's category order; empty for A to Z. */
    @Volatile private var groupOrder: List<String> = emptyList()

    /** Results, state changes and [publish] run here one at a time, so no two of them race. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.Default.limitedParallelism(1)
    /** Saves run in order on one IO thread: an older save can never land after a newer one. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = Dispatchers.IO.limitedParallelism(1)
    private val loadPermits = Semaphore(PARALLEL_SOURCES)
    /**
     * One load per source. Adding, removing or refreshing a source cancels only that source's
     * load, so a phone upload during start-up no longer drops the sources still loading.
     */
    private val sourceJobs = ConcurrentHashMap<String, Job>()

    /** What each source loaded last. Only touched on [serial]. */
    private class LoadedSource(
        val channels: List<LiveTvChannel>,
        val epgUrls: List<String>,
        /** The source's categories in its own order (the panel's category list, else the playlist's). */
        val groupOrder: List<String>,
    )
    /** Every category in the providers' order: sources in the order added, each in its own. Set by [publish]. */
    @Volatile private var providerGroupOrder: List<String> = emptyList()
    private val loaded = HashMap<String, LoadedSource>()

    /**
     * Makes the state match [profileId]: the first call (or a profile switch, or the first after
     * Live TV was let go while unused) reads the saved sources and loads their channels; later
     * calls for the same profile do nothing. Returns true when it started a load.
     */
    fun ensureLoaded(context: Context, profileId: Int): Boolean {
        if (loadedProfileId == profileId) return false
        appContext = context.applicationContext
        loadedProfileId = profileId
        lastProfileId = profileId
        profileJob?.cancel()
        cancelAllLoads()
        LiveTvStalker.clearSession()
        storage = null
        _uiState.value = LiveTvUiState(isLoading = true)
        profileJob = scope.launch(serial) {
            stopEpg()
            epgKey = null
            val store = withContext(Dispatchers.IO) {
                LiveTvStorage(appContext, profileId).also {
                    it.favoriteUrls() // first read parses the file
                    groupOrder = it.groupOrder()
                }
            }
            val sources = withContext(Dispatchers.IO) { store.sources() }
            val hiddenChannels = withContext(Dispatchers.IO) { store.hiddenChannelKeys() }
            storage = store
            loaded.clear()
            _uiState.value = LiveTvUiState(
                sources = sources,
                favoriteUrls = store.favoriteUrls(),
                customLists = store.customLists(),
                hiddenGroups = store.hiddenGroups(),
                groupNames = store.groupNames(),
                hiddenChannelKeys = hiddenChannels,
                recentChannel = store.recentChannel(),
                isLoading = sources.isNotEmpty(),
            )
            sources.forEach { launchSourceLoad(it, adding = false) }
        }
        watchIdle()
        return true
    }

    /**
     * Lets go of everything Live TV holds (channels, guide, loads, the zap list) once nothing
     * has shown it for [IDLE_RELEASE_MS]: after going back to Home, only the saved files remain.
     * The list and a Live TV channel in the player both count as showing it.
     */
    private fun watchIdle() {
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            while (isActive) {
                _uiState.subscriptionCount.first { it == 0 }
                val back = withTimeoutOrNull(IDLE_RELEASE_MS) { _uiState.subscriptionCount.first { it > 0 } }
                if (back != null) continue
                val released = withContext(serial) {
                    if (_uiState.subscriptionCount.value > 0) {
                        false
                    } else {
                        releaseAll()
                        true
                    }
                }
                if (released) return@launch
            }
        }
    }

    /** Loads Live TV again if it was let go while unused (the player coming back to a channel). */
    fun reloadIfReleased() {
        if (loadedProfileId != null || !::appContext.isInitialized) return
        val profileId = lastProfileId ?: return
        ensureLoaded(appContext, profileId)
    }

    /** Runs on [serial]. */
    private fun releaseAll() {
        profileJob?.cancel()
        profileJob = null
        cancelAllLoads()
        stopEpg()
        epgKey = null
        loaded.clear()
        zapList = emptyList()
        zapFolderKey = null
        storage = null
        LiveTvStalker.clearSession()
        loadedProfileId = null
        _uiState.value = LiveTvUiState()
        _releases.update { it + 1 }
    }

    /** Loads every saved source again (the Refresh button). */
    fun refresh() {
        if (storage == null) return
        _uiState.update { it.copy(error = null) }
        scope.launch(serial) {
            // The guides are downloaded again too, once the channels are back.
            forceGuideDownload = true
            epgKey = null
            _uiState.value.sources.forEach { launchSourceLoad(it, adding = false) }
        }
    }

    fun loadM3uUrl(url: String, epgUrl: String = "") {
        val trimmed = url.trim()
        launchAdd(LiveTvSource("", LiveTvSourceType.M3u, trimmed, epgUrl = epgUrl.trim()))
    }

    fun loadXtream(settings: LiveTvXtreamSettings, epgUrl: String = "") {
        val normalized = settings.normalized()
        launchAdd(LiveTvSource("", LiveTvSourceType.Xtream, normalized.serverUrl, xtream = normalized, epgUrl = epgUrl.trim()))
    }

    fun loadStalker(settings: LiveTvStalkerSettings, epgUrl: String = "") {
        val normalized = settings.normalized()
        launchAdd(LiveTvSource("", LiveTvSourceType.Stalker, normalized.portalUrl, stalker = normalized, epgUrl = epgUrl.trim()))
    }

    /**
     * Saves a changed source (a new server address, login or guide link) in place of the saved
     * one: it keeps its id, so its hidden channels and categories stay, and its favourites and
     * last channel move to the new links once it loaded. A failed load changes nothing.
     */
    fun updateSource(sourceId: String, edited: LiveTvSource) {
        val existing = _uiState.value.sources.firstOrNull { it.id == sourceId } ?: return
        val source = when (edited.type) {
            LiveTvSourceType.M3u -> edited.copy(url = edited.url.trim())
            LiveTvSourceType.Xtream -> edited.xtream.normalized().let { edited.copy(url = it.serverUrl, xtream = it) }
            LiveTvSourceType.Stalker -> edited.stalker.normalized().let { edited.copy(url = it.portalUrl, stalker = it) }
        }
        val updated = source.copy(id = sourceId, epgUrl = edited.epgUrl.trim(), name = edited.name.trim())
        if (updated.copy(name = "") == existing.copy(name = "")) {
            // Only the name changed: nothing to load again.
            if (updated.name != existing.name) renameSource(updated)
            _uiState.update { it.copy(addedCount = it.addedCount + 1, error = null) }
            return
        }
        launchAdd(updated)
    }

    private fun renameSource(source: LiveTvSource) {
        val store = storage ?: return
        scope.launch(serial) {
            val sources = _uiState.value.sources.map { if (it.id == source.id) source else it }
            _uiState.update { it.copy(sources = sources) }
            scope.launch(writer) { store.saveSources(sources) }
            ReshapedSync.onLocalChange()
        }
    }

    /**
     * Saves a playlist sent from a phone as a source, then shows it. Blocking (the upload
     * server's thread): [input] is copied to a file first so it is never held in memory as a whole.
     */
    fun importPlaylist(fileName: String, input: InputStream, maxBytes: Long): Boolean {
        val store = storage ?: return false
        val name = fileName.trim().ifBlank { "M3U playlist" }
        val candidate = withExistingId(LiveTvSource("", LiveTvSourceType.M3u, name), store)
        val saved = runCatching {
            store.savePlaylistFile(candidate.id) { temp ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw IllegalStateException("too large")
                        out.write(buffer, 0, read)
                    }
                }
            }
        }.isSuccess
        if (!saved) return false
        launchAdd(candidate)
        return true
    }

    /** Removes one source and its channels; the other sources' loads carry on. */
    fun removeSource(sourceId: String) {
        val store = storage ?: return
        sourceJobs.remove(sourceId)?.cancel()
        scope.launch(serial) {
            val sources = _uiState.value.sources.filterNot { it.id == sourceId }
            if (sources.size == _uiState.value.sources.size) return@launch
            LiveTvStalker.clearSession()
            loaded.remove(sourceId)
            _uiState.update { it.copy(sources = sources, sourceErrors = it.sourceErrors - sourceId, error = null) }
            scope.launch(writer) {
                store.saveSources(sources)
                store.deletePlaylistFile(sourceId)
            }
            ReshapedSync.onLocalChange()
            publish()
            updateLoading()
        }
    }

    /** Shows or hides a category. */
    fun setGroupHidden(group: String, hidden: Boolean) {
        val current = _uiState.value.hiddenGroups
        if ((group in current) == hidden) return
        saveHiddenGroups(if (hidden) current + group else current - group)
    }

    /** Shows every category, or hides every one (to then pick the few that are wanted). */
    fun setAllGroupsHidden(hidden: Boolean) {
        saveHiddenGroups(if (hidden) _uiState.value.groups.toHashSet() else emptySet())
    }

    /** Shows or hides single channels (a whole category's at once for Show all / Hide all). */
    fun setChannelsHidden(channels: Collection<LiveTvChannel>, hidden: Boolean) {
        val keys = channels.map { it.hideKey }
        val current = _uiState.value.hiddenChannelKeys
        val next = if (hidden) current + keys else current - keys.toSet()
        if (next.size == current.size) return
        _uiState.update { it.copy(hiddenChannelKeys = next) }
        refreshShownChannels()
        storage?.let { store -> scope.launch(writer) { store.saveHiddenChannelKeys(next) } }
        ReshapedSync.onLocalChange()
    }

    /** Moves a category [step] places up (negative) or down; the order is kept for every list. */
    fun moveGroup(group: String, step: Int) {
        val groups = _uiState.value.groups
        val from = groups.indexOf(group)
        val to = from + step
        if (from < 0 || to !in groups.indices) return
        val reordered = ArrayList(groups).apply { add(to, removeAt(from)) }
        groupOrder = reordered
        _uiState.update { it.copy(groups = reordered) }
        storage?.let { store -> scope.launch(writer) { store.saveGroupOrder(reordered) } }
        ReshapedSync.onLocalChange()
    }

    /**
     * Gives a category a name of its own (blank goes back to the playlist's name). It
     * keeps its channels, hiding and place: everything still goes by the playlist's name.
     */
    fun renameGroup(group: String, name: String) {
        val names = _uiState.value.groupNames
        val next = if (name.isBlank()) names - group else names + (group to name)
        if (next == names) return
        _uiState.update { state ->
            state.copy(groupNames = next).let { if (groupOrder.isEmpty()) it.copy(groups = orderedGroups(it.groupCounts.keys, next)) else it }
        }
        storage?.let { store -> scope.launch(writer) { store.saveGroupNames(next) } }
        ReshapedSync.onLocalChange()
    }

    /** Back to the providers' own order. */
    fun resetGroupOrder() {
        groupOrder = emptyList()
        _uiState.update { it.copy(groups = orderedGroups(it.groupCounts.keys, it.groupNames)) }
        storage?.let { store -> scope.launch(writer) { store.saveGroupOrder(emptyList()) } }
        ReshapedSync.onLocalChange()
    }

    /**
     * [names] in the viewer's order, then the ones it does not have yet in the providers' own
     * order (as IPTV players show them), with "Uncategorised" last.
     */
    private fun orderedGroups(names: Set<String>, renamed: Map<String, String> = _uiState.value.groupNames): List<String> {
        val ordered = groupOrder.filterTo(ArrayList()) { it in names }
        val placed = ordered.toHashSet()
        providerGroupOrder.forEach { if (it in names && it != LIVE_TV_UNGROUPED && placed.add(it)) ordered += it }
        names.filterNot(placed::contains)
            .sortedWith(
                compareBy<String> { it == LIVE_TV_UNGROUPED && it !in renamed }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { renamed[it]?.trim() ?: it },
            )
            .forEach(ordered::add)
        return ordered
    }

    /** Sorts the categories A to Z (by the names shown), as the viewer's own order. */
    fun sortGroupsAlphabetically() {
        val state = _uiState.value
        val sorted = state.groups.sortedWith(
            compareBy<String> { it == LIVE_TV_UNGROUPED && it !in state.groupNames }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { state.groupNames[it]?.trim() ?: it },
        )
        groupOrder = sorted
        _uiState.update { it.copy(groups = sorted) }
        storage?.let { store -> scope.launch(writer) { store.saveGroupOrder(sorted) } }
        ReshapedSync.onLocalChange()
    }

    private fun saveHiddenGroups(groups: Set<String>) {
        _uiState.update { it.copy(hiddenGroups = groups) }
        refreshShownChannels()
        storage?.let { store -> scope.launch(writer) { store.saveHiddenGroups(groups) } }
        ReshapedSync.onLocalChange()
    }

    fun toggleFavorite(channel: LiveTvChannel) {
        val store = storage ?: return
        val favorites = _uiState.value.favoriteUrls.toHashSet()
        if (!favorites.add(channel.streamUrl)) favorites.remove(channel.streamUrl)
        _uiState.update { it.copy(favoriteUrls = favorites) }
        scope.launch(writer) { store.saveFavoriteUrls(favorites) }
        ReshapedSync.onLocalChange()
    }

    // region Own playlists

    /** Makes a playlist named [name], with [channel] in it when given; returns its id. */
    fun createCustomList(name: String, channel: LiveTvChannel? = null): String? =
        createCustomList(name, listOfNotNull(channel))

    /** Makes a playlist named [name] holding [channels]; returns its id. */
    fun createCustomList(name: String, channels: List<LiveTvChannel>): String? {
        val title = name.trim().ifEmpty { return null }
        // Starts with the time it was made, so lists sort oldest first on every device.
        val id = System.currentTimeMillis().toString(36).padStart(9, '0') + java.util.UUID.randomUUID().toString().take(4)
        updateCustomLists { it + LiveTvCustomList(id, title, channels.map { c -> c.streamUrl }.distinct()) }
        return id
    }

    fun renameCustomList(id: String, name: String) {
        val title = name.trim().ifEmpty { return }
        // Typed a letter at a time: saved once the typing pauses.
        updateCustomLists(persistLater = true) { lists -> lists.map { if (it.id == id) it.copy(name = title) else it } }
    }

    fun deleteCustomList(id: String) {
        updateCustomLists { lists -> lists.filterNot { it.id == id } }
    }

    /** Adds [channels] to the end of list [id], skipping ones already in it. */
    fun addToCustomList(id: String, channels: Collection<LiveTvChannel>) {
        if (channels.isEmpty()) return
        updateCustomLists { lists ->
            lists.map { list ->
                if (list.id != id) return@map list
                val have = list.urls.toHashSet()
                list.copy(urls = list.urls + channels.map { it.streamUrl }.filter(have::add))
            }
        }
    }

    /** Makes [channels] favourites (ones already are stay). */
    fun addFavorites(channels: Collection<LiveTvChannel>) {
        val store = storage ?: return
        if (channels.isEmpty()) return
        val favorites = _uiState.value.favoriteUrls.toHashSet()
        if (!favorites.addAll(channels.map { it.streamUrl })) return
        _uiState.update { it.copy(favoriteUrls = favorites) }
        scope.launch(writer) { store.saveFavoriteUrls(favorites) }
        ReshapedSync.onLocalChange()
    }

    /** Takes [channels] out of favourites, in one change. */
    fun removeFavorites(channels: Collection<LiveTvChannel>) {
        val store = storage ?: return
        val favorites = _uiState.value.favoriteUrls.toHashSet()
        if (!favorites.removeAll(channels.mapTo(HashSet()) { it.streamUrl })) return
        _uiState.update { it.copy(favoriteUrls = favorites) }
        scope.launch(writer) { store.saveFavoriteUrls(favorites) }
        ReshapedSync.onLocalChange()
    }

    fun removeFromCustomList(id: String, url: String) {
        updateCustomLists { lists -> lists.map { if (it.id == id) it.copy(urls = it.urls - url) else it } }
    }

    /** Takes [channels] out of list [id], in one change. */
    fun removeFromCustomList(id: String, channels: Collection<LiveTvChannel>) {
        val urls = channels.mapTo(HashSet()) { it.streamUrl }
        updateCustomLists { lists -> lists.map { if (it.id == id) it.copy(urls = it.urls.filterNot(urls::contains)) else it } }
    }

    /**
     * Moves the channel [url] of list [id] past [step] of its neighbours; only those [shown]
     * counts (a channel no source lists now is hidden, and stepping over it would look stuck).
     */
    fun moveInCustomList(id: String, url: String, step: Int, shown: (String) -> Boolean = { true }) {
        updateCustomLists(persistLater = true) { lists ->
            lists.map { list ->
                val from = list.urls.indexOf(url)
                if (list.id != id || from < 0 || step == 0) return@map list
                var to = from
                var left = kotlin.math.abs(step)
                val direction = if (step > 0) 1 else -1
                while (left > 0) {
                    var next = to + direction
                    while (next in list.urls.indices && !shown(list.urls[next])) next += direction
                    if (next !in list.urls.indices) break
                    to = next
                    left--
                }
                if (to == from) return@map list
                list.copy(urls = list.urls.toMutableList().apply { add(to, removeAt(from)) })
            }
        }
    }

    private var pendingListSave: Job? = null

    /**
     * [persistLater]: a run of quick changes (a held ▲ moving a channel) is saved and synced
     * once it pauses, not on every step.
     */
    private fun updateCustomLists(persistLater: Boolean = false, change: (List<LiveTvCustomList>) -> List<LiveTvCustomList>) {
        val store = storage ?: return
        var saved: List<LiveTvCustomList> = emptyList()
        _uiState.update { state -> state.copy(customLists = change(state.customLists).also { saved = it }) }
        pendingListSave?.cancel()
        pendingListSave = scope.launch(writer) {
            if (persistLater) delay(LIST_SAVE_DELAY_MS)
            store.saveCustomLists(saved)
            if (persistLater) ReshapedSync.onLocalChange()
        }
        if (!persistLater) ReshapedSync.onLocalChange()
    }

    /** The playlists with links that moved ([moved], old link to new) changed over. */
    private fun List<LiveTvCustomList>.movedTo(moved: Map<String, LiveTvChannel>): List<LiveTvCustomList> =
        map { list -> if (list.urls.none(moved::containsKey)) list else list.copy(urls = list.urls.map { moved[it]?.streamUrl ?: it }.distinct()) }

    // endregion

    /** [channel] is the list's own entry (not a resolved Stalker link), so it can be found again. */
    fun recordRecentChannel(channel: LiveTvChannel) {
        val recent = LiveTvRecentChannel(channel.streamUrl, channel.name, channel.logoUrl, channel.group, channel.tvgId)
        if (_uiState.value.recentChannel == recent) return
        _uiState.update { it.copy(recentChannel = recent) }
        storage?.let { store -> scope.launch(writer) { store.saveRecentChannel(recent) } }
    }

    /** The list entry for a remembered channel, or a stand-in when the list no longer has it. */
    fun channelFor(recent: LiveTvRecentChannel): LiveTvChannel =
        _uiState.value.channels.firstOrNull { it.streamUrl == recent.streamUrl }
            ?: LiveTvChannel(
                id = recent.streamUrl,
                name = recent.name,
                streamUrl = recent.streamUrl,
                tvgId = recent.tvgId,
                logoUrl = recent.logoUrl,
                group = recent.group,
                headers = defaultStreamHeaders(recent.streamUrl),
                guideKey = recent.guideKey,
            )

    /** Whether [channel] comes from a Stalker portal, whose links are created per play. */
    fun isStalker(channel: LiveTvChannel): Boolean =
        channel.stalkerCommand != null &&
            _uiState.value.sources.firstOrNull { it.id == channel.sourceId }?.type == LiveTvSourceType.Stalker

    /** The channel with a link that plays now: Stalker links are created per play; others are as listed. */
    suspend fun playableChannel(channel: LiveTvChannel): LiveTvChannel {
        val source = _uiState.value.sources.firstOrNull { it.id == channel.sourceId }
        if (source?.type != LiveTvSourceType.Stalker || channel.stalkerCommand == null) return channel
        return try {
            LiveTvStalker.resolve(source.stalker, channel)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Stalker link failed, trying the listed link", error)
            channel
        }
    }

    /**
     * The channel as the player should open it, registered so the player treats it as Live TV,
     * and remembered as the last channel.
     */
    suspend fun prepareForPlayback(channel: LiveTvChannel): LiveTvChannel {
        val playback = playableChannel(channel)
        LiveTvPlaybackRegistry.register(playback.streamUrl, listUrl = channel.streamUrl)
        recordRecentChannel(channel)
        return playback
    }

    /**
     * The channel with the link to its past [programme] (catch-up), registered so the player
     * treats it as Live TV that can be sought; null when the provider keeps no such programme.
     */
    suspend fun catchupChannel(channel: LiveTvChannel, programme: LiveTvProgramme): LiveTvChannel? {
        val catchup = channel.catchup ?: return null
        val now = LiveTvClock.nowEpochMs()
        if (!LiveTvCatchupLinks.isPlayable(catchup, programme, now)) return null
        // Only an Xtream panel answers its /timeshift/ form; other "shift" servers take ?utc=.
        val source = _uiState.value.sources.firstOrNull { it.id == channel.sourceId }
        val xtreamPanel = source == null || source.type == LiveTvSourceType.Xtream ||
            (source.type == LiveTvSourceType.M3u && xtreamGuideUrlFor(source.url) != null)
        val zone = if (LiveTvCatchupLinks.needsPanelZone(channel.streamUrl, catchup, xtreamPanel)) {
            LiveTvCatchupLinks.xtreamLogin(channel.streamUrl)?.let { (server, user, pass) -> LiveTvXtream.zone(server, user, pass) }
        } else {
            null
        }
        // A programme still on air plays from its start up to now.
        val stop = minOf(programme.stopEpochMs, now)
        val link = LiveTvCatchupLinks.link(channel.streamUrl, catchup, programme.startEpochMs, stop, now, zone, xtreamPanel) ?: return null
        LiveTvPlaybackRegistry.register(link, listUrl = channel.streamUrl, catchup = true)
        recordRecentChannel(channel)
        return channel.copy(streamUrl = link)
    }

    /** The channel next to the one at [listUrl] in [channels], wrapping around. */
    fun neighbour(channels: List<LiveTvChannel>, listUrl: String?, step: Int): LiveTvChannel? {
        if (channels.isEmpty()) return null
        val index = channels.indexOfFirst { it.streamUrl == listUrl }
        val next = if (index < 0) 0 else Math.floorMod(index + step, channels.size)
        return channels[next]
    }

    // region Loads

    private fun cancelAllLoads() {
        sourceJobs.values.forEach(Job::cancel)
        sourceJobs.clear()
    }

    /** The screen shows "Loading" while any source is loading. */
    private fun updateLoading() {
        val busy = sourceJobs.values.any { it.isActive }
        _uiState.update { if (it.isLoading == busy) it else it.copy(isLoading = busy) }
    }

    /** A source being added keeps the id of a saved one that is the same source, so it replaces it. */
    private fun withExistingId(candidate: LiveTvSource, store: LiveTvStorage): LiveTvSource {
        val existing = _uiState.value.sources.firstOrNull { it.identity == candidate.identity }
        return candidate.copy(id = existing?.id ?: store.newSourceId())
    }

    /**
     * Loads a new source (or a saved one entered again) and adds it once it listed channels; a
     * failed attempt changes nothing but the error shown.
     */
    private fun launchAdd(input: LiveTvSource) {
        val store = storage ?: return
        val validation = validate(input)
        if (validation != null) {
            _uiState.update { it.copy(error = validation) }
            return
        }
        val candidate = if (input.id.isBlank()) withExistingId(input, store) else input
        _uiState.update { it.copy(error = null) }
        launchSourceLoad(candidate, adding = true)
    }

    /** Loads one source in its own job, replacing only an earlier load of the same source. */
    private fun launchSourceLoad(source: LiveTvSource, adding: Boolean) {
        val store = storage ?: return
        sourceJobs.remove(source.id)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val outcome = loadPermits.withPermit {
                try {
                    Result.success(loadSource(source))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w(TAG, "Live TV source ${source.type} failed", error)
                    Result.failure(error)
                }
            }
            val self = coroutineContext[Job]
            withContext(serial) {
                // A newer load of this source, or its removal, wins over this result.
                if (sourceJobs[source.id] !== self) return@withContext
                val known = _uiState.value.sources.any { it.id == source.id }
                if (!adding && !known) return@withContext
                outcome.onSuccess { (result, notice) ->
                    val previous = loaded[source.id]
                    loaded[source.id] = result
                    if (adding && known) {
                        if (previous != null) {
                            carryOverFavorites(source, previous, result, store)
                        } else {
                            // The old list never loaded (its server moved): Xtream links still name their channel.
                            _uiState.value.sources.firstOrNull { it.id == source.id }?.let { old -> carryOverXtreamFavorites(old, result, store) }
                        }
                    }
                    if (adding) {
                        val sources = _uiState.value.sources.let { current ->
                            if (known) current.map { if (it.id == source.id) source else it } else current + source
                        }
                        _uiState.update { it.copy(sources = sources, addedCount = it.addedCount + 1, error = notice) }
                        scope.launch(writer) {
                            store.saveSources(sources)
                            if (source.type != LiveTvSourceType.M3u || source.url.isHttpUrl()) store.deletePlaylistFile(source.id)
                        }
                        ReshapedSync.onLocalChange()
                    }
                    _uiState.update { state ->
                        val errors = state.sourceErrors - source.id
                        state.copy(sourceErrors = if (notice != null) errors + (source.id to notice) else errors)
                    }
                    publish()
                }
                outcome.onFailure { error ->
                    val reason = (error as? LiveTvException)?.error ?: fallbackError(source.type)
                    if (adding) {
                        if (!known && source.type == LiveTvSourceType.M3u && !source.url.isHttpUrl()) {
                            scope.launch(writer) { store.deletePlaylistFile(source.id) }
                        }
                        _uiState.update { it.copy(error = reason, isLoaded = it.isLoaded || it.sources.isNotEmpty()) }
                    } else {
                        // The source keeps the channels it had.
                        _uiState.update { it.copy(sourceErrors = it.sourceErrors + (source.id to reason), isLoaded = true) }
                    }
                }
            }
        }
        sourceJobs[source.id] = job
        job.invokeOnCompletion {
            sourceJobs.remove(source.id, job)
            updateLoading()
        }
        _uiState.update { it.copy(isLoading = true) }
        job.start()
    }

    private fun validate(source: LiveTvSource): LiveTvError? =
        if (source.epgUrl.isNotBlank() && !source.epgUrl.isHttpUrl()) LiveTvError.GuideInvalidUrl else validateSource(source)

    private fun validateSource(source: LiveTvSource): LiveTvError? = when (source.type) {
        LiveTvSourceType.M3u -> if (source.url.isBlank()) LiveTvError.InvalidUrl else null
        LiveTvSourceType.Xtream -> when {
            !source.xtream.isConfigured -> LiveTvError.XtreamRequired
            !source.xtream.serverUrl.isHttpUrl() -> LiveTvError.XtreamInvalidUrl
            else -> null
        }
        LiveTvSourceType.Stalker -> when {
            !source.stalker.isConfigured -> LiveTvError.StalkerRequired
            !source.stalker.portalUrl.isHttpUrl() -> LiveTvError.StalkerInvalidUrl
            else -> null
        }
    }

    private fun fallbackError(type: LiveTvSourceType): LiveTvError = when (type) {
        LiveTvSourceType.M3u -> LiveTvError.LoadFailed
        LiveTvSourceType.Xtream -> LiveTvError.XtreamFailed
        LiveTvSourceType.Stalker -> LiveTvError.StalkerFailed
    }

    /** One source's channels (tagged with the source) and guide links, plus a notice for a partial load. */
    private suspend fun loadSource(source: LiveTvSource): Pair<LoadedSource, LiveTvError?> {
        var notice: LiveTvError? = null
        var providerOrder: List<String> = emptyList()
        val (channels, epgUrls) = when (source.type) {
            LiveTvSourceType.M3u -> {
                val file = withContext(Dispatchers.IO) { storage?.playlistFile(source.id) }
                val playlist = when {
                    // An imported file; a source edited to a link loads the link.
                    file != null && !source.url.isHttpUrl() -> withContext(Dispatchers.IO) { file.bufferedReader().useLines { parseM3uPlaylist(it) } }
                    source.url.isHttpUrl() -> fetchM3u(source.url)
                    else -> throw LiveTvException(if (source.url.startsWith("http", ignoreCase = true)) LiveTvError.InvalidUrl else LiveTvError.FileEmpty)
                }
                if (playlist.channels.isEmpty()) {
                    throw LiveTvException(if (file != null) LiveTvError.FileNoChannels else LiveTvError.NoChannels)
                }
                // An Xtream panel's M3U link names no guide more often than not; the panel still has one.
                val guides = playlist.epgUrls.ifEmpty { listOfNotNull(xtreamGuideUrlFor(source.url)) }
                playlist.channels to guides
            }
            LiveTvSourceType.Xtream -> {
                val settings = source.xtream
                val loaded = LiveTvXtream.channels(settings)
                val channels = loaded.channels
                providerOrder = loaded.groupOrder
                if (channels.isEmpty()) throw LiveTvException(LiveTvError.XtreamNoChannels)
                // Xtream providers publish their guide at xmltv.php.
                channels to listOf("${settings.serverUrl}/xmltv.php?username=${settings.username.urlEncoded()}&password=${settings.password.urlEncoded()}")
            }
            LiveTvSourceType.Stalker -> {
                val (channels, incomplete, genres) = LiveTvStalker.channels(source.stalker)
                providerOrder = genres
                if (channels.isEmpty()) throw LiveTvException(LiveTvError.StalkerNoChannels)
                if (incomplete) notice = LiveTvError.StalkerIncomplete
                // The portal's own guide (see [stalkerGuideLink]).
                channels to listOf(stalkerGuideLink(source))
            }
        }
        // Ids only need to be unique within a source; the list keys on them across all of them.
        val tagged = ArrayList<LiveTvChannel>(channels.size)
        val stalker = source.type == LiveTvSourceType.Stalker
        channels.forEach { channel ->
            val group = channel.group.trim()
            // Portal channels without a guide id get one from their portal id, which the portal's guide uses.
            val tvgId = if (stalker && channel.tvgId.isNullOrBlank()) "stalker.${source.id}.${channel.id}".lowercase() else channel.tvgId
            tagged += channel.copy(
                id = "${source.id}/${channel.id}",
                sourceId = source.id,
                group = group,
                tvgId = tvgId,
                hideKey = liveTvHideKey(source.id, group, channel.name),
                guideKey = liveTvGuideKey(tvgId, channel.name, source.id),
            )
        }
        // A guide link the viewer added comes first: it is what they chose over the source's own.
        val guides = (listOfNotNull(source.epgUrl.trim().takeIf(String::isNotEmpty)) + epgUrls).distinct()
        // The provider's category order, keeping only categories with channels, then any it did not list, as they appear.
        val used = LinkedHashSet<String>()
        tagged.forEach { used += it.group }
        val order = ArrayList<String>(used.size)
        providerOrder.forEach { if (it in used && it !in order) order += it }
        if (order.size < used.size) {
            val placed = order.toHashSet()
            used.forEach { if (placed.add(it)) order += it }
        }
        return LoadedSource(tagged, guides, order) to notice
    }

    /**
     * A Stalker portal's guide, which is not a link but the portal's `get_epg_info`: a name for it
     * in the guide list, changing with the portal and MAC so an edited source downloads it again.
     */
    private fun stalkerGuideLink(source: LiveTvSource): String {
        val login = "${source.stalker.portalUrl.lowercase()}|${source.stalker.macAddress}"
        return "$STALKER_GUIDE_PREFIX${source.id}:${Integer.toHexString(login.hashCode())}"
    }

    /**
     * After an edit changed a source's links: favourites and the last channel of that source
     * move to the same channel's new link (same category and name, or for Xtream and Stalker the
     * same provider id). Runs on [serial].
     */
    private fun carryOverFavorites(source: LiveTvSource, before: LoadedSource, after: LoadedSource, store: LiveTvStorage) {
        val state = _uiState.value
        val recent = state.recentChannel
        val listed = state.customLists.flatMapTo(HashSet()) { it.urls }
        val wanted = before.channels.filter { it.streamUrl in state.favoriteUrls || it.streamUrl in listed || it.streamUrl == recent?.streamUrl }
        if (wanted.isEmpty()) return
        val byKey = HashMap<Long, LiveTvChannel>(after.channels.size * 2)
        after.channels.forEach { byKey.putIfAbsent(it.hideKey, it) }
        val byId = if (source.type == LiveTvSourceType.M3u) emptyMap() else after.channels.associateBy { it.id }
        val moved = HashMap<String, LiveTvChannel>()
        wanted.forEach { old ->
            val now = byKey[old.hideKey] ?: byId[old.id] ?: return@forEach
            if (now.streamUrl != old.streamUrl) moved[old.streamUrl] = now
        }
        if (moved.isEmpty()) return
        saveMoved(moved, store)
    }

    /**
     * Favourites, playlists and the last channel moved to their new links. Changed on the
     * current state, so an edit made while the source loaded is not lost.
     */
    private fun saveMoved(moved: Map<String, LiveTvChannel>, store: LiveTvStorage) {
        var favorites: Set<String> = emptySet()
        var lists: List<LiveTvCustomList> = emptyList()
        var newRecent: LiveTvRecentChannel? = null
        _uiState.update { state ->
            favorites = state.favoriteUrls.mapTo(HashSet()) { moved[it]?.streamUrl ?: it }
            lists = state.customLists.movedTo(moved)
            newRecent = state.recentChannel?.let { r -> moved[r.streamUrl]?.let { r.copy(streamUrl = it.streamUrl, logoUrl = it.logoUrl) } ?: r }
            state.copy(favoriteUrls = favorites, customLists = lists, recentChannel = newRecent)
        }
        scope.launch(writer) {
            store.saveFavoriteUrls(favorites)
            store.saveCustomLists(lists)
            newRecent?.let(store::saveRecentChannel)
        }
    }

    /**
     * Favourites and the last channel of [old] (an Xtream login, or its get.php list) follow
     * [after]'s channels with the same stream id, for a source edited before its list loaded.
     */
    private fun carryOverXtreamFavorites(old: LiveTvSource, after: LoadedSource, store: LiveTvStorage) {
        val login = xtreamLoginOf(old) ?: return
        val state = _uiState.value
        val recent = state.recentChannel
        fun matches(url: String): Boolean = LiveTvCatchupLinks.xtreamLogin(url)?.let { (server, user, pass) ->
            server.trimEnd('/').equals(login.first.trimEnd('/'), ignoreCase = true) && user == login.second && pass == login.third
        } == true
        val wanted = (state.favoriteUrls + state.customLists.flatMap { it.urls } + listOfNotNull(recent?.streamUrl)).filter(::matches)
        if (wanted.isEmpty()) return
        val byFile = HashMap<String, LiveTvChannel>(after.channels.size * 2)
        after.channels.forEach { byFile.putIfAbsent(it.streamUrl.substringBefore('?').substringAfterLast('/').substringBefore('.'), it) }
        val moved = HashMap<String, LiveTvChannel>()
        wanted.forEach { url ->
            val now = byFile[url.substringBefore('?').substringAfterLast('/').substringBefore('.')] ?: return@forEach
            if (now.streamUrl != url) moved[url] = now
        }
        if (moved.isEmpty()) return
        saveMoved(moved, store)
    }

    /** The server, user and password of an Xtream source, or of an M3U get.php link. */
    private fun xtreamLoginOf(source: LiveTvSource): Triple<String, String, String>? = when (source.type) {
        LiveTvSourceType.Xtream -> source.xtream.takeIf { it.isConfigured }?.let { Triple(it.serverUrl, it.username, it.password) }
        LiveTvSourceType.M3u -> source.url.toHttpUrlOrNull()?.takeIf { it.encodedPath.endsWith("/get.php", ignoreCase = true) }?.let { url ->
            val user = url.queryParameter("username") ?: return@let null
            val pass = url.queryParameter("password") ?: return@let null
            val folder = url.encodedPath.dropLast("/get.php".length)
            Triple(url.newBuilder().encodedPath(folder.ifEmpty { "/" }).query(null).build().toString().trimEnd('/'), user, pass)
        }
        LiveTvSourceType.Stalker -> null
    }

    private suspend fun fetchM3u(url: String): ParsedM3uPlaylist {
        if (url.looksLikeDirectVideoUrl()) return ParsedM3uPlaylist(listOf(directStreamChannel(url)), emptyList())
        val parsed = LiveTvHttp.stream(url, LIVE_TV_PLAYLIST_HEADERS) { input ->
            parseM3uPlaylist(input.bufferedReader().lineSequence())
        }
        return if (parsed.isHlsStream) ParsedM3uPlaylist(listOf(directStreamChannel(url)), emptyList()) else parsed
    }

    /**
     * Shows the channels of every saved source as one list, and (re)starts the guide when its
     * inputs changed. Runs on [serial].
     */
    private fun publish() {
        val sources = _uiState.value.sources
        val parts = sources.mapNotNull { loaded[it.id] }
        val channels = ArrayList<LiveTvChannel>(parts.sumOf { it.channels.size })
        parts.forEach { channels.addAll(it.channels) }
        val groupCounts = HashMap<String, Int>()
        val sourceCounts = HashMap<String, Int>()
        channels.forEach { channel ->
            // Channels without a category count under "Uncategorised", so they can be hidden too.
            groupCounts[channel.group] = (groupCounts[channel.group] ?: 0) + 1
            sourceCounts[channel.sourceId] = (sourceCounts[channel.sourceId] ?: 0) + 1
        }
        providerGroupOrder = parts.flatMap { it.groupOrder }.distinct()
        val groups = orderedGroups(groupCounts.keys)
        val epgUrls = parts.flatMap { it.epgUrls }.distinct()
        val guideKeys = channels.mapTo(HashSet(channels.size * 2)) { it.guideKey }
        val hasGuide = epgUrls.isNotEmpty() && guideKeys.isNotEmpty()
        val guideInput = Triple(epgUrls, guideKeys, liveTvGuideMatchingKey(channels))
        val guideChanged = epgKey != guideInput || epgJob?.isActive != true
        _uiState.update {
            it.copy(
                channels = channels,
                shownChannels = shownChannels(channels, it.hiddenGroups, it.hiddenChannelKeys),
                groups = groups,
                groupCounts = groupCounts,
                sourceCounts = sourceCounts,
                // What is on now stays shown until the guide is read again.
                currentProgrammes = if (hasGuide) it.currentProgrammes else emptyMap(),
                guideLogos = if (hasGuide) it.guideLogos else emptyMap(),
                isEpgLoading = hasGuide && (guideChanged || it.isEpgLoading),
                isLoaded = true,
                // A source's guide shows as loading until the next read says how it did.
                sourceGuides = sources.associate { source ->
                    val links = loaded[source.id]?.epgUrls
                    source.id to when {
                        links == null -> it.sourceGuides[source.id] ?: LiveTvSourceGuide.Loading
                        links.isEmpty() -> LiveTvSourceGuide.None
                        guideChanged -> LiveTvSourceGuide.Loading
                        else -> it.sourceGuides[source.id] ?: LiveTvSourceGuide.Loading
                    }
                },
            )
        }
        if (!hasGuide) {
            stopEpg()
            epgKey = null
        } else if (guideChanged) {
            epgKey = guideInput
            // A slow or failed channel source must not delay the already-loaded sources' EPG.
            startEpg(epgUrls, channels, guideKeys)
        }
    }

    /** Recomputes the channels zapping moves through after a category or channel was hidden or shown. */
    private fun refreshShownChannels() {
        scope.launch(serial) {
            val state = _uiState.value
            val shown = shownChannels(state.channels, state.hiddenGroups, state.hiddenChannelKeys)
            _uiState.update { current ->
                if (current.channels === state.channels && current.hiddenGroups === state.hiddenGroups &&
                    current.hiddenChannelKeys === state.hiddenChannelKeys
                ) {
                    current.copy(shownChannels = shown)
                } else {
                    current
                }
            }
        }
    }

    private fun shownChannels(channels: List<LiveTvChannel>, hiddenGroups: Set<String>, hiddenKeys: Set<Long>): List<LiveTvChannel> =
        if (hiddenGroups.isEmpty() && hiddenKeys.isEmpty()) {
            channels
        } else {
            channels.filter { it.group !in hiddenGroups && it.hideKey !in hiddenKeys }
        }

    // endregion

    // region Cloud sync (reshaped/sync)

    /** What [profileId] has now: the shown state when it is loaded, else the saved files. */
    internal suspend fun syncSnapshot(context: Context, profileId: Int): LiveTvSyncData {
        val shown = withContext(serial) {
            if (loadedProfileId != profileId || storage == null) return@withContext null
            val state = _uiState.value
            LiveTvSyncData(
                sources = state.sources.filter { it.isSyncable },
                favorites = state.favoriteUrls,
                customLists = state.customLists.associateBy { it.id },
                hiddenGroups = state.hiddenGroups,
                hiddenChannels = state.hiddenChannelKeys,
                groupNames = state.groupNames,
                groupOrder = groupOrder,
                recent = state.recentChannel,
            )
        }
        return shown ?: withContext(writer) { LiveTvStorage(context.applicationContext, profileId).syncData() }
    }

    /**
     * Makes the change sync brought in ([before] to [after]) on top of what [profileId] has now,
     * saves it, and shows it when the profile is loaded. Only changed sources load again.
     */
    internal suspend fun applySync(context: Context, profileId: Int, before: LiveTvSyncData, after: LiveTvSyncData) {
        if (before == after) return
        val appContext = context.applicationContext
        val applied = withContext(serial) {
            val store = storage
            if (loadedProfileId != profileId || store == null) return@withContext false
            val oldSources = _uiState.value.sources
            _uiState.update { state ->
                state.copy(
                    sources = state.sources.withSyncChange(before.sources, after.sources, store::newSourceId),
                    favoriteUrls = state.favoriteUrls.withSyncChange(before.favorites, after.favorites),
                    customLists = state.customLists.withSyncChange(before.customLists, after.customLists),
                    hiddenGroups = state.hiddenGroups.withSyncChange(before.hiddenGroups, after.hiddenGroups),
                    hiddenChannelKeys = state.hiddenChannelKeys.withSyncChange(before.hiddenChannels, after.hiddenChannels),
                    groupNames = state.groupNames.withSyncChange(before.groupNames, after.groupNames),
                    recentChannel = if (before.recent != after.recent) after.recent else state.recentChannel,
                )
            }
            if (before.groupOrder != after.groupOrder) groupOrder = after.groupOrder
            _uiState.update { it.copy(groups = orderedGroups(it.groupCounts.keys, it.groupNames)) }
            val state = _uiState.value
            val sources = state.sources
            if (sources != oldSources) {
                val kept = sources.associateBy { it.id }
                oldSources.filter { it.id !in kept }.forEach { gone ->
                    sourceJobs.remove(gone.id)?.cancel()
                    loaded.remove(gone.id)
                }
                LiveTvStalker.clearSession()
                val oldById = oldSources.associateBy { it.id }
                sources.filter { oldById[it.id] != it }.forEach { launchSourceLoad(it, adding = false) }
                publish()
                updateLoading()
            } else {
                refreshShownChannels()
            }
            val order = groupOrder
            scope.launch(writer) {
                store.saveSources(sources)
                store.saveFavoriteUrls(state.favoriteUrls)
                store.saveCustomLists(state.customLists)
                store.saveHiddenGroups(state.hiddenGroups)
                store.saveHiddenChannelKeys(state.hiddenChannelKeys)
                store.saveGroupNames(state.groupNames)
                store.saveGroupOrder(order)
                state.recentChannel?.let(store::saveRecentChannel)
            }
            true
        }
        if (applied) return
        withContext(writer) {
            val store = LiveTvStorage(appContext, profileId)
            val sources = store.sources()
            store.saveSources(sources.withSyncChange(before.sources, after.sources, store::newSourceId))
            store.saveFavoriteUrls(store.favoriteUrls().withSyncChange(before.favorites, after.favorites))
            store.saveCustomLists(store.customLists().withSyncChange(before.customLists, after.customLists))
            store.saveHiddenGroups(store.hiddenGroups().withSyncChange(before.hiddenGroups, after.hiddenGroups))
            store.saveHiddenChannelKeys(store.hiddenChannelKeys().withSyncChange(before.hiddenChannels, after.hiddenChannels))
            store.saveGroupNames(store.groupNames().withSyncChange(before.groupNames, after.groupNames))
            if (before.groupOrder != after.groupOrder) store.saveGroupOrder(after.groupOrder)
            if (before.recent != after.recent) after.recent?.let(store::saveRecentChannel)
        }
    }

    // endregion

    // region Guide

    /** The next programme after the one on now for [guideKey], from the kept guide; a map lookup. */
    fun nextProgramme(guideKey: String, nowEpochMs: Long = LiveTvClock.nowEpochMs()): LiveTvProgramme? =
        keptSchedule[guideKey]?.firstOrNull { it.startEpochMs > nowEpochMs }

    /** Every kept programme of [guideKey], earliest first: the last few hours and the next ones. */
    fun schedule(guideKey: String): List<LiveTvProgramme> = keptSchedule[guideKey].orEmpty()

    private fun stopEpg() {
        keptSchedule = emptyMap()
        epgGeneration++
        epgJob?.cancel()
        epgJob = null
    }

    /**
     * Reads the guide and moves each channel's "now playing" on every minute. Only runs while
     * something shows Live TV (the list or a channel in the player) and catches up when it is
     * opened again. The guide is saved compressed in the cache, downloaded again every 10 hours
     * (or on Refresh), and re-read from there whenever channels run out of kept programmes. A
     * guide that fails is tried again sooner, without holding back the ones that loaded.
     */
    private fun startEpg(epgUrls: List<String>, channels: List<LiveTvChannel>, guideKeys: Set<String>) {
        val window = if (LiveTvDevice.isLowMemory(appContext)) LiveTvGuideWindow.LowMemory else LiveTvGuideWindow.Regular
        // The last good guide stays visible while an independent refresh is running.
        epgGeneration++
        epgJob?.cancel()
        val generation = epgGeneration
        val guideFiles = epgUrls.map { File(guideDir(), "guide_${Integer.toHexString(it.hashCode())}.xml.gz") }
        val cacheFile = File(guideDir(), LiveTvGuideCache.FILE_NAME)
        val catchupKeys = channels.mapNotNullTo(HashSet()) { channel -> channel.guideKey.takeIf { channel.catchup != null } }
        // Names (and missing logos) decide name matches and guide logos: a list that renames
        // channels keeping their ids must not be served the matches kept for the old names.
        val cacheKey = LiveTvGuideCache.key(epgUrls, guideKeys, window, catchupKeys) * 31 + liveTvGuideMatchingKey(channels)
        guideWindow = window
        // Which sources each guide belongs to, and how a portal's guide is fetched. Read here, on [serial].
        val sourceLinks = _uiState.value.sources.associate { it.id to loaded[it.id]?.epgUrls.orEmpty() }
        val sourcesById = _uiState.value.sources.associateBy { it.id }
        // A feed belongs only to the playlists that list it. Matching every feed against every
        // source allowed the first provider's names/ids to overwrite another provider's guide.
        val requests = epgUrls.map { url ->
            val owners = sourceLinks.filterValues { url in it }.keys
            LiveTvGuideRequest.from(channels.filter { it.sourceId in owners })
        }
        val sourceForKey = channels.associate { it.guideKey to it.sourceId }
        val aheadHours = (window.aheadMs / (60L * 60 * 1000)).toInt()
        val downloads: List<suspend (File) -> Unit> = epgUrls.map { url ->
            val portal = url.takeIf { it.startsWith(STALKER_GUIDE_PREFIX) }
                ?.let { sourcesById[it.removePrefix(STALKER_GUIDE_PREFIX).substringBefore(':')] }
            val fetch: suspend (File) -> Unit = if (portal != null) {
                { file ->
                    // The portal's channel id (after "<source>/") to its raw XMLTV id.
                    val ids = HashMap<String, String>()
                    channels.forEach { channel ->
                        if (channel.sourceId == portal.id) channel.tvgId?.let { ids[channel.id.substringAfter('/')] = it }
                    }
                    LiveTvStalker.downloadGuide(portal.stalker, ids, aheadHours, file)
                }
            } else {
                { file -> LiveTvHttp.download(url, LIVE_TV_STREAM_HEADERS, file, LiveTvHttp.GUIDE_READ_TIMEOUT_S, expectXml = true) }
            }
            fetch
        }
        epgJob = scope.launch {
            withContext(Dispatchers.IO) {
                // Guides of an earlier source.
                guideDir().listFiles()?.filter { it.name.endsWith(".xml.gz") && it !in guideFiles }?.forEach(File::delete)
            }
            var schedule: LiveTvSchedule = keptSchedule
            var nextReadAtMs = 0L
            var firstRead = true
            val publishGuide = { kept: LiveTvSchedule, logos: Map<String, String>?, nowMs: Long, failedLinks: Set<String>? ->
                if (epgGeneration == generation) keptSchedule = kept
                val current = currentProgrammes(kept, guideKeys, nowMs)
                val guides = failedLinks?.let { guideStates(sourceLinks, it, kept, channels) }
                _uiState.update { state ->
                    if (epgGeneration != generation) {
                        state
                    } else {
                        state.copy(
                            guideLogos = if (logos == null || state.guideLogos == logos) state.guideLogos else logos,
                            guideVersion = state.guideVersion + 1,
                            currentProgrammes = current,
                            sourceGuides = guides?.let { state.sourceGuides + it } ?: state.sourceGuides,
                        )
                    }
                }
            }
            while (isActive) {
                _uiState.subscriptionCount.first { it > 0 }
                val nowMs = LiveTvClock.nowEpochMs()
                if (nowMs >= nextReadAtMs) {
                    // Nothing waits on a guide nobody is looking at: leaving Live TV for a film
                    // stops the download and read instead of letting them compete with playback.
                    val finished = whileSeen {
                        val force = forceGuideDownload
                        // Opening again: the programmes kept by the last full read, while still good.
                        val saved = if (firstRead && !force) {
                            withContext(Dispatchers.IO) { LiveTvGuideCache.read(cacheFile, cacheKey, guideFiles, nowMs, EPG_DOWNLOAD_MS) }
                        } else {
                            null
                        }
                        firstRead = false
                        if (saved != null) {
                            schedule = saved.schedule
                            nextReadAtMs = saved.nextReadAtMs
                            publishGuide(schedule, saved.logos, nowMs, emptySet())
                        } else {
                            val previous = schedule
                            val previousLogos = _uiState.value.guideLogos.filterKeys { it in guideKeys }
                            val loaded = HashMap<String, List<LiveTvProgramme>>()
                            val logos = HashMap<String, String>()
                            val truncated = HashSet<String>()
                            val failedLinks = HashSet<String>()
                            var partial = false
                            val scheduleRanks = HashMap<String, Int>()
                            val logoRanks = HashMap<String, Int>()
                            // Two independent imports at most, and a bounded result queue. A slow
                            // first feed no longer prevents a faster provider's guide from showing.
                            loadLiveTvGuides(epgUrls.size, read = { index ->
                                val epgUrl = epgUrls[index]
                                readGuide(
                                    downloads[index], epgUrl.takeUnless { it.startsWith(STALKER_GUIDE_PREFIX) },
                                    guideFiles[index], requests[index], nowMs, window, force,
                                )
                            }, publish = publish@ { index, guide ->
                                val epgUrl = epgUrls[index]
                                if (guide == null) {
                                    failedLinks += epgUrl
                                    return@publish
                                }
                                if (!guide.complete) partial = true
                                if (guide.refreshFailed) failedLinks += epgUrl
                                fun rank(key: String): Int = sourceLinks[sourceForKey[key]]?.indexOf(epgUrl)
                                    ?.takeIf { it >= 0 } ?: Int.MAX_VALUE
                                guide.schedule.forEach { (key, list) ->
                                    val priority = rank(key)
                                    if (priority < (scheduleRanks[key] ?: Int.MAX_VALUE)) {
                                        scheduleRanks[key] = priority
                                        loaded[key] = list
                                        if (key in guide.truncated) truncated += key else truncated -= key
                                    }
                                }
                                guide.logos.forEach { (key, logo) ->
                                    val priority = rank(key)
                                    if (priority < (logoRanks[key] ?: Int.MAX_VALUE)) {
                                        logoRanks[key] = priority
                                        logos[key] = logo
                                    }
                                }
                                publishGuide(
                                    HashMap(previous).apply { putAll(loaded) },
                                    HashMap(previousLogos).apply { putAll(logos) }, nowMs, null,
                                )
                            })
                            val failed = failedLinks.isNotEmpty()
                            if (force && epgGeneration == generation) forceGuideDownload = false
                            // A guide that failed, or broke off part way, keeps what it showed before.
                            schedule = if ((failed || partial) && previous.isNotEmpty()) HashMap(previous).apply { putAll(loaded) } else loaded
                            val regular = if (loaded.isEmpty()) {
                                nowMs + EPG_RETRY_MS
                            } else {
                                nextScheduleReadAt(loaded, truncated, nowMs, EPG_MIN_READ_GAP_MS, EPG_DOWNLOAD_MS)
                            }
                            nextReadAtMs = if (failed || partial) minOf(regular, nowMs + EPG_RETRY_MS) else regular
                            val shownLogos = if (failed || partial) HashMap(previousLogos).apply { putAll(logos) } else logos
                            publishGuide(schedule, shownLogos, nowMs, failedLinks)
                            // A guide that broke off part way is not saved as the kept programmes.
                            if (!failed && !partial && loaded.isNotEmpty() && epgGeneration == generation) {
                                val entry = LiveTvGuideCache.Entry(loaded, logos, nextReadAtMs)
                                withContext(Dispatchers.IO) { LiveTvGuideCache.write(cacheFile, cacheKey, guideFiles, entry) }
                            }
                        }
                        Unit
                    }
                    // It starts over when Live TV is shown again.
                    if (finished == null) continue
                }
                val current = currentProgrammes(schedule, guideKeys, nowMs)
                _uiState.update { state ->
                    when {
                        epgGeneration != generation -> state
                        state.currentProgrammes != current || state.isEpgLoading ->
                            state.copy(currentProgrammes = current, isEpgLoading = false)
                        else -> state
                    }
                }
                if (epgGeneration != generation) return@launch
                delay(EPG_TICK_MS)
            }
        }
    }

    /**
     * Runs [block] while something shows Live TV. Once nothing has for [UNSEEN_GRACE_MS] (the list
     * left for Home, a film started), it is cancelled and null is returned; the short grace keeps
     * it going across the step from the list to a channel in the player.
     */
    private suspend fun <T : Any> whileSeen(block: suspend () -> T): T? = coroutineScope {
        val work = async { block() }
        val watcher = launch {
            while (true) {
                _uiState.subscriptionCount.first { it == 0 }
                val back = withTimeoutOrNull(UNSEEN_GRACE_MS) { _uiState.subscriptionCount.first { it > 0 } }
                if (back == null) {
                    work.cancel()
                    return@launch
                }
            }
        }
        try {
            work.await()
        } catch (cancel: CancellationException) {
            // Only this read was stopped; a cancelled guide job carries on cancelling.
            currentCoroutineContext().ensureActive()
            null
        } finally {
            watcher.cancel()
        }
    }

    /**
     * One guide, downloaded when its saved copy is missing, old or [force]d; an old copy still
     * serves when the download fails. Null when there is no guide to read.
     */
    private suspend fun readGuide(
        download: suspend (File) -> Unit,
        /** The guide's link when it is one to fetch (not a portal's): it is read while it downloads. */
        url: String?,
        file: File,
        request: LiveTvGuideRequest,
        nowMs: Long,
        window: LiveTvGuideWindow,
        force: Boolean,
    ): LiveTvGuide? {
        try {
            val saved = withContext(Dispatchers.IO) { file.lastModified() }
            val cached = if (!force && saved != 0L && nowMs - saved in 0 until EPG_DOWNLOAD_MS) {
                readXmlTvGuide(file, request, nowMs, window)
            } else {
                null
            }
            if (cached?.canReplaceSavedGuide == true) return cached
            // Also repair a recently saved, incomplete feed from an earlier app version.
            if (force || saved == 0L || nowMs - saved !in 0 until EPG_DOWNLOAD_MS || cached != null) {
                if (url != null) {
                    try {
                        val read = LiveTvHttp.downloadReading(
                            url, LIVE_TV_STREAM_HEADERS, file, LiveTvHttp.GUIDE_READ_TIMEOUT_S,
                            read = { input -> readXmlTvGuide(input, request, nowMs, window) },
                            keep = { guide -> guide.canReplaceSavedGuide },
                        )
                        if (read.canReplaceSavedGuide) return read
                        // A cut or malformed response must never replace a usable saved feed.
                        if (saved != 0L) return (cached ?: readXmlTvGuide(file, request, nowMs, window)).afterFailedRefresh()
                        if (read.elements > 0) return read
                        Log.w(TAG, "Guide link gave no guide: ${url.substringBefore('?')}")
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        // The link without its query (which can hold a login), so a log says which guide.
                        Log.w(TAG, "Guide download failed: ${url.substringBefore('?')}", error)
                    }
                    // The guide saved before, if any.
                    if (saved == 0L) return null
                    return (cached ?: readXmlTvGuide(file, request, nowMs, window)).afterFailedRefresh()
                }
                try {
                    download(file)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w(TAG, "Guide download failed", error)
                    if (saved == 0L) return null
                    return (cached ?: readXmlTvGuide(file, request, nowMs, window)).afterFailedRefresh()
                }
            }
            return readXmlTvGuide(file, request, nowMs, window)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Guide failed", error)
            return null
        }
    }

    /** How each source's guide did: read for how many of its channels, or failed when every guide link of it did. */
    private fun guideStates(
        sourceLinks: Map<String, List<String>>,
        failedLinks: Set<String>,
        schedule: LiveTvSchedule,
        channels: List<LiveTvChannel>,
    ): Map<String, LiveTvSourceGuide> {
        val counts = HashMap<String, Int>()
        channels.forEach { if (!schedule[it.guideKey].isNullOrEmpty()) counts[it.sourceId] = (counts[it.sourceId] ?: 0) + 1 }
        return sourceLinks.mapValues { (id, links) ->
            val count = counts[id] ?: 0
            when {
                links.isEmpty() -> LiveTvSourceGuide.None
                count == 0 && links.all(failedLinks::contains) -> LiveTvSourceGuide.Failed
                else -> LiveTvSourceGuide(LiveTvSourceGuide.State.Loaded, count)
            }
        }
    }

    private fun guideDir(): File = File(appContext.cacheDir, "live_tv")

    // endregion
}
