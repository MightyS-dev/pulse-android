package dev.mightys.pulse

import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App Control: reads the rules the app owner set in the portal and keeps the current [ControlState]. It never blocks
 * [PulseClient.initialize], never retries in a loop and treats every failure as "keep the last known state, or normal".
 * All work runs on the SDK worker; listeners are called there too.
 */
public class ControlClient internal constructor(
    private val clock: PulseClock,
    private val scheduler: PulseScheduler,
    private val transport: PulseTransport,
) {
    /** The state to show right now. [ControlState.NORMAL] until the rules say otherwise. */
    @Volatile public var state: ControlState = ControlState.NORMAL
        private set

    /** Languages in order of preference (the app's first, then the system's). Set by the platform layer. */
    @Volatile public var languageTags: () -> List<String> = { listOf(Locale.getDefault().toLanguageTag()) }
    /** The store page the platform can build when the rules carry none. Set by the platform layer. */
    @Volatile public var fallbackStoreUrl: String? = null

    private class Config(val url: URL, val storage: PulseStorage, val cacheName: String, val localName: String, val appVersion: String,
        val appName: String, val sdk: String, val platform: String, val debug: Boolean)

    private val listeners = CopyOnWriteArrayList<ControlListener>()
    private var config: Config? = null
    private var rules: ControlRules? = null
    private var body: ByteArray? = null
    private var etag: String? = null
    private var savedAt = 0L
    private var freshThisRun = false
    private var local = ControlLocal()
    private var lastCheckAt = Long.MIN_VALUE
    private var requestToken: String? = null
    /** checkNow arrived while a request was out: ask once more when it is answered (never more than once in a row). */
    private var askAgain = false
    private var request: PulseCancellation? = null
    private var watchdog: PulseCancellation? = null
    private var timer: PulseCancellation? = null
    private val continued = hashSetOf<String>()

    /** Called for every change of [state], on a background thread. */
    public fun addListener(listener: ControlListener) { listeners.addIfAbsent(listener) }
    /** Stops calling [listener]. */
    public fun removeListener(listener: ControlListener) { listeners.remove(listener) }

    /** Checks the rules now, without waiting for the next return to the foreground. Does nothing when App Control is off. */
    public fun checkNow() { run { if (config != null) fetch(askAgain = true) } }

    /**
     * Lets the user leave the current state, where the state allows it: a suggestion is not shown again for 24 hours,
     * a card is marked as shown, a screen with [ControlState.allowContinue] is left until the app restarts. Locking
     * screens without it ignore the call.
     */
    public fun dismiss() {
        run {
            val c = config ?: return@run
            val current = state
            if (!current.isActive || !current.allowContinue && current.kind != ControlState.Kind.UpdateSuggested) return@run
            when (current.kind) {
                ControlState.Kind.UpdateSuggested -> saveLocal(local.copy(dismissedVersion = c.appVersion, dismissedAt = clock.nowMillis()))
                ControlState.Kind.Announcement -> rules?.announcement?.id?.let { id -> saveLocal(local.copy(shownAnnouncements = (local.shownAnnouncements + id).takeLast(ControlLocal.MAX_IDS))) }
                ControlState.Kind.WhatsNew -> saveLocal(local.copy(whatsNewShown = current.version))
                else -> continued.add(kindName(current.kind))
            }
            evaluate()
        }
    }

    /** Remembers that the user pressed Update, so a return on the same version can be counted (the loop guard). */
    public fun noteUpdatePressed() {
        run { val c = config ?: return@run; saveLocal(local.copy(pressPending = c.appVersion)) }
    }

    /** The store's update flow said no update is available: a required update becomes a suggestion. */
    public fun notePlayUpdateUnavailable() {
        run { val c = config ?: return@run; saveLocal(local.copy(playUnavailableFor = c.appVersion)); evaluate() }
    }

    /** The app came to the foreground: counts a return from the store, re-reads the clock rules and checks at most once a minute. */
    public fun onForeground() {
        run {
            if (config == null) return@run
            settlePress()
            evaluate()
            if (clock.nowMillis() - lastCheckAt >= MIN_INTERVAL_MILLIS || lastCheckAt > clock.nowMillis()) fetch()
        }
    }

    /** Starts the checks. Called once by [PulseClient.initialize] on the SDK worker; a mode of [ControlMode.Off] does nothing at all. */
    internal fun start(key: String, signalsUrl: URL, mode: ControlMode, storage: PulseStorage, appVersion: String, appName: String,
        sdk: String, platform: String, debug: Boolean) {
        if (mode == ControlMode.Off || config != null) return
        val url = try { URL(signalsUrl.toString().removeSuffix("signals") + "control/" + ControlIds.forKey(key)) } catch (_: Exception) { return }
        val hash = Hash.hex(key).substring(0, 16)
        val c = Config(url, storage, "control-$hash", "control-local-$hash", appVersion, appName, sdk, platform, debug)
        config = c
        local = settleVersion(ControlLocal.decode(try { storage.read(c.localName) } catch (_: Exception) { null }), appVersion)
        saveLocal(local)
        loadCache(c)
        evaluate()
        lastCheckAt = clock.nowMillis()
        fetch()
    }

    /** Cancels the timers and the request. */
    internal fun stop() {
        run { request?.cancel(); request = null; requestToken = null; watchdog?.cancel(); watchdog = null; timer?.cancel(); timer = null; config = null }
    }

    private fun settleVersion(saved: ControlLocal, appVersion: String): ControlLocal = when {
        saved.appVersion == null -> saved.copy(appVersion = appVersion)
        saved.appVersion != appVersion -> saved.copy(appVersion = appVersion, updatedTo = appVersion, updateReturns = 0, pressPending = null, playUnavailableFor = null)
        else -> saved
    }

    private fun settlePress() {
        val c = config ?: return
        val pending = local.pressPending ?: return
        saveLocal(local.copy(pressPending = null, updateReturns = if (pending == c.appVersion) local.updateReturns + 1 else 0))
    }

    private fun loadCache(c: Config) {
        val bytes = try { c.storage.read(c.cacheName) } catch (_: Exception) { null } ?: return
        val first = bytes.indexOf(NEWLINE); if (first < 0) return
        val second = (first + 1 until bytes.size).firstOrNull { bytes[it] == NEWLINE } ?: return
        val saved = String(bytes, first + 1, second - first - 1, Charsets.UTF_8).toLongOrNull() ?: return
        val content = bytes.copyOfRange(second + 1, bytes.size)
        val parsed = ControlRules.parse(content) ?: return
        rules = parsed; body = content; etag = String(bytes, 0, first, Charsets.UTF_8).takeIf { it.isNotEmpty() }; savedAt = saved
    }

    private fun saveCache() {
        val c = config ?: return; val content = body ?: return
        val head = ((etag ?: "") + "\n" + savedAt + "\n").toByteArray(Charsets.UTF_8)
        try { c.storage.replace(c.cacheName, head + content) } catch (_: Exception) { /* The next answer saves it again. */ }
    }

    private fun saveLocal(next: ControlLocal) {
        local = next
        val c = config ?: return
        try { c.storage.replace(c.localName, next.encode()) } catch (_: Exception) { /* Kept in memory for this run. */ }
    }

    private fun fetch(askAgain: Boolean = false) {
        val c = config ?: return
        if (requestToken != null) { if (askAgain) this.askAgain = true; return }
        val token = UUID.randomUUID().toString()
        requestToken = token
        lastCheckAt = clock.nowMillis()
        request = try {
            transport.send(PulseRequest(c.url, "", byteArrayOf(), false, isGet = true, ifNoneMatch = etag, control = true)) { response ->
                run { received(response, token) }
            }
        } catch (_: Exception) { requestToken = null; null }
        if (requestToken == token) watchdog = scheduler.schedule(DEADLINE_MILLIS, Runnable {
            run { if (requestToken == token) { request?.cancel(); request = null; requestToken = null } }
        })
    }

    private fun received(response: PulseResponse, token: String) {
        if (requestToken != token) return
        requestToken = null; request = null; watchdog?.cancel(); watchdog = null
        val again = askAgain; askAgain = false
        try { handle(response) } finally { if (again) fetch() }
    }

    private fun handle(response: PulseResponse) {
        when (response.status) {
            200 -> {
                val parsed = ControlRules.parse(response.body) ?: return
                rules = parsed; body = response.body; etag = "\"" + parsed.version + "\""; savedAt = clock.nowMillis(); freshThisRun = true
                saveCache(); evaluate()
            }
            304 -> if (rules != null) { savedAt = clock.nowMillis(); freshThisRun = true; saveCache(); evaluate() }
        }
    }

    private fun evaluate() {
        val c = config ?: return
        val current = rules
        val now = clock.nowMillis()
        if (current == null) { publish(ControlState.NORMAL); return }
        val ageHours = (now - savedAt).coerceAtLeast(0) / HOUR.toDouble()
        val dismissedAgo = if (local.dismissedVersion == c.appVersion) (now - local.dismissedAt).coerceAtLeast(0) / HOUR.toDouble() else null
        val input = ControlInput(current, now, c.platform, c.appVersion, c.debug, c.sdk,
            fromCache = !freshThisRun || ageHours > 1.0, cacheAgeHours = ageHours, updateReturns = local.updateReturns,
            playUpdateNotAvailable = local.playUnavailableFor == c.appVersion, canBuildStoreLink = fallbackStoreUrl != null,
            suggestDismissedHoursAgo = dismissedAgo, shownAnnouncements = local.shownAnnouncements.toSet(),
            justUpdated = local.updatedTo == c.appVersion, whatsNewShown = current.whatsNew?.version != null && local.whatsNewShown == current.whatsNew.version)
        var outcome = ControlDecision.decide(input)
        if (outcome.state in continued) outcome = outcome.copy(state = "normal", banners = outcome.banners - "testBuild")
        if ("maintenanceUpcoming" in continued) outcome = outcome.copy(banners = outcome.banners - "maintenanceUpcoming")
        val context = ControlContext(c.appName, c.appVersion, languageTags(), fallbackStoreUrl, now)
        publish(ControlStateBuilder.build(outcome, current, context, c.platform))
        scheduleBoundary(current, input, now)
    }

    private fun kindName(kind: ControlState.Kind): String = kind.name.replaceFirstChar { it.lowercase(Locale.ROOT) }

    private fun publish(next: ControlState) {
        if (next == state) return
        state = next
        for (listener in listeners) try { listener.onControlState(next) } catch (_: Exception) { /* A listener never breaks the SDK. */ }
    }

    /** The clock rules (maintenance window, heads-up, announcement end, cache limits) apply on time, also offline. */
    private fun scheduleBoundary(current: ControlRules, input: ControlInput, now: Long) {
        timer?.cancel(); timer = null
        val times = mutableListOf<Long>()
        current.maintenance?.let { m ->
            times += m.startsAt - m.headsUpMinutes * MINUTE; times += m.startsAt; times += m.endsAt; times += m.startsAt + ControlDecision.CACHE_MAINTENANCE_HOURS * HOUR
        }
        current.announcement?.let { times += it.until }
        times += savedAt + HOUR; times += savedAt + (ControlDecision.CACHE_LOCK_HOURS * HOUR).toLong()
        if (local.dismissedVersion == input.appVersion) times += local.dismissedAt + (ControlDecision.SUGGEST_QUIET_HOURS * HOUR).toLong()
        val next = times.filter { it > now }.minOrNull() ?: return
        val delay = (next - now + 1).coerceAtMost(MAX_TIMER_MILLIS)
        timer = scheduler.schedule(delay, Runnable { run { evaluate() } })
    }

    private fun run(action: () -> Unit) {
        try { scheduler.execute(Runnable { try { action() } catch (_: Exception) { /* Never reaches the app. */ } }) } catch (_: Exception) { /* A closed worker takes no work. */ }
    }

    private companion object {
        const val NEWLINE: Byte = 10
        const val MIN_INTERVAL_MILLIS = 60_000L
        const val DEADLINE_MILLIS = 5_000L
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L
        const val MAX_TIMER_MILLIS = 3_600_000L
    }
}
