package dev.mightys.pulse

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Reads whether the app may post notifications. Only reads: nothing here ever asks the user for the permission.
 * Values: granted, denied, notDetermined. Android has no provisional or ephemeral state and no tracking permission.
 */
internal object NotificationStatus {
    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /**
     * [enabled]: NotificationManager.areNotificationsEnabled (API 24+), [runtimeGranted]: the runtime permission (API 33+),
     * [rationale]: the system would show the permission rationale, which only happens after a refusal. Unknown inputs are null.
     */
    fun status(sdk: Int, enabled: Boolean?, runtimeGranted: Boolean?, rationale: Boolean?): String? {
        if (sdk >= 33 && runtimeGranted != null) {
            if (runtimeGranted) return if (enabled == false) "denied" else "granted"
            return if (rationale == true) "denied" else "notDetermined"
        }
        return enabled?.let { if (it) "granted" else "denied" }
    }

    /** Status reads from the system on the SDK worker. Returns null when nothing can be read. */
    fun read(app: Context, rationale: Boolean?): String? = try {
        val enabled = if (Build.VERSION.SDK_INT >= 24) (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.areNotificationsEnabled() else null
        val granted = if (Build.VERSION.SDK_INT >= 33) app.checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else null
        status(Build.VERSION.SDK_INT, enabled, granted, rationale)
    } catch (_: Exception) { null }

    /** Whether the system would show the permission rationale for [activity]; a question about the past, never a request. */
    fun rationale(activity: android.app.Activity): Boolean? = try {
        if (Build.VERSION.SDK_INT >= 33) activity.shouldShowRequestPermissionRationale(POST_NOTIFICATIONS) else null
    } catch (_: Exception) { null }
}
