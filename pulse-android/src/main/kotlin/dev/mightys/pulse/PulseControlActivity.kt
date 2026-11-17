package dev.mightys.pulse

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsetsController
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * The window of the built-in App Control screens: full screen for a required update, a blocked version and maintenance, a
 * dialog for a suggestion, a card for announcements. It only shows the current [ControlState] and goes away the moment the
 * state no longer asks for it. Back never closes a locking screen; it sends the app to the background.
 */
internal class PulseControlActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var shown: ControlState? = null
    private var dialog: AlertDialog? = null
    private var tryAgain: Runnable? = null
    private var backCallback: Any? = null
    private val listener = ControlListener { handler.post { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ControlHost.activityCreated()
        edgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = OnBackInvokedCallback { onBack() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        Pulse.controlClient.addListener(listener)
        render()
    }

    override fun onStop() {
        Pulse.controlClient.removeListener(listener)
        dialog?.dismiss(); dialog = null; shown = null
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        tryAgain?.let { handler.removeCallbacks(it) }
        ControlHost.activityDestroyed()
        super.onDestroy()
    }

    @Deprecated("Handled by the back callback on newer Android versions.")
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() { onBack() }

    private fun onBack() {
        val state = Pulse.controlClient.state
        if (ControlUiPolicy.back(state.kind) == ControlUiPolicy.Back.MoveToBackground) moveTaskToBack(true) else Pulse.controlClient.dismiss()
    }

    /** A screen problem never reaches the app: if drawing fails the window closes and the app goes on. */
    private fun render() {
        try { draw() } catch (error: Throwable) {
            android.util.Log.w("MightySPulse", "App Control screen not shown: " + error.javaClass.simpleName)
            try { finish() } catch (_: Throwable) { /* Already closing. */ }
        }
    }

    private fun draw() {
        if (isFinishing || isDestroyed) return
        val state = Pulse.controlClient.state
        if (!ControlUiPolicy.needsActivity(state.kind)) { finish(); return }
        if (state == shown) return
        shown = state
        dialog?.dismiss(); dialog = null
        val palette = ControlPalette.of(this)
        val actions = ControlActions(primary = { primary(state) }, secondary = { Pulse.controlClient.dismiss() }, outside = { Pulse.controlClient.dismiss() })
        when (ControlUiPolicy.surface(state.kind)) {
            ControlUiPolicy.Surface.FullScreen -> setContentView(ControlViews.fullScreen(this, state, palette, actions))
            ControlUiPolicy.Surface.Card -> setContentView(ControlViews.card(this, state, palette, actions))
            else -> { setContentView(View(this)); showSuggestion(state) }
        }
        // The window's insets controller exists only after a content view is set.
        darkBarIcons(ControlUiPolicy.surface(state.kind) == ControlUiPolicy.Surface.FullScreen && !palette.night)
    }

    private fun showSuggestion(state: ControlState) {
        val builder = AlertDialog.Builder(this).setTitle(state.title).setMessage(state.body).setOnCancelListener { Pulse.controlClient.dismiss() }
        state.buttonText?.let { builder.setPositiveButton(it) { _, _ -> primary(state) } }
        state.secondaryButtonText?.let { builder.setNegativeButton(it) { _, _ -> Pulse.controlClient.dismiss() } }
        dialog = builder.create().also { it.show() }
    }

    private fun primary(state: ControlState) {
        when (state.kind) {
            ControlState.Kind.Maintenance -> checkAgain()
            ControlState.Kind.UpdateRequired, ControlState.Kind.VersionBlocked -> Pulse.control.openStore()
            ControlState.Kind.UpdateSuggested -> { Pulse.control.openStore(); Pulse.controlClient.dismiss() }
            ControlState.Kind.Announcement -> { state.url?.let(::openLink); Pulse.controlClient.dismiss() }
            else -> Pulse.controlClient.dismiss()
        }
    }

    /** "Try again": ask the server at once. The screen leaves by itself when the answer says maintenance is over. */
    private fun checkAgain() {
        Pulse.controlClient.checkNow()
        shown = null
        tryAgain?.let { handler.removeCallbacks(it) }
        val again = Runnable { render() }
        tryAgain = again; handler.postDelayed(again, 1500)
    }

    @android.annotation.SuppressLint("UseKtx") // the SDK has no KTX dependency
    private fun openLink(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { /* No app can open it. */ }
    }

    /** Dark status and navigation bar icons on the light full screen; light icons everywhere else (over a dim card or a dark screen). */
    @Suppress("DEPRECATION")
    private fun darkBarIcons(dark: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) mask else 0, mask)
        } else if (Build.VERSION.SDK_INT >= 23) {
            var flags = window.decorView.systemUiVisibility
            val bits = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            flags = if (dark) flags or bits else flags and bits.inv()
            window.decorView.systemUiVisibility = flags
        }
    }

    @Suppress("DEPRECATION")
    private fun edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}
