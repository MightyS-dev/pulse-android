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
    private var storage: PulseStorage? = null
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
    private data class Waiter(val token: String, var target: Set<String>?, val completion: Runnable)
    private val waiters = mutableListOf<Waiter>()

    /** Conservative before initialization; true until persisted privacy has been read or opt-in saved. */
    public var optOut: Boolean
        get() = privacy.get() != 1
        set(value) {
            val requestedRevision = synchronized(privacyLock) { privacy.set(2); revision.incrementAndGet() }
            enqueue {
                if (requestedRevision != revision.get()) return@enqueue
                capture.reset(); early.clear(); queue.clear(); cancelActive(); cancelConfig(); installHash = null
                usage = null; usageUnreadable = false; sessionFields = emptyMap(); newInstallSession = false; installCreated = false
                lastScreen = null
                desiredPreference = value; preferenceRevision = requestedRevision
                generation = UUID.randomUUID().toString()
                persistPreference(); completeWaiters(); requestPump()
            }
        }

    /** Initialize once. Invalid keys/hosts are ignored; no IO occurs on the calling thread. */
    @JvmOverloads
    public fun initialize(key: String?, requested: PulseOptions? = null, storage: PulseStorage,
        environment: PulseEnvironment, defaultDebug: Boolean = false,
        dynamicValues: PulseDynamicValues = PulseDynamicValues { emptyMap() }) {
        enqueue {
            if (appKey != null || stopped || key.isNullOrBlank() || key.any { it.code < 32 || it.code == 127 }) return@enqueue
            val normalized = (requested ?: PulseOptions()).normalized()
            val address = endpoint(normalized.host) ?: return@enqueue
            this.storage = storage; options = normalized.copy(debug = normalized.debug ?: defaultDebug)
            appKey = key; endpoint = address; queueName = "queue-" + Hash.hex(key) + ".bin"
            queue = DurableQueue(options)
            interval = options.flushIntervalSeconds; nextFlush = clock.nowMillis() // first batch leaves at once; later ones follow the interval
            dynamic = dynamicValues
            system = environment.system().filterKeys { it in SystemKeys.BASE || options.sendAppIdentity && it in SystemKeys.IDENTITY || it in SystemKeys.EXTRA }
                .mapValues { if (it.key in SystemKeys.EXTRA) boundedExtra(it.value) else boundedString(it.value) }.toMutableMap().apply {
                    put("sdkName", PulseInfo.SDK_NAME); put("sdkVersion", PulseInfo.VERSION)
                    options.appVersion?.let { put("appVersion", boundedString(it)) }
                }
            readPrivacy(); applyCachedSettings(); requestPump(); fetchSettings()
        }
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

    private fun capture(at: Long, type: String, value: Double?, copied: PropertyCopy, capturedRevision: Long, screenKind: Int = 0) {
        if (privacy.get() == 2 || stopped || capturedRevision != revision.get()) return
        if (copied.dropped) warn("Unsupported or oversized properties ignored.")
        val sanitized = SignalJson.sanitize(copied.values) { warn("Unsupported or oversized properties ignored.") }
        val input = capture.capture(at, type, value, sanitized, localFields(at), if (appKey != null) extraFields() else null).copy(screen = screenKind)
        if (appKey == null) {
            if (early.size == 100) early.removeFirst()
            early.addLast(input)
        } else { append(input); requestPump() }
    }

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
        return input.copy(props = props, screen = 0)
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
    public fun background() { enqueue { if (!stopped) { touchUsage(); forced = true; pump() } } }

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
                if (stop) { touchUsage(); stopped = true; timer?.cancel(); timer = null; cancelActive(); cancelConfig(); if (load()) persist(); finishAllWaiters() }
                else { forced = true; pump() }
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
            store.remove(RemoteSettings.FILE)
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
        store(input)
    }

    /** Latest capture timestamp handed out in this process, in milliseconds. Touched only on the worker. */
    private var lastStamp = Long.MIN_VALUE / 2

    private fun store(captured: Input) {
        if (!signalAllowed(captured.type)) return
        // Strictly increasing at millisecond resolution, in storing order: automatic signals are stored before the
        // signal that started the session, so they keep the smaller timestamps.
        lastStamp = maxOf(captured.timestamp, lastStamp + 1)
        val input = captured.copy(timestamp = lastStamp)
        val hashed = if (input.userHash != null) input else input.copy(userHash = installHash())
        val json = SignalJson.encode(hashed, systemFor(input), options.debug == true) ?: return
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
        if (created) store(input.copy(type = "pulse.new_install", value = null, props = emptyMap(), newSession = false))
        if (started.updated && !created) {
            val props = mapOf<String, Any?>("previousVersion" to (started.previousVersion ?: ""), "previousBuild" to (started.previousBuild ?: ""))
            store(input.copy(type = "pulse.app_updated", value = null, props = props, newSession = false))
        }
        store(input.copy(type = "pulse.session_started", value = null, props = emptyMap(), newSession = false))
    }

    /** What the portal settings and the code options allow to be sent; a code option can only switch things off. */
    private fun signalAllowed(type: String?): Boolean {
        if (type == null) return true
        if (type == RemoteSettings.SCREEN_VIEW) return portalAllowsScreenViews()
        if (type in RemoteSettings.AUTOMATIC_TYPES)
            return automaticSignals && options.automaticSignals && settings.automaticSignals && type !in settings.disabledSignals
        return true
    }
    private fun portalAllowsScreenViews(): Boolean =
        settings.screenViews && settings.automaticSignals && RemoteSettings.SCREEN_VIEW !in settings.disabledSignals

    /** Use the settings saved by an earlier launch for this whole run. No file or a damaged file means everything on. */
    private fun applyCachedSettings() {
        val bytes = try { storage?.read(RemoteSettings.FILE) } catch (_: Exception) { null }
        settings = bytes?.let(RemoteSettings::parse) ?: RemoteSettings()
        screenViewHooksEnabled = options.automaticScreenViews && portalAllowsScreenViews()
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
        if (RemoteSettings.parse(response.body) == null) return
        try { store.replace(RemoteSettings.FILE, response.body) } catch (_: Exception) { warn("Settings not saved.") }
    }

    private fun cancelConfig() {
        configToken = null; configRequest?.cancel(); configRequest = null; configWatchdog?.cancel(); configWatchdog = null
    }

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
        val next = current.touched(capture.lastSeen)
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
                transport.send(PulseRequest(endpoint!!, appKey!!, batch.wire, batch.gzip)) { response -> enqueue { received(response, token) } }
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
