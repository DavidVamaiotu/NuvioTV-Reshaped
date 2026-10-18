package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.graphics.Bitmap
import java.io.Closeable
import java.io.File

/** A verified single-file source. Its identity survives URL rotation, but never uses duration alone. */
internal class IndexedPreviewFile private constructor(
    val reader: PreviewRangeReader,
    val index: MediaIndex,
    val identity: String,
) : Closeable {
    private val extractor = KeyframeExtractor(index, reader)
    private val buffer = ThumbBuffer.single()
    private val decoder = FfmpegThumbDecoder()
    fun nearestTime(positionMs: Long): Long? = index.keyframes.nearest(positionMs * 1000)
        .takeIf { it >= 0 }?.let { index.keyframes.ptsUs[it] / 1000 }
    fun frame(positionMs: Long, width: Int): Bitmap? {
        reader.beginOperation()
        val key = index.keyframes.nearest(positionMs * 1000)
        if (key < 0) return null
        val unit = extractor.extractAu(key, buffer) ?: return null
        try {
            val frame = decoder.decode(unit, index.video, threads = 1) ?: return null
            if (index.video.dolbyVision?.profile == 5 && !frame.colour.doviIpt) return null
            val colour = ColourClassifier.classify(index.video.colour, frame.colour, index.video.dolbyVision)
            val size = ThumbGeometry.outputSize(frame.width, frame.height, frame.sarNum, frame.sarDen, index.video.displayAspect)
            val outW = minOf(width, size[0])
            val outH = (size[1].toLong() * outW / size[0]).toInt().coerceAtLeast(2) and -2
            return decoder.render(frame, colour, outW, outH)
        } finally {
            unit.release()
            // One working set only, even when the local tap's decoder is used next.
            decoder.close()
        }
    }
    fun cancel() = reader.cancel()
    override fun close() { reader.close(); decoder.close(); buffer.release() }
    companion object {
        fun open(directory: File, contentIdentity: String, url: String, headers: Map<String, String>, register: (PreviewRangeReader) -> Unit, permitted: () -> Boolean = { true }): IndexedPreviewFile {
            val reader = PreviewRangeReader(url, headers, permitted)
            register(reader)
            try {
                val head = reader.read(0, MkvIndexReader.HEAD_BYTES)
                require(reader.totalLength > 0)
                val identity = "$contentIdentity|${reader.totalLength}|${PreviewStore.digest(head)}"
                val file = File(directory, PreviewStore.digest(identity.toByteArray()) + ".media")
                directory.mkdirs()
                val cached = if (file.isFile && file.length() in 1..(8L * 1024 * 1024)) {
                    KeyframeIndexCodec.decode(file.readBytes())?.takeIf { it.fileLength == reader.totalLength }
                } else null
                val index = cached ?: when {
                    MkvIndexReader.looksLikeMatroska(head) -> MkvIndexReader.read(reader, head)
                    Mp4IndexReader.looksLikeMp4(head) -> Mp4IndexReader.read(reader, head)
                    AviIndexReader.looksLikeAvi(head) -> AviIndexReader.read(reader, head)
                    else -> throw UnsupportedMediaException("unsupported preview container")
                }
                require(index.keyframes.count in 1..200000 && index.video.width in 1..8192 && index.video.height in 1..8192)
                if (cached == null) runCatching {
                    val data = KeyframeIndexCodec.encode(index)
                    if (data.size <= 8 * 1024 * 1024) {
                        val temp = File(file.path + ".tmp")
                        temp.writeBytes(data)
                        temp.renameTo(file)
                    }
                }
                return IndexedPreviewFile(reader, index, identity)
            } catch (error: Throwable) { reader.close(); throw error }
        }
    }
}
