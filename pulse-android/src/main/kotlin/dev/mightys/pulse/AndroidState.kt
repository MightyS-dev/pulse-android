package dev.mightys.pulse

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display

/**
 * Cached values that change while the app runs. Reads happen on the SDK worker (through [execute]) after a configuration
 * change or when an activity starts; connection and power state are kept up to date by system notifications.
 */
internal class AndroidState(context: Context, private val execute: (Runnable) -> Unit) : PulseDynamicValues, ComponentCallbacks {
    private val app = context.applicationContext ?: context
    @Volatile private var snapshot: Map<String, String> = emptyMap()
    @Volatile private var connectionType: String? = null
    @Volatile private var lowPower: Boolean? = null
    @Volatile private var notification: String? = null
    @Volatile private var rationale: Boolean? = null

    override fun values(): Map<String, String> {
        val result = LinkedHashMap(snapshot)
        connectionType?.let { result["connectionType"] = it }
        lowPower?.let { result["lowPowerMode"] = it.toString() }
        notification?.let { result["notificationPermission"] = it }
        return result
    }

    /** An Activity started: remember whether the system would show the permission rationale (a read, never a request). */
    fun noteActivity(activity: android.app.Activity) { rationale = NotificationStatus.rationale(activity) }

    /** Register notifications and read the first values. Call on the SDK worker. */
    fun start() {
        try { app.registerComponentCallbacks(this) } catch (_: Exception) { /* values then refresh with activities only */ }
        registerConnection()
        registerPower()
        refresh()
    }

    override fun onConfigurationChanged(newConfig: Configuration) { execute(Runnable { refresh() }) }
    @Deprecated("Part of the ComponentCallbacks contract.")
    override fun onLowMemory() = Unit

    /** Re-read configuration, display and accessibility settings. Call on the SDK worker. */
    fun refresh() {
        try { snapshot = DynamicFields.fields(read()) } catch (_: Exception) { /* keep the previous values */ }
        notification = NotificationStatus.read(app, rationale)
        if (Build.VERSION.SDK_INT < 24) readConnectionOnce()
    }

    @Suppress("DEPRECATION")
    private fun read(): RawState {
        val config = app.resources.configuration
        val system = Resources.getSystem().configuration
        val metrics = displayMetrics()
        val manager = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val packages = app.packageManager
        val resolver = app.contentResolver
        val locales = if (Build.VERSION.SDK_INT >= 24) system.locales.takeIf { !it.isEmpty }?.get(0) else system.locale
        val appLocale = if (Build.VERSION.SDK_INT >= 24) config.locales.takeIf { !it.isEmpty }?.get(0) else config.locale
        return RawState(
            orientation = config.orientation, uiMode = config.uiMode, layoutDirection = config.layoutDirection,
            fontScale = config.fontScale,
            fontWeightAdjustment = if (Build.VERSION.SDK_INT >= 31 && config.fontWeightAdjustment != Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED) config.fontWeightAdjustment else null,
            smallestWidthDp = config.smallestScreenWidthDp, isWatch = packages.hasSystemFeature(PackageManager.FEATURE_WATCH),
            isAutomotive = packages.hasSystemFeature("android.hardware.type.automotive"),
            widthPx = metrics.widthPixels, heightPx = metrics.heightPixels, densityDpi = metrics.densityDpi, density = metrics.density,
            displayCount = try { manager?.displays?.size } catch (_: Exception) { null },
            animatorScale = globalFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE),
            transitionScale = globalFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE),
            inversion = secureInt(resolver, "accessibility_display_inversion_enabled"),
            highContrast = secureInt(resolver, "high_text_contrast_enabled"),
            daltonizer = secureInt(resolver, "accessibility_display_daltonizer_enabled"),
            appLanguage = appLocale?.language, preferredLanguage = locales?.language, region = locales?.country,
        )
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        try {
            val manager = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) { display.getRealMetrics(metrics); if (metrics.widthPixels > 0) return metrics }
        } catch (_: Exception) { /* fall back to the resource metrics */ }
        return app.resources.displayMetrics
    }

    private fun globalFloat(resolver: android.content.ContentResolver, name: String): Float? = try {
        Settings.Global.getFloat(resolver, name, 1f)
    } catch (_: Exception) { null }

    private fun secureInt(resolver: android.content.ContentResolver, name: String): Int? = try {
        Settings.Secure.getInt(resolver, name, 0)
    } catch (_: Exception) { null }

    private fun typeOf(capabilities: NetworkCapabilities?): String = if (capabilities == null) "none" else AndroidMetadata.connection(
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

    private fun registerConnection() {
        try {
            val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            if (Build.VERSION.SDK_INT >= 23) {
                connectionType = typeOf(manager.getNetworkCapabilities(manager.activeNetwork))
            }
            if (Build.VERSION.SDK_INT >= 24) {
                manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { connectionType = typeOf(capabilities) }
                    override fun onLost(network: Network) { connectionType = "none" }
                })
            }
        } catch (_: Exception) { /* no permission or no connectivity service: the value is left out */ }
    }

    @Suppress("DEPRECATION")
    private fun readConnectionOnce() {
        try {
            val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val info = manager.activeNetworkInfo
            connectionType = when {
                info == null || !info.isConnected -> "none"
                info.type == ConnectivityManager.TYPE_WIFI -> "wifi"
                info.type == ConnectivityManager.TYPE_MOBILE -> "cellular"
                info.type == ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                else -> "other"
            }
        } catch (_: Exception) { /* left out */ }
    }

    private fun registerPower() {
        try {
            val manager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            lowPower = manager.isPowerSaveMode
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) { lowPower = manager.isPowerSaveMode }
            }
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
        } catch (_: Exception) { /* left out */ }
    }
}
