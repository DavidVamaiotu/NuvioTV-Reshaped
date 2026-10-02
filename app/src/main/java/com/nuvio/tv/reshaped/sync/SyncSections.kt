package com.nuvio.tv.reshaped.sync

import com.nuvio.tv.reshaped.livetv.LiveTvCustomList
import com.nuvio.tv.reshaped.livetv.LiveTvRecentChannel
import com.nuvio.tv.reshaped.livetv.LiveTvSource
import com.nuvio.tv.reshaped.livetv.LiveTvSourceType
import com.nuvio.tv.reshaped.livetv.LiveTvStalkerSettings
import com.nuvio.tv.reshaped.livetv.LiveTvSyncData
import com.nuvio.tv.reshaped.livetv.LiveTvXtreamSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * How Live TV data is laid out in the synced file, one section per kind so each item merges on
 * its own. The phone app writes the same sections.
 */
internal object LiveTvSections {
    private val TRUE = JsonPrimitive(true)

    fun prefix(profileId: Int) = "live_tv/$profileId/"
    private fun sources(p: Int) = prefix(p) + "sources"
    private fun favorites(p: Int) = prefix(p) + "favorites"
    // TV only: the phone app leaves sections it does not write as they are.
    private fun customLists(p: Int) = prefix(p) + "custom_lists"
    // TV only (imported playlists); TV versions before it and the phone app never read it.
    private fun imported(p: Int) = prefix(p) + "imported"
    private fun hiddenGroups(p: Int) = prefix(p) + "hidden_groups"
    private fun hiddenChannels(p: Int) = prefix(p) + "hidden_channels"
    private fun groupNames(p: Int) = prefix(p) + "group_names"
    private fun groupOrder(p: Int) = prefix(p) + "group_order"
    // TV only: each source's own category order, by source identity.
    private fun sourceGroupOrder(p: Int) = prefix(p) + "source_group_order"
    private fun recent(p: Int) = prefix(p) + "recent"

    /**
     * [data] as sections. A source keeps the id the file already gives it ([base]), so devices
     * that each gave the same source their own id do not keep replacing each other's.
     *
     * A source is deleted for every device only when the viewer removed it here ([removed], by
     * identity): one this device merely lacks (not loaded yet, an older version that could not
     * read it) stays in the file, and sync brings it back here. Nothing is sent for Live TV when
     * this device has no sources at all while the file has some and none was removed here: it
     * has not got them yet, and its empty lists must never empty the other devices'.
     */
    fun toSections(
        profileId: Int,
        data: LiveTvSyncData,
        base: SyncSections,
        /** The Drive copies of imported playlists, by source id (see [SyncedPlaylists]). */
        playlists: Map<String, SyncedPlaylists.Ref> = emptyMap(),
        removed: Set<String> = emptySet(),
    ): Map<String, Map<String, JsonElement>> {
        val knownSources = knownSources(profileId, base)
        if (data.sources.isEmpty() && knownSources.isNotEmpty() && knownSources.keys.none(removed::contains)) return emptyMap()
        // Imported playlists the file still lists with the links (as versions before their own
        // section wrote them) stay there too, so a TV on such a version keeps them.
        val linksHave = SyncDoc.values(base, sources(profileId)).filterValues(::isFileEntry).keys
        return sectionsWith(profileId, data, knownSources, playlists, removed, linksHave)
    }

    /** Every Drive copy of an imported playlist the file names, for every profile. */
    fun playlistDriveIds(doc: SyncSections): Set<String> {
        val ids = HashSet<String>()
        doc.forEach { (name, entries) ->
            if (!name.startsWith("live_tv/") || !(name.endsWith("/sources") || name.endsWith("/imported"))) return@forEach
            entries.values.forEach { entry ->
                val value = entry.value as? JsonObject ?: return@forEach
                if (value.text("type") == FILE_TYPE) value.text("file").takeIf(String::isNotBlank)?.let(ids::add)
            }
        }
        return ids
    }

    /** The sections of [profileId]'s sources, by identity: links and logins, and imported playlists. */
    fun sourceSections(profileId: Int): List<String> = listOf(sources(profileId), imported(profileId))

