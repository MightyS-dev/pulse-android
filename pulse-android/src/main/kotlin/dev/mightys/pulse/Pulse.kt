package dev.mightys.pulse

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.View
import java.io.File

/** Privacy-first analytics facade for Android. Calls are safe before initialization and from multiple threads. */
public object Pulse {
    private val scheduler = PulseSerialScheduler()
    private val client = PulseClient(scheduler = scheduler, logger = PulseLogger { Log.w("MightySPulse", it) })
    private var lifecycle: PulseLifecycle? = null
    private val screens = AutomaticScreens(client)
    private var screensRegistered = false
    private val launchTimer = LaunchTimer.system()
    private val entry = EntryCallbacks(launchTimer, { client.deepLink(it) })
    private val errorHooks = ErrorHooks(client, { Thread.getDefaultUncaughtExceptionHandler() }, { Thread.setDefaultUncaughtExceptionHandler(it) })
    @SuppressLint("StaticFieldLeak") // holds the application context only
    private var state: AndroidState? = null
    private val installer = HeatmapInstaller({ x, y, w, h, keyboard, input, ignored -> client.heatmapTap(x, y, w, h, keyboard, input, ignored) }, { client.heatmapActive })
    private val shotTaker by lazy { HeatmapShot(client) }
    private val heatmapHooks = HeatmapHooks({ client.heatmapActive }, installer::install, installer::uninstall, { shotTaker.request(it) },
        sdkUiVisible = { controlHost?.uiVisible == true })
    private val mainHandler by lazy { android.os.Handler(Looper.getMainLooper()) }
    private var controlHost: ControlHost? = null
    @SuppressLint("StaticFieldLeak") // holds the application context only
    private var appContext: Context? = null
    private val controlStore = ControlStore(client.control, { appContext }, { controlHost?.currentActivity() })
    private val controlHandle = PulseControl(client.control, controlStore)

    /**
     * App Control: what the app owner set in the portal (require an update, suggest one, maintenance, a block, an
     * announcement), as a state to read and listen to. Works after [initialize]; before it the state is normal.
     */
    @JvmStatic
    public val control: PulseControl get() = controlHandle

    internal val controlClient: ControlClient get() = client.control

    /** Initialize once with an application context and ingest key. Context lookup and all IO run off-main. */
    @JvmStatic
    @JvmOverloads
    public fun initialize(context: Context?, appKey: String?, options: PulseOptions? = null) {
        if (context == null) return
        // The directory is the app's data path plus fixed names: no disk access here, so a crash right after this call
        // can already be written. The crash handler creates the directory if it is not there yet.
        safely {
            val app = context.applicationContext ?: context
            client.prepareCrashStorage(PulseFileStorage(File(File(app.applicationInfo.dataDir, "no_backup"), "mightys-pulse")))
        }
        safely { prepareControl(context) }
        safely {
            scheduler.execute(Runnable {
                safely {
                    val applicationContext = context.applicationContext ?: context
                    val configured = options ?: PulseOptions()
                    val debug = applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    val state = this.state ?: AndroidState(applicationContext) { scheduler.execute(it) }.also { this.state = it; it.start() }
                    client.initialize(appKey, configured, PulseFileStorage(File(applicationContext.noBackupFilesDir, "mightys-pulse")),
                        PulseEnvironment { AndroidMetadata.system(applicationContext, configured.sendAppIdentity) }, debug, state,
                        PulseLaunch { launchTimer.launchMs() })
                    ExitHook.run(Build.VERSION.SDK_INT, configured.captureErrors, SystemExitInfoSource(applicationContext), System.currentTimeMillis()) { client.reportExits(it) }
                    if (lifecycle == null && applicationContext is Application) {
                        val callbacks = PulseLifecycle(client) { activity -> state.noteActivity(activity); scheduler.execute(Runnable { state.refresh() }) }
                        applicationContext.registerActivityLifecycleCallbacks(callbacks)
                        lifecycle = callbacks
                    }
                    // Initialized from a visible Activity whose start already happened: that is the foreground (repeats are ignored).
                    if (context is android.app.Activity && !context.isFinishing) client.appForegrounded()
                }
            })
        }
        safely { registerScreenHooks(context) }
        safely { installControlScreens(context, (options ?: PulseOptions()).control) }
        safely { errorHooks.install((options ?: PulseOptions()).captureErrors) }
    }

