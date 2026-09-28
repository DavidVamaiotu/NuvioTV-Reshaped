package com.nuvio.tv.reshaped.livetv

import android.util.Log
import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/*
 * Xtream Codes and Stalker (MAG) providers. Channel lists are read with a streaming JSON reader,
 * one entry at a time, so a 20 000 channel provider never becomes a JSON tree in memory.
 */

internal fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

private fun String.isHttp(): Boolean = isHttpUrl()

// region Xtream

internal fun LiveTvXtreamSettings.normalized(): LiveTvXtreamSettings = copy(
    serverUrl = serverUrl.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/'),
    username = username.trim(),
    password = password.trim(),
)

internal object LiveTvXtream {
    suspend fun channels(settings: LiveTvXtreamSettings): List<LiveTvChannel> {
        val categories = LiveTvHttp.stream(apiUrl(settings, "get_live_categories"), LIVE_TV_PLAYLIST_HEADERS) { input ->
            readObjects(input) { fields ->
                val id = fields["category_id"] ?: fields["id"] ?: return@readObjects null
                val name = fields["category_name"] ?: fields["name"] ?: return@readObjects null
                id to name
            }
        }.toMap()
        val extension = liveExtension(settings)
        val seen = HashSet<String>()
        val channels = LiveTvHttp.stream(apiUrl(settings, "get_live_streams"), LIVE_TV_PLAYLIST_HEADERS) { input ->
            var index = 0
            readObjects(input) { fields ->
                val position = index++
                val name = fields["name"] ?: return@readObjects null
                val streamId = fields["stream_id"] ?: fields["id"] ?: return@readObjects null
                // Always the panel's own link, as IPTV players use: "direct_source" is often the
                // panel's upstream origin, which refuses clients, and the panel redirects to it when it is meant to be used.
                val streamUrl = settings.liveStreamUrl(streamId, extension)
                if (!seen.add(streamUrl)) return@readObjects null
                LiveTvChannel(
                    id = "xtream-$streamId-$position",
                    name = name,
                    streamUrl = streamUrl,
                    tvgId = fields["epg_channel_id"] ?: fields["tvg_id"],
                    logoUrl = fields["stream_icon"] ?: fields["logo"],
                    group = fields["category_id"]?.let(categories::get).orEmpty(),
                    headers = LIVE_TV_STREAM_HEADERS,
                )
            }
        }
        return channels
    }

    /**
     * The live format this account may use: MPEG-TS, as IPTV players prefer, unless the account only
     * allows HLS ("allowed_output_formats" in the login reply); a TS link then fails on every channel.
     */
    private suspend fun liveExtension(settings: LiveTvXtreamSettings): String {
        val formats = try {
            val login = JSONObject(LiveTvHttp.text(loginUrl(settings), LIVE_TV_PLAYLIST_HEADERS))
            val allowed = login.optJSONObject("user_info")?.optJSONArray("allowed_output_formats")
            if (allowed == null) emptyList() else List(allowed.length()) { allowed.optString(it).trim().lowercase() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w("LiveTvXtream", "Could not read the account's formats; using TS", error)
            emptyList()
        }
        return if (formats.isEmpty() || "ts" in formats || "m3u8" !in formats) "ts" else "m3u8"
    }

    private fun loginUrl(settings: LiveTvXtreamSettings): String =
        "${settings.serverUrl}/player_api.php?username=${settings.username.urlEncoded()}" +
            "&password=${settings.password.urlEncoded()}"

    private fun apiUrl(settings: LiveTvXtreamSettings, action: String): String =
        "${settings.serverUrl}/player_api.php?username=${settings.username.urlEncoded()}" +
            "&password=${settings.password.urlEncoded()}&action=${action.urlEncoded()}"

    private fun LiveTvXtreamSettings.liveStreamUrl(streamId: String, extension: String): String =
        "$serverUrl/live/${username.urlEncoded()}/${password.urlEncoded()}/${streamId.urlEncoded()}.$extension"
}

// endregion

// region Stalker

internal fun LiveTvStalkerSettings.normalized(): LiveTvStalkerSettings = copy(
    portalUrl = portalUrl.trim().trimEnd('/'),
    macAddress = macAddress.trim().uppercase(),
    username = username.trim(),
    password = password.trim(),
)

internal data class StalkerChannels(val channels: List<LiveTvChannel>, val incomplete: Boolean)

private class StalkerSession(val settings: LiveTvStalkerSettings, val token: String) {
    /** Built once per session and shared by every channel, not copied into each. */
    var playbackHeaders: Map<String, String>? = null
}

internal object LiveTvStalker {
    private const val MAX_PAGES = 500
    private const val PARALLEL_PAGES = 4

