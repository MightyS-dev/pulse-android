package dev.mightys.pulse

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens the store for the Update button: the Play update flow when it can, else the store page. Never throws. */
internal class ControlStore(
    private val control: ControlClient,
    private val context: () -> Context?,
    private val activity: () -> Activity?,
) {
    private val play by lazy { PlayUpdateFlow(control) }

    fun open(state: ControlState) {
        try {
            val app = context() ?: return
            val kind = state.kind
            if (ControlUiPolicy.isUpdate(kind)) control.noteUpdatePressed()
            val current = activity()
            val plan = PlayUpdatePlan.plan(kind, current != null && AndroidMetadata.installerPackage(app) == "com.android.vending", PlayLibrary.available())
            val page = { openPage(app, state) }
            if (plan == PlayUpdatePlan.Plan.Page || current == null) page() else play.start(current, plan == PlayUpdatePlan.Plan.Immediate, page)
        } catch (_: Throwable) { /* A failed open leaves the screen as it is. */ }
    }

    /** Completes a flexible update that finished downloading while the app was away. */
    fun resume() { if (PlayLibrary.available()) try { play.completeIfDownloaded() } catch (_: Throwable) { /* Optional. */ } }

    @SuppressLint("UseKtx") // the SDK has no KTX dependency
    private fun openPage(app: Context, state: ControlState) {
        for (address in StoreLinks.candidates(state.url, app.packageName)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { app.startActivity(intent); return } catch (_: ActivityNotFoundException) { /* Try the next address. */ } catch (_: SecurityException) { /* Same. */ }
        }
    }
}
