// SPDX-License-Identifier: GPL-3.0-only
// Adapted from ysosrs123/NuvioTV-Fork (Nuvio Arc), commit ae6212ef58d25df819a716f2a38314732e719a2c.
// Original container/keyframe/colour implementation by ysosrs123; see NOTICE.md in this package.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Fetches one indexed keyframe and returns it ready for the decoder (H.264/HEVC as Annex-B with the track's parameter
 * sets prepended). Reads ask only for the keyframe's own bytes (Matroska: one block, sized from recent keyframes),
 * never more than [ThumbReads.MAX_READ] at once, straight into a [ThumbBuffer] where the Annex-B framing is done in
 * place.
 * Not thread-safe: one instance per fetch under way.
 */
internal class KeyframeExtractor(
    private val index: MediaIndex,
    private val reader: RangeReader,
    private val memory: ThumbMemory = NativeThumbMemory,
) {
    private companion object {
        const val TAG = "ThumbWorker"
        const val ID_SIMPLEBLOCK = 0xA3L
        const val ID_BLOCKGROUP = 0xA0L
        const val ID_BLOCK = 0xA1L
        const val ID_REFBLOCK = 0xFBL
        const val ID_TIMECODE = 0xE7L
        const val MIN_WINDOW = 256 * 1024
        const val MAX_WINDOW = ThumbReads.MAX_READ
        const val MAX_KEYFRAME = ThumbReads.MAX_READ
        /** Enough for any EBML element header, and a block's track number, timecode and flags. */
        const val PEEK = 16
        /** Without CueRelativePosition the cluster is walked in reads growing to this, [MAX_CLUSTER_SCAN] in all. */
        const val SCAN_STEP = 4 * 1024 * 1024
        const val MAX_CLUSTER_SCAN = 32 * 1024 * 1024
        const val MAX_SCAN_READS = 9
        val NO_BYTES = ByteArray(0)
    }

    // Recent span (block start to keyframe end), sizes the next window. A new maximum is taken at once and decays 1/8
    // per keyframe, so one large keyframe does not inflate every later read.
    private var largestSeen = 0
    /** Moves inside a buffer go through this, so overlapping ranges are safe whatever ByteBuffer.put does. */
    private val scratch = ByteArray(64 * 1024)
    /** Matroska cluster header length (id + size field), learned from the last cluster start read. */
    private var clusterHeaderLen = -1
    var bytesRead = 0L
        private set
    var requests = 0
        private set

    private fun window(): Int = (largestSeen * 5 / 4 + 64 * 1024).coerceIn(MIN_WINDOW, MAX_WINDOW)

    private fun noteSpan(span: Int) {
        largestSeen = maxOf(span, largestSeen - largestSeen / 8)
    }

    /** Reads [length] bytes at file [offset] into [out] at [at]; the room must be there already. */
    private fun readInto(out: ThumbBuffer, at: Int, offset: Long, length: Int): Int {
        requests++
        val dst = out.data.duplicate()
        dst.clear()
        dst.position(at)
        dst.limit(at + length)
        val n = reader.readInto(offset, length, dst)
        bytesRead += n
        return n
    }

    private fun tooLarge(what: String, bytes: Long) {
        Log.w(TAG, "$what is ${bytes / 1024} KB, above the ${MAX_KEYFRAME / (1024 * 1024)} MB read limit: skipped")
    }

    /** Keyframe [i] ready for the decoder, or null when it cannot be located. */
    fun extract(i: Int): ByteArray? {
        val buf = ThumbBuffer.single(memory)
        try {
            return extractAu(i, buf)?.toByteArray()
        } finally {
            buf.release()
        }
    }

    /** Keyframe [i] in [out]; the AU hands [out] back on release. Null when it cannot be located ([out] kept). */
    fun extractAu(i: Int, out: ThumbBuffer): ThumbAu? {
        val n = extractInto(i, out)
        return if (n > 0) ThumbAu(out.data, n, out) else null
    }

    /** Keyframe [i] written to [out] from offset 0; its length, or -1 when it cannot be located or is too large. */
    fun extractInto(i: Int, out: ThumbBuffer): Int {
        val kf = index.keyframes
        val video = index.video
        val prefix = if (video.codec.nal && index.kind != ContainerKind.AVI) video.parameterSetsAnnexB else NO_BYTES
        if (prefix.size > ThumbReads.MAX_PREFIX) return -1
        val base = prefix.size
        val start: Int
        val end: Int
        when (index.kind) {
            ContainerKind.MP4, ContainerKind.AVI -> {
                val size = kf.size[i]
                if (size <= 0) return -1
                if (size > MAX_KEYFRAME) {
                    tooLarge("keyframe $i", size.toLong())
                    return -1
                }
                out.ensure(base + size) ?: return -1
                if (readInto(out, base, kf.offset[i], size) != size) return -1
                start = base
                end = base + size
            }
            ContainerKind.MATROSKA -> {
                val f = mkvKeyframe(out, base, kf.offset[i], kf.relPos[i], kf.ptsUs[i]) ?: return -1
                if (f.frameStart < 0) return -1
                start = f.frameStart
                end = f.frameEnd
            }
        }
        val data = out.data
        return when {
            !video.codec.nal -> {                                       // raw sample + extradata
                move(data, start, 0, end - start, scratch)
                end - start
            }
            index.kind == ContainerKind.AVI || video.nalLengthSize != 4 -> throughArray(out, start, end)
            else -> annexBInPlace(data, prefix, start, end)
        }
    }

    /** Annex-B in AVI chunks and NAL lengths other than 4 bytes (rare): converted through one keyframe-sized array. */
    private fun throughArray(out: ThumbBuffer, start: Int, end: Int): Int {
        val sample = ByteArray(end - start)
        val src = out.data.duplicate()
        src.clear()
        src.position(start)
        src.limit(end)
        src.get(sample)
        val au = (if (index.kind == ContainerKind.AVI) tapSampleToAnnexB(sample, index.video) else toAnnexB(sample))
            ?: return -1
        val data = out.ensure(au.size) ?: return -1
        val dst = data.duplicate()
        dst.clear()
        dst.put(au)
        return au.size
    }

    /**
     * 4-byte NAL lengths -> start codes, written over the sample in [data] at [start, end), parameter sets at 0.
     * The write position never passes the read position, so no second buffer is needed. Dolby Vision: the enhancement
     * layer (63) is dropped, the RPU (62) kept, profile 5 needs it for reshaping.
     */
    private fun annexBInPlace(data: ByteBuffer, prefix: ByteArray, start: Int, end: Int): Int {
        val hevc = index.video.codec == VideoCodec.HEVC
        val head = data.duplicate()
        head.clear()
        head.put(prefix)
        var w = prefix.size
        var p = start
        var nals = 0
        while (p + 4 <= end) {
            var len = 0L
            for (k in 0 until 4) len = (len shl 8) or (data.get(p + k).toLong() and 0xFF)
            p += 4
            if (len <= 0 || p + len > end) break
            val l = len.toInt()
            val first = data.get(p).toInt()
            val type = if (hevc) (first shr 1) and 0x3F else first and 0x1F
            if (!(hevc && type == 63)) {
                data.put(w, 0)
                data.put(w + 1, 0)
                data.put(w + 2, 0)
                data.put(w + 3, 1)
                move(data, p, w + 4, l, scratch)
                w += 4 + l
                nals++
            }
            p += l
        }
        return if (nals > 0) w else -1
    }

    /** A located block: the frame at [frameStart, frameEnd) when it is a complete video keyframe, else -1. */
    private class Found(val frameStart: Int, val frameEnd: Int, val end: Int, val incompleteUntil: Int) {
        val isFrame: Boolean get() = frameStart >= 0
    }

    private class Head(val id: Long, val start: Int, val dataStart: Int, val size: Long)

    private val tooLargeBlock = Found(-1, -1, 0, 0)
    private var tooLargeSpan = 0
    /** Bytes at the buffer's base after the last [blockAt]. */
    private var held = 0

    private fun peek(data: ByteBuffer, at: Int, limit: Int): ByteArray {
        val n = minOf(PEEK, limit - at).coerceAtLeast(0)
        val b = ByteArray(n)
        val src = data.duplicate()
        src.clear()
        src.position(at)
        src.get(b)
        return b
    }

    private fun head(data: ByteBuffer, p: Int, limit: Int): Head? {
        if (p >= limit) return null
        val h = peek(data, p, limit)
        val id = Ebml.readId(h, 0) ?: return null
        val size = Ebml.readSize(h, id[1].toInt()) ?: return null
        return Head(id[0], p, p + id[1].toInt() + size[1].toInt(), size[0])
    }

    /** Complete children of [start, end). Stops quietly at the first incomplete element. */
    private fun children(data: ByteBuffer, start: Int, end: Int): List<Ebml.Element> {
        val out = ArrayList<Ebml.Element>()
        var p = start
        while (p < end) {
            val h = head(data, p, end) ?: break
            val dataEnd: Long = if (h.size == Ebml.UNKNOWN_SIZE) end.toLong() else h.dataStart + h.size
            if (dataEnd > end || dataEnd < h.dataStart) break
            out.add(Ebml.Element(h.id, p, h.dataStart, dataEnd.toInt()))
            p = dataEnd.toInt()
        }
        return out
    }

    /** Parses a SimpleBlock/BlockGroup element at [p], with valid bytes up to [limit]. */
    private fun parseBlockAt(data: ByteBuffer, p: Int, limit: Int): Found? {
        val h = head(data, p, limit) ?: return null
        if (h.size == Ebml.UNKNOWN_SIZE) return null
        if (h.id != ID_SIMPLEBLOCK && h.id != ID_BLOCKGROUP) return null
        val endL = h.dataStart + h.size
        if (endL > limit) return Found(-1, -1, 0, endL.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val end = endL.toInt()
        val frame = if (h.id == ID_SIMPLEBLOCK) {
            blockFrame(data, h.dataStart, end, keyFromFlags = true, keyByGroup = false)
        } else {
            var bStart = -1
            var bEnd = -1
            var hasRef = false
            for (c in children(data, h.dataStart, end)) {
                if (c.id == ID_BLOCK) { bStart = c.dataStart; bEnd = c.dataEnd }
                else if (c.id == ID_REFBLOCK) hasRef = true
            }
            if (bStart < 0) null else blockFrame(data, bStart, bEnd, keyFromFlags = false, keyByGroup = !hasRef)
        }
        return Found(frame?.first ?: -1, frame?.second ?: -1, end, 0)
    }

    /** Block payload range for the video track when it is an unlaced keyframe; else null. */
    private fun blockFrame(
        data: ByteBuffer, a: Int, e: Int, keyFromFlags: Boolean, keyByGroup: Boolean,
    ): Pair<Int, Int>? {
        val tn = Ebml.readSize(peek(data, a, e), 0) ?: return null     // track number is a size-style vint
        if (tn[0] != index.video.trackNumber) return null
        val flagsAt = a + tn[1].toInt() + 2
        if (flagsAt >= e) return null
        val flags = data.get(flagsAt).toInt() and 0xFF
        if (flags and 0x06 != 0) return null              // laced: never for video keyframes in practice
        val key = if (keyFromFlags) flags and 0x80 != 0 else keyByGroup
        if (!key) return null
        return (flagsAt + 1) to e
    }

    private fun mkvKeyframe(out: ThumbBuffer, base: Int, clusterAbs: Long, relPos: Long, ptsUs: Long): Found? {
        // Old muxers (mkvmerge 2.x) write no CueRelativePosition and put the keyframe deep in large clusters.
        if (relPos < 0) return scanCluster(out, base, clusterAbs, ptsUs)
        val tried = clusterHeaderLen
        if (tried <= 0) return firstInCluster(out, base, clusterAbs, relPos)
        val jumpAt = clusterAbs + tried + relPos
        val f = blockAt(out, base, jumpAt, have = 0)
        if (f != null && f.isFrame) return f
        // Not a video keyframe there: this cluster's size field may be longer or shorter than the last one's.
        val hdr = clusterHeaderLength(clusterAbs) ?: return null
        if (hdr == tried) return f.result(jumpAt)
        clusterHeaderLen = hdr
        val start = clusterAbs + hdr + relPos
        return blockAt(out, base, start, realign(out, base, start, hdr - tried)).result(start)
    }

    /** First keyframe of this extractor: the cluster start is read, which may hold the block already. */
    private fun firstInCluster(out: ThumbBuffer, base: Int, clusterAbs: Long, relPos: Long): Found? {
        val w = window()
        out.ensure(base + w) ?: return null
        val n = readInto(out, base, clusterAbs, w)
        val data = out.data
        val ch = clusterHead(data, base, base + n) ?: return null
        val hdr = ch.dataStart - base
        clusterHeaderLen = hdr
        val start = clusterAbs + hdr + relPos
        val at = base + hdr + relPos
        if (at + PEEK <= base + n) {
            // The block starts in this read: it goes to the front and only its missing rest is read.
            val i = at.toInt()
            move(data, i, base, base + n - i, scratch)
            return blockAt(out, base, start, base + n - i).result(start)
        }
        return blockAt(out, base, start, have = 0).result(start)
    }

    private fun Found?.result(start: Long): Found? {
        if (this === tooLargeBlock) tooLarge("block at $start", tooLargeSpan.toLong())
        return this?.takeIf { it.isFrame }
    }

    private fun clusterHead(data: ByteBuffer, p: Int, limit: Int): Head? {
        val h = head(data, p, limit) ?: return null
        return if (h.id == MkvIndexReader.ID_CLUSTER) h else null
    }

    /** Id plus size field of the cluster at [clusterAbs], from a 16-byte read. */
    private fun clusterHeaderLength(clusterAbs: Long): Int? {
        requests++
        val h = reader.read(clusterAbs, PEEK)
        bytesRead += h.size
        return try {
            val id = Ebml.readId(h, 0) ?: return null
            if (id[0] != MkvIndexReader.ID_CLUSTER) return null
            val size = Ebml.readSize(h, id[1].toInt()) ?: return null
            (id[1] + size[1]).toInt()
        } catch (e: UnsupportedMediaException) {
            null
        }
    }

    /**
     * The bytes at [base] were read for a start [delta] bytes off [start]: they are moved to fit [start], reading the
     * few bytes in front if needed. Returns how many now lie at [base] for [start], 0 when they are of no use.
     */
    private fun realign(out: ThumbBuffer, base: Int, start: Long, delta: Int): Int {
        val n = held
        if (n <= PEEK) return 0
        if (delta > 0) {
            if (delta >= n - PEEK) return 0
            move(out.data, base + delta, base, n - delta, scratch)
            return n - delta
        }
        val d = -delta
        val data = out.ensure(base + n + d, keep = base + n) ?: return 0
        move(data, base, base + d, n, scratch)
        if (readInto(out, base, start, d) < d) return 0
        return n + d
    }

    /** A misplaced jump can land on bytes that are no EBML at all: that is "not here", not a broken file. */
    private fun tryParse(data: ByteBuffer, p: Int, limit: Int): Found? =
        try {
            parseBlockAt(data, p, limit)
        } catch (e: UnsupportedMediaException) {
            null
        }

    /**
     * The block at file offset [start], whose first [have] bytes are already at [base] (0 = read a window there).
     * Only the missing rest of the block is read; a block above [MAX_KEYFRAME] is not. [held] is left at the number
     * of bytes at [base].
     */
    private fun blockAt(out: ThumbBuffer, base: Int, start: Long, have: Int): Found? {
        var n = have
        held = n
        if (n <= 0) {
            val w = window()
            out.ensure(base + w) ?: return null
            n = readInto(out, base, start, w)
            held = n
            if (n <= 0) return null
        }
        val f = tryParse(out.data, base, base + n) ?: return null
        if (f.isFrame) {
            noteSpan(f.end - base)
            return f
        }
        if (f.incompleteUntil <= 0) return f
        val span = f.incompleteUntil - base
        if (span > MAX_KEYFRAME) {
            tooLargeSpan = span
            return tooLargeBlock
        }
        out.ensure(base + span, keep = base + n) ?: return null
        val more = readInto(out, base + n, start + n, span - n)
        held = n + more
        if (more < span - n) return null
        val g = tryParse(out.data, base, base + span) ?: return null
        if (g.isFrame) noteSpan(span)
        return g
    }

    /**
     * Walks a cluster from its start for the first video keyframe within 1 ms of [ptsUs]. Holds one element at a time:
     * an element that is not needed is stepped over, its bytes not read when it ends beyond what is in the buffer.
     */
    private fun scanCluster(out: ThumbBuffer, base: Int, clusterAbs: Long, ptsUs: Long): Found? {
        var step = window()
        out.ensure(base + step) ?: return null
        var limit = base + readInto(out, base, clusterAbs, step)
        var reads = 1
        var scanned = (limit - base).toLong()
        var fileAt = clusterAbs                       // file offset of buffer index [base]
        val ch = clusterHead(out.data, base, limit) ?: return null
        clusterHeaderLen = ch.dataStart - base
        val clusterEnd = if (ch.size == Ebml.UNKNOWN_SIZE) Long.MAX_VALUE else clusterAbs + clusterHeaderLen + ch.size
        var p = ch.dataStart
        var clusterTc = 0L
        while (true) {
            if (fileAt + (p - base) >= clusterEnd) return null
            val h = if (p < limit) head(out.data, p, limit) else null
            var need = 0                              // bytes wanted at [base] after a refill
            if (h != null) {
                if (h.id == MkvIndexReader.ID_CLUSTER) return null       // next cluster: not in this one
                if (h.size == Ebml.UNKNOWN_SIZE) return null
                val endL = h.dataStart + h.size
                val elementLen = endL - p
                val wanted = h.id == ID_TIMECODE || h.id == ID_SIMPLEBLOCK || h.id == ID_BLOCKGROUP
                if (wanted && elementLen <= MAX_KEYFRAME) {
                    if (endL <= limit) {
                        val data = out.data
                        if (h.id == ID_TIMECODE) {
                            val tc = peek(data, h.dataStart, endL.toInt())
                            clusterTc = Ebml.uint(tc, 0, tc.size)
                        } else {
                            val f = parseBlockAt(data, p, limit)
                            if (f != null && f.isFrame && blockTimeMatches(data, h, endL.toInt(), clusterTc, ptsUs)) {
                                noteSpan(f.end - p)
                                return f
                            }
                        }
                        p = endL.toInt()
                        continue
                    }
                    need = elementLen.toInt()
                } else {
                    if (endL <= limit) {
                        p = endL.toInt()
                        continue
                    }
                    // Not needed and running past the buffer: carry on at its end without reading it.
                    fileAt += endL - base
                    p = base
                    limit = base
                }
            }
            if (scanned >= MAX_CLUSTER_SCAN || reads >= MAX_SCAN_READS) return null
            val keep = (limit - p).coerceAtLeast(0)
            move(out.data, p, base, keep, scratch)
            fileAt += p - base
            p = base
            limit = base + keep
            step = minOf(step * 2, SCAN_STEP)
            val want = (maxOf(need, keep + step) - keep)
                .coerceAtMost(ThumbReads.BUFFER_MAX - base - keep).coerceAtMost(ThumbReads.MAX_READ)
            out.ensure(base + keep + want, keep = base + keep) ?: return null
            val got = readInto(out, base + keep, fileAt + keep, want)
            if (got <= 0) return null
            limit += got
            scanned += got
            reads++
        }
    }

    private fun blockTimeMatches(data: ByteBuffer, h: Head, end: Int, clusterTc: Long, ptsUs: Long): Boolean {
        var a = h.dataStart
        if (h.id == ID_BLOCKGROUP) {
            val block = children(data, a, end).firstOrNull { it.id == ID_BLOCK } ?: return false
            a = block.dataStart
        }
        val b = peek(data, a, end)
        val tn = Ebml.readSize(b, 0) ?: return false
        val t = tn[1].toInt()
        if (t + 1 >= b.size) return false
        val rel = ((b[t].toInt() shl 8) or (b[t + 1].toInt() and 0xFF)).toShort().toLong()
        val us = (clusterTc + rel) * index.timecodeScaleNs / 1000L
        return kotlin.math.abs(us - ptsUs) <= 1_000L
    }

    private fun toAnnexB(sample: ByteArray): ByteArray? {
        val n = index.video.nalLengthSize
        val hevc = index.video.codec == VideoCodec.HEVC
        val out = ByteArrayOutputStream(sample.size + index.video.parameterSetsAnnexB.size + 64)
        out.write(index.video.parameterSetsAnnexB)
        var p = 0
        var nals = 0
        while (p + n <= sample.size) {
            var len = 0L
            for (k in 0 until n) len = (len shl 8) or (sample[p + k].toLong() and 0xFF)
            p += n
            if (len <= 0 || p + len > sample.size) break
            val type = if (hevc) (sample[p].toInt() shr 1) and 0x3F else sample[p].toInt() and 0x1F
            // Dolby Vision: drop the enhancement layer (63), keep the RPU (62), profile 5 needs it for reshaping.
            if (!(hevc && type == 63)) {
                out.write(0); out.write(0); out.write(0); out.write(1)
                out.write(sample, p, len.toInt())
                nals++
            }
            p += len.toInt()
        }
        return if (nals > 0) out.toByteArray() else null
    }
}

/**
 * Moves [len] bytes inside [data] from [from] to [to] through [scratch], front to back when moving down and back to
 * front when moving up, so overlapping ranges are fine.
 */
internal fun move(data: ByteBuffer, from: Int, to: Int, len: Int, scratch: ByteArray) {
    if (from == to || len <= 0) return
    val src = data.duplicate()
    val dst = data.duplicate()
    src.clear()
    dst.clear()
    var done = 0
    while (done < len) {
        val n = minOf(scratch.size, len - done)
        val at = if (to < from) done else len - done - n
        src.position(from + at)
        src.get(scratch, 0, n)
        dst.position(to + at)
        dst.put(scratch, 0, n)
        done += n
    }
}

/**
 * A keyframe sample as the player's extractor wrote it (Annex-B or length-prefixed) -> Annex-B access unit with
 * the index's parameter sets first and the Dolby Vision enhancement layer (HEVC NAL type 63) removed.
 */
internal fun tapSampleToAnnexB(sample: ByteArray, video: VideoTrack): ByteArray? {
    val hevc = video.codec == VideoCodec.HEVC
    val out = ByteArrayOutputStream(sample.size + video.parameterSetsAnnexB.size + 64)
    out.write(video.parameterSetsAnnexB)
    var nals = 0
    fun emit(from: Int, to: Int) {
        if (to <= from) return
        val type = if (hevc) (sample[from].toInt() shr 1) and 0x3F else sample[from].toInt() and 0x1F
        if (hevc && type == 63) return
        out.write(0); out.write(0); out.write(0); out.write(1)
        out.write(sample, from, to - from)
        nals++
    }
    val annexB = sample.size >= 4 && sample[0].toInt() == 0 && sample[1].toInt() == 0 &&
        (sample[2].toInt() == 1 || (sample[2].toInt() == 0 && sample[3].toInt() == 1))
    if (annexB) {
        // NAL payloads lie between start codes (00 00 01, optionally preceded by 00).
        var i = 0
        var nalStart = -1
        while (i + 2 < sample.size) {
            if (sample[i].toInt() == 0 && sample[i + 1].toInt() == 0 && sample[i + 2].toInt() == 1) {
                if (nalStart >= 0) {
                    var end = i
                    while (end > nalStart && sample[end - 1].toInt() == 0) end--   // trailing zero of a 4-byte code
                    emit(nalStart, end)
                }
                i += 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart >= 0) emit(nalStart, sample.size)
    } else {
        val n = video.nalLengthSize
        var p = 0
        while (p + n <= sample.size) {
            var len = 0L
            for (k in 0 until n) len = (len shl 8) or (sample[p + k].toLong() and 0xFF)
            p += n
            if (len <= 0 || p + len > sample.size) break
            emit(p, p + len.toInt())
            p += len.toInt()
        }
    }
    return if (nals > 0) out.toByteArray() else null
}
