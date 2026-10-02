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
    private fun hiddenGroups(p: Int) = prefix(p) + "hidden_groups"
    private fun hiddenChannels(p: Int) = prefix(p) + "hidden_channels"
    private fun groupNames(p: Int) = prefix(p) + "group_names"
    private fun groupOrder(p: Int) = prefix(p) + "group_order"
    private fun recent(p: Int) = prefix(p) + "recent"

    /**
     * [data] as sections. A source keeps the id the file already gives it ([base]), so devices
     * that each gave the same source their own id do not keep replacing each other's.
     */
    fun toSections(profileId: Int, data: LiveTvSyncData, base: SyncSections): Map<String, Map<String, JsonElement>> {
        val knownSources = SyncDoc.values(base, sources(profileId))
        return sectionsWith(profileId, data, knownSources)
    }

    private fun sectionsWith(profileId: Int, data: LiveTvSyncData, knownSources: Map<String, JsonElement>): Map<String, Map<String, JsonElement>> = mapOf(
        sources(profileId) to data.sources.associate { source ->
            val known = (knownSources[source.identity] as? JsonObject)?.text("id")
            source.identity to source.copy(id = known?.takeIf(String::isNotBlank) ?: source.id).toJson()
        },
        favorites(profileId) to data.favorites.associateWith { TRUE },
        customLists(profileId) to data.customLists.mapValues { (_, list) -> list.toJson() },
        hiddenGroups(profileId) to data.hiddenGroups.associateWith { TRUE },
        hiddenChannels(profileId) to data.hiddenChannels.associate { it.toString() to TRUE },
        groupNames(profileId) to data.groupNames.mapValues { JsonPrimitive(it.value) },
        groupOrder(profileId) to if (data.groupOrder.isEmpty()) emptyMap() else mapOf("order" to JsonArray(data.groupOrder.map(::JsonPrimitive))),
        recent(profileId) to (data.recent?.let { mapOf("channel" to it.toJson()) } ?: emptyMap()),
    )

    fun fromSections(profileId: Int, doc: SyncSections): LiveTvSyncData = LiveTvSyncData(
        sources = SyncDoc.values(doc, sources(profileId)).entries.sortedBy { it.key }.mapNotNull { (it.value as? JsonObject)?.toSource() },
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

    private fun JsonObject.toSource(): LiveTvSource? {
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
