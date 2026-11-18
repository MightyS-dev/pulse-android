package dev.mightys.pulse

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** Process lifecycle ignores configuration changes and waits until the last Activity has stopped. */
internal class PulseLifecycle(private val client: PulseClient, private val foreground: (Activity) -> Unit = {}) :
    Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {
    /** Called on the main thread. Adding the observer also delivers ON_START if the app is already visible. */
    fun observe(lifecycle: Lifecycle) { lifecycle.addObserver(this) }
    override fun onStart(owner: LifecycleOwner) { client.appForegrounded() }
    override fun onStop(owner: LifecycleOwner) { client.appBackgrounded() }
    override fun onActivityStarted(activity: Activity) { foreground(activity) }
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
