package dev.mightys.pulse

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.util.Log
import java.io.File

/** Privacy-first analytics facade for Android. Calls are safe before initialization and from multiple threads. */
public object Pulse {
    private val scheduler = PulseSerialScheduler()
    private val client = PulseClient(scheduler = scheduler, logger = PulseLogger { Log.w("MightySPulse", it) })
    private var lifecycle: PulseLifecycle? = null
    @SuppressLint("StaticFieldLeak") // holds the application context only
    private var state: AndroidState? = null

    /** Initialize once with an application context and ingest key. Context lookup and all IO run off-main. */
    @JvmStatic
    @JvmOverloads
    public fun initialize(context: Context?, appKey: String?, options: PulseOptions? = null) {
        if (context == null) return
        safely {
            scheduler.execute(Runnable {
                safely {
                    val applicationContext = context.applicationContext ?: context
                    val configured = options ?: PulseOptions()
                    val debug = applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    val state = this.state ?: AndroidState(applicationContext) { scheduler.execute(it) }.also { this.state = it; it.start() }
                    client.initialize(appKey, configured, PulseFileStorage(File(applicationContext.noBackupFilesDir, "mightys-pulse")),
                        PulseEnvironment { AndroidMetadata.system(applicationContext, configured.sendAppIdentity) }, debug, state)
                    if (lifecycle == null && applicationContext is Application) {
                        val callbacks = PulseLifecycle(client) { scheduler.execute(Runnable { state.refresh() }) }
                        applicationContext.registerActivityLifecycleCallbacks(callbacks)
                        lifecycle = callbacks
                    }
                }
            })
        }
    }

    /** Capture a named event with optional finite numeric value and primitive properties. Invalid inputs are ignored. */
    @JvmStatic
    @JvmOverloads
    public fun track(type: String?, value: Double? = null, props: Map<String, *>? = null) {
        safely { client.track(type, value, props) }
    }

    /** Hash a caller-selected user ID on-device; null or empty clears it for future signals. */
    @JvmStatic
    public fun setUser(identifier: String?) { safely { client.setUser(identifier) } }

    /** Nonblocking flush. Optional completion runs on the SDK worker; it is not a database durability acknowledgement. */
    @JvmStatic
    @JvmOverloads
    public fun flush(completion: Runnable? = null) { safely { client.flush(completion) } }

    /** Blocking flush for background workers. Returns false immediately on Android's main thread or after timeout. */
    @JvmStatic
    public fun flush(timeoutMillis: Long): Boolean = try {
        if (Looper.myLooper() == Looper.getMainLooper()) false else client.flush(timeoutMillis)
    } catch (_: Exception) { false }

    /** Persisted app-wide opt-out. Before initialize it conservatively reads true; explicit opt-in waits for its disk write. */
    @get:JvmStatic
    @set:JvmStatic
    public var optOut: Boolean
        get() = client.optOut
        set(value) { safely { client.optOut = value } }

    private fun safely(action: () -> Unit) { try { action() } catch (_: Exception) { /* Never propagate SDK errors into the host app. */ } }
}
