package dev.mightys.pulse

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * The Google Play update flow, immediate for required updates and flexible for suggestions. This class is loaded only
 * after [PlayLibrary.available] found the Play In-App Updates library in the app, so apps without it never touch it.
 */
internal class PlayUpdateFlow(private val control: ControlClient) {
    private var manager: AppUpdateManager? = null
    @Volatile private var downloaded = false

    fun start(activity: Activity, immediate: Boolean, fallback: () -> Unit) {
        val updates = manager ?: AppUpdateManagerFactory.create(activity.applicationContext).also { manager = it }
        val type = if (immediate) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        updates.appUpdateInfo.addOnSuccessListener { info ->
            try {
                val availability = info.updateAvailability()
                when {
                    availability == UpdateAvailability.UPDATE_NOT_AVAILABLE -> { control.notePlayUpdateUnavailable(); fallback() }
                    info.isUpdateTypeAllowed(type) -> {
                        if (!immediate) updates.registerListener(InstallStateUpdatedListener { state -> if (state.installStatus() == InstallStatus.DOWNLOADED) downloaded = true })
                        updates.startUpdateFlow(info, activity, AppUpdateOptions.newBuilder(type).build())
                    }
                    else -> fallback()
                }
            } catch (_: Throwable) { fallback() }
        }.addOnFailureListener { fallback() }
    }

    fun completeIfDownloaded() { if (downloaded) { downloaded = false; manager?.completeUpdate() } }
}
