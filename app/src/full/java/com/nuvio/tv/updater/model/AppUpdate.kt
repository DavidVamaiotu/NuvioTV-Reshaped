package com.nuvio.tv.updater.model

data class AppUpdate(
    val tag: String,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long?,
    // Nuvio RS hook: tester channel switching (TesterChannel.isRemoteNewer)
    val publishedAt: String? = null,
    val prerelease: Boolean = false
)
