package com.nuvio.tv.reshaped.livetv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class ParsedM3uPlaylist(
    val channels: List<LiveTvChannel>,
    val epgUrls: List<String>,
    /** The link was an HLS stream itself (a single channel), not a channel list. */
    val isHlsStream: Boolean = false,
)

/**
 * Parses an M3U playlist line by line, so a large playlist is read from the network or a file
 * without ever sitting in memory as one string. Duplicate links and "#### Category ####"
 * separator entries are dropped.
 */
internal fun parseM3uPlaylist(lines: Sequence<String>): ParsedM3uPlaylist {
    val channels = ArrayList<LiveTvChannel>()
    val seenUrls = HashSet<String>()
    val epgUrls = LinkedHashSet<String>()
    // Thousands of channels share a few groups and header sets: each is kept once.
    val groups = HashMap<String, String>()
    val headerSets = HashMap<Map<String, String>, Map<String, String>>()
    var metadata: M3uMetadata? = null
    var pendingHeaders = emptyMap<String, String>()
    var isHlsStream = false
    // Catch-up the playlist header gives every channel, and one shared instance per kind.
    var defaultCatchup: Map<String, String> = emptyMap()
    val catchups = HashMap<LiveTvCatchup, LiveTvCatchup>()

    for (rawLine in lines) {
        val line = rawLine.trim().removePrefix("﻿")
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXTM3U", ignoreCase = true) -> {
                val attributes = parseM3uAttributes(line)
                defaultCatchup = attributes.filterKeys { it in CATCHUP_ATTRIBUTES }
                listOfNotNull(attributes["url-tvg"], attributes["x-tvg-url"], attributes["tvg-url"])
                    .flatMap { it.split(',', ';') }
                    .map(String::trim)
                    .filter { it.isHttpUrl() }
                    .forEach(epgUrls::add)
            }
            line.startsWith("#EXT-X-", ignoreCase = true) -> {
                // HLS tags: this is a stream's own playlist, and its "entries" are video segments.
                isHlsStream = true
                break
            }
            line.startsWith("#EXTINF", ignoreCase = true) -> metadata = parseExtInf(line)
            line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                pendingHeaders = pendingHeaders + ("User-Agent" to line.substringAfter('=').trim())
            line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ->
                pendingHeaders = pendingHeaders + ("Referer" to line.substringAfter('=').trim())
            line.startsWith("#EXTHTTP:", ignoreCase = true) ->
                pendingHeaders = pendingHeaders + parseExtHttpHeaders(line.substringAfter(':'))
            line.startsWith("#") -> Unit
            else -> {
                val url = line.substringBefore('|').trim()
                val current = metadata
                metadata = null
                val headers = pendingHeaders
                pendingHeaders = emptyMap()
                if (url.isEmpty() || !seenUrls.add(url)) continue
                val name = current?.name?.takeIf(String::isNotBlank) ?: "Channel ${channels.size + 1}"
                if (isLikelyCategoryHeading(name)) continue
                val extraHeaders = headers + parseUrlHeaders(line)
                val defaults = defaultStreamHeaders(url)
                val group = current?.group.orEmpty()
                channels += LiveTvChannel(
                    id = "m${channels.size}",
                    name = name,
                    streamUrl = url,
                    tvgId = current?.tvgId,
                    logoUrl = current?.logoUrl,
                    tvgName = current?.tvgName?.takeIf { it != name },
                    catchup = m3uCatchup(current?.catchup.orEmpty(), defaultCatchup)?.let { catchups.getOrPut(it) { it } },
                    group = groups.getOrPut(group) { group },
                    headers = if (extraHeaders.isEmpty()) {
                        defaults
                    } else {
                        (defaults + extraHeaders).let { headerSets.getOrPut(it) { it } }
                    },
                )
            }
        }
    }
    if (isHlsStream) return ParsedM3uPlaylist(channels = emptyList(), epgUrls = emptyList(), isHlsStream = true)
    channels.trimToSize()
    return ParsedM3uPlaylist(channels = channels, epgUrls = epgUrls.toList())
}

private class M3uMetadata(
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val group: String,
    /** The entry's catch-up attributes, usually none. */
    val catchup: Map<String, String>,
)

private val CATCHUP_ATTRIBUTES = setOf("catchup", "catchup-type", "catchup-days", "catchup-source", "tvg-rec", "timeshift")

/**
 * A channel's catch-up from its `catchup*` attributes (falling back on the playlist header's),
 * as IPTV players read them: `catchup`/`catchup-type` the kind, `catchup-days` (or `tvg-rec`,
 * `timeshift`) how far back, `catchup-source` the link template.
 */
internal fun m3uCatchup(entry: Map<String, String>, playlist: Map<String, String>): LiveTvCatchup? {
    if (entry.isEmpty() && playlist.isEmpty()) return null
    fun value(name: String) = entry[name]?.takeIf(String::isNotBlank) ?: playlist[name]?.takeIf(String::isNotBlank)
    val type = (value("catchup") ?: value("catchup-type"))?.trim()?.lowercase()
    val days = (value("catchup-days") ?: value("tvg-rec") ?: value("timeshift"))?.trim()?.toIntOrNull()
    val template = value("catchup-source")?.trim()
    if (type == null && (days ?: 0) <= 0) return null
    if (type == "disabled" || type == "none" || type == "0" || days == 0) return null
    val kind = when (type) {
        null, "default", "1" -> if (template == null) LiveTvCatchup.Kind.Shift else LiveTvCatchup.Kind.Default
        "append" -> LiveTvCatchup.Kind.Append
        "shift", "timeshift" -> LiveTvCatchup.Kind.Shift
        "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> LiveTvCatchup.Kind.Flussonic
        "xc", "xtream" -> LiveTvCatchup.Kind.Xtream
        else -> if (template != null) LiveTvCatchup.Kind.Default else return null
    }
    if ((kind == LiveTvCatchup.Kind.Default || kind == LiveTvCatchup.Kind.Append) && template == null) return null
    return LiveTvCatchup(kind, (days ?: 1).coerceIn(1, 30), template)
}

