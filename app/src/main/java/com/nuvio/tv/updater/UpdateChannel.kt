package com.nuvio.tv.updater

enum class UpdateChannel(val storedValue: String) {
    STABLE("stable"),
    BETA("beta"),
    TESTER("tester"); // Nuvio RS hook: tester channel

    companion object {
        fun fromStoredValue(value: String?): UpdateChannel? = entries.firstOrNull {
            it.storedValue.equals(value, ignoreCase = true)
        }

        fun defaultForVersion(versionName: String): UpdateChannel =
            if (TesterChannel.isTesterVersion(versionName)) TESTER // Nuvio RS hook
            else if (VersionUtils.isPrerelease(versionName)) BETA else STABLE
    }
}
