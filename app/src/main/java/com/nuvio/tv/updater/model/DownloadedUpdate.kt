package com.nuvio.tv.updater.model

import com.nuvio.tv.updater.TesterChannel

/** Persist release ordering with the APK so background installs also respect Beta/Tester moves. */
data class DownloadedUpdate(
    val tag: String,
    val path: String,
    val publishedAt: String?,
    val prerelease: Boolean
) {
    fun isNewerThan(versionName: String, versionCode: Long): Boolean =
        TesterChannel.isRemoteNewer(tag, publishedAt, prerelease, versionName, versionCode)
}
