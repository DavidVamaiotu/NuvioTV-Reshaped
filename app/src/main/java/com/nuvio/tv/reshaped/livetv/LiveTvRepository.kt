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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live TV's channel list, guide, favorites and last channel for the active profile. It lives for
 * the app's process, so coming back from the player shows the list as it was, without a reload.
 * Loads run in its own scope: leaving the screen does not cancel them.
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

    /**
     * Makes the state match [profileId]: the first call (or a profile switch) reads the saved
     * source and loads its channels; later calls for the same profile do nothing.
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
            storage = store
            _uiState.value = LiveTvUiState(
                sourceType = store.sourceType(),
                sourceUrl = store.sourceUrl(),
                stalkerSettings = store.stalkerSettings(),
                xtreamSettings = store.xtreamSettings(),
                favoriteUrls = store.favoriteUrls(),
                recentChannel = store.recentChannel(),
            )
            reloadSaved(store)
        }
    }

    /** Loads the saved source again (the Refresh button). */
    fun refresh() {
        val store = storage ?: return
        launchLoad { reloadSaved(store) }
    }

    private suspend fun reloadSaved(store: LiveTvStorage) {
        val state = _uiState.value
        when {
            state.sourceType == LiveTvSourceType.Xtream && state.xtreamSettings.isConfigured ->
                loadXtreamNow(state.xtreamSettings)
            state.sourceType == LiveTvSourceType.Stalker && state.stalkerSettings.isConfigured ->
                loadStalkerNow(state.stalkerSettings)
            state.sourceType == LiveTvSourceType.M3u && store.hasPlaylistFile() ->
                loadPlaylistFileNow(state.sourceUrl)
            state.sourceType == LiveTvSourceType.M3u && state.sourceUrl.isHttpUrl() ->
                loadM3uUrlNow(state.sourceUrl)
            else -> _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun loadM3uUrl(url: String) = launchLoad { loadM3uUrlNow(url.trim()) }

    fun loadXtream(settings: LiveTvXtreamSettings) = launchLoad { loadXtreamNow(settings.normalized()) }

    fun loadStalker(settings: LiveTvStalkerSettings) = launchLoad { loadStalkerNow(settings.normalized()) }

    /**
     * Saves a playlist sent from a phone, then shows it. Blocking (the upload server's thread):
     * [input] is copied to a file first so it is never held in memory as a whole.
     */
    fun importPlaylist(fileName: String, input: InputStream, maxBytes: Long): Boolean {
        val store = storage ?: return false
        val saved = runCatching {
            store.savePlaylistFile { temp ->
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
        launchLoad { loadPlaylistFileNow(fileName.trim().ifBlank { "M3U playlist" }) }
        return true
    }

    /** Removes the active source and its channels; saved provider logins stay. */
    fun disconnect() {
        val store = storage ?: return
        loadJob?.cancel()
        stopEpg()
        LiveTvStalker.clearSession()
        scope.launch(Dispatchers.IO) {
            store.clearSource()
            guideDir().deleteRecursively()
        }
        _uiState.update {
            LiveTvUiState(
                stalkerSettings = it.stalkerSettings,
                xtreamSettings = it.xtreamSettings,
                favoriteUrls = it.favoriteUrls,
                recentChannel = it.recentChannel,
            )
        }
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

    /**
     * The channel as the player should open it (Stalker links are created per play), registered
     * so the player treats it as Live TV, and remembered as the last channel.
     */
    suspend fun prepareForPlayback(channel: LiveTvChannel): LiveTvChannel {
        val state = _uiState.value
        val playback = if (state.sourceType == LiveTvSourceType.Stalker && channel.stalkerCommand != null) {
            try {
                LiveTvStalker.resolve(state.stalkerSettings, channel)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Stalker link failed, trying the listed link", error)
                channel
            }
        } else {
            channel
        }
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

    private suspend fun loadM3uUrlNow(url: String) {
        if (!url.isHttpUrl()) return fail(LiveTvError.InvalidUrl)
        startLoading()
        runLoad(LiveTvError.LoadFailed) {
            val playlist = if (url.looksLikeDirectVideoUrl()) {
                ParsedM3uPlaylist(listOf(directStreamChannel(url)), emptyList())
            } else {
                LiveTvHttp.stream(url, LIVE_TV_PLAYLIST_HEADERS) { input ->
                    parseM3uPlaylist(input.bufferedReader().lineSequence())
                }.let { parsed ->
                    if (parsed.isHlsStream) ParsedM3uPlaylist(listOf(directStreamChannel(url)), emptyList()) else parsed
                }
            }
            if (playlist.channels.isEmpty()) throw LiveTvException(LiveTvError.NoChannels)
            storage?.let { store ->
                withContext(Dispatchers.IO) {
                    store.deletePlaylistFile()
                    store.saveM3uSource(url)
                }
            }
            showChannels(LiveTvSourceType.M3u, url, playlist.channels, playlist.epgUrls)
        }
    }

    private suspend fun loadPlaylistFileNow(displayName: String) {
        val store = storage ?: return
        startLoading()
        runLoad(LiveTvError.FileNoChannels) {
            val file: File = store.playlistFile() ?: throw LiveTvException(LiveTvError.FileEmpty)
            val playlist = withContext(Dispatchers.IO) {
                file.bufferedReader().useLines { parseM3uPlaylist(it) }
            }
            if (playlist.channels.isEmpty()) {
                withContext(Dispatchers.IO) { store.deletePlaylistFile() }
                throw LiveTvException(LiveTvError.FileNoChannels)
            }
            withContext(Dispatchers.IO) { store.saveM3uSource(displayName) }
            showChannels(LiveTvSourceType.M3u, displayName, playlist.channels, playlist.epgUrls)
        }
    }

    private suspend fun loadXtreamNow(settings: LiveTvXtreamSettings) {
        if (!settings.isConfigured) return fail(LiveTvError.XtreamRequired)
        if (!settings.serverUrl.isHttpUrl()) return fail(LiveTvError.XtreamInvalidUrl)
        _uiState.update { it.copy(xtreamSettings = settings) }
        startLoading()
        runLoad(LiveTvError.XtreamFailed) {
            val channels = LiveTvXtream.channels(settings)
            if (channels.isEmpty()) throw LiveTvException(LiveTvError.XtreamNoChannels)
            storage?.let { store ->
                withContext(Dispatchers.IO) {
                    store.deletePlaylistFile()
                    store.saveXtream(settings)
                }
            }
            // Xtream providers publish their guide at xmltv.php.
            val epg = "${settings.serverUrl}/xmltv.php?username=${settings.username.urlEncoded()}&password=${settings.password.urlEncoded()}"
            showChannels(LiveTvSourceType.Xtream, settings.serverUrl, channels, listOf(epg))
        }
    }

    private suspend fun loadStalkerNow(settings: LiveTvStalkerSettings) {
        if (!settings.isConfigured) return fail(LiveTvError.StalkerRequired)
        if (!settings.portalUrl.isHttpUrl()) return fail(LiveTvError.StalkerInvalidUrl)
        _uiState.update { it.copy(stalkerSettings = settings) }
        startLoading()
        runLoad(LiveTvError.StalkerFailed) {
            val (channels, incomplete) = LiveTvStalker.channels(settings)
            if (channels.isEmpty()) throw LiveTvException(LiveTvError.StalkerNoChannels)
            storage?.let { store ->
                withContext(Dispatchers.IO) {
                    store.deletePlaylistFile()
                    store.saveStalker(settings)
                }
            }
            showChannels(LiveTvSourceType.Stalker, settings.portalUrl, channels, emptyList())
            if (incomplete) _uiState.update { it.copy(error = LiveTvError.StalkerIncomplete) }
        }
    }

    /** The shown source changes only once the new one loaded, so a failed attempt keeps the list. */
    private fun startLoading() {
        _uiState.update { it.copy(isLoading = true, error = null) }
    }

    private suspend fun runLoad(fallback: LiveTvError, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w(TAG, "Live TV load failed", error)
            fail((error as? LiveTvException)?.error ?: fallback)
        }
    }

    private fun fail(error: LiveTvError) {
        _uiState.update { it.copy(isLoading = false, isLoaded = it.channels.isNotEmpty(), error = error) }
    }

    private fun showChannels(
        type: LiveTvSourceType,
        sourceUrl: String,
        channels: List<LiveTvChannel>,
        epgUrls: List<String>,
    ) {
        val groups = channels.mapNotNullTo(HashSet()) { it.group.takeIf(String::isNotBlank) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        val hasGuide = epgUrls.isNotEmpty() && channels.any { !it.tvgId.isNullOrBlank() }
        _uiState.update {
            it.copy(
                sourceType = type,
                sourceUrl = sourceUrl,
                channels = channels,
                groups = groups,
                currentProgrammes = emptyMap(),
                isEpgLoading = hasGuide,
                isLoading = false,
                isLoaded = true,
                error = null,
            )
        }
        if (hasGuide) startEpg(sourceUrl, epgUrls, channels)
    }

    // endregion

    // region Guide

    private fun stopEpg() {
        epgJob?.cancel()
        epgJob = null
    }

    /**
     * Reads the guide and moves each channel's "now playing" on every minute. Only runs while
     * something shows Live TV (the list or a channel in the player) and catches up when it is
     * opened again. The guide is saved compressed in the cache, downloaded again every 10 hours,
     * and re-read from there whenever channels run out of kept programmes.
     */
    private fun startEpg(sourceUrl: String, epgUrls: List<String>, channels: List<LiveTvChannel>) {
        stopEpg()
        val tvgIds = channels.mapNotNullTo(LinkedHashSet()) { it.tvgId?.takeIf(String::isNotBlank) }
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
                        state.sourceUrl != sourceUrl -> state
                        state.currentProgrammes != current || state.isEpgLoading ->
                            state.copy(currentProgrammes = current, isEpgLoading = false)
                        else -> state
                    }
                }
                if (_uiState.value.sourceUrl != sourceUrl) return@launch
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
