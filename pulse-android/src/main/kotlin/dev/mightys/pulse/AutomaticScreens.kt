package dev.mightys.pulse

import android.app.Activity
import android.content.Context
import java.lang.ref.WeakReference

/**
 * Automatic screen hooks. They are live from the moment [Pulse.initialize] is called, so the first Activity is never
 * missed. A name is handed to the client at once, so it is captured in call order with manual screens; the client
 * decides later (code option, saved portal settings) whether it is sent.
 */
internal class AutomaticScreens(private val client: PulseClient) {
    /** The Activity whose screen initialize already reported; its next resume is not reported again. */
    @Volatile private var reported: WeakReference<Activity>? = null
    val callbacks = ScreenViewCallbacks({ name -> report(name) }, ::consume, ::forget)

    // One resume of the reported Activity is covered. If it pauses first, its resume had already fired before the SDK
    // was initialized, so the next resume is a real new appearance and counts.
    private fun consume(activity: Activity): Boolean {
        if (reported?.get() !== activity) return false
        reported = null; return true
    }
    private fun forget(activity: Activity) { if (reported?.get() === activity) reported = null }

    /**
     * Initialize was called with an Activity (from onCreate or onResume): that Activity is the screen being shown, and
     * its own resume callback may already have fired. The client ignores the same screen twice in a row, so this never
     * doubles the callback.
     */
    fun initializedFrom(context: Context) {
        if (context !is Activity || context.isFinishing) return
        val name = context.javaClass.simpleName
        if (name.isNotEmpty()) { reported = WeakReference(context); report(name) }
    }

    private fun report(name: String) {
        try { client.automaticScreen(name) } catch (_: Exception) { /* Never reaches the app. */ }
    }
}
