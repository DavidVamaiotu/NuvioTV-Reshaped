package com.nuvio.tv.updater

import com.nuvio.tv.updater.model.DownloadedUpdate
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedUpdateTest {
    private val buildTime = Instant.parse("2026-10-23T08:00:00Z").epochSecond
    private val versionCode = buildTime + 100_000_000L

    @Test
    fun `background cache keeps a newer beta when switching from tester`() {
        val downloaded = DownloadedUpdate(
            "1.1.0-beta.8-autosync.1", "/updates/beta.apk", "2026-10-23T09:00:00Z", true
        )
        assertTrue(downloaded.isNewerThan("1.1.0-beta.8-tester.9", versionCode))
        assertFalse(downloaded.isNewerThan(downloaded.tag, versionCode + 3600))
    }

    @Test
    fun `background cache keeps a newer tester when switching from beta`() {
        val downloaded = DownloadedUpdate(
            "1.1.0-beta.8-tester.1", "/updates/tester.apk", "2026-10-23T09:00:00Z", true
        )
        assertTrue(downloaded.isNewerThan("1.1.0-beta.8-autosync.9", versionCode))
    }

    @Test
    fun `background cache rejects older builds across channels and nuvio versions`() {
        val downloaded = DownloadedUpdate(
            "1.1.0-beta.8-tester.99", "/updates/tester.apk", "2026-10-23T07:00:00Z", true
        )
        assertFalse(downloaded.isNewerThan("1.1.0-beta.8-autosync.1", versionCode))
        assertFalse(downloaded.copy(publishedAt = "2026-10-23T09:00:00Z")
            .isNewerThan("1.1.0-beta.9-autosync.1", versionCode))
    }
}
