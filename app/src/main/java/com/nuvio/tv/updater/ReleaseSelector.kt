package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubReleaseDto

internal object ReleaseSelector {
    private val prereleaseNamePattern = Regex(
        "(?:^|[\\s._-])(alpha|beta|rc|preview)(?:[\\s._-]|$)",
        RegexOption.IGNORE_CASE
    )

    fun eligibleReleases(
        releases: List<GitHubReleaseDto>,
        channel: UpdateChannel,
        canonicalAutoSyncOnly: Boolean = false
    ): List<GitHubReleaseDto> = releases
        .asSequence()
        .filterNot(GitHubReleaseDto::draft)
        .filter { release -> TesterChannel.belongsTo(release, channel) } // Nuvio RS hook
        .filter { release ->
            !canonicalAutoSyncOnly ||
                VersionUtils.isCanonicalAutoSync(release.tagName) ||
                VersionUtils.isCanonicalAutoSync(release.name) ||
                TesterChannel.isTesterRelease(release) // Nuvio RS hook
        }
        .mapNotNull { release ->
            val version = releaseVersion(release) ?: return@mapNotNull null
            ReleaseCandidate(
                release = release,
                version = version,
                prerelease = isPrerelease(release, version)
            )
        }
        .filter { candidate -> channel != UpdateChannel.STABLE || !candidate.prerelease }
        .sortedByDescending(ReleaseCandidate::version)
        .map(ReleaseCandidate::release)
        .toList()

    private fun releaseVersion(release: GitHubReleaseDto): SemanticVersion? =
        VersionUtils.parse(release.tagName) ?: VersionUtils.parse(release.name)

    private fun isPrerelease(
        release: GitHubReleaseDto,
        version: SemanticVersion
    ): Boolean = release.prerelease ||
        version.prerelease.isNotEmpty() ||
        prereleaseNamePattern.containsMatchIn(
            release.name.orEmpty().replace(Regex("-autosync\\.\\d+$", RegexOption.IGNORE_CASE), "")
        )

    private data class ReleaseCandidate(
        val release: GitHubReleaseDto,
        val version: SemanticVersion,
        val prerelease: Boolean
    )
}
