package com.nuvio.tv.reshaped.livetv

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    private lateinit var appContext: Context
    private var storage: LiveTvStorage? = null
    private var loadedProfileId: Int? = null
    private var loadJob: Job? = null
    private var epgJob: Job? = null
    @Volatile private var epgGeneration = 0
    private var epgKey: Pair<List<String>, Set<String>>? = null
    /** The viewer's category order; empty for A to Z. */
    @Volatile private var groupOrder: List<String> = emptyList()

    /** What each source loaded last. Only touched from [publish] and the loads, which run one at a time. */
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
        loadJob?.cancel()
        stopEpg()
        LiveTvStalker.clearSession()
        _uiState.value = LiveTvUiState(isLoading = true)
        loadJob = scope.launch {
            val store = withContext(Dispatchers.IO) {
                LiveTvStorage(appContext, profileId).also { it.favoriteUrls() } // first read parses the file
            }
            val sources = withContext(Dispatchers.IO) { store.sources() }
            groupOrder = withContext(Dispatchers.IO) { store.groupOrder() }
            storage = store
            synchronized(loaded) { loaded.clear() }
            _uiState.value = LiveTvUiState(
                sources = sources,
                favoriteUrls = store.favoriteUrls(),
                hiddenGroups = store.hiddenGroups(),
                hiddenChannelUrls = store.hiddenChannelUrls(),
                recentChannel = store.recentChannel(),
            )
            reloadAll()
        }
    }

    /** Loads every saved source again (the Refresh button). */
    fun refresh() {
        if (storage == null) return
        launchLoad { reloadAll() }
    }

    private suspend fun reloadAll() {
        val sources = _uiState.value.sources
        if (sources.isEmpty()) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        startLoading()
        val permits = Semaphore(PARALLEL_SOURCES)
        val results = coroutineScope {
            sources.map { source ->
                async {
                    permits.withPermit {
                        source.id to try {
                            Result.success(loadSource(source))
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            Log.w(TAG, "Live TV source ${source.type} failed", error)
                            Result.failure(error)
                        }
                    }
                }
            }.awaitAll()
        }
        val errors = HashMap<String, LiveTvError>()
        synchronized(loaded) {
            results.forEach { (id, result) ->
                result.onSuccess { loaded[id] = it.first }
                val error = result.exceptionOrNull()?.let { (it as? LiveTvException)?.error ?: fallbackError(sources, id) }
                    ?: result.getOrNull()?.second
                if (error != null) errors[id] = error
            }
        }
        publish(errors)
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

    /** Removes one source and its channels. */
    fun removeSource(sourceId: String) {
        val store = storage ?: return
        val sources = _uiState.value.sources.filterNot { it.id == sourceId }
        if (sources.size == _uiState.value.sources.size) return
        loadJob?.cancel()
        LiveTvStalker.clearSession()
        synchronized(loaded) { loaded.remove(sourceId) }
        _uiState.update { it.copy(sources = sources, isLoading = false, error = null) }
        scope.launch(Dispatchers.IO) {
            store.saveSources(sources)
            store.deletePlaylistFile(sourceId)
        }
        publish(_uiState.value.sourceErrors - sourceId)
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
    fun setChannelsHidden(streamUrls: Collection<String>, hidden: Boolean) {
        val current = _uiState.value.hiddenChannelUrls
        val next = if (hidden) current + streamUrls else current - streamUrls.toSet()
        if (next.size == current.size) return
        _uiState.update { it.copy(hiddenChannelUrls = next) }
        storage?.let { store -> scope.launch(Dispatchers.IO) { store.saveHiddenChannelUrls(next) } }
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
        storage?.let { store -> scope.launch(Dispatchers.IO) { store.saveGroupOrder(reordered) } }
    }

    /** Back to A to Z. */
    fun resetGroupOrder() {
        groupOrder = emptyList()
        _uiState.update { it.copy(groups = orderedGroups(it.groupCounts.keys)) }
        storage?.let { store -> scope.launch(Dispatchers.IO) { store.saveGroupOrder(emptyList()) } }
    }

    /** [names] in the viewer's order, then the ones it does not have yet, A to Z. */
    private fun orderedGroups(names: Set<String>): List<String> {
        val ordered = groupOrder.filterTo(ArrayList()) { it in names }
        val placed = ordered.toHashSet()
        names.filterNot(placed::contains).sortedWith(String.CASE_INSENSITIVE_ORDER).forEach(ordered::add)
        return ordered
    }

    private fun saveHiddenGroups(groups: Set<String>) {
        _uiState.update { it.copy(hiddenGroups = groups) }
        storage?.let { store -> scope.launch(Dispatchers.IO) { store.saveHiddenGroups(groups) } }
    }

    fun toggleFavorite(channel: LiveTvChannel) {
        val store = storage ?: return
        val favorites = _uiState.value.favoriteUrls.toHashSet()
        if (!favorites.add(channel.streamUrl)) favorites.remove(channel.streamUrl)
        _uiState.update { it.copy(favoriteUrls = favorites) }
        store.saveFavoriteUrls(favorites)
    }

    /** [channel] is the list's own entry (not a resolved Stalker link), so it can be found again. */
    fun recordRecentChannel(channel: LiveTvChannel) {
        val recent = LiveTvRecentChannel(channel.streamUrl, channel.name, channel.logoUrl, channel.group, channel.tvgId)
        if (_uiState.value.recentChannel == recent) return
        _uiState.update { it.copy(recentChannel = recent) }
        storage?.saveRecentChannel(recent)
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
            )

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

    private fun launchLoad(block: suspend () -> Unit) {
        loadJob?.cancel()
        loadJob = scope.launch { block() }
    }

    // region Loads

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
            _uiState.update { it.copy(error = validation, isLoading = false) }
            return
        }
        val candidate = if (input.id.isBlank()) withExistingId(input, store) else input
        val isNew = _uiState.value.sources.none { it.id == candidate.id }
        launchLoad {
            startLoading()
            try {
                val (result, notice) = loadSource(candidate)
                synchronized(loaded) { loaded[candidate.id] = result }
                val sources = _uiState.value.sources.let { current ->
                    if (isNew) current + candidate else current.map { if (it.id == candidate.id) candidate else it }
                }
                _uiState.update { it.copy(sources = sources) }
                withContext(Dispatchers.IO) {
                    store.saveSources(sources)
                    if (candidate.type != LiveTvSourceType.M3u || candidate.url.isHttpUrl()) store.deletePlaylistFile(candidate.id)
                }
                val errors = _uiState.value.sourceErrors - candidate.id
                publish(if (notice != null) errors + (candidate.id to notice) else errors)
                if (notice != null) _uiState.update { it.copy(error = notice) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Live TV source could not be added", error)
                if (isNew && candidate.type == LiveTvSourceType.M3u && !candidate.url.isHttpUrl()) {
                    withContext(Dispatchers.IO) { store.deletePlaylistFile(candidate.id) }
                }
                fail((error as? LiveTvException)?.error ?: fallbackError(candidate.type))
            }
        }
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

    private fun fallbackError(sources: List<LiveTvSource>, id: String): LiveTvError =
        fallbackError(sources.firstOrNull { it.id == id }?.type ?: LiveTvSourceType.M3u)

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
        channels.forEach { tagged += it.copy(id = "${source.id}/${it.id}", sourceId = source.id) }
        return LoadedSource(tagged, epgUrls) to notice
    }

    private suspend fun fetchM3u(url: String): ParsedM3uPlaylist {
        if (url.looksLikeDirectVideoUrl()) return ParsedM3uPlaylist(listOf(directStreamChannel(url)), emptyList())
        val parsed = LiveTvHttp.stream(url, LIVE_TV_PLAYLIST_HEADERS) { input ->
            parseM3uPlaylist(input.bufferedReader().lineSequence())
        }
        return if (parsed.isHlsStream) ParsedM3uPlaylist(listOf(directStreamChannel(url)), emptyList()) else parsed
    }

    /** The shown channels change only once a load finished, so a failed attempt keeps the list. */
    private fun startLoading() {
        _uiState.update { it.copy(isLoading = true, error = null) }
    }

    private fun fail(error: LiveTvError) {
        _uiState.update { it.copy(isLoading = false, isLoaded = it.channels.isNotEmpty() || it.sources.isNotEmpty(), error = error) }
    }

    /** Shows the channels of every saved source as one list, and (re)starts the guide when its inputs changed. */
    private fun publish(errors: Map<String, LiveTvError>) {
        val sources = _uiState.value.sources
        val parts = synchronized(loaded) { sources.mapNotNull { loaded[it.id] } }
        val channels = ArrayList<LiveTvChannel>(parts.sumOf { it.channels.size })
        parts.forEach { channels.addAll(it.channels) }
        val groupCounts = HashMap<String, Int>()
        val sourceCounts = HashMap<String, Int>()
        channels.forEach { channel ->
            if (channel.group.isNotBlank()) groupCounts[channel.group] = (groupCounts[channel.group] ?: 0) + 1
            sourceCounts[channel.sourceId] = (sourceCounts[channel.sourceId] ?: 0) + 1
        }
        val groups = orderedGroups(groupCounts.keys)
        val epgUrls = parts.flatMap { it.epgUrls }.distinct()
        val tvgIds = channels.mapNotNullTo(LinkedHashSet()) { it.tvgId?.takeIf(String::isNotBlank) }
        val hasGuide = epgUrls.isNotEmpty() && tvgIds.isNotEmpty()
        val guideChanged = epgKey != (epgUrls to tvgIds) || epgJob?.isActive != true
        _uiState.update {
            it.copy(
                channels = channels,
                groups = groups,
                groupCounts = groupCounts,
                sourceCounts = sourceCounts,
                sourceErrors = errors,
                currentProgrammes = if (hasGuide && !guideChanged) it.currentProgrammes else emptyMap(),
                isEpgLoading = hasGuide && (guideChanged || it.isEpgLoading),
                isLoading = false,
                isLoaded = true,
                error = null,
            )
        }
        if (!hasGuide) {
            stopEpg()
            epgKey = null
        } else if (guideChanged) {
            epgKey = epgUrls to tvgIds
            startEpg(epgUrls, tvgIds)
        }
    }

    // endregion

    // region Guide

    private fun stopEpg() {
        epgGeneration++
        epgJob?.cancel()
        epgJob = null
    }

    /**
     * Reads the guide and moves each channel's "now playing" on every minute. Only runs while
     * something shows Live TV (the list or a channel in the player) and catches up when it is
     * opened again. The guide is saved compressed in the cache, downloaded again every 10 hours,
     * and re-read from there whenever channels run out of kept programmes.
     */
    private fun startEpg(epgUrls: List<String>, tvgIds: Set<String>) {
        stopEpg()
        val generation = epgGeneration
        val channelIds = tvgIds.mapTo(HashSet()) { it.lowercase() }
        val guideFiles = epgUrls.map { File(guideDir(), "guide_${Integer.toHexString(it.hashCode())}.xml.gz") }
        epgJob = scope.launch {
            withContext(Dispatchers.IO) {
                // Guides of an earlier source.
                guideDir().listFiles()?.filter { it !in guideFiles }?.forEach(File::delete)
            }
            var schedule: LiveTvSchedule = emptyMap()
            var nextReadAtMs = 0L
            while (isActive) {
                _uiState.subscriptionCount.first { it > 0 }
                val nowMs = LiveTvClock.nowEpochMs()
                if (nowMs >= nextReadAtMs) {
                    val loaded = HashMap<String, List<LiveTvProgramme>>()
                    epgUrls.forEachIndexed { index, epgUrl ->
                        readGuide(epgUrl, guideFiles[index], channelIds, nowMs)
                            .forEach { (id, list) -> loaded.putIfAbsent(id, list) }
                    }
                    schedule = loaded
                    nextReadAtMs = if (loaded.isEmpty()) {
                        nowMs + EPG_RETRY_MS
                    } else {
                        nextScheduleReadAt(loaded, nowMs, EPG_MIN_READ_GAP_MS, EPG_DOWNLOAD_MS)
                    }
                }
                val current = currentProgrammes(schedule, tvgIds, nowMs)
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

    /** One guide, downloaded when its saved copy is missing or old; an old copy still serves when the download fails. */
    private suspend fun readGuide(url: String, file: File, channelIds: Set<String>, nowMs: Long): LiveTvSchedule {
        try {
            val saved = withContext(Dispatchers.IO) { file.lastModified() }
            if (saved == 0L || nowMs - saved !in 0 until EPG_DOWNLOAD_MS) {
                try {
                    LiveTvHttp.download(url, LIVE_TV_STREAM_HEADERS, file)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w(TAG, "Guide download failed", error)
                    if (saved == 0L) return emptyMap()
                }
            }
            return readXmlTvSchedule(file, channelIds, nowMs)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Guide failed", error)
            return emptyMap()
        }
    }

    private fun guideDir(): File = File(appContext.cacheDir, "live_tv")

    // endregion
}
