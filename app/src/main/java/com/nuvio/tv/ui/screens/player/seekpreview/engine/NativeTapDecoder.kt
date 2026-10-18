package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.graphics.Bitmap
import android.media.MediaFormat

/** Applies the same colour pipeline to already-buffered keyframes as independent range previews. */
internal object NativeTapDecoder {
    fun decode(format: MediaFormat, sample: ByteArray): Bitmap? {
        if (!ThumbNative.available) return null
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        val codec = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> VideoCodec.H264
            MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION -> VideoCodec.HEVC
            "video/av01" -> VideoCodec.AV1
            "video/mp4v-es" -> VideoCodec.MPEG4
            "video/mpeg2" -> VideoCodec.MPEG2
            else -> return null
        }
        fun int(key: String): Int = if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrDefault(0) else 0
        val standard = int(MediaFormat.KEY_COLOR_STANDARD)
        val transfer = when (int(MediaFormat.KEY_COLOR_TRANSFER)) { 6 -> 16; 7 -> 18; 3 -> 1; else -> 0 }
        val range = when (int(MediaFormat.KEY_COLOR_RANGE)) { 1 -> 2; 2 -> 1; else -> 0 }
        val init = (0..2).mapNotNull { i -> format.getByteBuffer("csd-$i")?.duplicate()?.let { buffer ->
            if (buffer.remaining() > ThumbReads.MAX_PREFIX) return null
            ByteArray(buffer.remaining()).also(buffer::get)
        } }.fold(ByteArray(0)) { all, bytes -> all + bytes }
        if (init.size > ThumbReads.MAX_PREFIX) return null
        val dvProfile = format.getString("reshaped-codecs")?.split('.')?.getOrNull(1)?.toIntOrNull()
        val track = VideoTrack(codec, int(MediaFormat.KEY_WIDTH), int(MediaFormat.KEY_HEIGHT), 0.0, 4,
            if (codec.nal) init else ByteArray(0), if (transfer in setOf(16, 18)) 10 else 8,
            ContainerColour(transfer, if (standard == 6) 9 else 0, if (standard == 6) 9 else 0, range),
            if (mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION && dvProfile != null) DolbyVisionConfig(dvProfile, 0, dvProfile == 7) else null,
            0, if (codec.nal) ByteArray(0) else init)
        val bytes = if (codec.nal) tapSampleToAnnexB(sample, track) ?: return null else sample
        FfmpegThumbDecoder().use { decoder ->
            val frame = decoder.decode(bytes, track, 1) ?: return null
            if (dvProfile == 5 && !frame.colour.doviIpt) return null // Never show undecoded profile-5 IPT as SDR.
            val colour = ColourClassifier.classify(track.colour, frame.colour, track.dolbyVision)
            val size = ThumbGeometry.outputSize(frame.width, frame.height, frame.sarNum, frame.sarDen, 0.0)
            val width = minOf(320, size[0])
            val height = ((size[1].toLong() * width / size[0]).toInt() and -2).coerceAtLeast(2)
            return decoder.render(frame, colour, width, height)
        }
    }
}
