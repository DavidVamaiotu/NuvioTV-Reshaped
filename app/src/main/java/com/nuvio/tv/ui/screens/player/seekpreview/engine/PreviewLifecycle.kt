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
