package dev.mightys.pulse

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Sends a screen view when an Activity is resumed. Records only the class name and hands it to the SDK queue. */
internal class ScreenViewCallbacks(
    private val screen: (String) -> Unit,
    /** True when this resume was already reported (initialize was called from this Activity). */
    private val alreadyReported: (Activity) -> Boolean = { false },
    private val leaving: (Activity) -> Unit = {},
    private val fragmentsAvailable: Boolean = FragmentScreens.available(),
    private val watchFragments: (Activity, (String) -> Unit) -> Unit = FragmentScreens::watch,
) : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (fragmentsAvailable) try { watchFragments(activity, screen) } catch (_: Throwable) { /* Optional feature never breaks the app. */ }
    }
    override fun onActivityResumed(activity: Activity) {
        if (alreadyReported(activity)) return
        val name = activity.javaClass.simpleName
        if (name.isNotEmpty()) screen(name)
    }
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = leaving(activity)
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
