package com.nuvio.tv.ui.screens.player.seekpreview.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

/** Tiny real MP4 tables exercise the reader, including counts that formerly allocated/looped unboundedly. */
class Mp4IndexSafetyTest {
    private fun ints(vararg values: Int) = ByteBuffer.allocate(values.size * 4).apply { values.forEach { putInt(it) } }.array()
    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val bytes = body.fold(ByteArray(0)) { a, b -> a + b }
        return ints(bytes.size + 8) + type.toByteArray() + bytes
    }
    private fun file(overrides: Map<String, ByteArray> = emptyMap()): ByteArray {
        fun table(name: String, vararg values: Int) = box(name, overrides[name] ?: ints(*values))
        val sample = ByteArray(78).apply { this[25] = 64; this[27] = 36 }
        val tables = box("stbl", box("stsd", ints(0, 1), box("av01", sample, box("av1C", byteArrayOf(0x81.toByte(),0,0,0)))),
            table("stsz",0,3,3), table("stss",0,1,1), table("stco",0,1,100),
            table("stsc",0,1,1,3,1), table("stts",0,1,3,40), table("ctts",0,1,3,0))
        val mdia = box("mdia", box("hdlr",ints(0,0),"vide".toByteArray()),
            box("mdhd",ints(0,0,0,1000,120)), box("minf",tables))
        return box("moov",box("mvhd",ints(0,0,0,1000,120)),box("trak",mdia))
    }
    private fun read(file: ByteArray): MediaIndex {
        val reader = object : RangeReader {
            override val totalLength = file.size.toLong()
            override fun read(offset: Long, length: Int) = file.copyOfRange(offset.toInt(), minOf(file.size, offset.toInt() + length))
        }
        return Mp4IndexReader.read(reader,file)
    }
    @Test fun validSmallTablesRetainTheirExactKeyframeRange() {
        val index = read(file())
        assertEquals(1,index.keyframes.count)
        assertEquals(100L,index.keyframes.offset[0])
        assertEquals(3,index.keyframes.size[0])
        assertEquals(120_000L,index.durationUs)
        assertEquals(1,read(file(mapOf("ctts" to ints(0,0)))).keyframes.count)
    }
    @Test(timeout = 2000) fun rejectsOversizedAndTruncatedCountsBeforeAllocatingTables() {
        for ((name, bytes) in listOf(
            "stsz" to ints(0,3,Int.MAX_VALUE), "stsz" to ints(0,0,3),
            "stss" to ints(0,Int.MAX_VALUE), "stco" to ints(0,Int.MAX_VALUE),
            "stsc" to ints(0,Int.MAX_VALUE), "stts" to ints(0,Int.MAX_VALUE), "ctts" to ints(0,Int.MAX_VALUE))) {
            assertThrows("$name count",UnsupportedMediaException::class.java) { read(file(mapOf(name to bytes))) }
        }
    }
    @Test(timeout = 2000) fun rejectsRunCountsThatExceedTheSampleTable() {
        for ((name, bytes) in listOf("stsc" to ints(0,1,1,Int.MAX_VALUE,1),
            "stts" to ints(0,1,Int.MAX_VALUE,40), "ctts" to ints(0,1,Int.MAX_VALUE,0))) {
            assertThrows("$name run",UnsupportedMediaException::class.java) { read(file(mapOf(name to bytes))) }
        }
    }
}