    /** One session per portal login, so several Stalker sources do not keep renewing each other's. */
    private val sessions = java.util.concurrent.ConcurrentHashMap<LiveTvStalkerSettings, StalkerSession>()

    fun clearSession() {
        sessions.clear()
    }

    /** The portal's channels; [StalkerChannels.incomplete] when some pages still failed after a retry. */
    suspend fun channels(settings: LiveTvStalkerSettings): StalkerChannels = withSession(settings) { session ->
        val genres = genres(session)
        var index = 0
        val toChannel: (Map<String, String>) -> LiveTvChannel? = { fields -> fields.toChannel(session, genres, index++) }
        // One request where the portal supports it, otherwise every page of the ordered list.
        val all = runCatching { dataObjects(session, "itv", "get_all_channels", toChannel) }
            .getOrElse { if (it is CancellationException) throw it else emptyList() }
        val seen = HashSet<String>()
        val result = if (all.isNotEmpty()) StalkerChannels(all, incomplete = false) else orderedPages(session, toChannel)
        result.copy(channels = ArrayList(result.channels.filter { seen.add(it.id.ifBlank { it.streamUrl }) }))
    }

    /** A playable link for a list entry: Stalker links are created per play and expire. */
    suspend fun resolve(settings: LiveTvStalkerSettings, channel: LiveTvChannel): LiveTvChannel {
        val command = channel.stalkerCommand ?: return channel
        return withSession(settings.normalized()) { session ->
            val js = JSONObject(request(session.settings, session.token, "itv", "create_link", mapOf("cmd" to command)))
                .let { it.optJSONObject("js") ?: it }
            // An expired session answers without a link: the failure renews it once (withSession).
            val link = (js.optNonBlank("cmd") ?: js.optNonBlank("url") ?: js.optNonBlank("stream_url"))
                ?.toStalkerPlayableUrl()?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("no link")
            channel.copy(streamUrl = link, headers = channel.headers + playbackHeaders(session))
        }
    }

    /**
     * Runs [block] with a portal session. Portals expire sessions without notice, so a failure
     * with a cached session is retried once after a fresh handshake.
     */
    private suspend fun <T> withSession(settings: LiveTvStalkerSettings, block: suspend (StalkerSession) -> T): T {
        val cached = sessions[settings]
        if (cached != null) {
            try {
                return block(cached)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                sessions.remove(settings, cached)
            }
        }
        return block(handshake(settings))
    }

    private suspend fun handshake(settings: LiveTvStalkerSettings): StalkerSession {
        val js = JSONObject(request(settings, null, "stb", "handshake")).let { it.optJSONObject("js") ?: it }
        val token = js.optNonBlank("token") ?: throw LiveTvException(LiveTvError.StalkerToken)
        // Many portals only list channels after the device profile was requested with the token.
        runCatching { request(settings, token, "stb", "get_profile") }
            .onFailure { if (it is CancellationException) throw it }
        return StalkerSession(settings, token).also { sessions[settings] = it }
    }

    private suspend fun genres(session: StalkerSession): Map<String, String> =
        dataObjects(session, "itv", "get_genres") { fields ->
            val id = fields["id"] ?: fields["alias"]
            val title = fields["title"] ?: fields["name"]
            if (id == null || title == null) null else id to title
        }.toMap()

