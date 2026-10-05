package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TesterChannelTest {
    private val releases = listOf(
        release("1.1.0-beta.3-tester.2", prerelease = true),
        release("1.1.0-beta.3-tester.10", prerelease = true),
        release("1.1.0-beta.3-autosync.6", prerelease = true),
        release("1.1.0-beta.3-autosync.5", prerelease = true),
        release("1.0.0-autosync.20")
    )

    @Test
    fun `tester tags are recognised`() {
        assertTrue(TesterChannel.isTesterVersion("1.1.0-beta.3-tester.1"))
        assertTrue(TesterChannel.isTesterVersion("1.2.0-tester.4"))
        assertFalse(TesterChannel.isTesterVersion("1.1.0-beta.3-autosync.6"))
        assertFalse(TesterChannel.isTesterVersion("1.1.0-beta.3-tester"))
        assertFalse(TesterChannel.isTesterVersion("tester.1"))
    }

    @Test
    fun `tester tags are not canonical autosync releases`() {
        // Stable and Beta apps already installed only follow canonical tags, so they never see testers.
        assertFalse(VersionUtils.isCanonicalAutoSync("1.1.0-beta.3-tester.1"))
    }

    @Test
    fun `tester channel lists only tester releases in revision order`() {
        val selected = ReleaseSelector.eligibleReleases(releases, UpdateChannel.TESTER, canonicalAutoSyncOnly = true)

        assertEquals(
            listOf("1.1.0-beta.3-tester.10", "1.1.0-beta.3-tester.2"),
            selected.map { it.tagName }
        )
    }

    @Test
    fun `beta and stable channels never list tester releases`() {
        val beta = ReleaseSelector.eligibleReleases(releases, UpdateChannel.BETA, canonicalAutoSyncOnly = true)
        val stable = ReleaseSelector.eligibleReleases(releases, UpdateChannel.STABLE, canonicalAutoSyncOnly = true)

        assertEquals(
            listOf("1.1.0-beta.3-autosync.6", "1.1.0-beta.3-autosync.5", "1.0.0-autosync.20"),
            beta.map { it.tagName }
        )
        assertEquals(listOf("1.0.0-autosync.20"), stable.map { it.tagName })
    }

    @Test
    fun `newer nuvio version outranks tester revisions`() {
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-beta.4-tester.1", "1.1.0-beta.3-tester.9"))
        assertTrue(VersionUtils.isRemoteNewer("1.1.0-tester.1", "1.1.0-beta.3-tester.9"))
        assertFalse(VersionUtils.isRemoteNewer("1.1.0-beta.3-tester.1", "1.1.0-beta.3-tester.2"))
    }

    @Test
    fun `tester builds default to the tester channel`() {
        assertEquals(UpdateChannel.TESTER, UpdateChannel.defaultForVersion("1.1.0-beta.3-tester.1"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.defaultForVersion("1.1.0-beta.3-autosync.6"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.defaultForVersion("1.0.0-autosync.20"))
    }

    @Test
    fun `same line keeps the usual version comparison`() {
        assertTrue(newer("1.1.0-beta.3-tester.2", null, true, "1.1.0-beta.3-tester.1", BETA_BUILT_AT))
        assertFalse(newer("1.1.0-beta.3-tester.1", LATER, true, "1.1.0-beta.3-tester.2", BETA_BUILT_AT))
        assertTrue(newer("1.1.0-beta.3-autosync.7", null, true, "1.1.0-beta.3-autosync.6", BETA_BUILT_AT))
    }

    @Test
    fun `beta to tester waits for a tester build made after the installed beta`() {
        assertTrue(newer("1.1.0-beta.3-tester.1", LATER, true, "1.1.0-beta.3-autosync.6", BETA_BUILT_AT))
        assertFalse(newer("1.1.0-beta.3-tester.1", EARLIER, true, "1.1.0-beta.3-autosync.6", BETA_BUILT_AT))
    }

    @Test
    fun `tester to beta waits for a beta build made after the installed tester`() {
        assertTrue(newer("1.1.0-beta.3-autosync.7", LATER, true, "1.1.0-beta.3-tester.4", BETA_BUILT_AT))
        assertFalse(newer("1.1.0-beta.3-autosync.6", EARLIER, true, "1.1.0-beta.3-tester.4", BETA_BUILT_AT))
    }

    @Test
    fun `never offers an older nuvio version across channels`() {
        assertFalse(newer("1.1.0-beta.2-autosync.9", LATER, true, "1.1.0-beta.3-tester.4", BETA_BUILT_AT))
    }

    @Test
    fun `stable builds never outrank a tester build`() {
        assertFalse(newer("1.1.0-autosync.1", LATER, false, "1.1.0-beta.3-tester.4", BETA_BUILT_AT))
        assertFalse(newer("1.0.0-autosync.21", LATER, false, "1.1.0-beta.3-tester.4", BETA_BUILT_AT))
    }

    @Test
    fun `stable to tester is offered at once`() {
        // Stable builds carry no lead, so any tester build outranks them.
        assertTrue(newer("1.1.0-beta.3-tester.1", "2026-09-01T00:00:00Z", true, "1.0.0-autosync.20", STABLE_BUILT_AT))
    }

    @Test
    fun `missing publish time is never offered across channels`() {
        assertFalse(newer("1.1.0-beta.3-tester.1", null, true, "1.1.0-beta.3-autosync.6", BETA_BUILT_AT))
        assertFalse(newer("1.1.0-beta.3-tester.1", "not a date", true, "1.1.0-beta.3-autosync.6", BETA_BUILT_AT))
    }

    @Test
    fun `switching message only between beta and tester`() {
        assertTrue(TesterChannel.isSwitching(UpdateChannel.TESTER, "1.1.0-beta.3-autosync.6"))
        assertTrue(TesterChannel.isSwitching(UpdateChannel.BETA, "1.1.0-beta.3-tester.1"))
        assertFalse(TesterChannel.isSwitching(UpdateChannel.TESTER, "1.1.0-beta.3-tester.1"))
        assertFalse(TesterChannel.isSwitching(UpdateChannel.BETA, "1.1.0-beta.3-autosync.6"))
        assertFalse(TesterChannel.isSwitching(UpdateChannel.STABLE, "1.1.0-beta.3-tester.1"))
    }

    private fun newer(
        remoteTag: String,
        publishedAt: String?,
        prerelease: Boolean,
        localName: String,
        localCode: Long
    ) = TesterChannel.isRemoteNewer(remoteTag, publishedAt, prerelease, localName, localCode)

    private fun release(tag: String, prerelease: Boolean = false) = GitHubReleaseDto(
        tagName = tag,
        name = "Nuvio Reshaped $tag",
        prerelease = prerelease
    )

    private companion object {
        // 2026-10-03T12:00:00Z, stamped as beta/tester (+100000000) and as stable.
        const val BETA_BUILT_AT = 1_791_028_800L + 100_000_000L
        const val STABLE_BUILT_AT = 1_791_028_800L
        const val EARLIER = "2026-10-03T11:50:00Z"
        const val LATER = "2026-10-03T12:20:00Z"
    }
}
