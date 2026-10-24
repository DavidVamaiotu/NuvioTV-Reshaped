// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, 447d883. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import androidx.media3.common.C
import androidx.media3.common.MimeTypes

/**
 * What held a session's thumbnails back, for one summary line when they give up for a title: the generating screen
 * hands over to playback, or the session ends with none made. The worker writes, the line may be built on any thread.
 */
internal class GiveUpNotes {
    @Volatile var memoryRefusals = 0
        private set
    @Volatile var lastMemoryRefusal: String? = null
        private set
    @Volatile var fetchFailures = 0
        private set
    @Volatile var lastFetchError: String? = null
        private set
    @Volatile var noConnection = 0
        private set
    @Volatile var rateLimits = 0
        private set
    @Volatile var decodeFailures = 0
        private set
    @Volatile var endedBy: String? = null
        private set
    /** The summary was written for this session already. */
    @Volatile var logged = false
    /** What the "Generate thumbnails before play" screen did for this title. */
    @Volatile var beforePlay = "not decided"

    /** Anything at all held thumbnails back. */
    val anything: Boolean
        get() = memoryRefusals + fetchFailures + noConnection + rateLimits + decodeFailures > 0 || endedBy != null

    fun memoryRefused(reason: String) {
        memoryRefusals++
        lastMemoryRefusal = reason
    }

    fun fetchFailed(e: Throwable) {
        fetchFailures++
        lastFetchError = e.javaClass.simpleName
    }

    fun noConnectionFree() {
        noConnection++
    }

    fun rateLimited() {
        rateLimits++
    }

    fun decodeFailed() {
        decodeFailures++
    }

    fun ended(t: Throwable) {
        endedBy = errorNote(t)
    }

    /** The likeliest cause. A refused decode never fetches, and a fetch that fails never reaches the decoder. */
    fun reason(decoderReady: Boolean): String = when {
        !decoderReady -> "decoder unavailable"
        memoryRefusals > 0 -> "memory floor"
        fetchFailures > 0 -> "fetches failed"
        rateLimits > 0 -> "rate limited"
        noConnection > 0 -> "no connection free"
        decodeFailures > 0 -> "decodes failed"
        endedBy != null -> "session error"
        else -> "nothing seen"
    }

    /**
     * [memory]: MemAvailable against the floors. [connections]: the server's requests in use. [decoder]: backend state.
     * [source], [video] and [settings]: what was playing and how, never a link, host or name.
     */
    fun line(
        event: String,
        made: Int,
        stored: Int,
        memory: String,
        connections: String,
        decoder: String,
        decoderReady: Boolean,
        source: String,
        video: String,
        settings: String,
    ): String = buildString {
        append("gave up ($event): reason=").append(reason(decoderReady))
        append(" made=").append(made).append(" stored=").append(stored)
        append("; memory: ").append(memory).append(", decodes refused ").append(memoryRefusals)
        lastMemoryRefusal?.let { append(" (last: ").append(it).append(')') }
        append("; connections: ").append(connections).append(", none free x").append(noConnection)
        append("; fetches failed ").append(fetchFailures)
        lastFetchError?.let { append(" (last: ").append(it).append(')') }
        append(", rate limited x").append(rateLimits)
        append("; decodes failed ").append(decodeFailures)
        append("; decoder: ").append(decoder)
        append("; playback: source ").append(source).append(", video ").append(video)
        append(", before play ").append(beforePlay)
        if (settings.isNotEmpty()) append(", ").append(settings)
        endedBy?.let { append("; ended by ").append(it) }
    }

    companion object {
        private val HTTP_STATUS = Regex("""(?i)\b(http|response code:?) \d{3}\b""")

        /**
         * What a session error says without any link, host or file name: the app's own media errors keep their text,
         * an HTTP error keeps its status, everything else is named by its type only.
         */
        fun errorNote(t: Throwable): String {
            val message = t.message.orEmpty()
            val detail = when {
                t is UnsupportedMediaException -> message
                else -> HTTP_STATUS.find(message)?.value
            }
            return if (detail.isNullOrBlank()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $detail"
        }

        /** e.g. "3840x2160 HEVC DV P8 HDR10". */
        fun videoNote(width: Int, height: Int, mimeType: String?, codecs: String?, colorTransfer: Int): String {
            val dv = mimeType == MimeTypes.VIDEO_DOLBY_VISION || codecs?.let { DV_CODEC.containsMatchIn(it) } == true
            val dvMatch = codecs?.let { DV_CODEC.find(it) }
            val codec = when {
                mimeType == MimeTypes.VIDEO_DOLBY_VISION -> when (dvMatch?.groupValues?.get(1)?.lowercase()) {
                    "dvav", "dva1" -> "H.264"
                    "dav1" -> "AV1"
                    else -> "HEVC"
                }
                else -> CODEC_NAMES[mimeType] ?: mimeType?.substringAfter('/')?.uppercase() ?: "unknown"
            }
            return buildString {
                append(width).append('x').append(height).append(' ').append(codec)
                if (dv) {
                    append(" DV")
                    dvMatch?.groupValues?.get(2)?.toIntOrNull()?.let { append(" P").append(it) }
                }
                when (colorTransfer) {
                    C.COLOR_TRANSFER_ST2084 -> append(" HDR10")
                    C.COLOR_TRANSFER_HLG -> append(" HLG")
                }
            }
        }

        private val DV_CODEC = Regex("""(dvhe|dvh1|dvav|dva1|dav1)\.(\d+)""", RegexOption.IGNORE_CASE)
        private val CODEC_NAMES = mapOf(
            MimeTypes.VIDEO_H265 to "HEVC",
            MimeTypes.VIDEO_H264 to "H.264",
            MimeTypes.VIDEO_AV1 to "AV1",
            MimeTypes.VIDEO_VP9 to "VP9",
            MimeTypes.VIDEO_MPEG2 to "MPEG-2",
            MimeTypes.VIDEO_VC1 to "VC-1",
        )
    }
}