    private suspend fun orderedPages(
        session: StalkerSession,
        toChannel: (Map<String, String>) -> LiveTvChannel?,
    ): StalkerChannels {
        // Pages load a few at a time, so the mapper is shared across threads.
        val mapper: (Map<String, String>) -> LiveTvChannel? = { synchronized(this) { toChannel(it) } }
        suspend fun page(number: Int): StalkerPage<LiveTvChannel> =
            LiveTvHttp.stream(
                url(session.settings, session.token, "itv", "get_ordered_list", mapOf("p" to number.toString())),
                baseHeaders(session.settings) + tokenHeader(session.token),
            ) { input -> readStalkerPage(input, mapper) }

        val first = page(1)
        if (first.entries.isEmpty()) return StalkerChannels(emptyList(), incomplete = false)
        var failedPages = 0
        val entries = ArrayList(first.entries)
        val perPage = first.maxPageItems?.takeIf { it > 0 } ?: first.entries.size
        val total = first.totalItems
        if (total != null && perPage > 0) {
            val lastPage = ((total + perPage - 1) / perPage).coerceAtMost(MAX_PAGES)
            (2..lastPage).chunked(PARALLEL_PAGES).forEach { numbers ->
                coroutineScope {
                    numbers.map { number ->
                        async {
                            // A portal that drops a page under load usually serves it on a second try.
                            runCatching { page(number).entries }
                                .recoverCatching { if (it is CancellationException) throw it else page(number).entries }
                                .getOrElse {
                                    if (it is CancellationException) throw it
                                    synchronized(entries) { failedPages++ }
                                    emptyList()
                                }
                        }
                    }.awaitAll()
                }.forEach(entries::addAll)
            }
        } else {
            for (number in 2..MAX_PAGES) {
                val data = page(number).entries
                if (data.isEmpty()) break
                entries += data
            }
        }
        if (failedPages > 0) Log.w("LiveTv", "Stalker: $failedPages pages could not be loaded")
        return StalkerChannels(entries, incomplete = failedPages > 0)
    }

    private fun Map<String, String>.toChannel(
        session: StalkerSession,
        genres: Map<String, String>,
        index: Int,
    ): LiveTvChannel? {
        val name = this["name"] ?: this["title"] ?: return null
        val command = this["cmd"] ?: this["mc_cmd"] ?: this["url"] ?: return null
        val streamUrl = command.toStalkerPlayableUrl().takeIf(String::isNotBlank) ?: return null
        return LiveTvChannel(
            id = this["id"] ?: "stalker-$index-${streamUrl.hashCode()}",
            name = name,
            streamUrl = streamUrl,
            tvgId = this["xmltv_id"] ?: this["tvg_id"],
            logoUrl = (this["logo"] ?: this["logo_url"])?.takeIf(String::isHttp),
            group = (this["tv_genre_id"] ?: this["genre_id"])?.let(genres::get).orEmpty(),
            headers = playbackHeaders(session),
            stalkerCommand = command,
        )
    }

    /** The `data` array of a `{js:{data:[...]}}` answer, each entry mapped from its plain fields. */
    private suspend fun <T : Any> dataObjects(
        session: StalkerSession,
        type: String,
        action: String,
        map: (Map<String, String>) -> T?,
    ): List<T> =
        LiveTvHttp.stream(
            url(session.settings, session.token, type, action),
            baseHeaders(session.settings) + tokenHeader(session.token),
        ) { input -> readStalkerPage(input, map).entries }

    private suspend fun request(
        settings: LiveTvStalkerSettings,
        token: String?,
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap(),
    ): String = LiveTvHttp.text(url(settings, token, type, action, extra), baseHeaders(settings) + tokenHeader(token))

    private fun url(
        settings: LiveTvStalkerSettings,
        token: String?,
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val parameters = buildMap {
            put("type", type)
            put("action", action)
            put("JsHttpRequest", "1-xml")
            if (!token.isNullOrBlank()) put("token", token)
            if (settings.username.isNotBlank()) put("login", settings.username)
            if (settings.password.isNotBlank()) put("password", settings.password)
            putAll(extra)
        }
        val endpoint = settings.portalEndpoint()
        return endpoint + parameters.entries.joinToString("&", prefix = if ('?' in endpoint) "&" else "?") { (key, value) ->
            "${key.urlEncoded()}=${value.urlEncoded()}"
        }
    }

    private fun playbackHeaders(session: StalkerSession): Map<String, String> =
        session.playbackHeaders ?: (baseHeaders(session.settings) + tokenHeader(session.token)).also { session.playbackHeaders = it }