    /** What the control screens need from the app, set before the client starts: the language and the store page. */
    private fun prepareControl(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        client.control.languageTags = { ControlLanguages.tags(app) }
        client.control.fallbackStoreUrl = StoreLinks.playPage(app.packageName)
    }

    /** The built-in screens go in only in automatic mode, on the calling thread, so the first Activity is not missed. */
    @Synchronized
    private fun installControlScreens(context: Context, mode: ControlMode) {
        val application = (context.applicationContext ?: context) as? Application ?: return
        if (mode != ControlMode.Automatic || controlHost != null) return
        val host = ControlHost(client.control, mainHandler::post, resume = { controlStore.resume() })
        host.start()
        application.registerActivityLifecycleCallbacks(host)
        controlHost = host
        if (context is android.app.Activity && !context.isFinishing) host.onActivityStarted(context)
    }

    /** Hooks go in right away, on the calling thread, so the launcher Activity's resume is not missed. */
    @Synchronized
    private fun registerScreenHooks(context: Context) {
        val application = (context.applicationContext ?: context) as? Application ?: return
        if (!screensRegistered) {
            application.registerActivityLifecycleCallbacks(screens.callbacks); application.registerActivityLifecycleCallbacks(entry)
            heatmapHooks.debuggable = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            client.heatmapScreenListener = Runnable { heatmapHooks.screenChanged { mainHandler.post(it) } }
            application.registerActivityLifecycleCallbacks(heatmapHooks.callbacks)
            screensRegistered = true
        }
        if (context is android.app.Activity) heatmapHooks.initializedFrom(context)
        screens.initializedFrom(context)
        entry.initializedFrom(context)
    }

    /** Capture a named event with optional finite numeric value and primitive properties. Invalid inputs are ignored. */
    @JvmStatic
    @JvmOverloads
    public fun track(type: String?, value: Double? = null, props: Map<String, *>? = null) {
        safely { client.track(type, value, props) }
    }

    /**
     * Send an error you handled yourself as `pulse.error` (kind `handled`): its type, filtered message and readable
     * frames, with optional props. Unhandled errors and crashes are captured on their own unless `captureErrors` is off.
     */
    @JvmStatic
    @JvmOverloads
    public fun trackError(error: Throwable?, props: Map<String, *>? = null) {
        safely { client.trackError(error, props) }
    }

    /**
     * Record a screen view by hand (same `pulse.screen_view` signal as the automatic capture; useful for Compose
     * destinations or custom navigation). The same screen again without another in between sends nothing.
     */
    @JvmStatic
    public fun screen(name: String?) { safely { client.screen(name) } }

    /**
     * Keep a view and everything inside it out of heatmaps: taps on it are never captured. Marks the view with a tag, so
     * call it on the main thread (Java: `Pulse.heatmapIgnore(view);`). Pass `false` to take the mark off again. Text fields
     * (EditText and other text editors, password fields) and WebViews are never captured, marked or not. For Compose, wrap
     * the area in a View that carries this mark (the SDK cannot look inside Compose).
     */
    @JvmStatic
    @JvmOverloads
    public fun heatmapIgnore(view: View?, ignore: Boolean = true) {
        safely { view?.setTag(R.id.pulse_heatmap_ignore, if (ignore) true else null) }
    }

    /**
     * Report a link that opened the app as `pulse.deep_link`: scheme, host and the path with numeric and uuid-like segments
     * as `:id`, plus the three utm_ campaign tags. Links in the launching Activity's intent are reported on their own; call
     * this from `onNewIntent` when your Activity does not call `setIntent`.
     */
    @JvmStatic
    public fun deepLink(uri: Uri?) { safely { client.deepLink(uri?.toString()) } }

    /** Same as [deepLink] with the link as text. */
    @JvmStatic
    public fun deepLink(uri: String?) { safely { client.deepLink(uri) } }

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
