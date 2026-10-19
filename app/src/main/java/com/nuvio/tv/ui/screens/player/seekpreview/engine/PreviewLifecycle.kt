package com.nuvio.tv.ui.screens.player.seekpreview.engine

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/** Application lifecycle only; no player-screen dependency or retained Activity. */
internal object PreviewLifecycle : Application.ActivityLifecycleCallbacks {
    private var registered = false
    @Synchronized fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(this)
        registered = true
    }
    override fun onActivityResumed(activity: Activity) { SeekThumbnails.hostResumed = true; NextEpisodeThumbs.resume() }
    override fun onActivityPaused(activity: Activity) { SeekThumbnails.hostResumed = false; NextEpisodeThumbs.cancel() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** Available after a restart too: Android retains why the previous app process exited. */
internal fun previewDiagnosticReport(context: Context): String {
    val exits = if (android.os.Build.VERSION.SDK_INT >= 30) runCatching {
        val manager = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        manager?.getHistoricalProcessExitReasons(context.packageName, 0, 4).orEmpty().joinToString("\n") { exit ->
            val reason = when (exit.reason) {
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
                android.app.ApplicationExitInfo.REASON_CRASH -> "Java crash"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                else -> "reason ${exit.reason}"
            }
            "${java.util.Date(exit.timestamp)}: $reason, status=${exit.status}, pssKiB=${exit.pss}, rssKiB=${exit.rss}"
        }
    }.getOrDefault("Exit history unavailable") else "Exit history requires Android 11 or later"
    return PreviewDiagnostics.text() + "\n\nPrevious app process exits\n" + exits
}
