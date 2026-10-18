// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Nuvio Arc / ysosrs123, ae6212ef; hardened cache parsing. See NOTICE.md.
package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Compact serialisation of a [MediaIndex] so a re-opened title needs no index reads. */
internal object KeyframeIndexCodec {
    /** A cached index with another version is discarded and read again. */
    private const val VERSION = 2

    fun encode(m: MediaIndex): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).use { s ->
            s.writeInt(VERSION)
            s.writeInt(m.kind.ordinal)
            s.writeLong(m.fileLength)
            s.writeLong(m.durationUs)
            s.writeLong(m.segmentDataStart)
            s.writeLong(m.timecodeScaleNs)
            val v = m.video
            s.writeInt(v.codec.ordinal); s.writeInt(v.width); s.writeInt(v.height); s.writeDouble(v.displayAspect)
            s.writeInt(v.nalLengthSize); s.writeInt(v.parameterSetsAnnexB.size); s.write(v.parameterSetsAnnexB)
            s.writeInt(v.bitDepth); s.writeLong(v.trackNumber)
            val c = v.colour
            s.writeInt(c.transfer); s.writeInt(c.primaries); s.writeInt(c.matrix); s.writeInt(c.range)
            s.writeInt(c.maxCll); s.writeInt(c.masteringMaxNits)
            val dv = v.dolbyVision
            s.writeBoolean(dv != null)
            if (dv != null) { s.writeInt(dv.profile); s.writeInt(dv.blCompatId); s.writeBoolean(dv.elPresent) }
            s.writeInt(v.extradata.size); s.write(v.extradata)
            val k = m.keyframes
            s.writeInt(k.count)
            for (i in 0 until k.count) {
                s.writeLong(k.ptsUs[i]); s.writeLong(k.offset[i]); s.writeLong(k.relPos[i]); s.writeInt(k.size[i])
            }
        }
        return bo.toByteArray()
    }

    fun decode(b: ByteArray): MediaIndex? = runCatching {
        DataInputStream(b.inputStream()).use { s ->
            if (s.readInt() != VERSION) return null
            val kind = ContainerKind.values()[s.readInt()]
            val fileLength = s.readLong()
            val duration = s.readLong()
            val segStart = s.readLong()
            val tcScale = s.readLong()
            val codec = VideoCodec.values()[s.readInt()]
            val w = s.readInt(); val h = s.readInt(); val aspect = s.readDouble()
            val nal = s.readInt(); val psSize = s.readInt(); require(psSize in 0..65536)
            val ps = ByteArray(psSize).also { s.readFully(it) }
            val bitDepth = s.readInt(); val track = s.readLong()
            val colour = ContainerColour(s.readInt(), s.readInt(), s.readInt(), s.readInt(), s.readInt(), s.readInt())
            val dv = if (s.readBoolean()) DolbyVisionConfig(s.readInt(), s.readInt(), s.readBoolean()) else null
            val extraSize = s.readInt(); require(extraSize in 0..65536)
            val extradata = ByteArray(extraSize).also { s.readFully(it) }
            val n = s.readInt(); require(n in 1..200000 && s.available() >= n * 28)
            require(w in 1..8192 && h in 1..8192 && fileLength > 0 && duration > 0)
            val pts = LongArray(n); val off = LongArray(n); val rel = LongArray(n); val size = IntArray(n)
            for (i in 0 until n) {
                pts[i] = s.readLong(); off[i] = s.readLong(); rel[i] = s.readLong(); size[i] = s.readInt()
                require(pts[i] >= 0 && off[i] in 0 until fileLength && (i == 0 || pts[i] >= pts[i - 1]))
            }
            MediaIndex(
                kind, fileLength, duration,
                VideoTrack(codec, w, h, aspect, nal, ps, bitDepth, colour, dv, track, extradata),
                KeyframeIndex(pts, off, rel, size), segStart, tcScale,
            )
        }
    }.getOrNull()
}
