package com.nuvio.tv.reshaped.livetv

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live TV's saved sources, favorites, hidden categories and last channel, per profile. Small
 * values live in their own preferences file (read once, off the startup path); an imported
 * playlist is a file per source, because it can be megabytes and preferences keep everything in
 * memory and rewrite it on each save.
 */
internal class LiveTvStorage(context: Context, private val profileId: Int) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val playlistDir = File(context.applicationContext.filesDir, "live_tv")

    private fun key(base: String) = "${base}_$profileId"
    private fun string(base: String): String? = prefs.getString(key(base), null)?.takeIf(String::isNotBlank)
    private fun SharedPreferences.Editor.putOrRemove(base: String, value: String?): SharedPreferences.Editor =
        apply { if (value.isNullOrBlank()) remove(key(base)) else putString(key(base), value) }

    // region Sources

    /** The saved sources, in the order they were added. A single source saved by an older version is moved over. */
    fun sources(): List<LiveTvSource> {
        val saved = string(SOURCES) ?: return migrateLegacySource()
        return runCatching {
            val array = JSONArray(saved)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toSource() }
        }.getOrDefault(emptyList())
    }

    fun saveSources(sources: List<LiveTvSource>) {
        val array = JSONArray()
        sources.forEach { array.put(it.toJson()) }
        // An empty list is saved too, so an older single source is not moved over again.
        prefs.edit().putString(key(SOURCES), array.toString()).apply()
    }

    fun newSourceId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun JSONObject.toSource(): LiveTvSource? {
        val id = optString("id").takeIf(String::isNotBlank) ?: return null
        val type = LiveTvSourceType.entries.firstOrNull { it.name == optString("type") } ?: return null
        return LiveTvSource(
            id = id,
            type = type,
            url = optString("url"),
            stalker = LiveTvStalkerSettings(optString("portal"), optString("mac"), optString("stalkerUser"), optString("stalkerPassword")),
            xtream = LiveTvXtreamSettings(optString("server"), optString("xtreamUser"), optString("xtreamPassword")),
        )
    }

    private fun LiveTvSource.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("url", url)
        when (type) {
            LiveTvSourceType.M3u -> Unit
            LiveTvSourceType.Stalker -> {
                put("portal", stalker.portalUrl)
                put("mac", stalker.macAddress)
                put("stalkerUser", stalker.username)
                put("stalkerPassword", stalker.password)
            }
            LiveTvSourceType.Xtream -> {
                put("server", xtream.serverUrl)
                put("xtreamUser", xtream.username)
                put("xtreamPassword", xtream.password)
            }
        }
    }

    /** Versions before multiple sources kept one active source in separate keys. */
    private fun migrateLegacySource(): List<LiveTvSource> {
        val type = LiveTvSourceType.entries.firstOrNull { it.name == string(LEGACY_SOURCE_TYPE) }
        val url = string(LEGACY_SOURCE_URL).orEmpty()
        val id = "main"
        val source = when (type) {
            LiveTvSourceType.M3u -> {
                val legacyFile = File(playlistDir, "playlist_$profileId.m3u")
                if (legacyFile.isFile) legacyFile.renameTo(playlistFileFor(id))
                LiveTvSource(id, LiveTvSourceType.M3u, url).takeIf { url.isNotBlank() }
            }
            LiveTvSourceType.Xtream -> LiveTvSource(
                id, LiveTvSourceType.Xtream, url,
                xtream = LiveTvXtreamSettings(string(XTREAM_SERVER).orEmpty(), string(XTREAM_USER).orEmpty(), string(XTREAM_PASSWORD).orEmpty()),
            ).takeIf { it.xtream.isConfigured }
            LiveTvSourceType.Stalker -> LiveTvSource(
                id, LiveTvSourceType.Stalker, url,
                stalker = LiveTvStalkerSettings(string(STALKER_PORTAL).orEmpty(), string(STALKER_MAC).orEmpty(), string(STALKER_USER).orEmpty(), string(STALKER_PASSWORD).orEmpty()),
            ).takeIf { it.stalker.isConfigured }
            null -> null
        }
        val sources = listOfNotNull(source)
        saveSources(sources)
        prefs.edit().apply {
            listOf(
                LEGACY_SOURCE_TYPE, LEGACY_SOURCE_URL, STALKER_PORTAL, STALKER_MAC, STALKER_USER, STALKER_PASSWORD,
                XTREAM_SERVER, XTREAM_USER, XTREAM_PASSWORD,
            ).forEach { remove(key(it)) }
        }.apply()
        return sources
    }

    // endregion

    // region Imported playlists

    private fun playlistFileFor(sourceId: String) = File(playlistDir, "playlist_${profileId}_$sourceId.m3u")

    /** The imported playlist of [sourceId], or null. */
    fun playlistFile(sourceId: String): File? = playlistFileFor(sourceId).takeIf { it.isFile && it.length() > 0L }

    /** Saves an imported playlist for [sourceId] from [write]; call off the main thread. */
    fun savePlaylistFile(sourceId: String, write: (File) -> Unit): File {
        val target = playlistFileFor(sourceId)
        playlistDir.mkdirs()
        val temp = File(target.path + ".tmp")
        write(temp)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
        return target
    }

    fun deletePlaylistFile(sourceId: String) {
        playlistFileFor(sourceId).delete()
    }

    // endregion

    fun hiddenGroups(): Set<String> =
        string(HIDDEN_GROUPS)?.lineSequence()?.filter(String::isNotEmpty)?.toHashSet().orEmpty()

    fun saveHiddenGroups(groups: Set<String>) {
        prefs.edit().putOrRemove(HIDDEN_GROUPS, groups.joinToString("\n")).apply()
    }

    fun hiddenChannelUrls(): Set<String> =
        string(HIDDEN_CHANNELS)?.lineSequence()?.filter(String::isNotEmpty)?.toHashSet().orEmpty()

    fun saveHiddenChannelUrls(urls: Set<String>) {
        prefs.edit().putOrRemove(HIDDEN_CHANNELS, urls.joinToString("\n")).apply()
    }

    /** Categories in the order the viewer put them; ones not in it follow, A to Z. */
    fun groupOrder(): List<String> =
        string(GROUP_ORDER)?.lineSequence()?.filter(String::isNotEmpty)?.toList().orEmpty()

    fun saveGroupOrder(groups: List<String>) {
        prefs.edit().putOrRemove(GROUP_ORDER, groups.joinToString("\n")).apply()
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
        private const val SOURCES = "sources"
        private const val HIDDEN_GROUPS = "hidden_groups"
        private const val GROUP_ORDER = "group_order"
        private const val HIDDEN_CHANNELS = "hidden_channel_urls"
        private const val LEGACY_SOURCE_TYPE = "source_type"
        private const val LEGACY_SOURCE_URL = "source_url"
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
