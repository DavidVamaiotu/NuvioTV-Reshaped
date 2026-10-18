package com.nuvio.tv.ui.screens.player.seekpreview.engine

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PreviewStoreTest {
    @Test fun bitmapKeysSeparateProvidersAndReplacedPicturesInTheSameSlot() {
        val directory = Files.createTempDirectory("preview-cache-key").toFile()
        try {
            PreviewStore(directory, "original", 1_000_000).use { original ->
                PreviewStore(directory, "server", 1_000_000).use { server ->
                    original.put(0, 0, PreviewOrigin.ORIGINAL, byteArrayOf(1))
                    server.put(0, 0, PreviewOrigin.SERVER, byteArrayOf(2))
                    val before = original.imageKey(original.atSlot(0)!!)
                    assertNotEquals(before, server.imageKey(server.atSlot(0)!!))
                    original.put(0, 5, PreviewOrigin.ORIGINAL, byteArrayOf(3), replace = true)
                    assertNotEquals(before, original.imageKey(original.atSlot(0)!!))
                }
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun preservesTheServerCueIntervalInsteadOfPretendingEveryPreviewIsTenSeconds() {
        val directory = Files.createTempDirectory("preview-server").toFile()
        try {
            PreviewStore(directory, "server-file", 1_000_000).use {
                it.put(20_000, 0, PreviewOrigin.SERVER, byteArrayOf(1), endMs = 30_000)
                assertEquals(30_000L, it.nearest(20_000)!!.endMs)
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun overlappingSessionsShareOneManifestAndDoNotOverwriteEachOther() {
        val directory = Files.createTempDirectory("preview-shared").toFile()
        try {
            val first = PreviewStore(directory, "same-file", 1_000_000)
            val second = PreviewStore(directory, "same-file", 1_000_000)
            first.put(0, 0, PreviewOrigin.ORIGINAL, byteArrayOf(1))
            second.put(10_000, 10_000, PreviewOrigin.ORIGINAL, byteArrayOf(2))
            first.close(); second.close()
            PreviewStore(directory, "same-file", 1_000_000).use {
                assertArrayEquals(byteArrayOf(1), it.read(it.nearest(0)!!))
                assertArrayEquals(byteArrayOf(2), it.read(it.nearest(10_000)!!))
            }
        } finally { directory.deleteRecursively() }
    }
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
