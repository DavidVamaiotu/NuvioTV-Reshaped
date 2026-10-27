package com.nuvio.tv.ui.screens.player.seekpreview.torbox

import kotlin.math.abs
import kotlin.math.ceil
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
    val base = request.contentId.orEmpty().trim().removePrefix("movie:").removePrefix("tmdb:").removePrefix("imdb:")
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
            .removePrefix("movie:").removePrefix("tmdb:").removePrefix("imdb:")
            .substringBefore(':').substringBefore('/')
        val knownId = content.matches(Regex("tt[0-9]{7,10}")) || content.toIntOrNull() != null
        val movieMatches = content == imdbId || (tmdbId != null && content.toIntOrNull() == tmdbId)
        if (knownId && !movieMatches) return null
        val exact = request.sourceHash?.equals(sourceHash, true) == true && request.sourceFileIndex == sourceFileIndex
        if (exact) return TorboxPackMatch(this, true)
        // Another release or cut of this movie is still useful as an approximate preview.
        if (!movieMatches) return null
        return TorboxPackMatch(this, false)
    }
}

internal data class TorboxPackMatch(val pack: TorboxPreviewPack, val exactSource: Boolean)

/** Prefer exact files, then the closest runtime; revision breaks ties between equally good releases. */
internal fun torboxPreviewMatchOrder(request: TorboxMovieRequest): Comparator<TorboxPackMatch> =
    compareByDescending<TorboxPackMatch> { it.exactSource }
        .thenBy { abs(it.pack.durationMs - request.durationMs) }
        .thenByDescending { it.pack.revision }
        .thenBy { it.pack.id }

/** Approximate releases span playback's full timeline; exact files retain their original timestamps. */
internal class TorboxPreviewTiming(
    private val playbackDurationMs: Long,
    private val sourceDurationMs: Long,
    private val exactSource: Boolean,
) {
    init { require(playbackDurationMs > 0L && sourceDurationMs > 0L) }

    fun sourcePosition(playbackMs: Long, mapping: TorboxVisualMapping? = null): Long {
        val position = playbackMs.coerceIn(0L, playbackDurationMs - 1L)
        return (mapping?.reference(position) ?:
            if (exactSource) position else (position.toDouble() / playbackDurationMs * sourceDurationMs).toLong())
            .coerceIn(0L, sourceDurationMs - 1L)
    }

    fun playbackPosition(sourceMs: Long, mapping: TorboxVisualMapping? = null): Long =
        mapping?.playback(sourceMs) ?:
            if (exactSource) sourceMs else ceil(sourceMs.toDouble() / sourceDurationMs * playbackDurationMs).toLong()

    fun cueStart(frame: TorboxSpriteFrame): Long = cueStart(frame.startMs)

    fun cueStart(startMs: Long, mapping: TorboxVisualMapping? = null): Long =
        playbackPosition(startMs, mapping).let { if (startMs == 0L) minOf(0L, it) else it }

    fun cueEnd(frame: TorboxSpriteFrame): Long = cueEnd(frame.startMs, frame.endMs)

    fun cueEnd(startMs: Long, endMs: Long, mapping: TorboxVisualMapping? = null): Long {
        // A longer playback/audio tail keeps the last available image rather than losing previews.
        val end = if (endMs == sourceDurationMs) maxOf(playbackDurationMs, playbackPosition(endMs, mapping))
            else playbackPosition(endMs, mapping)
        return end.coerceAtLeast(cueStart(startMs, mapping) + 1L)
    }
}

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
