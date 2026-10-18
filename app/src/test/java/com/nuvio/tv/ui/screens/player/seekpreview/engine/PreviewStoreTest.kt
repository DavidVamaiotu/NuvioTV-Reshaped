package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PreviewStoreTest {
    @Test fun reopensImagesLazilyWithTheirRealTimestampAndProvenance() {
        val directory = Files.createTempDirectory("preview-store").toFile()
        try {
            PreviewStore(directory, "release-a", 1_000_000).use { it.put(5_400_000, 5_399_800, PreviewOrigin.ORIGINAL, byteArrayOf(1, 2, 3)) }
            PreviewStore(directory, "release-a", 1_000_000).use {
                val entry = it.nearest(5_400_000)!!
                assertEquals(5_399_800L, entry.timeMs)
                assertEquals(PreviewOrigin.ORIGINAL, entry.origin)
                assertArrayEquals(byteArrayOf(1, 2, 3), it.read(entry))
            }
            PreviewStore(directory, "release-b", 1_000_000).use { assertNull(it.nearest(5_400_000)) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun tornDataDoesNotProduceAnImageFromAnotherOffset() {
        val directory = Files.createTempDirectory("preview-torn").toFile()
        try {
            PreviewStore(directory, "a", 1_000_000).use { it.put(0, 0, PreviewOrigin.PLAYBACK, byteArrayOf(1, 2, 3)) }
            directory.listFiles()!!.first { it.extension == "frames" }.writeBytes(byteArrayOf(1))
            PreviewStore(directory, "a", 1_000_000).use { assertNull(it.nearest(0)) }
        } finally { directory.deleteRecursively() }
    }
}
