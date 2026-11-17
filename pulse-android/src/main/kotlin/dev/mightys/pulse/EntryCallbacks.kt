package dev.mightys.pulse

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import java.lang.ref.WeakReference

/** Time from process start to the first resumed Activity. Frozen at the first mark; before that, to now. */
internal class LaunchTimer(private val startUptime: Long?, private val uptime: () -> Long) {
    private var marked = false
    @Volatile private var frozen: Long? = null

    @Synchronized
    fun mark() {
        if (marked) return
        marked = true
        val start = startUptime ?: return
        frozen = (uptime() - start).takeIf { it >= 0 }
    }

    fun launchMs(): Long? = frozen ?: startUptime?.let { (uptime() - it).takeIf { ms -> ms >= 0 } }

    companion object {
        /** API 24+ knows when the process started; older systems leave `launchMs` out. */
        fun system(): LaunchTimer = LaunchTimer(if (Build.VERSION.SDK_INT >= 24) Process.getStartUptimeMillis() else null) { SystemClock.uptimeMillis() }
    }
}

/** What an Activity was started with: [identity] tells one Intent from another, [data] is set only for ACTION_VIEW links. */
internal class EntryIntent(val identity: Any, val data: String?)

/**
 * Hooks that are live from the moment [Pulse.initialize] is called: marks the first resumed Activity for `launchMs` and
 * reports links that open the app (ACTION_VIEW data) once each, from onCreate of a fresh launch or from a new Intent
 * seen at resume (an app that calls setIntent in onNewIntent). Only the link text is handed on; the client keeps scheme,
 * host and the path without ids.
 */
internal class EntryCallbacks(
    private val launch: LaunchTimer,
    private val link: (String) -> Unit,
    private val read: (Activity) -> EntryIntent? = ::systemRead,
) : Application.ActivityLifecycleCallbacks {
    @Volatile private var seen: WeakReference<Any>? = null

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { check(activity, savedInstanceState == null) }
    override fun onActivityResumed(activity: Activity) { launch.mark(); check(activity, true) }

    /** Initialize was called with an Activity that may already be created: its link is reported unless it was already. */
    fun initializedFrom(context: Context) { if (context is Activity && !context.isFinishing) check(context, true) }

    private fun check(activity: Activity, report: Boolean) {
        try {
            val entry = read(activity) ?: return
            if (seen?.get() === entry.identity) return
            seen = WeakReference(entry.identity)
            val data = entry.data
            if (report && data != null) link(data)
        } catch (_: Exception) { /* Never reaches the app. */ }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        fun systemRead(activity: Activity): EntryIntent? {
            val intent = activity.intent ?: return null
            return EntryIntent(intent, if (intent.action == Intent.ACTION_VIEW) intent.dataString else null)
        }
    }
}
