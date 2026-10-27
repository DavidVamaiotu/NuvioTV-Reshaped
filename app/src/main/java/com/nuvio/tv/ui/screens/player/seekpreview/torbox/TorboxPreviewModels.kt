package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import kotlin.math.abs
import kotlinx.coroutines.CancellationException

/** Pure matching and timestamp rules; no network, player, or Android dependencies. */
internal data class TorboxMovieRequest(
    val contentId: String?, val contentType: String?, val durationMs: Long,
    val sourceHash: String?, val sourceFileIndex: Int?
)

/** Reuse player metadata before an existing TMDB lookup; never guess identity from runtime. */
internal suspend fun resolveTorboxMovieRequest(
    request: TorboxMovieRequest,
    cachedImdb: () -> String?,
    tmdbToImdb: suspend (Int) -> String?,
): TorboxMovieRequest {
    if (request.durationMs <= 0 || request.contentType?.lowercase() in setOf("series", "tv", "show", "channel", "live")) return request
    fun imdb(value: String?) = value?.trim()?.takeIf { it.matches(Regex("tt[0-9]{7,10}")) }
    val base = request.contentId.orEmpty().trim().removePrefix("tmdb:").removePrefix("movie:")
        .substringBefore(':').substringBefore('/')
    imdb(base)?.let { return request.copy(contentId = it) }
    val resolved = try {
        imdb(cachedImdb()) ?: base.toIntOrNull()?.takeIf { it > 0 }?.let { imdb(tmdbToImdb(it)) }
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { null }
    return resolved?.let { request.copy(contentId = it) } ?: request
}

internal data class TorboxPreviewPack(
    val id: String, val imdbId: String, val tmdbId: Int?, val revision: Int,
    val sourceHash: String, val sourceFileIndex: Int, val durationMs: Long,
    val indexPath: String, val indexSha256: String
) {
    fun match(request: TorboxMovieRequest): TorboxPackMatch? {
        if (request.durationMs <= 0 || request.contentType?.lowercase() in setOf("series", "tv", "show", "channel", "live")) return null
        val content = request.contentId.orEmpty().trim()
            .removePrefix("tmdb:").removePrefix("movie:").substringBefore(':').substringBefore('/')
        val knownId = content.matches(Regex("tt[0-9]{7,10}")) || content.toIntOrNull() != null
        val movieMatches = content == imdbId || (tmdbId != null && content.toIntOrNull() == tmdbId)
        if (knownId && !movieMatches) return null
        if (request.sourceHash?.equals(sourceHash, true) == true &&
            request.sourceFileIndex != null && request.sourceFileIndex != sourceFileIndex) return null
        val exact = request.sourceHash?.equals(sourceHash, true) == true && request.sourceFileIndex == sourceFileIndex
        if (exact) return TorboxPackMatch(this, true)
        // Runtime is a compatibility filter, not proof that two cuts have identical timelines.
        // Candidate selection only. Different files must pass visual fingerprint verification.
        if (!movieMatches || listOf(1.0, 24.0 / 25, 25.0 / 24).minOf {
                abs(request.durationMs * it - durationMs)
            } > 120_000) return null
        return TorboxPackMatch(this, false)
    }
}

internal data class TorboxPackMatch(val pack: TorboxPreviewPack, val exactSource: Boolean)

internal data class TorboxSpriteSheet(val path: String, val width: Int, val height: Int, val bytes: Int, val sha256: String)

internal data class TorboxSpriteFrame(
    val startMs: Long, val endMs: Long, val actualMs: Long,
    val sheet: String, val x: Int, val y: Int, val width: Int, val height: Int
)

internal class TorboxSpriteTimeline(val frames: List<TorboxSpriteFrame>, val sheets: Map<String, TorboxSpriteSheet>) {
    init {
        require(frames.size in 1..100_000)
        require(sheets.size in 1..4_000)
        require(frames.first().startMs == 0L)
        for ((index, frame) in frames.withIndex()) {
            require(frame.startMs >= 0 && frame.endMs > frame.startMs)
            require(index == 0 || frames[index - 1].endMs == frame.startMs)
            val sheet = requireNotNull(sheets[frame.sheet])
            require(sheet.width in 1..4096 && sheet.height in 1..4096 && sheet.width.toLong() * sheet.height <= 8_000_000)
            require(sheet.bytes in 1..2 * 1024 * 1024 && safePreviewPath(sheet.path))
            require(frame.x >= 0 && frame.y >= 0 && frame.width == 320 && frame.height in 1..640)
            require(frame.x.toLong() + frame.width <= sheet.width && frame.y.toLong() + frame.height <= sheet.height)
        }
    }

    fun frameAt(positionMs: Long): TorboxSpriteFrame? {
        if (positionMs < 0 || positionMs >= frames.last().endMs) return null
        var low = 0
        var high = frames.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val frame = frames[middle]
            if (positionMs < frame.startMs) high = middle - 1
            else if (positionMs >= frame.endMs) low = middle + 1
            else return frame
        }
        return null
    }
}

internal fun safePreviewPath(path: String): Boolean =
    path.isNotEmpty() && path.length <= 512 && !path.startsWith('/') &&
        path.matches(Regex("[A-Za-z0-9._/-]+")) && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

/** Full relative paths distinguish equal sheet basenames belonging to different movies. */
internal fun previewFileMatches(listed: String, wanted: String, rootName: String): Boolean {
    if (!safePreviewPath(wanted)) return false
    val normalized = listed.replace('\\', '/').trimStart('/')
    return normalized == wanted || normalized == "$rootName/$wanted"
}
