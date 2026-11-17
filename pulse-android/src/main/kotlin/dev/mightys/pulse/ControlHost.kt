package dev.mightys.pulse

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Shows the built-in App Control screens (automatic mode). It remembers the Activity that is on screen (weakly), starts
 * [PulseControlActivity] over it when the state asks for a screen, and draws the heads-up bar on the app's own window.
 * It never finishes the app and never touches app state.
 */
internal class ControlHost(
    private val control: ControlClient,
    private val post: (Runnable) -> Unit = Handler(Looper.getMainLooper())::post,
    private val launch: (Activity) -> Unit = { it.startActivity(Intent(it, PulseControlActivity::class.java)) },
    private val resume: () -> Unit = {},
) : Application.ActivityLifecycleCallbacks {
    private var current: WeakReference<Activity>? = null
    private var bar: Pair<WeakReference<Activity>, TextView>? = null
    private var launching = false

    /** True while an App Control screen (the control activity or the heads-up bar) is on screen. */
    val uiVisible: Boolean get() = alive || bar != null

    /** The Activity on screen, or null while the app is in the background. */
    fun currentActivity(): Activity? = current?.get()?.takeIf { !it.isFinishing }

    private val listener = ControlListener { post(Runnable { reconcile() }) }
    fun start() { control.addListener(listener) }
    fun stop() { control.removeListener(listener); removeBar() }

    /** Called on the main thread. Safe to call as often as needed. */
    fun reconcile() {
        try {
            val state = control.state
            val activity = currentActivity()
            if (state.kind == ControlState.Kind.MaintenanceUpcoming && activity != null) showBar(activity, state) else removeBar()
            if (ControlUiPolicy.needsActivity(state.kind) && activity != null && !alive && !launching) {
                launching = true
                post(Runnable { launching = false; try { if (!alive && control.state.let { ControlUiPolicy.needsActivity(it.kind) }) currentActivity()?.let(launch) } catch (_: Exception) { /* Shown at the next start. */ } })
            }
        } catch (_: Exception) { /* A screen problem never reaches the app. */ }
    }

    private fun showBar(activity: Activity, state: ControlState) {
        val existing = bar
        if (existing != null && existing.first.get() === activity) { existing.second.text = state.body; return }
        removeBar()
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val view = ControlViews.headsUp(activity, state, ControlPalette.of(activity)) { control.dismiss() }
        // The bar sits in the app's window: its taps are never heatmap points.
        view.setTag(R.id.pulse_heatmap_ignore, true)
        decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        view.post { clearStatusBar(view) }
        bar = WeakReference(activity) to view
    }

    /** In an edge to edge window the bar would sit under the status bar: move it down by the overlap. */
    private fun clearStatusBar(view: TextView) {
        val spot = IntArray(2); view.getLocationOnScreen(spot)
        val bars = if (android.os.Build.VERSION.SDK_INT >= 23) view.rootWindowInsets?.let { ControlInsets.top(it) } ?: 0 else 0
        if (spot[1] < bars) view.setPadding(view.paddingLeft, view.paddingTop + (bars - spot[1]), view.paddingRight, view.paddingBottom)
    }

    private fun removeBar() {
        val old = bar ?: return
        bar = null
        try { (old.second.parent as? ViewGroup)?.removeView(old.second) } catch (_: Exception) { /* Gone with its window. */ }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) {
        if (activity is PulseControlActivity) return
        current = WeakReference(activity)
        try { resume() } catch (_: Exception) { /* Optional. */ }
        reconcile()
    }
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) {
        if (activity is PulseControlActivity) return
        if (current?.get() === activity) { current = null; removeBar() }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        @Volatile private var aliveCount = 0
        val alive: Boolean get() = aliveCount > 0
        fun activityCreated() { aliveCount++ }
        fun activityDestroyed() { if (aliveCount > 0) aliveCount-- }
    }
}

internal object ControlInsets {
    @Suppress("DEPRECATION")
    fun top(insets: android.view.WindowInsets): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(android.view.WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
}
