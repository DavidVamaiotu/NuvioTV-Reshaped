package com.nuvio.tv.reshaped.livetv

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.delay
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
    /** Sources loaded at once on a refresh: each holds a connection and a parse buffer. */
    private const val PARALLEL_SOURCES = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(LiveTvUiState())
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

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
    private var loadedProfileId: Int? = null
    private var profileJob: Job? = null
    private var epgJob: Job? = null
    @Volatile private var epgGeneration = 0
    /** The guide programmes kept for each [LiveTvChannel.guideKey], for "up next" and the guide. */
    @Volatile private var keptSchedule: LiveTvSchedule = emptyMap()
    private var epgKey: Pair<List<String>, Set<String>>? = null
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
    private class LoadedSource(val channels: List<LiveTvChannel>, val epgUrls: List<String>)
    private val loaded = HashMap<String, LoadedSource>()

    /**
     * Makes the state match [profileId]: the first call (or a profile switch) reads the saved
     * sources and loads their channels; later calls for the same profile do nothing.
     */
    fun ensureLoaded(context: Context, profileId: Int) {
        if (loadedProfileId == profileId) return
        appContext = context.applicationContext
        loadedProfileId = profileId
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
                hiddenGroups = store.hiddenGroups(),
                hiddenChannelKeys = hiddenChannels,
                recentChannel = store.recentChannel(),
                isLoading = sources.isNotEmpty(),
            )
            sources.forEach { launchSourceLoad(it, adding = false) }
        }
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

    fun loadM3uUrl(url: String) {
        val trimmed = url.trim()
        launchAdd(LiveTvSource("", LiveTvSourceType.M3u, trimmed))
    }

    fun loadXtream(settings: LiveTvXtreamSettings) {
        val normalized = settings.normalized()
        launchAdd(LiveTvSource("", LiveTvSourceType.Xtream, normalized.serverUrl, xtream = normalized))
    }

    fun loadStalker(settings: LiveTvStalkerSettings) {
        val normalized = settings.normalized()
        launchAdd(LiveTvSource("", LiveTvSourceType.Stalker, normalized.portalUrl, stalker = normalized))
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
    }

    /** Back to A to Z. */
    fun resetGroupOrder() {
        groupOrder = emptyList()
        _uiState.update { it.copy(groups = orderedGroups(it.groupCounts.keys)) }
        storage?.let { store -> scope.launch(writer) { store.saveGroupOrder(emptyList()) } }
    }

    /** [names] in the viewer's order, then the ones it does not have yet, A to Z, with "Uncategorised" last. */
    private fun orderedGroups(names: Set<String>): List<String> {
        val ordered = groupOrder.filterTo(ArrayList()) { it in names }
        val placed = ordered.toHashSet()
        names.filterNot(placed::contains)
            .sortedWith(compareBy<String> { it == LIVE_TV_UNGROUPED }.thenBy(String.CASE_INSENSITIVE_ORDER) { it })
            .forEach(ordered::add)
        return ordered
    }

    private fun saveHiddenGroups(groups: Set<String>) {
        _uiState.update { it.copy(hiddenGroups = groups) }
        refreshShownChannels()
        storage?.let { store -> scope.launch(writer) { store.saveHiddenGroups(groups) } }
    }

    fun toggleFavorite(channel: LiveTvChannel) {
        val store = storage ?: return
        val favorites = _uiState.value.favoriteUrls.toHashSet()
        if (!favorites.add(channel.streamUrl)) favorites.remove(channel.streamUrl)
        _uiState.update { it.copy(favoriteUrls = favorites) }
        scope.launch(writer) { store.saveFavoriteUrls(favorites) }
    }

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
                    loaded[source.id] = result
                    if (adding) {
                        val sources = _uiState.value.sources.let { current ->
                            if (known) current.map { if (it.id == source.id) source else it } else current + source
                        }
                        _uiState.update { it.copy(sources = sources, addedCount = it.addedCount + 1, error = notice) }
                        scope.launch(writer) {
                            store.saveSources(sources)
                            if (source.type != LiveTvSourceType.M3u || source.url.isHttpUrl()) store.deletePlaylistFile(source.id)
                        }
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

    private fun validate(source: LiveTvSource): LiveTvError? = when (source.type) {
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
        val (channels, epgUrls) = when (source.type) {
            LiveTvSourceType.M3u -> {
                val file = withContext(Dispatchers.IO) { storage?.playlistFile(source.id) }
                val playlist = when {
                    file != null -> withContext(Dispatchers.IO) { file.bufferedReader().useLines { parseM3uPlaylist(it) } }
                    source.url.isHttpUrl() -> fetchM3u(source.url)
                    else -> throw LiveTvException(if (source.url.startsWith("http", ignoreCase = true)) LiveTvError.InvalidUrl else LiveTvError.FileEmpty)
                }
                if (playlist.channels.isEmpty()) {
                    throw LiveTvException(if (file != null) LiveTvError.FileNoChannels else LiveTvError.NoChannels)
                }
                playlist.channels to playlist.epgUrls
            }
            LiveTvSourceType.Xtream -> {
                val settings = source.xtream
                val channels = LiveTvXtream.channels(settings)
                if (channels.isEmpty()) throw LiveTvException(LiveTvError.XtreamNoChannels)
                // Xtream providers publish their guide at xmltv.php.
                channels to listOf("${settings.serverUrl}/xmltv.php?username=${settings.username.urlEncoded()}&password=${settings.password.urlEncoded()}")
            }
            LiveTvSourceType.Stalker -> {
                val (channels, incomplete) = LiveTvStalker.channels(source.stalker)
                if (channels.isEmpty()) throw LiveTvException(LiveTvError.StalkerNoChannels)
                if (incomplete) notice = LiveTvError.StalkerIncomplete
                channels to emptyList()
            }
        }
        // Ids only need to be unique within a source; the list keys on them across all of them.
        val tagged = ArrayList<LiveTvChannel>(channels.size)
        channels.forEach { channel ->
            val group = channel.group.trim()
            tagged += channel.copy(
                id = "${source.id}/${channel.id}",
                sourceId = source.id,
                group = group,
                hideKey = liveTvHideKey(source.id, group, channel.name),
                guideKey = liveTvGuideKey(channel.tvgId, channel.name),
            )
        }
        return LoadedSource(tagged, epgUrls) to notice
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
        val groups = orderedGroups(groupCounts.keys)
        val epgUrls = parts.flatMap { it.epgUrls }.distinct()
        val guideKeys = channels.mapTo(HashSet(channels.size * 2)) { it.guideKey }
        val hasGuide = epgUrls.isNotEmpty() && guideKeys.isNotEmpty()
        val guideChanged = epgKey != (epgUrls to guideKeys) || epgJob?.isActive != true
        _uiState.update {
            it.copy(
                channels = channels,
                shownChannels = shownChannels(channels, it.hiddenGroups, it.hiddenChannelKeys),
                groups = groups,
                groupCounts = groupCounts,
                sourceCounts = sourceCounts,
                currentProgrammes = if (hasGuide && !guideChanged) it.currentProgrammes else emptyMap(),
                guideLogos = if (hasGuide) it.guideLogos else emptyMap(),
                isEpgLoading = hasGuide && (guideChanged || it.isEpgLoading),
                isLoaded = true,
            )
        }
        if (!hasGuide) {
            stopEpg()
            epgKey = null
        } else if (guideChanged) {
            epgKey = epgUrls to guideKeys
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
        stopEpg()
        val generation = epgGeneration
        val guideFiles = epgUrls.map { File(guideDir(), "guide_${Integer.toHexString(it.hashCode())}.xml.gz") }
        val window = if (LiveTvDevice.isLowMemory(appContext)) LiveTvGuideWindow.LowMemory else LiveTvGuideWindow.Regular
        guideWindow = window
        epgJob = scope.launch {
            withContext(Dispatchers.IO) {
                // Guides of an earlier source.
                guideDir().listFiles()?.filter { it !in guideFiles }?.forEach(File::delete)
            }
            val request = LiveTvGuideRequest.from(channels)
            var schedule: LiveTvSchedule = emptyMap()
            var nextReadAtMs = 0L
            while (isActive) {
                _uiState.subscriptionCount.first { it > 0 }
                val nowMs = LiveTvClock.nowEpochMs()
                if (nowMs >= nextReadAtMs) {
                    val force = forceGuideDownload
                    val loaded = HashMap<String, List<LiveTvProgramme>>()
                    val logos = HashMap<String, String>()
                    val truncated = HashSet<String>()
                    var failed = false
                    epgUrls.forEachIndexed { index, epgUrl ->
                        val guide = readGuide(epgUrl, guideFiles[index], request, nowMs, window, force)
                        if (guide == null) {
                            failed = true
                            return@forEachIndexed
                        }
                        guide.schedule.forEach { (key, list) ->
                            if (loaded.putIfAbsent(key, list) == null && key in guide.truncated) truncated += key
                        }
                        guide.logos.forEach(logos::putIfAbsent)
                    }
                    if (force && epgGeneration == generation) forceGuideDownload = false
                    schedule = loaded
                    if (epgGeneration == generation) keptSchedule = loaded
                    val regular = if (loaded.isEmpty()) {
                        nowMs + EPG_RETRY_MS
                    } else {
                        nextScheduleReadAt(loaded, truncated, nowMs, EPG_MIN_READ_GAP_MS, EPG_DOWNLOAD_MS)
                    }
                    nextReadAtMs = if (failed) minOf(regular, nowMs + EPG_RETRY_MS) else regular
                    _uiState.update { state ->
                        if (epgGeneration != generation) {
                            state
                        } else {
                            state.copy(
                                guideLogos = if (state.guideLogos == logos) state.guideLogos else logos,
                                guideVersion = state.guideVersion + 1,
                            )
                        }
                    }
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
     * One guide, downloaded when its saved copy is missing, old or [force]d; an old copy still
     * serves when the download fails. Null when there is no guide to read.
     */
    private suspend fun readGuide(
        url: String,
        file: File,
        request: LiveTvGuideRequest,
        nowMs: Long,
        window: LiveTvGuideWindow,
        force: Boolean,
    ): LiveTvGuide? {
        try {
            val saved = withContext(Dispatchers.IO) { file.lastModified() }
            if (force || saved == 0L || nowMs - saved !in 0 until EPG_DOWNLOAD_MS) {
                try {
                    LiveTvHttp.download(url, LIVE_TV_STREAM_HEADERS, file, LiveTvHttp.GUIDE_READ_TIMEOUT_S)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w(TAG, "Guide download failed", error)
                    if (saved == 0L) return null
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

    private fun guideDir(): File = File(appContext.cacheDir, "live_tv")

    // endregion
}
