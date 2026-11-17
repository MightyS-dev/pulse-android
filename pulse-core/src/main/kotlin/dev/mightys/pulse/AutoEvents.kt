package dev.mightys.pulse

import java.net.URLDecoder

/** Reads how long the process has been alive; null when unknown. Called on the state worker at the first session start. */
public fun interface PulseLaunch {
    /** Milliseconds from process start to the first frame (or, where unknown, to now); null when the platform cannot say. */
    public fun launchMs(): Long?
}

/** One record of why an earlier process of the app ended, already reduced to a reason name and an optional stack head. */
public class PulseExitRecord @JvmOverloads constructor(
    /** When the OS says the process ended, milliseconds since the Unix epoch. */
    public val timeMillis: Long,
    /** One of anr, crash_native, low_memory, excessive_resource, initialization_failure, signaled, other. */
    public val reason: String,
    /** Frames of the main thread where the OS gave a stack, top first; empty otherwise. */
    public val frames: List<String> = emptyList(),
)

/** Constants shared by the automatic events and their quota guards. */
internal object AutoSignals {
    const val APP_OPENED = "pulse.app_opened"
    const val APP_BACKGROUNDED = "pulse.app_backgrounded"
    const val DEEP_LINK = "pulse.deep_link"
    const val PERMISSION_CHANGED = "pulse.permission_changed"
    const val DAY_CAP = 300
    const val MAX_EXITS_PER_LAUNCH = 10
    const val EXIT_MAX_AGE_MILLIS = 7L * 86_400_000L
    const val EXIT_MAX_FUTURE_MILLIS = 86_400_000L
    const val MAX_LAUNCH_MS = 3_600_000L
    val OPT_IN = setOf(APP_OPENED, APP_BACKGROUNDED)
    val SESSION_CAPS = mapOf(APP_OPENED to 20, APP_BACKGROUNDED to 20, DEEP_LINK to 10, PERMISSION_CHANGED to 5, RemoteSettings.SCREEN_VIEW to 100)
    /** System key to the permission name used in `pulse.permission_changed`. */
    val PERMISSIONS = linkedMapOf("notificationPermission" to "notification", "trackingPermission" to "tracking")
    val PERMISSION_VALUES = setOf("granted", "denied", "notDetermined", "provisional", "ephemeral", "authorized", "restricted")
    const val FOREGROUND_FILE = "foreground"
    const val QUOTA_FILE = "auto-quota"
    const val PERMISSIONS_FILE = "permissions"
    const val EXIT_MARKER_FILE = "exit-marker"
}

/** Foregrounds counted per local day and the length of the last finished foreground period. Immutable. */
internal data class ForegroundStats(val day: Long, val count: Long, val previousSeconds: Long?) {
    fun foregrounded(today: Long): ForegroundStats = copy(day = today, count = if (day == today) count + 1 else 1)
    fun backgrounded(seconds: Long): ForegroundStats = copy(previousSeconds = seconds)
    fun countFor(today: Long): Long? = if (day == today && count > 0) count else null

    fun encode(): ByteArray = JsonWriter.value(linkedMapOf<String, Any?>("v" to 1, "day" to day, "count" to count,
        "previous" to (previousSeconds ?: -1L))).toByteArray(Charsets.UTF_8)

    companion object {
        fun fresh() = ForegroundStats(Long.MIN_VALUE, 0, null)
        fun decode(bytes: ByteArray): ForegroundStats? = try {
            val map = JsonReader.utf8(bytes)?.let(JsonReader::parse) as Map<*, *>
            fun number(key: String) = requireNotNull((map[key] as? JsonNumber)?.text?.toLongOrNull())
            require(number("v") == 1L)
            ForegroundStats(number("day"), number("count").also { require(it >= 0) }, number("previous").takeIf { it >= 0 })
        } catch (_: Exception) { null }
    }
}

/** Automatic signals sent on one local day, for the daily cap. Immutable. */
internal data class DayQuota(val day: Long, val count: Int) {
    /** The state after one more signal on [today], or null when the cap is reached. */
    fun take(today: Long): DayQuota? {
        val current = if (day == today) count else 0
        return if (current >= AutoSignals.DAY_CAP) null else DayQuota(today, current + 1)
    }
    fun encode(): ByteArray = JsonWriter.value(linkedMapOf<String, Any?>("v" to 1, "day" to day, "count" to count)).toByteArray(Charsets.UTF_8)