private val m3uAttributeRegex = Regex("""([\w-]+)="([^"]*)"""")

private fun parseExtInf(line: String): M3uMetadata {
    // The display name follows the first comma outside quoted attributes; it may hold commas itself.
    val nameComma = firstUnquotedComma(line)
    val attributes = parseM3uAttributes(if (nameComma >= 0) line.substring(0, nameComma) else line)
    val displayName = (if (nameComma >= 0) line.substring(nameComma + 1) else "").trim()
        .ifBlank { attributes["tvg-name"].orEmpty() }
    return M3uMetadata(
        name = displayName,
        tvgId = attributes["tvg-id"]?.takeIf(String::isNotBlank),
        tvgName = attributes["tvg-name"]?.takeIf(String::isNotBlank),
        catchup = if (attributes.keys.any(CATCHUP_ATTRIBUTES::contains)) attributes.filterKeys(CATCHUP_ATTRIBUTES::contains) else emptyMap(),
        logoUrl = attributes["tvg-logo"]?.takeIf(String::isNotBlank),
        group = attributes["group-title"].orEmpty(),
    )
}

private fun parseM3uAttributes(line: String): Map<String, String> {
    if ('"' !in line) return emptyMap()
    return m3uAttributeRegex.findAll(line)
        .associate { match -> match.groupValues[1].lowercase() to match.groupValues[2].trim() }
}

/** Kodi style `url|User-Agent=...&Referer=...`. */
private fun parseUrlHeaders(line: String): Map<String, String> {
    val options = line.substringAfter('|', "")
    if (options.isEmpty()) return emptyMap()
    return options.split('&').mapNotNull { entry ->
        val key = entry.substringBefore('=').trim()
        val value = entry.substringAfter('=', "").trim()
        if (key.isBlank() || value.isBlank()) null else key to value
    }.toMap()
}

private fun parseExtHttpHeaders(value: String): Map<String, String> =
    value.trim().removePrefix("{").removeSuffix("}")
        .split(',')
        .mapNotNull { entry ->
            val key = entry.substringBefore(':').trim().trim('"')
            val headerValue = entry.substringAfter(':', "").trim().trim('"')
            if (key.isBlank() || headerValue.isBlank()) null else key to headerValue
        }
        .toMap()

/**
 * The guide of an Xtream panel's M3U link (`…/get.php?username=…&password=…`), as IPTV players
 * use it when the playlist names no guide: the panel serves it at `xmltv.php` with the same login.
 */
internal fun xtreamGuideUrlFor(playlistUrl: String): String? {
    val url = playlistUrl.toHttpUrlOrNull() ?: return null
    if (!url.encodedPath.endsWith("/get.php", ignoreCase = true)) return null
    val username = url.queryParameter("username")?.takeIf(String::isNotBlank) ?: return null
    val password = url.queryParameter("password")?.takeIf(String::isNotBlank) ?: return null
    val folder = url.encodedPath.dropLast("get.php".length)
    return url.newBuilder()
        .encodedPath(folder + "xmltv.php")
        .query(null)
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .build()
        .toString()
}

internal fun defaultStreamHeaders(url: String): Map<String, String> =
    if (url.isHttpUrl()) LIVE_TV_STREAM_HEADERS else emptyMap()

internal fun String.isHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private val categoryHeadingRegex = Regex("""^\s*#+\s*.+\s*#+\s*$""")

/** "##### SPORTS #####" style separator entries some providers put in their lists. */
internal fun isLikelyCategoryHeading(name: String): Boolean {
    val trimmed = name.trim()
    // Cheap checks first: this runs for every channel of a list.
    return trimmed.length >= 3 && trimmed.startsWith('#') && trimmed.endsWith('#') &&
        categoryHeadingRegex.matches(trimmed)
}

/** A single stream link rather than a playlist (the user pasted one channel). */
internal fun String.looksLikeDirectVideoUrl(): Boolean {
    val path = substringBefore('#').substringBefore('?').lowercase()
    if (path.endsWith(".m3u") || path.endsWith(".m3u8")) return false
    return DIRECT_VIDEO_EXTENSIONS.any(path::endsWith)
}

internal fun directStreamChannel(url: String): LiveTvChannel =
    LiveTvChannel(
        id = "direct-${url.hashCode()}",
        name = url.substringBefore('?').substringAfterLast('/').ifBlank { "Live stream" },
        streamUrl = url,
        headers = defaultStreamHeaders(url),
    )

private val DIRECT_VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".ts", ".mpeg", ".mpg")

internal val LIVE_TV_PLAYLIST_HEADERS = mapOf(
    "User-Agent" to "VLC/3.0.0 LibVLC/3.0.0",
    "Accept" to "application/x-mpegURL, application/vnd.apple.mpegurl, audio/mpegurl, text/plain, */*",
)

internal val LIVE_TV_STREAM_HEADERS = mapOf("User-Agent" to "VLC/3.0.0 LibVLC/3.0.0")

private fun firstUnquotedComma(line: String): Int {
    var quoted = false
    for (i in line.indices) {
        when (line[i]) {
            '"' -> quoted = !quoted
            ',' -> if (!quoted) return i
        }
    }
    return -1
}
