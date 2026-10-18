package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.app.ActivityManager
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import java.io.Closeable
import java.util.concurrent.Semaphore

/** Shared only by preview providers. Never schedules or changes other Nuvio features. */
internal object PreviewDecodeGate {
    private val permit = Semaphore(1)
    fun <T> tryRun(block: () -> T): T? {
        if (!permit.tryAcquire()) return null
        return try { block() } finally { permit.release() }
    }
}

internal class PreviewResources(context: Context, private val onTrim: () -> Unit) : Closeable, ComponentCallbacks2, Application.ActivityLifecycleCallbacks {
    private val app = context.applicationContext
    private val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val budget: PreviewBudget
    @Volatile var hidden = false
        private set
    init {
        val memory = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memory)
        budget = PreviewBudget(am == null || am.isLowRamDevice || memory.totalMem < 3L * 1024 * 1024 * 1024)
        app.registerComponentCallbacks(this)
        (app as? Application)?.registerActivityLifecycleCallbacks(this)
    }
    fun allows(video: VideoTrack): Boolean = allowsDimensions(video.width, video.height)
    fun allowsDimensions(width: Int, height: Int): Boolean {
        if (width !in 1..4096 || height !in 1..2304) return false
        val memory = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memory) ?: return false
        val runtime = Runtime.getRuntime()
        return !hidden && !memory.lowMemory && budget.canDecode(width, height,
            memory.availMem / (1024 * 1024), (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024))
    }
    fun resume() { hidden = false }
    fun allowsVerification(video: VideoTrack): Boolean {
        val memory = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memory) ?: return false
        val runtime = Runtime.getRuntime()
        val heapFree = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        return !hidden && !memory.lowMemory && memory.availMem >= 450L * 1024 * 1024 && heapFree >= 48L * 1024 * 1024 &&
            video.width in 1..4096 && video.height in 1..2304
    }
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) hidden = true
        onTrim()
    }
    override fun onLowMemory() { onTrim() }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    override fun onActivityResumed(activity: Activity) { hidden = false }
    override fun onActivityPaused(activity: Activity) { hidden = true; onTrim() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
    override fun close() {
        app.unregisterComponentCallbacks(this)
        (app as? Application)?.unregisterActivityLifecycleCallbacks(this)
    }
}
