package dev.mightys.pulse

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * Heatmap wiring for Android. Nothing is installed on a window until the SDK says capture is on (portal switch and code
 * option), so an app with heatmaps off has no wrapped window callback. [active] is cheap and true only while taps are
 * captured. The debug screenshot is requested about one second after a screen becomes visible, and only when the app is
 * debuggable ([debuggable]).
 */
internal class HeatmapHooks(
    private val active: () -> Boolean,
    private val install: (Activity) -> Unit,
    private val uninstall: (Activity) -> Unit,
    private val shot: (Activity) -> Unit,
    private val post: (Long, Runnable) -> Unit = { delay, action -> Handler(Looper.getMainLooper()).postDelayed(action, delay) },
    /** True while an App Control screen or bar covers the app: no debug picture is taken then. */
    private val sdkUiVisible: () -> Boolean = { false },
) {
    @Volatile var debuggable = false
    @Volatile private var current: WeakReference<Activity>? = null
    private var generation = 0

    val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) { if (activity is PulseControlActivity) return; current = WeakReference(activity); if (active()) shown(activity) }
        override fun onActivityPaused(activity: Activity) { if (activity is PulseControlActivity) return; if (current?.get() === activity) current = null }
        override fun onActivityDestroyed(activity: Activity) { uninstall(activity) }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    /** Pulse.initialize was called from an Activity that may already be resumed. Main thread. */
    fun initializedFrom(activity: Activity) { if (activity !is PulseControlActivity && !activity.isFinishing && current?.get() == null) current = WeakReference(activity) }

    /** The SDK's heatmap screen changed while capture is on. Called from the SDK worker; the work moves to the main thread. */
    fun screenChanged(runOnMain: (Runnable) -> Unit) {
        runOnMain(Runnable { current?.get()?.let { if (active()) shown(it) } })
    }

    private fun shown(activity: Activity) {
        try {
            if (activity is PulseControlActivity) return
            install(activity)
            if (!debuggable) return
            // One request at a time: a newer one makes an older one that has not fired yet do nothing.
            val mine = ++generation
            post(SHOT_DELAY_MILLIS, Runnable { if (mine == generation && current?.get() === activity && active() && !sdkUiVisible()) shot(activity) })
        } catch (_: Throwable) { /* never reaches the app */ }
    }

    private companion object { const val SHOT_DELAY_MILLIS = 1000L }
}
