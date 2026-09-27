package com.nuvio.tv.reshaped.livetv

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Live TV's saved source, favorites and last channel, per profile. Small values live in their own
 * preferences file (read once, off the startup path); an imported playlist is a file, because
 * it can be megabytes and preferences keep everything in memory and rewrite it on each save.
 */
internal class LiveTvStorage(context: Context, private val profileId: Int) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val playlistFile = File(File(context.applicationContext.filesDir, "live_tv"), "playlist_$profileId.m3u")

    private fun key(base: String) = "${base}_$profileId"
    private fun string(base: String): String? = prefs.getString(key(base), null)?.takeIf(String::isNotBlank)
    private fun SharedPreferences.Editor.putOrRemove(base: String, value: String?): SharedPreferences.Editor =
        apply { if (value.isNullOrBlank()) remove(key(base)) else putString(key(base), value) }

    fun sourceType(): LiveTvSourceType =
        LiveTvSourceType.entries.firstOrNull { it.name == string(SOURCE_TYPE) } ?: LiveTvSourceType.M3u

    fun sourceUrl(): String = string(SOURCE_URL).orEmpty()

    fun saveM3uSource(url: String) {
        prefs.edit().putOrRemove(SOURCE_TYPE, LiveTvSourceType.M3u.name).putOrRemove(SOURCE_URL, url).apply()
    }

    fun stalkerSettings() = LiveTvStalkerSettings(
        portalUrl = string(STALKER_PORTAL).orEmpty(),
        macAddress = string(STALKER_MAC).orEmpty(),
        username = string(STALKER_USER).orEmpty(),
        password = string(STALKER_PASSWORD).orEmpty(),
    )

    fun saveStalker(settings: LiveTvStalkerSettings) {
        prefs.edit().apply {
            putOrRemove(SOURCE_TYPE, LiveTvSourceType.Stalker.name)
            putOrRemove(SOURCE_URL, settings.portalUrl)
            putOrRemove(STALKER_PORTAL, settings.portalUrl)
            putOrRemove(STALKER_MAC, settings.macAddress)
            putOrRemove(STALKER_USER, settings.username)
            putOrRemove(STALKER_PASSWORD, settings.password)
        }.apply()
    }

    fun xtreamSettings() = LiveTvXtreamSettings(
        serverUrl = string(XTREAM_SERVER).orEmpty(),
        username = string(XTREAM_USER).orEmpty(),
        password = string(XTREAM_PASSWORD).orEmpty(),
    )

    fun saveXtream(settings: LiveTvXtreamSettings) {
        prefs.edit().apply {
            putOrRemove(SOURCE_TYPE, LiveTvSourceType.Xtream.name)
            putOrRemove(SOURCE_URL, settings.serverUrl)
            putOrRemove(XTREAM_SERVER, settings.serverUrl)
            putOrRemove(XTREAM_USER, settings.username)
            putOrRemove(XTREAM_PASSWORD, settings.password)
        }.apply()
    }

    /** Forgets the active source; saved provider logins stay so they can be picked again. */
    fun clearSource() {
        prefs.edit().apply {
            remove(key(SOURCE_TYPE))
            remove(key(SOURCE_URL))
        }.apply()
        playlistFile.delete()
    }

    fun hasPlaylistFile(): Boolean = playlistFile.isFile && playlistFile.length() > 0L

    /** The imported playlist file, or null. */
    fun playlistFile(): File? = playlistFile.takeIf { hasPlaylistFile() }

    /** Saves an imported playlist from [write]; call off the main thread. */
    fun savePlaylistFile(write: (File) -> Unit): File {
        playlistFile.parentFile?.mkdirs()
        val temp = File(playlistFile.path + ".tmp")
        write(temp)
        if (!temp.renameTo(playlistFile)) {
            playlistFile.delete()
            temp.renameTo(playlistFile)
        }
        return playlistFile
    }

    fun deletePlaylistFile() {
        playlistFile.delete()
    }

    fun favoriteUrls(): Set<String> =
        string(FAVORITES)?.lineSequence()?.map(String::trim)?.filter(String::isNotBlank)?.toHashSet().orEmpty()

    fun saveFavoriteUrls(urls: Set<String>) {
        prefs.edit().putOrRemove(FAVORITES, urls.joinToString("\n")).apply()
    }

    fun recentChannel(): LiveTvRecentChannel? {
        val url = string(RECENT_URL) ?: return null
        val name = string(RECENT_NAME) ?: return null
        return LiveTvRecentChannel(
            streamUrl = url,
            name = name,
            logoUrl = string(RECENT_LOGO),
            group = string(RECENT_GROUP).orEmpty(),
            tvgId = string(RECENT_TVG_ID),
        )
    }

    fun saveRecentChannel(channel: LiveTvRecentChannel) {
        prefs.edit().apply {
            putOrRemove(RECENT_URL, channel.streamUrl)
            putOrRemove(RECENT_NAME, channel.name)
            putOrRemove(RECENT_LOGO, channel.logoUrl)
            putOrRemove(RECENT_GROUP, channel.group)
            putOrRemove(RECENT_TVG_ID, channel.tvgId)
        }.apply()
    }

    companion object {
        const val PREFS = "nuvio_live_tv"
        private const val SOURCE_TYPE = "source_type"
        private const val SOURCE_URL = "source_url"
        private const val STALKER_PORTAL = "stalker_portal_url"
        private const val STALKER_MAC = "stalker_mac_address"
        private const val STALKER_USER = "stalker_username"
        private const val STALKER_PASSWORD = "stalker_password"
        private const val XTREAM_SERVER = "xtream_server_url"
        private const val XTREAM_USER = "xtream_username"
        private const val XTREAM_PASSWORD = "xtream_password"
        private const val FAVORITES = "favorite_channel_urls"
        private const val RECENT_URL = "recent_channel_url"
        private const val RECENT_NAME = "recent_channel_name"
        private const val RECENT_LOGO = "recent_channel_logo"
        private const val RECENT_GROUP = "recent_channel_group"
        private const val RECENT_TVG_ID = "recent_channel_tvg_id"
    }
}
