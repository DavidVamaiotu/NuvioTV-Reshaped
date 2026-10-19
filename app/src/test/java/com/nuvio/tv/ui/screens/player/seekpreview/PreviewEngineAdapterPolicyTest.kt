package com.nuvio.tv.ui.screens.player.seekpreview

import org.junit.Assert.*
import org.junit.Test

class PreviewEngineAdapterPolicyTest {
    @Test fun directFileAndRedirectRoutesAreNotRejectedByFolderName() {
        for (path in listOf("/resolve/film", "/torrent/file.mkv", "/transcode/direct/film.mp4")) {
            assertTrue(previewSingleFileUrl("https://cdn.example" + path))
        }
        assertTrue(previewSingleFileUrl("https://cdn.example/file.mkv#list.m3u8"))
    }
    @Test fun adaptiveStreamsAndNonHttpInputsRemainExcluded() {
        for (url in listOf("https://cdn.example/MAIN.M3U8?token=x", "https://cdn.example/a.ism/Manifest", "https://cdn.example/manifest/", "file:///film.mkv")) {
            assertFalse(previewSingleFileUrl(url))
        }
    }
}
