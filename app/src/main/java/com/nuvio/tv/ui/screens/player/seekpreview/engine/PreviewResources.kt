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
import java.util.concurrent.atomic.AtomicLong
import android.os.Process
import java.io.File

/** Shared only by preview providers. Never schedules or changes other Nuvio features. */
internal object PreviewDecodeGate {
    private val permit = Semaphore(1)
    private val targetUntil = AtomicLong()
    val busyForTap: Boolean get() = permit.availablePermits() == 0 || System.nanoTime() < targetUntil.get()
    /** A short renewable lease lets the settled target acquire the next free decode slot. */
    fun prioritizeTarget(): Long = (System.nanoTime() + 500_000_000L).also { targetUntil.set(it) }
    fun releaseTarget(lease: Long) { targetUntil.compareAndSet(lease, 0) }
    fun <T> tryRun(foreground: Boolean = false, block: () -> T): T? {
        if (!foreground && System.nanoTime() < targetUntil.get()) return null
        if (!permit.tryAcquire()) return null
        val priority = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull()
        if (priority != null) runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        return try { block() } finally {
            if (priority != null) runCatching { Process.setThreadPriority(priority) }
            permit.release()
        }
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
    fun allows(video: VideoTrack, paused: Boolean = false, explicit: Boolean = false): Boolean =
        allowsDimensions(video.width, video.height, paused, explicit)
    fun allowsDimensions(width: Int, height: Int, paused: Boolean = false, explicit: Boolean = false): Boolean {
        if (width !in 1..4096 || height !in 1..2304) return false
        val memory = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memory) ?: return false
        val runtime = Runtime.getRuntime()
        val fourK = width.toLong() * height > 1920L * 1088
        return !hidden && !memory.lowMemory && (!fourK || nativeHeadroom()) && budget.canDecode(width, height,
            memory.availMem / (1024 * 1024), (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024), paused, explicit)
    }
    fun resume() { hidden = false }
    fun allowsImages(): Boolean {
        val memory = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memory) ?: return false
        val runtime = Runtime.getRuntime()
        return !hidden && !memory.lowMemory && memory.availMem >= 96L * 1024 * 1024 &&
            runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory() >= 12L * 1024 * 1024
    }
    fun allowsIndex(): Boolean {
        val memory = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memory) ?: return false
        val runtime = Runtime.getRuntime()
        return !hidden && !memory.lowMemory && memory.availMem >= 200L * 1024 * 1024 &&
            runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory() >= 48L * 1024 * 1024
    }
    fun allowsVerification(video: VideoTrack): Boolean {
        val memory = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memory) ?: return false
        val runtime = Runtime.getRuntime()
        val heapFree = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        return !hidden && !memory.lowMemory && nativeHeadroom() && memory.availMem >= 450L * 1024 * 1024 && heapFree >= 64L * 1024 * 1024 &&
            video.width in 1..4096 && video.height in 1..2304
    }
    /** Native buffers also need swap/address space; free Java heap alone is insufficient on 32-bit TVs. */
    private fun nativeHeadroom(): Boolean = runCatching {
        val mem = File("/proc/meminfo").useLines { lines ->
            lines.filter { it.startsWith("SwapTotal:") || it.startsWith("SwapFree:") }
                .associate { it.substringBefore(':') to it.substringAfter(':').trim().substringBefore(' ').toLong() }
        }
        if ((mem["SwapTotal"] ?: 0) > 0 && (mem["SwapFree"] ?: 0) < 64 * 1024) return@runCatching false
        if (Process.is64Bit()) return@runCatching true
        val sizeKb = File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmSize:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
        } ?: return@runCatching false
        sizeKb < 3_000L * 1024
    }.getOrDefault(false)
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
