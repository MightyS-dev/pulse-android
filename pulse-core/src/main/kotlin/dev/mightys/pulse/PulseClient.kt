package dev.mightys.pulse

import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Platform bridge for the Pulse facade. State, hashing, serialization and disk IO use one serial worker. */
public class PulseClient @JvmOverloads constructor(
    private val clock: PulseClock = PulseClock { System.currentTimeMillis() },
    private val scheduler: PulseScheduler = PulseSerialScheduler(),
    private val transport: PulseTransport = PulseHttpTransport(),
    private val random: PulseRandom = PulseRandom { Math.random() },
    private val logger: PulseLogger = PulseLogger {},
) {
    // 0 is uninitialized, 1 is durably enabled, 2 is closed. Early captures stay memory-only until privacy is read.
    private val privacy = AtomicInteger(0)
    private val revision = AtomicLong(0)
    private val privacyLock = Any()
    private var desiredPreference: Boolean? = null
    private var preferenceRevision = 0L
    private var generation = ""
    @Volatile private var storage: PulseStorage? = null
    /** Where a crash is written while [storage] is not loaded yet; known as soon as initialize returns. */
    @Volatile private var crashStorage: PulseStorage? = null
    private var queueName: String? = null
    private var endpoint: java.net.URL? = null
    private var appKey: String? = null
    private var options = PulseOptions()
    private var system = emptyMap<String, String>()
    private var queue = DurableQueue(options)
    private val capture = CaptureState()
    private val early = ArrayDeque<Input>()
    private var loaded = false
    private var interval = 20.0
    private var nextFlush = 0L
    private var pauseUntil = 0L
    private var storageRetryAt = 0L
    private var forced = false
    private var halted = false
    private var stopped = false
    private var gzipDisabled = false
    private var active: PulseCancellation? = null
    private var activeId: String? = null
    private var timer: PulseCancellation? = null
    private var watchdog: PulseCancellation? = null
    private var pumpQueued = false
    private var installHash: String? = null
    private var installCreated = false
    /** Test switch that turns the three automatic session signals off whatever the options and the portal say. */
    internal var automaticSignals = true
    @Volatile private var settings = RemoteSettings()
    private var configToken: String? = null
    private var configRequest: PulseCancellation? = null
    private var configWatchdog: PulseCancellation? = null
    /** Settings of this run's refresh. Only the heatmap rules follow it at once; everything else holds for the run. */
    private var liveSettings: RemoteSettings? = null
    private var debugBuild = false
    private val heatmap = HeatmapCapture(::heatmapAllowed, { capture.sessionAt(it) }, ::emitHeatmap)
    private val shots = HeatmapShots({ storage }, transport, clock, ::shotTarget, { system["appVersion"] ?: "" }, { debugBuild },
        { action -> enqueue { if (privacy.get() == 1 && !stopped) action() } }, ::warn)
    /** True while taps are captured: allowed by the portal and the options, and a screen is known. Cheap: read it before any hit test. */
    @Volatile public var heatmapActive: Boolean = false
        private set
    /** Called on the SDK worker when the heatmap screen changes while capture is on (the debug screenshot hook). */
    @Volatile public var heatmapScreenListener: Runnable? = null

    /**
     * Whether automatic screen hooks should be installed: the code option is not false and the settings saved from an
     * earlier launch allow screen views (on when there are none). Settled by [initialize]. Read it from the SDK worker after initialize has run.
     */
    @Volatile public var screenViewHooksEnabled: Boolean = true
        private set
    private var dynamic = PulseDynamicValues { emptyMap() }
    private var usage: UsageStats? = null
    private var usageUnreadable = false
    private var sessionFields = emptyMap<String, String>()
    private var newInstallSession = false
    private var lastScreen: String? = null
    private val errorCounts = HashMap<String, Int>()
    private var errorCountSession: String? = null
    private var launch = PulseLaunch { null }
    private var launchPending = true
    private var foreground: ForegroundStats? = null
    private var foregroundStart: Long? = null
    private var foregroundSeen = false
    private var quota: DayQuota? = null
    private val sessionCaps = HashMap<String, Int>()
    private var capSession: String? = null
    private var droppedAutomatic = 0
    private var permissions: Map<String, String>? = null
    @Volatile private var currentSession: String? = null
    private data class Waiter(val token: String, var target: Set<String>?, val completion: Runnable)
    private val waiters = mutableListOf<Waiter>()
    /** App Control: the rules the app owner set in the portal and the state derived from them. */
    public val control: ControlClient = ControlClient(clock, scheduler, transport)

    /** Conservative before initialization; true until persisted privacy has been read or opt-in saved. */
    public var optOut: Boolean
        get() = privacy.get() != 1
        set(value) {
            val requestedRevision = synchronized(privacyLock) { privacy.set(2); revision.incrementAndGet() }
            enqueue {
                if (requestedRevision != revision.get()) return@enqueue
                capture.reset(); early.clear(); queue.clear(); cancelActive(); cancelConfig(); installHash = null
                heatmap.drop(); heatmap.setScreen(null, 0); shots.forget(); heatmapActive = false
                usage = null; usageUnreadable = false; sessionFields = emptyMap(); newInstallSession = false; installCreated = false
                lastScreen = null; errorCounts.clear(); errorCountSession = null
                foreground = null; foregroundStart = null; quota = null; sessionCaps.clear(); capSession = null; droppedAutomatic = 0
                permissions = null
                desiredPreference = value; preferenceRevision = requestedRevision
                generation = UUID.randomUUID().toString()
                persistPreference(); completeWaiters(); requestPump()
            }
        }

    /** Initialize once. Invalid keys/hosts are ignored; no IO occurs on the calling thread. */
    @JvmOverloads
    public fun initialize(key: String?, requested: PulseOptions? = null, storage: PulseStorage,
        environment: PulseEnvironment, defaultDebug: Boolean = false,
        dynamicValues: PulseDynamicValues = PulseDynamicValues { emptyMap() }, launch: PulseLaunch = PulseLaunch { null }) {
        if (crashStorage == null && !key.isNullOrBlank()) crashStorage = storage
        enqueue {
            if (appKey != null || stopped || key.isNullOrBlank() || key.any { it.code < 32 || it.code == 127 }) return@enqueue
            val normalized = (requested ?: PulseOptions()).normalized()
            val address = endpoint(normalized.host) ?: return@enqueue
            this.storage = storage; options = normalized.copy(debug = normalized.debug ?: defaultDebug)
            appKey = key; endpoint = address; debugBuild = defaultDebug; queueName = "queue-" + Hash.hex(key) + ".bin"
            queue = DurableQueue(options)
            interval = options.flushIntervalSeconds; nextFlush = clock.nowMillis() // first batch leaves at once; later ones follow the interval
            dynamic = dynamicValues; this.launch = launch
            system = environment.system().filterKeys { it in SystemKeys.BASE || options.sendAppIdentity && it in SystemKeys.IDENTITY || it in SystemKeys.EXTRA }
                .mapValues { if (it.key in SystemKeys.EXTRA) boundedExtra(it.value) else boundedString(it.value) }.toMutableMap().apply {
                    put("sdkName", PulseInfo.SDK_NAME); put("sdkVersion", PulseInfo.VERSION)
                    options.appVersion?.let { put("appVersion", boundedString(it)) }
                }
            readPrivacy(); sendPendingCrash(); applyCachedSettings(); requestPump(); fetchSettings()
            startControl(key, address, storage, normalized)
        }
    }

    /** App Control starts after everything else; it never waits for the network and a failure in it never reaches the app. */
    private fun startControl(key: String, address: java.net.URL, storage: PulseStorage, normalized: PulseOptions) {
        try {
            control.start(key, address, normalized.control, storage, system["appVersion"].orEmpty(), system["appName"].orEmpty(),
                PulseInfo.SDK_NAME + "@" + PulseInfo.VERSION, "android", debugBuild)
        } catch (_: Exception) { warn("App Control not started.") }
    }

    /** Capture one signal. Unsupported props and invalid signals are dropped safely. */
    @JvmOverloads
    public fun track(type: String?, value: Double? = null, props: Map<String, *>? = null) {
        if (privacy.get() == 2 || (type == null || type.length > 200 || type.isBlank()) || !JsonWriter.validUnicode(type) || value?.isFinite() == false) return
        val at = clock.nowMillis()
        val capturedRevision = revision.get()
        val copied = try { copyProps(props) } catch (_: Exception) { PropertyCopy(emptyMap(), true) }
        enqueue { capture(at, type, value, copied, capturedRevision) }
    }

    private fun capture(at: Long, type: String, value: Double?, copied: PropertyCopy, capturedRevision: Long, screenKind: Int = 0,
        error: Map<String, Any?>? = null, heatmap: Map<String, Any?>? = null) {
        if (privacy.get() == 2 || stopped || capturedRevision != revision.get()) return
        if (copied.dropped) warn("Unsupported or oversized properties ignored.")
        val sanitized = SignalJson.sanitize(copied.values) { warn("Unsupported or oversized properties ignored.") }
        val input = capture.capture(at, type, value, sanitized, localFields(at), if (appKey != null) extraFields() else null).copy(screen = screenKind, error = error, heatmap = heatmap)
        currentSession = input.session
        if (appKey == null) {
            if (early.size == 100) early.removeFirst()
            early.addLast(input)
        } else { append(input); requestPump() }
    }

    /**
     * Send a handled error as `pulse.error` with kind `handled`: its type, filtered message and readable frames, plus
     * optional props. At most 10 per error type and session are sent; the rest are dropped.
     */
    @JvmOverloads
    public fun trackError(error: Throwable?, props: Map<String, *>? = null) {
        if (error == null || privacy.get() == 2) return
        val at = clock.nowMillis()
        val capturedRevision = revision.get()
        val copied = try { copyProps(props) } catch (_: Exception) { PropertyCopy(emptyMap(), true) }
        enqueue { captureError(at, error, copied, capturedRevision) }
    }

    private fun captureError(at: Long, error: Throwable, copied: PropertyCopy, capturedRevision: Long) {
        if (privacy.get() == 2 || stopped || capturedRevision != revision.get()) return
        val report = ErrorPayload.from("handled", error)
        val type = report["type"] as String
        val session = capture.sessionAt(at)
        if (session == null || session != errorCountSession) errorCounts.clear()
        val count = errorCounts[type] ?: 0
        if (count >= MAX_ERRORS_PER_TYPE) return
        capture(at, "pulse.error", null, copied, capturedRevision, error = report)
        errorCountSession = capture.sessionAt(at)
        errorCounts[type] = count + 1
    }

    /**
     * A handler for uncaught exceptions that writes a small crash file and then calls [previous] with the same
     * arguments. The next launch sends the file as `pulse.error` with kind `crash` and deletes it. Platform adapters
     * install it as the default handler; waiting for the file is bounded by [timeoutMillis].
     */
    @JvmOverloads
    public fun crashHandler(previous: Thread.UncaughtExceptionHandler?, timeoutMillis: Long = 1000): Thread.UncaughtExceptionHandler =
        CrashHandler(previous, ::crashTarget, clock, { currentSession }, timeoutMillis.coerceIn(0, 5000))

    /**
     * Where a crash goes right now. Opted out or stopped: nowhere. Privacy known: the loaded storage. Privacy not known
     * yet (the worker has not run): the storage given to initialize, marked early; the next launch reads the stored
     * choice first and drops the file when the user is opted out.
     */
    private fun crashTarget(): Pair<PulseStorage, Boolean>? {
        if (stopped) return null
        return when (privacy.get()) {
            2 -> null
            1 -> storage?.let { it to false }
            else -> (storage ?: crashStorage)?.let { it to true }
        }
    }

    /** Lets a platform adapter name the crash directory before its worker has created the real storage (no IO). */
    public fun prepareCrashStorage(early: PulseStorage) { if (crashStorage == null) crashStorage = early }

    /** Send the crash file an earlier launch left behind, once. An opted-out app drops it instead. */
    private fun sendPendingCrash() {
        val files = storage ?: return
        if (privacy.get() != 1) return
        try {
            val bytes = files.read(CrashRecord.FILE) ?: return
            files.remove(CrashRecord.FILE)
            val now = clock.nowMillis()
            val record = CrashRecord.decode(bytes, now) ?: return
            store(Input(record.at, record.session, null, "pulse.error", null, emptyMap(), local = localFields(record.at),
                extra = extraFields(), error = record.error))
        } catch (_: Exception) { warn("Crash report not read.") }
    }

    private fun dropCrashFile(store: PulseStorage) { try { store.remove(CrashRecord.FILE) } catch (_: Exception) { warn("Crash report not removed.") } }

    /**
     * Record a screen appearance as `pulse.screen_view` with `screen` and, when known, `previousScreen`.
     * The name is cleaned (query and fragment removed, numeric and uuid-like path segments become `:id`, at most 128
     * characters). The same screen appearing again without another screen in between sends nothing.
     */
    public fun screen(name: String?) { screen(name, false) }

    /** A screen reported by an automatic hook; dropped when the code option `automaticScreenViews` is false. */
    public fun automaticScreen(name: String?) { screen(name, true) }

    private fun screen(name: String?, automatic: Boolean) {
        if (privacy.get() == 2) return
        val cleaned = ScreenName.clean(name) ?: return
        val at = clock.nowMillis()
        val capturedRevision = revision.get()
        // Captured here, in call order, with its time. Whether it is sent (code option, saved portal settings, same
        // screen twice) and its previousScreen are judged when it is stored, still in that order.
        enqueue {
            capture(at, "pulse.screen_view", null, PropertyCopy(mapOf("screen" to cleaned), false), capturedRevision, if (automatic) 2 else 1)
        }
    }

    /** Decide a captured screen view when it is stored. Null means it is not sent; a session it opened still starts. */
    private fun resolveScreen(input: Input): Input? {
        val name = input.props["screen"] as? String
        if (name == null || input.screen == 2 && !options.automaticScreenViews || name == lastScreen) {
            if (input.newSession) startSession(input)
            return null
        }
        val props = linkedMapOf<String, Any?>("screen" to name)
        lastScreen?.let { props["previousScreen"] = it }
        lastScreen = name
        return input.copy(props = props, screen = 0, auto = input.screen == 2)
    }

    /** Hash a caller-selected ID on the worker, or clear it. The raw ID is never persisted or logged. */
    public fun setUser(identifier: String?) {
        if (privacy.get() == 2) return
        val capturedRevision = revision.get()
        enqueue { if (privacy.get() != 2 && !stopped && capturedRevision == revision.get()) capture.setUser(identifier) }
    }

    /** Request nonblocking drain of the captured queue. Completion also runs on opt-out, halt or shutdown. */
    @JvmOverloads
    public fun flush(completion: Runnable? = null): PulseCancellation {
        val token = UUID.randomUUID().toString()
        val cancelled = AtomicInteger(0)
        enqueue {
            if (cancelled.get() != 0) return@enqueue
            if (appKey == null || optOut || stopped || halted) { callback(completion); return@enqueue }
            heatmap.flush(clock.nowMillis())
            val didLoad = load()
            if (completion != null) waiters.add(Waiter(token, if (didLoad) liveIds() else null, completion))
            forced = true; pump()
        }
        return PulseCancellation { cancelled.set(1); enqueue { waiters.removeAll { it.token == token } } }
    }

    /** Blocking worker-only drain, bounded by timeout. False means the queue has not finished. */
    public fun flush(timeoutMillis: Long): Boolean {
        if ((scheduler as? PulseSerialScheduler)?.isWorkerThread() == true) return false
        val done = CountDownLatch(1)
        val cancellation = flush(Runnable { done.countDown() })
        return try { done.await(timeoutMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        finally { cancellation.cancel() }
    }

    /** Best-effort background save and flush. Never waits for network. */
    public fun background() { enqueue { if (!stopped) { heatmap.flush(clock.nowMillis()); touchUsage(); forced = true; pump() } } }

    /** Bounded local save for background workers. The UI adapter calls the nonblocking variant. */
    @JvmOverloads
    public fun backgroundSave(timeoutMillis: Long = 2000): Boolean = saveAndWait(timeoutMillis, false)

    /** Stop this client's timers and request, and save locally, with at most two seconds of waiting. */
    @JvmOverloads
    public fun stop(timeoutMillis: Long = 2000): Boolean = saveAndWait(timeoutMillis, true)

    private fun saveAndWait(timeoutMillis: Long, stop: Boolean): Boolean {
        if ((scheduler as? PulseSerialScheduler)?.isWorkerThread() == true) return false
        val done = CountDownLatch(1)
        enqueue {
            try {
                if (stop) { control.stop(); heatmap.flush(clock.nowMillis()); touchUsage(); stopped = true; heatmapActive = false; timer?.cancel(); timer = null; cancelActive(); cancelConfig(); if (load()) persist(); finishAllWaiters() }
                else { heatmap.flush(clock.nowMillis()); forced = true; pump() }
            } finally { done.countDown() }
        }
        return try { done.await(timeoutMillis.coerceIn(0, 2000), TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    private fun readPrivacy() {
        try {
            val store = storage ?: return
            val marker = store.read("opt-out")
            val savedGeneration = store.read("privacy-generation")
            val text = savedGeneration?.let(JsonReader::utf8)
            val valid = savedGeneration == null || text != null && UUID.fromString(text).toString() == text
            if (valid && marker != null && !marker.contentEquals(byteArrayOf(0))) dropCrashFile(store)
            if (desiredPreference == null) {
                generation = if (valid) text ?: "" else ""
                synchronized(privacyLock) {
                    if (revision.get() == 0L) privacy.set(if (valid && (marker == null || marker.contentEquals(byteArrayOf(0)))) 1 else 2)
                }
            }
        } catch (_: Exception) { synchronized(privacyLock) { privacy.set(2) }; warn("Privacy read failed; collection suspended.") }
    }

    private fun persistPreference() {
        val desired = desiredPreference ?: return
        val store = storage ?: return
        try {
            if (desired) store.replace("opt-out", byteArrayOf(1))
            store.replace("privacy-generation", generation.toByteArray(Charsets.UTF_8))
            queueName?.let(store::remove)
            store.remove(INSTALL_ID)
            store.remove(USAGE)
            store.remove(CrashRecord.FILE)
            store.remove(RemoteSettings.FILE)
            store.remove(HeatmapShots.FILE)
            store.remove(AutoSignals.FOREGROUND_FILE)
            store.remove(AutoSignals.QUOTA_FILE)
            store.remove(AutoSignals.PERMISSIONS_FILE)
            // Records from the time of the opt-out must not be sent after a later opt-in: the marker moves to now.
            if (desired) store.replace(AutoSignals.EXIT_MARKER_FILE, ExitMarker.encode(clock.nowMillis()))
            if (!desired) store.replace("opt-out", byteArrayOf(0))
            desiredPreference = null
            synchronized(privacyLock) { if (preferenceRevision == revision.get()) privacy.set(if (desired) 2 else 1) }
        } catch (_: Exception) {
            synchronized(privacyLock) { privacy.set(2) }
            storageRetryAt = clock.nowMillis() + 1000
            warn("Privacy write failed; collection and sending suspended.")
        }
    }

    private fun append(captured: Input) {
        val input = if (captured.screen > 0) resolveScreen(captured) ?: return else captured
        if (input.newSession) startSession(input)
        checkPermissions(input)
        store(input)
    }

    /** Latest capture timestamp handed out in this process, in milliseconds. Touched only on the worker. */
    private var lastStamp = Long.MIN_VALUE / 2

    /** [exact]: the signal keeps its own time (an exit record carries the OS time) and does not move the running stamp. */
    private fun store(stored: Input, exact: Boolean = false) {
        if (!signalAllowed(stored.type)) return
        if (!withinQuota(stored)) return
        if (stored.type == RemoteSettings.SCREEN_VIEW) screenStored(stored.props["screen"] as? String, stored.timestamp)
        // A heatmap signal never carries (and so never uses up) the dropped count.
        val carried = if (droppedAutomatic > 0 && stored.type != RemoteSettings.HEATMAP) stored.copy(local = stored.local + ("droppedAutomatic" to droppedAutomatic.toString())) else stored
        val captured = if (carried.error != null && options.errorDetail == ErrorDetail.TYPE_ONLY) carried.copy(error = ErrorPayload.typeOnly(carried.error)) else carried
        // Strictly increasing at millisecond resolution, in storing order: automatic signals are stored before the
        // signal that started the session, so they keep the smaller timestamps.
        val input = if (exact) captured else { lastStamp = maxOf(captured.timestamp, lastStamp + 1); captured.copy(timestamp = lastStamp) }
        val hashed = if (input.userHash != null) input else input.copy(userHash = installHash())
        val json = SignalJson.encode(hashed, systemFor(input), options.debug == true) ?: return
        if (stored.type != RemoteSettings.HEATMAP) droppedAutomatic = 0
        queue.entries.add(Entry(UUID.randomUUID().toString(), input.timestamp, json))
    }

    private fun systemFor(input: Input): Map<String, String> {
        val merged = LinkedHashMap(system)
        merged.putAll(sessionFields)
        merged.putAll(input.local)
        merged.putAll(input.extra ?: extraFields())
        if (input.sessionStart > 0 && input.timestamp >= input.sessionStart) {
            merged["sessionSeconds"] = ((input.timestamp - input.sessionStart) / 1000).toString()
        }
        if (newInstallSession) merged["isNewInstall"] = "true"
        foregroundKeys(input.timestamp, merged)
        if (settings.hiddenKeys.isNotEmpty()) merged.keys.removeAll(settings.hiddenKeys)
        return merged
    }

    /** Time zone values, read when the signal is captured. */
    private fun localFields(at: Long): Map<String, String> {
        val zone = TimeZone.getDefault()
        return mapOf("timeZone" to boundedExtra(zone.id), "utcOffsetMinutes" to (zone.getOffset(at) / 60000).toString())
    }

    /** Platform values that can change while the app runs, from cached state. */
    private fun extraFields(): Map<String, String> = try {
        dynamic.values().filterKeys { it in SystemKeys.EXTRA }.mapValues { boundedExtra(it.value) }.filterValues { it.isNotEmpty() }
    } catch (_: Exception) { emptyMap() }

    private fun startSession(input: Input) {
        installHash()
        val created = installCreated; installCreated = false
        val current = usage ?: loadUsage()
        val offset = TimeZone.getDefault().getOffset(input.timestamp).toLong()
        val started = current.startSession(input.timestamp, offset, input.previousStart, input.previousEnd,
            system["appVersion"] ?: "", system["appBuildNumber"] ?: "")
        // The new version is remembered only when pulse.app_updated can actually be queued, so a launch where it is
        // switched off does not swallow the update: a later launch with it allowed still sends it.
        val holdVersion = started.updated && !created && !signalAllowed("pulse.app_updated")
        usage = if (holdVersion) started.usage.copy(version = current.version, build = current.build) else started.usage
        sessionFields = started.fields; newInstallSession = created
        persistUsage()
        if (created) store(input.copy(type = "pulse.new_install", value = null, props = emptyMap(), newSession = false, error = null, heatmap = null))
        if (started.updated && !created) {
            val props = mapOf<String, Any?>("previousVersion" to (started.previousVersion ?: ""), "previousBuild" to (started.previousBuild ?: ""))
            store(input.copy(type = "pulse.app_updated", value = null, props = props, newSession = false, error = null, heatmap = null))
        }
        val cold = launchPending; launchPending = false
        val local = if (cold) launchFields(input.local) else input.local
        store(input.copy(type = "pulse.session_started", value = null, props = emptyMap(), newSession = false, local = local, error = null, heatmap = null))
    }

    /** `launchMs` for the session_started of the first session of the process; left out when unknown or absurd. */
    private fun launchFields(local: Map<String, String>): Map<String, String> {
        val ms = try { launch.launchMs() } catch (_: Exception) { null }
        return if (ms != null && ms in 0..AutoSignals.MAX_LAUNCH_MS) local + ("launchMs" to ms.toString()) else local
    }

    /** What the portal settings and the code options allow to be sent; a code option can only switch things off. */
    private fun signalAllowed(type: String?): Boolean {
        if (type == null) return true
        if (type == RemoteSettings.HEATMAP) return heatmapAllowed()
        if (type in settings.pausedSignals) return false
        if (type == RemoteSettings.SCREEN_VIEW) return portalAllowsScreenViews()
        if (type in RemoteSettings.AUTOMATIC_TYPES) {
            // Opt-in types need the portal to list them; no cached settings means they stay off.
            if (type in AutoSignals.OPT_IN && type !in settings.enabledSignals) return false
            return automaticSignals && options.automaticSignals && settings.automaticSignals && type !in settings.disabledSignals
        }
        return type !in settings.disabledSignals
    }
    private fun portalAllowsScreenViews(): Boolean =
        settings.screenViews && settings.automaticSignals && RemoteSettings.SCREEN_VIEW !in settings.disabledSignals &&
            RemoteSettings.SCREEN_VIEW !in settings.pausedSignals

    /** Use the settings saved by an earlier launch for this whole run. No file or a damaged file means everything on. */
    private fun applyCachedSettings() {
        val bytes = try { storage?.read(RemoteSettings.FILE) } catch (_: Exception) { null }
        settings = bytes?.let(RemoteSettings::parse) ?: RemoteSettings()
        screenViewHooksEnabled = options.automaticScreenViews && portalAllowsScreenViews()
        liveSettings = null; refreshHeatmap()
    }

    /** Ask the portal for the settings once. The answer is saved for the next launch and never changes this run. */
    private fun fetchSettings() {
        val store = storage ?: return
        val base = endpoint ?: return
        val key = appKey ?: return
        if (privacy.get() != 1 || stopped) return
        val url = try { java.net.URL(base.toString().removeSuffix("signals") + "config") } catch (_: Exception) { return }
        val tag = settings.version?.let { "\"$it\"" }
        val token = UUID.randomUUID().toString()
        configToken = token
        configRequest = try {
            transport.send(PulseRequest(url, key, byteArrayOf(), false, true, tag)) { response ->
                enqueue { settingsReceived(response, token, store) }
            }
        } catch (_: Exception) { configToken = null; null }
        configWatchdog = scheduler.schedule(REQUEST_DEADLINE_MILLIS, Runnable {
            safely { if (configToken == token) { configRequest?.cancel(); configToken = null; configRequest = null } }
        })
    }

    private fun settingsReceived(response: PulseResponse, token: String, store: PulseStorage) {
        if (configToken != token) return
        configToken = null; configRequest = null; configWatchdog?.cancel(); configWatchdog = null
        if (response.status != 200 || stopped || privacy.get() != 1) return
        val parsed = RemoteSettings.parse(response.body) ?: return
        try { store.replace(RemoteSettings.FILE, response.body) } catch (_: Exception) { warn("Settings not saved.") }
        liveSettings = parsed; refreshHeatmap()
    }

    // ---- heatmaps: taps per screen, and the debug screenshot ----

    /** Portal switch and code option, automatic signals, the type not disabled or paused, and screen views allowed (the screen name comes from them). */
    private fun heatmapAllowed(): Boolean {
        if (privacy.get() != 1 || stopped || appKey == null) return false
        val live = liveSettings ?: settings
        return options.captureHeatmaps && options.automaticSignals && automaticSignals && live.heatmaps && live.automaticSignals &&
            RemoteSettings.HEATMAP !in live.disabledSignals && RemoteSettings.HEATMAP !in live.pausedSignals && portalAllowsScreenViews()
    }

    /** Re-reads the rules: switching off stops capture at once and drops the buffer. */
    private fun refreshHeatmap() {
        val allowed = heatmapAllowed()
        if (!allowed) heatmap.drop()
        heatmapActive = allowed && heatmap.screen != null
    }

    /** A screen view was stored: it is the heatmap screen from now on, and the old screen's points go out first. */
    private fun screenStored(name: String?, at: Long) {
        heatmap.setScreen(name, at); refreshHeatmap()
        if (heatmapActive) try { heatmapScreenListener?.run() } catch (_: Exception) { warn("Heatmap screen hook failed.") }
    }

    private fun emitHeatmap(at: Long, points: Map<String, Any?>) {
        capture(at, RemoteSettings.HEATMAP, null, PropertyCopy(emptyMap(), false), revision.get(), heatmap = points)
        heatmap.adoptSession(currentSession)
    }

    private fun shotTarget(): HeatmapShots.Target? { val where = endpoint ?: return null; val key = appKey ?: return null; return HeatmapShots.Target(where, key) }

    /**
     * One tap, already reduced by the platform to a position in logical px (dp on Android) and the privacy facts it could
     * see. A tap is dropped here when capture is off, no screen is known, the keyboard is visible, the target is a text or
     * secure input or the target is marked to be ignored. Safe on any thread; the work runs on the SDK worker.
     */
    public fun heatmapTap(x: Double, y: Double, width: Double, height: Double, keyboardVisible: Boolean, inputTarget: Boolean, ignored: Boolean) {
        if (!heatmapActive || keyboardVisible || inputTarget || ignored) return
        val at = clock.nowMillis(); val capturedRevision = revision.get()
        enqueue { if (capturedRevision == revision.get() && privacy.get() == 1) heatmap.onTap(at, x, y, width, height, false, false, false) }
    }

    /** Whether a debug picture of the current screen is wanted now (debug app build, capture on, not yet sent for this version, not rate limited). */
    public fun heatmapShotTicket(widthDp: Double, heightDp: Double, result: (HeatmapShotTicket?) -> Unit) {
        enqueue {
            val ticket = if (heatmapAllowed()) shots.ticket(heatmap.screen, widthDp, heightDp) else null
            try { result(ticket) } catch (_: Exception) { warn("Heatmap picture hook failed.") }
        }
    }

    /** Uploads one encoded picture (PNG, JPEG or WebP, at most 2 MiB) for a ticket. [done] gets true only when the server stored it. */
    @JvmOverloads
    public fun heatmapScreenshot(ticket: HeatmapShotTicket, bytes: ByteArray, contentType: String, done: (Boolean) -> Unit = {}) {
        enqueue { if (heatmapAllowed()) shots.upload(ticket, bytes, contentType, done) else done(false) }
    }

    private fun cancelConfig() {
        configToken = null; configRequest?.cancel(); configRequest = null; configWatchdog?.cancel(); configWatchdog = null
    }

    // ---- automatic events: foreground, deep links, permissions, quota guards, exit reasons ----

    /**
     * The app came to the foreground (the first Activity started). Counts the foreground for `foregroundsToday` and sends
     * `pulse.app_opened` when the portal switched it on. Repeated calls without a [appBackgrounded] in between do nothing.
     */
    public fun appForegrounded() {
        control.onForeground()
        if (privacy.get() == 2) return
        val at = clock.nowMillis(); val capturedRevision = revision.get()
        enqueue { foregrounded(at, capturedRevision) }
    }

    /** The app went to the background (ProcessLifecycle ON_STOP). Saves the session end, records the signal and requests a flush. */
    public fun appBackgrounded() {
        if (privacy.get() == 2) return
        val at = clock.nowMillis(); val capturedRevision = revision.get()
        enqueue { backgrounded(at, capturedRevision) }
    }

    private fun foregrounded(at: Long, capturedRevision: Long) {
        if (privacy.get() == 2 || stopped || capturedRevision != revision.get() || appKey == null || foregroundStart != null) return
        val cold = !foregroundSeen
        foregroundSeen = true; foregroundStart = at
        val today = UsageStats.dayOf(at, TimeZone.getDefault().getOffset(at).toLong())
        foreground = (foreground ?: loadForeground()).foregrounded(today)
        persistForeground()
        capture(at, AutoSignals.APP_OPENED, null, PropertyCopy(mapOf("coldStart" to cold.toString()), false), capturedRevision)
    }

    private fun backgrounded(at: Long, capturedRevision: Long) {
        if (privacy.get() == 2 || stopped || capturedRevision != revision.get() || appKey == null) return
        val start = foregroundStart ?: return
        foregroundStart = null
        val seconds = ((at - start) / 1000).coerceAtLeast(0)
        // Persist the real end before trying the signal, even when the type is disabled or its cap was reached.
        // This must not move the last signal time used by the 30 minute inactivity rule.
        capture.backgrounded(at)
        touchUsage()
        // The signal shows the previous period in previousForegroundSeconds; this period is stored after it.
        // Idle for 30 minutes or more: the session has ended, and a background event alone must not open a new one. It is dropped.
        if (capture.sessionAt(at) != null) capture(at, AutoSignals.APP_BACKGROUNDED, null, PropertyCopy(mapOf("foregroundSeconds" to seconds), false), capturedRevision)
        foreground = (foreground ?: loadForeground()).backgrounded(seconds)
        persistForeground()
        background()
    }

    /**
     * Report a link that opened the app, as `pulse.deep_link` with scheme, host and a path where numeric and uuid-like
     * segments become `:id`, plus the three utm_ campaign tags when present. Nothing else of the link is read or sent.
     */
    public fun deepLink(uri: String?) {
        if (privacy.get() == 2) return
        val props = DeepLink.props(uri) ?: return
        val at = clock.nowMillis(); val capturedRevision = revision.get()
        enqueue { capture(at, AutoSignals.DEEP_LINK, null, PropertyCopy(props, false), capturedRevision) }
    }

    /**
     * Send the exit records an Android process left behind as `pulse.error` with kind `exit`. Only records newer than the
     * stored marker, not older than 7 days, at most 10 per launch (the newest), each once. Does nothing with `captureErrors` off.
     */
    public fun reportExits(records: List<PulseExitRecord>) {
        if (privacy.get() == 2) return
        val copy = records.toList()
        enqueue { exitsReceived(copy) }
    }

    private fun exitsReceived(records: List<PulseExitRecord>) {
        if (privacy.get() != 1 || stopped || !options.captureErrors || appKey == null) return
        val files = storage ?: return
        val now = clock.nowMillis()
        val marker = try { files.read(AutoSignals.EXIT_MARKER_FILE)?.let { ExitMarker.decode(it) ?: return } } catch (_: Exception) { return }
        val newer = records.filter { it.timeMillis > (marker ?: Long.MIN_VALUE) && it.timeMillis <= now + AutoSignals.EXIT_MAX_FUTURE_MILLIS }
        val newest = newer.maxOfOrNull { it.timeMillis } ?: return
        // The marker is written first: a crash while sending can lose a record but never send one twice.
        try { files.replace(AutoSignals.EXIT_MARKER_FILE, ExitMarker.encode(newest)) } catch (_: Exception) { warn("Exit marker not saved."); return }
        val session = UUID.randomUUID().toString()
        newer.filter { it.timeMillis >= now - AutoSignals.EXIT_MAX_AGE_MILLIS }.sortedBy { it.timeMillis }.takeLast(AutoSignals.MAX_EXITS_PER_LAUNCH).forEach {
            val error = ErrorPayload.normalize("exit", it.reason, null, it.frames, false)
            store(Input(it.timeMillis, session, null, "pulse.error", null, emptyMap(), local = localFields(it.timeMillis), extra = extraFields(), error = error), exact = true)
        }
        requestPump()
    }

    /** Sends `pulse.permission_changed` once when a status read now differs from the one stored; the first reading is only stored. */
    private fun checkPermissions(input: Input) {
        val current = extraFields().filterKeys { it in AutoSignals.PERMISSIONS }.filterValues { it in AutoSignals.PERMISSION_VALUES }
        if (current.isEmpty()) return
        var known = permissions ?: loadPermissions()
        var changed = false
        for ((key, name) in AutoSignals.PERMISSIONS) {
            val now = current[key] ?: continue
            val before = known[name]
            if (before == now) continue
            known = known + (name to now); changed = true
            if (before != null) store(input.copy(type = AutoSignals.PERMISSION_CHANGED, value = null,
                props = mapOf("permission" to name, "from" to before, "to" to now), newSession = false, screen = 0, error = null, auto = false, heatmap = null))
        }
        if (changed) { permissions = known; persistPermissions() }
    }

    /** Per session and per local day limits for automatic signals; a dropped one is counted for `droppedAutomatic`. */
    private fun withinQuota(input: Input): Boolean {
        val type = input.type ?: return true
        val automatic = type in RemoteSettings.AUTOMATIC_TYPES || type == RemoteSettings.SCREEN_VIEW && input.auto
        if (!automatic) return true
        if (input.session != capSession) { sessionCaps.clear(); capSession = input.session }
        val cap = AutoSignals.SESSION_CAPS[type]
        if (cap != null && (sessionCaps[type] ?: 0) >= cap) { droppedAutomatic++; return false }
        val today = UsageStats.dayOf(input.timestamp, TimeZone.getDefault().getOffset(input.timestamp).toLong())
        val next = (quota ?: loadQuota()).take(today)
        if (next == null) { droppedAutomatic++; return false }
        quota = next; persistQuota()
        sessionCaps[type] = (sessionCaps[type] ?: 0) + 1
        return true
    }

    private fun foregroundKeys(at: Long, into: MutableMap<String, String>) {
        val stats = foreground ?: loadForeground()
        stats.countFor(UsageStats.dayOf(at, TimeZone.getDefault().getOffset(at).toLong()))?.let { into["foregroundsToday"] = it.toString() }
        stats.previousSeconds?.let { into["previousForegroundSeconds"] = it.toString() }
    }

    private fun <T> loadState(name: String, decode: (ByteArray) -> T?, fresh: T): T {
        val bytes = try { storage?.read(name) } catch (_: Exception) { null }
        return (bytes?.let(decode) ?: fresh)
    }
    private fun loadForeground(): ForegroundStats = loadState(AutoSignals.FOREGROUND_FILE, ForegroundStats::decode, ForegroundStats.fresh()).also { foreground = it }
    private fun loadQuota(): DayQuota = loadState(AutoSignals.QUOTA_FILE, DayQuota::decode, DayQuota.fresh()).also { quota = it }
    private fun loadPermissions(): Map<String, String> = loadState(AutoSignals.PERMISSIONS_FILE, PermissionStore::decode, emptyMap<String, String>()).also { permissions = it }
    private fun persistState(name: String, bytes: ByteArray?) {
        val store = storage ?: return
        if (bytes == null || privacy.get() != 1) return
        try { store.replace(name, bytes) } catch (_: Exception) { warn("Automatic event state not saved.") }
    }
    private fun persistForeground() = persistState(AutoSignals.FOREGROUND_FILE, foreground?.encode())
    private fun persistQuota() = persistState(AutoSignals.QUOTA_FILE, quota?.encode())
    private fun persistPermissions() = persistState(AutoSignals.PERMISSIONS_FILE, permissions?.let(PermissionStore::encode))

    private fun loadUsage(): UsageStats {
        val store = storage
        var loaded: UsageStats? = null
        if (store != null) {
            try { loaded = store.read(USAGE)?.let(UsageStats::decode) } catch (_: Exception) { usageUnreadable = true }
        }
        return (loaded ?: UsageStats.fresh()).also { usage = it }
    }

    private fun persistUsage() {
        val store = storage ?: return
        val current = usage ?: return
        if (usageUnreadable) return
        try { store.replace(USAGE, current.encode()) } catch (_: Exception) { warn("Usage statistics not saved.") }
    }

    private fun touchUsage() {
        val current = usage ?: return
        if (current.openStart != capture.sessionStart) return
        val next = current.touched(capture.sessionEnd)
        if (next !== current) { usage = next; persistUsage() }
    }

        /** SHA-256 of the anonymous install id. Falls back to an in-memory id when storage fails, without retrying. */
    private fun installHash(): String {
        installHash?.let { return it }
        var id: String? = null
        var readable = true
        val store = storage
        if (store != null) {
            try { id = store.read(INSTALL_ID)?.let(JsonReader::utf8)?.takeIf { INSTALL_ID_FORMAT.matches(it) } }
            catch (_: Exception) { readable = false }
        }
        if (id == null) {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            id = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
            if (store != null && readable) {
                try { store.replace(INSTALL_ID, id.toByteArray(Charsets.UTF_8)); installCreated = true }
                catch (_: Exception) { warn("Install id not saved.") }
            }
        }
        return Hash.hex(id).also { installHash = it }
    }

    private fun load(): Boolean {
        if (loaded || storage == null) return loaded
        try {
            val bytes = storage!!.read(queueName!!)
            if (bytes != null) {
                val snapshot = Snapshot.decode(bytes)
                if (snapshot != null && snapshot.generation == generation) {
                    queue.entries.addAll(0, snapshot.entries)
                    queue.pending.addAll(snapshot.pending.map { if (it.retryAt > clock.nowMillis() + 3600000) it.copy(retryAt = clock.nowMillis()) else it })
                    interval = snapshot.interval; pauseUntil = minOf(snapshot.pauseUntil, clock.nowMillis() + 3600000)
                    if (queue.entries.isNotEmpty()) nextFlush = clock.nowMillis()
                } else warn("Damaged or obsolete queue discarded.")
            }
            loaded = true
            if (optOut) { queue.clear(); early.clear() }
            else { early.forEach(::append); early.clear() }
            prune(); return true
        } catch (_: Exception) {
            storageRetryAt = clock.nowMillis() + 1000; warn("Queue read failed; sending suspended."); return false
        }
    }

    private fun persist(): Boolean = try {
        val store = storage
        if (store != null && loaded) {
            if (optOut) store.remove(queueName!!)
            else store.replace(queueName!!, Snapshot(generation, queue.entries, queue.pending, interval, pauseUntil).encode())
        }
        storageRetryAt = 0; true
    } catch (_: Exception) {
        storageRetryAt = clock.nowMillis() + 1000; warn("Queue save failed; sending suspended."); false
    }

    private fun prune() { if (queue.prune(clock.nowMillis())) cancelActive() }
    private fun cancelActive() { active?.cancel(); active = null; activeId = null; watchdog?.cancel(); watchdog = null }
    private fun liveIds(): Set<String> = queue.entries.mapTo(hashSetOf()) { it.id }
    private fun completeWaiters() {
        val live = liveIds()
        val done = waiters.filter { waiter ->
            if (waiter.target == null) waiter.target = live
            optOut || halted || waiter.target!!.none(live::contains)
        }
        waiters.removeAll(done.toSet()); done.forEach { callback(it.completion) }
    }
    private fun finishAllWaiters() { val all = waiters.toList(); waiters.clear(); all.forEach { callback(it.completion) } }
    private fun callback(completion: Runnable?) { try { completion?.run() } catch (_: Exception) { warn("Completion callback failed.") } }
    private fun requestPump() {
        if (pumpQueued || stopped) return
        pumpQueued = true; enqueue { pumpQueued = false; pump() }
    }

    private fun pump() {
        timer?.cancel(); timer = null
        if (stopped || storage == null) return
        if (clock.nowMillis() < storageRetryAt) { schedule(storageRetryAt); return }
        persistPreference()
        if (desiredPreference != null || !load()) { schedule(storageRetryAt); return }
        prune()
        if (!persist()) { schedule(storageRetryAt); return }
        completeWaiters()
        if (optOut || halted || queue.entries.isEmpty() || activeId != null) return
        if (clock.nowMillis() < pauseUntil) { schedule(pauseUntil); return }
        if (queue.pending.isEmpty()) {
            if (!forced && waiters.isEmpty() && queue.entries.size < options.maxBatchSize && clock.nowMillis() < nextFlush) {
                schedule(nextFlush); return
            }
            queue.pending.add(queue.makeBatch(queue.entries, options.gzip && !gzipDisabled))
        }
        if (clock.nowMillis() < queue.pending[0].retryAt) { schedule(queue.pending[0].retryAt); return }
        if (!persist()) { schedule(storageRetryAt); return }
        synchronized(privacyLock) {
            if (privacy.get() != 1) return
            val token = UUID.randomUUID().toString(); val batch = queue.pending[0]
            activeId = token
            active = try {
                transport.send(PulseRequest(endpoint!!, appKey!!, batch.wire, batch.gzip, batchId = batch.batchId)) { response -> enqueue { received(response, token) } }
            } catch (_: Exception) { enqueue { received(PulseResponse(0), token) }; null }
            // A transport that never answers must not stall the queue: treat the request as a network failure and retry.
            watchdog?.cancel()
            watchdog = scheduler.schedule(REQUEST_DEADLINE_MILLIS, Runnable {
                safely { if (activeId == token) { active?.cancel(); received(PulseResponse(0), token) } }
            })
        }
    }

    private fun received(response: PulseResponse, token: String) {
        if (activeId != token || queue.pending.isEmpty() || stopped) return
        active = null; activeId = null; watchdog?.cancel(); watchdog = null
        if (optOut) { pump(); return }
        val batch = queue.pending[0]; val status = response.status
        when {
            status in 200..299 -> {
                val ids = batch.ids.toHashSet(); queue.entries.removeAll { it.id in ids }; queue.pending.removeAt(0)
                applyAdvice(response.body); nextFlush = clock.nowMillis() + millis(interval)
            }
            (status == 415 || status == 413) && batch.gzip -> {
                if (status == 415) gzipDisabled = true
                queue.pending[0] = batch.copy(wire = batch.json, gzip = false, retryAt = clock.nowMillis())
            }
            status == 401 -> { halted = true; warn("Application key rejected; sending stopped until restart.") }
            status == 400 || status == 413 -> isolate(batch)
            else -> retry(batch, response)
        }
        if (queue.entries.isEmpty()) forced = false
        pump()
    }

    private fun isolate(batch: Batch) {
        queue.pending.removeAt(0)
        val ids = batch.ids.toHashSet(); val members = queue.entries.filter { it.id in ids }
        if (members.size > 1) {
            val half = members.size / 2
            queue.pending.addAll(0, listOf(queue.makeBatch(members.take(half), false), queue.makeBatch(members.drop(half), false)))
        } else { queue.entries.removeAll { it.id in ids }; warn("Invalid signal dropped after isolation.") }
    }

    private fun retry(batch: Batch, response: PulseResponse) {
        val exponential = minOf(300.0, Math.pow(2.0, minOf(9, batch.failures).toDouble()))
        val sample = random.sample().let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.5 }
        var delay = millis((exponential * (0.5 + sample)).coerceIn(1.0, 300.0))
        RetryAfter.delay(response.retryAfter, clock.nowMillis())?.let { delay = maxOf(delay, it) }
        if (response.status == 429 || response.status == 503) {
            if (applyAdvice(response.body)) delay = maxOf(delay, millis(interval))
        }
        queue.pending[0] = batch.copy(failures = minOf(1000000, batch.failures + 1), retryAt = clock.nowMillis() + delay)
    }

    private fun applyAdvice(bytes: ByteArray): Boolean {
        val advice = Advice.read(bytes)
        advice.interval?.let { interval = it }
        advice.pause?.let { pauseUntil = if (it) clock.nowMillis() + millis(interval) else 0 }
        return advice.interval != null
    }
    private fun schedule(deadline: Long) {
        timer?.cancel(); timer = scheduler.schedule((deadline - clock.nowMillis()).coerceAtLeast(1), Runnable { safely { pump() } })
    }
    private fun enqueue(action: () -> Unit) { try { scheduler.execute(Runnable { safely(action) }) } catch (_: Exception) { /* A closed owner cannot accept work. */ } }
    private fun safely(action: () -> Unit) { try { action() } catch (_: Exception) { warn("SDK action failed safely.") } }
    private fun warn(message: String) { if (options.debug == true) try { logger.warn(message) } catch (_: Exception) { /* Diagnostics cannot affect the app. */ } }

    private companion object {
        const val INSTALL_ID = "install-id"
        const val MAX_ERRORS_PER_TYPE = 10
        const val USAGE = "usage"
        const val REQUEST_DEADLINE_MILLIS = 150_000L
        val INSTALL_ID_FORMAT = Regex("^[0-9a-f]{32}$")
        fun millis(seconds: Double): Long = (seconds * 1000).toLong()
        fun boundedExtra(value: String): String = boundedString(value).take(SystemKeys.MAX_EXTRA_LENGTH)
        fun boundedString(value: String): String {
            if (!JsonWriter.validUnicode(value)) return ""
            var result = value.take(256)
            if (result.lastOrNull()?.isHighSurrogate() == true) result = result.dropLast(1)
            while (result.toByteArray(Charsets.UTF_8).size > 256) result = result.dropLast(if (result.last().isLowSurrogate()) 2 else 1)
            return result
        }
        data class PropertyCopy(val values: Map<String, Any?>, val dropped: Boolean)
        fun copyProps(props: Map<String, *>?): PropertyCopy {
            val copied = linkedMapOf<String, Any?>()
            var dropped = false
            val iterator = props?.entries?.iterator() ?: return PropertyCopy(copied, false)
            var inspected = 0
            while (iterator.hasNext() && inspected++ < 256) {
                val (key, value) = iterator.next()
                if (key.length <= 128 &&
                    (value is Boolean || value is Int || value is Long || value is Float || value is Double || value is String && value.length <= 1024)) copied[key] = value
                else dropped = true
            }
            return PropertyCopy(copied, dropped || iterator.hasNext())
        }
        fun endpoint(host: String?): java.net.URL? = try {
            val uri = URI(host ?: PulseInfo.DEFAULT_HOST)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null) null
            else URI(uri.scheme, null, uri.host, uri.port, uri.path.trimEnd('/') + "/api/v1/signals", null, null).toURL()
        } catch (_: Exception) { null }
    }
}
