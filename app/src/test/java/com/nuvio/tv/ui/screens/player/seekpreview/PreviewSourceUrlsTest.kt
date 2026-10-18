package com.nuvio.tv.ui.screens.player.seekpreview

import org.junit.Assert.*
import org.junit.Test

class PreviewSourceUrlsTest {
    @Test fun recognisesExactFileServerUrlsWithAPathPrefix() {
        assertEquals("release-2", previewServerSource("https://example.test/jellyfin/Videos/item-1/stream.mkv?MediaSourceId=release-2")?.mediaSourceId)
        assertEquals("release-3", previewServerSource("https://example.test/emby/videos/item_1/stream.mp4?mediaSourceId=release-3")?.mediaSourceId)
    }
    @Test fun recognisesHlsButNeverInventsAnExactMediaSource() {
        assertNotNull(previewServerSource("https://example.test/Videos/item-1/master.m3u8"))
        assertNull(previewServerSource("https://example.test/Videos/item-1/master.m3u8")?.mediaSourceId)
        assertNull(previewServerSource("https://example.test/Videos/item-1/stream.mkv?MediaSourceId=bad%2Fid")?.mediaSourceId)
    }
    @Test fun leavesOrdinaryFilesAndUnsupportedUrlsAlone() {
        for (url in listOf("https://cdn.test/videos/movie.mkv", "https://cdn.test/Videos/movie-file/trailer.mkv", "https://cdn.test/movie.mp4", "not a URL")) {
            assertNull(url, previewServerSource(url))
        }
    }
}