    companion object {
        fun fresh() = DayQuota(Long.MIN_VALUE, 0)
        fun decode(bytes: ByteArray): DayQuota? = try {
            val map = JsonReader.utf8(bytes)?.let(JsonReader::parse) as Map<*, *>
            fun number(key: String) = requireNotNull((map[key] as? JsonNumber)?.text?.toLongOrNull())
            require(number("v") == 1L)
            DayQuota(number("day"), number("count").toInt().also { require(it in 0..100000) })
        } catch (_: Exception) { null }
    }
}

/** The last seen status of each permission, so a change is noticed once. */
internal object PermissionStore {
    fun encode(values: Map<String, String>): ByteArray = JsonWriter.value(LinkedHashMap<String, Any?>(values)).toByteArray(Charsets.UTF_8)
    fun decode(bytes: ByteArray): Map<String, String>? = try {
        val map = JsonReader.utf8(bytes)?.let(JsonReader::parse) as Map<*, *>
        map.entries.associate { (it.key as String) to (it.value as String) }.filter { it.key in AutoSignals.PERMISSIONS.values && it.value in AutoSignals.PERMISSION_VALUES }
    } catch (_: Exception) { null }
}

/** The newest exit record time already handled. */
internal object ExitMarker {
    fun encode(at: Long): ByteArray = JsonWriter.value(linkedMapOf<String, Any?>("at" to at)).toByteArray(Charsets.UTF_8)
    fun decode(bytes: ByteArray): Long? = try {
        ((JsonReader.utf8(bytes)?.let(JsonReader::parse) as Map<*, *>)["at"] as? JsonNumber)?.text?.toLongOrNull()
    } catch (_: Exception) { null }
}

/** Reduces a link to scheme, host and a path without ids; never a query, a fragment, credentials or a port. */
internal object DeepLink {
    private const val MAX_RAW = 2048
    private const val MAX_PATH = 128
    private const val MAX_HOST = 128
    private const val MAX_UTM = 64
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val NUMERIC = Regex("^[0-9]+$")
    private val UUID_LIKE = Regex("^[0-9a-fA-F]{8}(-?[0-9a-fA-F]{4}){3}-?[0-9a-fA-F]{12}$")

    fun props(raw: String?): Map<String, Any?>? {
        if (raw == null || raw.length > MAX_RAW || !JsonWriter.validUnicode(raw)) return null
        val text = raw.trim()
        val match = SCHEME.matchEntire(text) ?: return null
        val scheme = match.groupValues[1].lowercase(java.util.Locale.ROOT).take(32)
        val noFragment = match.groupValues[2].substringBefore('#')
        val query = if (noFragment.contains('?')) noFragment.substringAfter('?') else ""
        var rest = noFragment.substringBefore('?')
        var host = ""
        if (rest.startsWith("//")) {
            rest = rest.removePrefix("//")
            val authority = rest.substringBefore('/')
            rest = if (rest.contains('/')) "/" + rest.substringAfter('/') else ""
            host = authority.substringAfterLast('@').substringBefore(':').lowercase(java.util.Locale.ROOT).take(MAX_HOST)
            if (host.isEmpty() && rest.isEmpty()) return null
        } else if (rest.isBlank()) return null
        val path = cut(rest.split('/').joinToString("/") { if (NUMERIC.matches(it) || UUID_LIKE.matches(it)) ":id" else it }, MAX_PATH)
        val result = linkedMapOf<String, Any?>("scheme" to scheme)
        if (host.isNotEmpty()) result["host"] = host
        result["path"] = path.ifEmpty { "/" }
        val tags = utm(query)
        listOf("utm_source" to "utmSource", "utm_medium" to "utmMedium", "utm_campaign" to "utmCampaign").forEach { (tag, key) -> tags[tag]?.let { result[key] = it } }
        return result
    }

    private fun utm(query: String): Map<String, String> {
        val found = linkedMapOf<String, String>()
        for (pair in query.split('&').take(64)) {
            val name = pair.substringBefore('=')
            if (name !in setOf("utm_source", "utm_medium", "utm_campaign") || name in found || !pair.contains('=')) continue
            val value = try { URLDecoder.decode(pair.substringAfter('='), "UTF-8") } catch (_: Exception) { continue }
            val clean = cut(ErrorFilter.apply(value.trim()), MAX_UTM)
            if (clean.isNotEmpty() && JsonWriter.validUnicode(clean)) found[name] = clean
        }
        return found
    }

    private fun cut(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (text[limit - 1].isHighSurrogate()) limit - 1 else limit
        return text.substring(0, end)
    }
}