    private fun baseHeaders(settings: LiveTvStalkerSettings): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; MAG254; en) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3",
        "X-User-Agent" to "Model: MAG254; Link: Ethernet",
        "Referer" to settings.portalUrl.trim().substringBefore("/portal.php").trimEnd('/') + "/c/",
        "Cookie" to "mac=${settings.macAddress}; stb_lang=en; timezone=${java.util.TimeZone.getDefault().id.urlEncoded()}",
    )

    private fun tokenHeader(token: String?): Map<String, String> =
        if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")

    private fun LiveTvStalkerSettings.portalEndpoint(): String {
        val normalized = portalUrl.trim().trimEnd('/')
        return when {
            normalized.endsWith("portal.php", ignoreCase = true) -> normalized
            normalized.contains("portal.php?", ignoreCase = true) -> normalized
            normalized.endsWith("/c", ignoreCase = true) -> normalized.dropLast(2) + "/portal.php"
            else -> "$normalized/portal.php"
        }
    }

    private fun String.toStalkerPlayableUrl(): String =
        trim().removePrefix("ffmpeg ").removePrefix("auto ").substringBefore(' ').trim()

    private fun JSONObject.optNonBlank(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf(String::isNotBlank)
}

private class StalkerPage<T>(val entries: List<T>, val totalItems: Int?, val maxPageItems: Int?)

/** Streams a Stalker answer (`{"js":{"total_items":..,"data":[{..},..]}}` or without `js`). */
private fun <T : Any> readStalkerPage(input: InputStream, map: (Map<String, String>) -> T?): StalkerPage<T> {
    var entries: List<T> = emptyList()
    var total: Int? = null
    var perPage: Int? = null
    JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
        reader.isLenient = true
        fun readBody() {
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "js" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) readBody() else reader.skipValue()
                    "data" -> entries = if (reader.peek() == JsonToken.BEGIN_ARRAY) reader.readObjectArray(map) else {
                        reader.skipValue(); emptyList()
                    }
                    "total_items" -> total = reader.nextScalar()?.toIntOrNull()
                    "max_page_items" -> perPage = reader.nextScalar()?.toIntOrNull()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        if (reader.peek() == JsonToken.BEGIN_OBJECT) readBody()
    }
    return StalkerPage(entries, total, perPage)
}

// endregion

// region Streaming JSON helpers

/** Reads a top-level array of objects (or `{"data":[...]}`), mapping each object's plain fields. */
private fun <T : Any> readObjects(input: InputStream, map: (Map<String, String>) -> T?): List<T> =
    JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
        reader.isLenient = true
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> reader.readObjectArray(map)
            JsonToken.BEGIN_OBJECT -> {
                var result: List<T> = emptyList()
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "data" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        result = reader.readObjectArray(map)
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
                result
            }
            else -> emptyList()
        }
    }

private fun <T : Any> JsonReader.readObjectArray(map: (Map<String, String>) -> T?): List<T> {
    val result = ArrayList<T>()
    val fields = HashMap<String, String>(16)
    beginArray()
    while (hasNext()) {
        if (peek() != JsonToken.BEGIN_OBJECT) {
            skipValue()
            continue
        }
        fields.clear()
        beginObject()
        while (hasNext()) {
            val name = nextName()
            nextScalar()?.let { fields[name] = it }
        }
        endObject()
        // The map is reused for the next entry: [map] must not keep it.
        map(fields)?.let(result::add)
    }
    endArray()
    return result
}

/** A string, number or boolean as trimmed text; null (and skipped) for blanks, nulls and nested values. */
private fun JsonReader.nextScalar(): String? = when (peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> nextString().trim().takeIf(String::isNotBlank)
    JsonToken.BOOLEAN -> nextBoolean().toString()
    else -> {
        skipValue()
        null
    }
}

// endregion

/** A load failure the screen shows as a translated message. */
enum class LiveTvError {
    InvalidUrl, NoChannels, LoadFailed, FileEmpty, FileNoChannels,
    StalkerRequired, StalkerInvalidUrl, StalkerNoChannels, StalkerFailed, StalkerToken,
    /** The list loaded, but some of the portal's pages did not: a notice, not a failed load. */
    StalkerIncomplete,
    XtreamRequired, XtreamInvalidUrl, XtreamNoChannels, XtreamFailed,
}

internal class LiveTvException(val error: LiveTvError) : Exception(error.name)