    /** Every source [doc] has for [profileId], by identity (imported ones from either section). */
    private fun knownSources(profileId: Int, doc: SyncSections): Map<String, JsonElement> =
        SyncDoc.values(doc, sources(profileId)) + SyncDoc.values(doc, imported(profileId))

    private fun isFileEntry(entry: JsonElement?): Boolean = (entry as? JsonObject)?.text("type") == FILE_TYPE

    private fun sectionsWith(
        profileId: Int,
        data: LiveTvSyncData,
        knownSources: Map<String, JsonElement>,
        playlists: Map<String, SyncedPlaylists.Ref>,
        removed: Set<String>,
        linksHave: Set<String>,
    ): Map<String, Map<String, JsonElement>> {
        val links = HashMap<String, JsonElement>()
        val files = HashMap<String, JsonElement>()
        fun put(identity: String, entry: JsonElement) {
            if (!isFileEntry(entry)) {
                links[identity] = entry
                return
            }
            files[identity] = entry
            if (identity in linksHave) links[identity] = entry
        }
        // Sources the file has that this device lacks but never removed stay as they are.
        knownSources.forEach { (identity, entry) -> if (identity !in removed) put(identity, entry) }
        data.sources.forEach { source ->
            val knownEntry = knownSources[source.identity] as? JsonObject
            val known = knownEntry?.text("id")
            val json = source.copy(id = known?.takeIf(String::isNotBlank) ?: source.id).toJson()
            if (!SyncedPlaylists.isImported(source)) {
                put(source.identity, json)
                return@forEach
            }
            // An imported playlist: named with its Drive copy. Without one yet (not sent, or
            // the upload failed), the entry the file has stays as it is, never deleted.
            val ref = playlists[source.id]
            when {
                ref != null -> put(source.identity, JsonObject(json + mapOf(
                    "type" to JsonPrimitive(FILE_TYPE), "file" to JsonPrimitive(ref.driveId), "hash" to JsonPrimitive(ref.hash),
                )))
                knownEntry != null -> put(source.identity, knownEntry)
            }
        }
        return mapOf(
            sources(profileId) to links,
            // Their own section: versions that cannot read them never see them, so never delete them.
            imported(profileId) to files,
            favorites(profileId) to data.favorites.associateWith { TRUE },
            customLists(profileId) to data.customLists.mapValues { (_, list) -> list.toJson() },
            hiddenGroups(profileId) to data.hiddenGroups.associateWith { TRUE },
            hiddenChannels(profileId) to data.hiddenChannels.associate { it.toString() to TRUE },
            groupNames(profileId) to data.groupNames.mapValues { JsonPrimitive(it.value) },
            groupOrder(profileId) to if (data.groupOrder.isEmpty()) emptyMap() else mapOf("order" to JsonArray(data.groupOrder.map(::JsonPrimitive))),
            sourceGroupOrder(profileId) to data.sourceGroupOrders.mapValues { (_, groups) -> JsonArray(groups.map(::JsonPrimitive)) },
            recent(profileId) to (data.recent?.let { mapOf("channel" to it.toJson()) } ?: emptyMap()),
        )
    }

