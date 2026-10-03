package com.nuvio.tv.updater

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import java.time.Instant

/**
 * Nuvio RS "Tester" update channel: builds for people who help find bugs, published from
 * subtitle-autosync-tester (autosync-tester-release.yml) as `<nuvio version>-tester.N`.
 *
 * Tester releases are pre-releases whose tags never end in `-autosync.N`, so Stable and Beta
 * apps (which only follow canonical `-autosync.N` tags) never see them. Only the Tester channel
 * lists them, and it lists nothing else. Downloading and installing work as for every channel.
 */
internal object TesterChannel {
    private val testerTagPattern = Regex(
        "^v?\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?-tester\\.\\d+$",
        RegexOption.IGNORE_CASE
    )

    // Release builds are stamped with their build time as versionCode; beta and tester builds add
    // this lead over stable (mirror-nuviotv-release.yml, autosync-tester-release.yml).
    private const val BETA_LINE_VERSION_CODE_LEAD = 100_000_000L

    private val installedTesterBuildKey = booleanPreferencesKey("reshaped_installed_tester_build")

    fun isTesterVersion(raw: String?): Boolean =
        !raw.isNullOrBlank() && testerTagPattern.matches(raw.trim())

    fun isTesterRelease(release: GitHubReleaseDto): Boolean = isTesterVersion(release.tagName)

    /** Tester releases belong to the Tester channel only, and the Tester channel to them. */
    fun belongsTo(release: GitHubReleaseDto, channel: UpdateChannel): Boolean =
        isTesterRelease(release) == (channel == UpdateChannel.TESTER)

    /**
     * Follows a sideloaded build: installing a tester APK over another build moves the app to the
     * Tester channel, and installing a non-tester APK over a tester build moves it back to the
     * channel that build defaults to. An unchanged build keeps the channel the viewer picked.
     * Returns null when no channel was stored yet.
     */
    fun followInstalledBuild(
        prefs: MutablePreferences,
        storedChannel: UpdateChannel?,
        versionName: String
    ): UpdateChannel? {
        val installedTester = isTesterVersion(versionName)
        // Builds from before this key existed were never tester builds.
        val previousTester = prefs[installedTesterBuildKey] ?: false
        prefs[installedTesterBuildKey] = installedTester
        return if (storedChannel != null && installedTester != previousTester) {
            UpdateChannel.defaultForVersion(versionName)
        } else {
            storedChannel
        }
    }

    /**
     * Whether [remoteTag] is an update for the installed build. Within the tester line, or within
     * stable and beta, this is the usual version comparison. Between them the versions say nothing
     * about build order, and Android refuses a lower versionCode, so the remote build is offered
     * only when it was published after the installed one was built (beta and tester releases run
     * in one queue, so publish order is build order) and is not on an older Nuvio version.
     */
    fun isRemoteNewer(
        remoteTag: String,
        remotePublishedAt: String?,
        remotePrerelease: Boolean,
        localVersionName: String,
        localVersionCode: Long
    ): Boolean {
        val remoteTester = isTesterVersion(remoteTag)
        if (remoteTester == isTesterVersion(localVersionName)) {
            return VersionUtils.isRemoteNewer(remoteTag, localVersionName)
        }

        val remoteBase = VersionUtils.parse(remoteTag)?.copy(autoSyncRevision = null) ?: return false
        val localBase = VersionUtils.parse(localVersionName)?.copy(autoSyncRevision = null) ?: return false
        if (remoteBase < localBase) return false

        val publishedAt = remotePublishedAt
            ?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
            ?: return false
        // A stable release (no lead) never outranks a beta or tester build. A non-prerelease from
        // the beta line can't be told apart from stable, so it is treated the same way.
        val lead = if (remoteTester || remotePrerelease) BETA_LINE_VERSION_CODE_LEAD else 0L
        return publishedAt + lead > localVersionCode
    }

    /** True when the viewer picked a channel the installed build isn't from (Beta <-> Tester). */
    fun isSwitching(channel: UpdateChannel, localVersionName: String): Boolean =
        channel != UpdateChannel.STABLE &&
            (channel == UpdateChannel.TESTER) != isTesterVersion(localVersionName)
}
