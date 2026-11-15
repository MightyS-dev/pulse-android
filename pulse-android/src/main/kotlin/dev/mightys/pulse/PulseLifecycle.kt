package dev.mightys.pulse

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal class ActivityCounter(private val background: () -> Unit) {
    private var count = 0
    fun started() { count++ }
    fun stopped(changingConfiguration: Boolean) {
        // A stop without a matching start belongs to an activity started before registration (initialize called
        // from Activity.onCreate). Flushing then is harmless; skipping it would never flush single-activity apps.
        if (count > 0) count--
        if (count == 0 && !changingConfiguration) background()
    }
}

internal class PulseLifecycle(client: PulseClient, private val foreground: () -> Unit = {}) : Application.ActivityLifecycleCallbacks {
    private val counter = ActivityCounter { client.background() }
    override fun onActivityStarted(activity: Activity) { counter.started(); foreground() }
    override fun onActivityStopped(activity: Activity) { counter.stopped(activity.isChangingConfigurations) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