    fun fromSections(profileId: Int, doc: SyncSections): LiveTvSyncData = LiveTvSyncData(
        sources = knownSources(profileId, doc).entries.sortedBy { it.key }.mapNotNull { (it.value as? JsonObject)?.toSource() },
        favorites = SyncDoc.values(doc, favorites(profileId)).keys,
        customLists = SyncDoc.values(doc, customLists(profileId)).mapNotNull { (id, value) ->
            (value as? JsonObject)?.toCustomList(id)?.let { id to it }
        }.toMap(),
        hiddenGroups = SyncDoc.values(doc, hiddenGroups(profileId)).keys,
        hiddenChannels = SyncDoc.values(doc, hiddenChannels(profileId)).keys.mapNotNullTo(HashSet()) { it.toLongOrNull() },
        groupNames = SyncDoc.values(doc, groupNames(profileId)).mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let { key to it }
        }.toMap(),
        groupOrder = (SyncDoc.values(doc, groupOrder(profileId))["order"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
        sourceGroupOrders = SyncDoc.values(doc, sourceGroupOrder(profileId)).mapNotNull { (identity, value) ->
            (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }?.let { identity to it }
        }.toMap(),
        recent = (SyncDoc.values(doc, recent(profileId))["channel"] as? JsonObject)?.toRecent(),
    )

    private fun LiveTvSource.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type.name)
        put("url", url)
        // Only when set, so a source without one reads the same as from versions before guide links.
        if (epgUrl.isNotBlank()) put("epg", epgUrl)
        if (name.isNotBlank()) put("name", name)
        if (userAgent.isNotBlank()) put("ua", userAgent)
        when (type) {
            LiveTvSourceType.M3u -> Unit
            LiveTvSourceType.Xtream -> {
                put("server", xtream.serverUrl)
                put("user", xtream.username)
                put("password", xtream.password)
            }
            LiveTvSourceType.Stalker -> {
                put("portal", stalker.portalUrl)
                put("mac", stalker.macAddress)
                put("user", stalker.username)
                put("password", stalker.password)
            }
        }
    }

    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** The Drive copy each synced imported playlist names, by source identity. */
    fun playlistRefs(profileId: Int, doc: SyncSections): Map<String, SyncedPlaylists.Ref> =
        knownSources(profileId, doc).mapNotNull { (identity, value) ->
            val entry = value as? JsonObject ?: return@mapNotNull null
            if (entry.text("type") != FILE_TYPE) return@mapNotNull null
            val file = entry.text("file").ifBlank { return@mapNotNull null }
            identity to SyncedPlaylists.Ref(file, entry.text("hash"))
        }.toMap()

    /**
     * An imported playlist's type in the file. The phone app (one source, links and logins only)
     * keeps it without reading it; TV versions before this one drop it when they sync.
     */
    private const val FILE_TYPE = "M3uFile"

    private fun JsonObject.toSource(): LiveTvSource? {
        if (text("type") == FILE_TYPE) {
            val name = text("url").ifBlank { return null }
            return LiveTvSource(text("id"), LiveTvSourceType.M3u, name, epgUrl = text("epg"), name = text("name"), userAgent = text("ua"))
        }
        val type = LiveTvSourceType.entries.firstOrNull { it.name == text("type") } ?: return null
        return toSourceOfType(type)?.copy(epgUrl = text("epg"), name = text("name"), userAgent = text("ua"))
    }

    private fun JsonObject.toSourceOfType(type: LiveTvSourceType): LiveTvSource? {
        return when (type) {
            LiveTvSourceType.M3u -> LiveTvSource(text("id"), type, text("url")).takeIf { it.url.isNotBlank() }
            LiveTvSourceType.Xtream -> LiveTvSource(
                text("id"), type, text("url"),
                xtream = LiveTvXtreamSettings(text("server"), text("user"), text("password")),
            ).takeIf { it.xtream.isConfigured }
            LiveTvSourceType.Stalker -> LiveTvSource(
                text("id"), type, text("url"),
                stalker = LiveTvStalkerSettings(text("portal"), text("mac"), text("user"), text("password")),
            ).takeIf { it.stalker.isConfigured }
        }
    }

    private fun LiveTvCustomList.toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("channels", JsonArray(urls.map(::JsonPrimitive)))
    }

    private fun JsonObject.toCustomList(id: String): LiveTvCustomList? {
        if (id.isBlank()) return null
        val urls = (get("channels") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
        return LiveTvCustomList(id, text("name"), urls)
    }

    private fun LiveTvRecentChannel.toJson(): JsonObject = buildJsonObject {
        put("url", streamUrl)
        put("name", name)
        logoUrl?.let { put("logo", it) }
        put("group", group)
        tvgId?.let { put("tvg_id", it) }
    }

    private fun JsonObject.toRecent(): LiveTvRecentChannel? {
        val url = text("url").ifBlank { return null }
        val name = text("name").ifBlank { return null }
        return LiveTvRecentChannel(url, name, text("logo").ifBlank { null }, text("group"), text("tvg_id").ifBlank { null })
    }
}
