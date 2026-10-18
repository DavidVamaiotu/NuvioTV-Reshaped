package com.nuvio.tv.core.player

import android.app.ActivityManager
import android.content.Context

/** Shared by preview extraction and playback; full-screen playback does not use this policy. */
internal object TrailerMemoryPolicy {
    const val LOW_MEMORY_MAX_HEIGHT = 1080

    fun isLowMemory(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return isLowMemory(info.totalMem, Runtime.getRuntime().maxMemory(), manager.isLowRamDevice)
    }

    internal fun isLowMemory(totalRamBytes: Long, maxHeapBytes: Long, lowRamDevice: Boolean): Boolean =
        lowRamDevice || totalRamBytes <= 0L || totalRamBytes < 2_355L * 1024 * 1024 ||
            maxHeapBytes < 512L * 1024 * 1024
}
