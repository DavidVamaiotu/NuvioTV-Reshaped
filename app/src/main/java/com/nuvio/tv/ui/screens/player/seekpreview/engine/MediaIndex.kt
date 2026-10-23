// SPDX-License-Identifier: GPL-3.0-only
// Adapted from ysosrs123/NuvioTV-Fork (Nuvio Arc), commit ae6212ef58d25df819a716f2a38314732e719a2c.
// Original container/keyframe/colour implementation by ysosrs123; see NOTICE.md in this package.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.io.IOException
import java.nio.ByteBuffer

/** Blocking, bounded byte-range access to one media file (HTTP in the app, a byte array in tests). */
internal interface RangeReader {
    /** Reads up to [length] bytes at [offset], fewer only at end of file. */
    @Throws(IOException::class)
    fun read(offset: Long, length: Int): ByteArray

    /** As [read], into [dst] from its position, which moves on by the count returned. */
    @Throws(IOException::class)
    fun readInto(offset: Long, length: Int, dst: ByteBuffer): Int {
        val b = read(offset, length)
        dst.put(b)
        return b.size
    }

    /** Total file length once known (from the first response), else -1. */
    val totalLength: Long
}

/**
 * [nal]: keyframes go to the decoder as Annex-B with the track's parameter sets, the others as the raw sample
 * plus [VideoTrack.extradata]. Order is persisted (KeyframeIndexCodec): append only.
 */
internal enum class VideoCodec(val ffmpegName: String, val nal: Boolean) {
    H264("h264", true),
    HEVC("hevc", true),
    MPEG4("mpeg4", false),        // MPEG-4 Part 2: XviD / DivX 4-5 / ASP
    MPEG2("mpeg2video", false),
    MPEG1("mpeg1video", false),
    VC1("vc1", false),            // WVC1 (advanced profile)
    WMV3("wmv3", false),          // VC-1 simple/main (needs coded width/height)
    AV1("libdav1d", false),
}

/** Colour as declared by the container (ISO/IEC 23091-2 code points; 0 = not present). */
internal data class ContainerColour(
    val transfer: Int = 0,
    val primaries: Int = 0,
    val matrix: Int = 0,
    /** 1 = limited (tv), 2 = full (pc), 0 = unknown. */
    val range: Int = 0,
    val maxCll: Int = 0,
    val masteringMaxNits: Int = 0,
)

internal data class DolbyVisionConfig(val profile: Int, val blCompatId: Int, val elPresent: Boolean)

internal class VideoTrack(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    /** Display aspect ratio (width / height) from the container, or 0 when not declared. */
    val displayAspect: Double,
    val nalLengthSize: Int,
    val parameterSetsAnnexB: ByteArray,
    val bitDepth: Int,
    val colour: ContainerColour,
    val dolbyVision: DolbyVisionConfig?,
    /** Matroska track number (0 for MP4, the stream index for AVI). */
    val trackNumber: Long,
    /** Decoder extradata for non-NAL codecs (VOL / sequence header / av1C / BITMAPINFOHEADER tail). */
    val extradata: ByteArray = ByteArray(0),
)

/**
 * Absolute byte offsets. MKV: [offset] = cluster start, [relPos] = CueRelativePosition (-1 when absent),
 * [size] = -1. MP4: [offset] = sample start, [size] = sample size, [relPos] = -1.
 */
internal class KeyframeIndex(
    val ptsUs: LongArray,
    val offset: LongArray,
    val relPos: LongArray,
    val size: IntArray,
) {
    val count: Int get() = ptsUs.size

    fun nearest(timeUs: Long): Int {
        if (count == 0) return -1
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ptsUs[mid] < timeUs) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && timeUs - ptsUs[lo - 1] <= ptsUs[lo] - timeUs) return lo - 1
        return lo
    }
}

/** Order is persisted (KeyframeIndexCodec): append only. AVI: [KeyframeIndex.offset] = chunk data, size exact. */
internal enum class ContainerKind { MATROSKA, MP4, AVI }

internal class MediaIndex(
    val kind: ContainerKind,
    val fileLength: Long,
    val durationUs: Long,
    val video: VideoTrack,
    val keyframes: KeyframeIndex,
    /** Matroska: absolute offset of the Segment's data (cluster positions are relative to it). */
    val segmentDataStart: Long = 0L,
    /** Matroska TimecodeScale in ns (block/cluster timecodes -> time). */
    val timecodeScaleNs: Long = 1_000_000L,
)

internal class UnsupportedMediaException(message: String) : IOException(message)
