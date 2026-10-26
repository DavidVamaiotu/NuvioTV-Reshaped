package com.nuvio.tv.ui.screens.player.seekpreview

import android.graphics.Bitmap
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekPreviewFilmstripTest {
    private val bitmap = mockk<Bitmap>()
    private fun frame(at: Long) = SeekPreviewThumbnail(bitmap, at, at + 30_000,
        actualFrameMs = at, origin = "ORIGINAL")

    @Test fun `filmstrip walks actual neighbors in both directions without probing overlapping slots`() = runBlocking {
        val times = listOf(4_000L, 16_000L, 24_000L, 36_000L, 44_000L)
        val calls = mutableListOf<Pair<Long, Boolean>>()
        val track = object : SeekPreviewTrack {
            override var offsetMs = 0L
            override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? = error("side work must not steer generation")
            override suspend fun neighborThumbnailFor(frame: SeekPreviewThumbnail, forward: Boolean): SeekPreviewThumbnail? {
                calls += frame.cueStartMs to forward
                val index = times.indexOf(frame.cueStartMs) + if (forward) 1 else -1
                return times.getOrNull(index)?.let { this@SeekPreviewFilmstripTest.frame(it) }
            }
        }
        val strip = track.fillFilmstrip(Filmstrip.around(frame(24_000), null, null, 1, 0), true)
        assertEquals(times, strip.frames.map { it?.thumbnail?.cueStartMs })
        assertEquals(listOf(24_000L to true, 24_000L to false, 36_000L to true, 16_000L to false), calls)
    }

    @Test fun `a disk miss is retried when new frames arrive`() = runBlocking {
        var ready = false
        val track = object : SeekPreviewTrack {
            override var offsetMs = 0L
            override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? = null
            override suspend fun neighborThumbnailFor(frame: SeekPreviewThumbnail, forward: Boolean): SeekPreviewThumbnail? =
                if (ready && !forward && frame.cueStartMs == 24_000L) this@SeekPreviewFilmstripTest.frame(16_000) else null
        }
        val first = track.fillFilmstrip(Filmstrip.around(frame(24_000), null, null, 1, 0), true)
        assertNull(first.frames[1]?.thumbnail)
        ready = true
        val next = Filmstrip.around(frame(24_000), first, 0, 2, 0)
        assertEquals(16_000L, track.fillFilmstrip(next, true).frames[1]?.thumbnail?.cueStartMs)
    }

    @Test fun `external cue neighbors keep their preview sync offset`() = runBlocking {
        val probes = mutableListOf<Long>()
        val track = object : SeekPreviewTrack {
            override var offsetMs = 2_000L
            override suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail? {
                probes += positionMs
                return if (positionMs < 12_000) SeekPreviewThumbnail(bitmap, 2_000, 12_000)
                    else SeekPreviewThumbnail(bitmap, 22_000, 32_000)
            }
        }
        val center = SeekPreviewThumbnail(bitmap, 12_000, 22_000)
        assertEquals(2_000L, track.neighborThumbnailFor(center, false)?.cueStartMs)
        assertEquals(22_000L, track.neighborThumbnailFor(center, true)?.cueStartMs)
        assertEquals(listOf(9_999L, 20_000L), probes)
    }

    @Test fun `neighbors publish before the complete strip finishes`() = runBlocking {
        val snapshots=mutableListOf<List<Long?>>()
        val track=object:SeekPreviewTrack {
            override var offsetMs=0L
            override suspend fun thumbnailFor(positionMs:Long):SeekPreviewThumbnail?=null
            override suspend fun neighborThumbnailFor(frame:SeekPreviewThumbnail,forward:Boolean)=
                this@SeekPreviewFilmstripTest.frame(frame.cueStartMs+if(forward) 10_000 else -10_000)
        }
        track.fillFilmstrip(Filmstrip.around(frame(30_000),null,null,1,0),false,
            onProgress={snapshots+=it.frames.map {f->f?.thumbnail?.cueStartMs}})
        assertEquals(4,snapshots.size)
        assertEquals(listOf(null,null,30_000L,40_000L,null),snapshots.first())
        assertEquals(listOf(10_000L,20_000L,30_000L,40_000L,50_000L),snapshots.last())
    }
    @Test fun `new center stops remaining work without publishing obsolete neighbors`() = runBlocking {
        var current=true
        var calls=0
        var published=0
        val track=object:SeekPreviewTrack {
            override var offsetMs=0L
            override suspend fun thumbnailFor(positionMs:Long):SeekPreviewThumbnail?=null
            override suspend fun neighborThumbnailFor(frame:SeekPreviewThumbnail,forward:Boolean):SeekPreviewThumbnail? {
                calls++;current=false
                return this@SeekPreviewFilmstripTest.frame(40_000)
            }
        }
        track.fillFilmstrip(Filmstrip.around(frame(30_000),null,null,1,0),false,
            stillCurrent={current},onProgress={published++})
        assertEquals(1,calls)
        assertEquals(0,published)
    }
}
