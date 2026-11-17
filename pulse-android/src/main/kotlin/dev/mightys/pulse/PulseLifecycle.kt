package dev.mightys.pulse

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal class ActivityCounter(
    private val background: () -> Unit,
    private val foreground: () -> Unit = {},
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var count = 0
    /** When the last Activity stopped for a configuration change; the Activity that follows is not a new foreground. */
    private var configStopAt = -1L
    fun started() {
        val first = count == 0
        count++
        if (!first) return
        val recreating = configStopAt >= 0 && nowMillis() - configStopAt <= CONFIG_WINDOW_MILLIS
        configStopAt = -1L
        if (!recreating) foreground()
    }
    fun stopped(changingConfiguration: Boolean) {
        // A stop without a matching start belongs to an activity started before registration (initialize called
        // from Activity.onCreate). Flushing then is harmless; skipping it would never flush single-activity apps.
        if (count > 0) count--
        if (count == 0 && changingConfiguration) configStopAt = nowMillis()
        if (count == 0 && !changingConfiguration) background()
    }
    private companion object { const val CONFIG_WINDOW_MILLIS = 5_000L }
}

internal class PulseLifecycle(client: PulseClient, private val foreground: (Activity) -> Unit = {}) : Application.ActivityLifecycleCallbacks {
    private val counter = ActivityCounter({ client.appBackgrounded(); client.background() }, { client.appForegrounded() })
    override fun onActivityStarted(activity: Activity) { counter.started(); foreground(activity) }
    override fun onActivityStopped(activity: Activity) { counter.stopped(activity.isChangingConfigurations) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
