package dev.mightys.pulse

import java.util.Locale

/** ISO 8601 times with a Z or an offset, read without java.time (the SDK supports API 21). */
internal object ControlTime {
    private val pattern = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?(Z|z|[+-]\\d{2}:?\\d{2})?$")

    /** Milliseconds since the epoch, or null when the text is not a valid time. */
    fun parse(text: String): Long? {
        val m = pattern.matchEntire(text.trim()) ?: return null
        val g = m.groupValues
        val year = g[1].toInt(); val month = g[2].toInt(); val day = g[3].toInt()
        val hour = g[4].toInt(); val minute = g[5].toInt(); val second = g[6].toInt()
        if (month !in 1..12 || day !in 1..daysIn(year, month) || hour > 23 || minute > 59 || second > 59) return null
        val millis = if (g[7].isEmpty()) 0L else g[7].padEnd(3, '0').substring(0, 3).toLong()
        var offsetMinutes = 0
        val zone = g[8]
        if (zone.isNotEmpty() && zone[0] != 'Z' && zone[0] != 'z') {
            val digits = zone.substring(1).replace(":", "")
            val h = digits.substring(0, 2).toInt(); val mm = digits.substring(2).toInt()
            if (h > 23 || mm > 59) return null
            offsetMinutes = (h * 60 + mm) * (if (zone[0] == '-') -1 else 1)
        }
        val seconds = days(year, month, day) * 86400L + hour * 3600L + minute * 60L + second - offsetMinutes * 60L
        return seconds * 1000L + millis
    }

    private fun daysIn(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 (civil calendar). */
    private fun days(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /** Test and diagnostics helper: the time as text. */
    fun format(millis: Long): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return format.format(java.util.Date(millis))
    }
}

internal data class ControlUpdateRule(val min: String?, val latest: String?, val mode: String?, val storeVersion: String?, val storeUrl: String?)
internal data class ControlBlocked(val version: String?, val platforms: Set<String>?, val allowContinue: Boolean)
internal data class ControlMaintenance(val startsAt: Long, val endsAt: Long, val headsUpMinutes: Int, val exemptAboveStore: Boolean, val platforms: Set<String>?)
internal data class ControlAnnouncement(val id: String, val title: String?, val body: String?, val url: String?, val buttonText: String?, val until: Long, val platforms: Set<String>?)
internal data class ControlWhatsNew(val version: String?, val body: String?)
internal data class ControlCustom(val title: String?, val body: String?, val buttonText: String?)

/**
 * The rules of format 1. A field of the wrong type counts as missing, unknown fields are ignored, so a surprise in the
 * answer never turns into a lock.
 */
internal data class ControlRules(
    val format: Int?,
    val version: Long,
    val off: Boolean,
    val update: Map<String, ControlUpdateRule>,
    val blocked: List<ControlBlocked>,
    val maintenance: ControlMaintenance?,
    val announcement: ControlAnnouncement?,
    val whatsNew: ControlWhatsNew?,
    val custom: Map<String, ControlCustom>,
    val disabledSdkVersions: List<String>,
) {
    companion object {
        const val MAX_BYTES = 65536

        /** The rules of a good answer, or null when the bytes are not a JSON object of format 1 within the size limit. */
        fun parse(bytes: ByteArray): ControlRules? {
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            return try {
                val root = JsonReader.utf8(bytes)?.let(JsonReader::parse) as? Map<*, *> ?: return null
                fromMap(root).takeIf { it.format == 1 }
            } catch (_: Exception) { null }
        }

        fun fromMap(root: Map<*, *>): ControlRules = ControlRules(
            format = int(root["format"]),
            version = (root["version"] as? JsonNumber)?.text?.toLongOrNull() ?: 0L,
            off = root["off"] == true,
            update = (root["update"] as? Map<*, *>).orEmpty().entries.mapNotNull { (key, value) ->
                val rule = value as? Map<*, *> ?: return@mapNotNull null
                if (key !is String) null else key to ControlUpdateRule(text(rule["min"]), text(rule["latest"]), text(rule["mode"]),
                    text(rule["storeVersion"]), text(rule["storeUrl"]))
            }.toMap(),
            blocked = (root["blocked"] as? List<*>).orEmpty().mapNotNull { item ->
                (item as? Map<*, *>)?.let { ControlBlocked(text(it["version"]), platforms(it, "platforms"), it["allowContinue"] == true) }
            },
            maintenance = (root["maintenance"] as? Map<*, *>)?.let { m ->
                val starts = text(m["startsAt"])?.let(ControlTime::parse); val ends = text(m["endsAt"])?.let(ControlTime::parse)
                if (starts == null || ends == null) null
                else ControlMaintenance(starts, ends, int(m["headsUpMinutes"]) ?: 0, m["exemptAboveStore"] == true, platforms(m, "platforms"))
            },
            announcement = (root["announcement"] as? Map<*, *>)?.let { a ->
                val until = text(a["until"])?.let(ControlTime::parse); val id = text(a["id"])
                if (until == null || id == null) null
                else ControlAnnouncement(id, text(a["title"]), text(a["body"]), text(a["url"]), text(a["buttonText"]), until, platforms(a, "platforms"))
            },
            whatsNew = (root["whatsNew"] as? Map<*, *>)?.let { ControlWhatsNew(text(it["version"]), text(it["body"])) },
            custom = ((root["texts"] as? Map<*, *>)?.get("custom") as? Map<*, *>).orEmpty().entries.mapNotNull { (key, value) ->
                val entry = value as? Map<*, *> ?: return@mapNotNull null
                if (key !is String) null else key to ControlCustom(text(entry["title"]), text(entry["body"]), text(entry["buttonText"]))
            }.toMap(),
            disabledSdkVersions = (root["disabledSdkVersions"] as? List<*>).orEmpty().filterIsInstance<String>(),
        )

        private fun text(value: Any?): String? = value as? String
        private fun int(value: Any?): Int? = (value as? JsonNumber)?.text?.toIntOrNull()
        /** Null means every platform (the field is missing or not a list), as the contract says. */
        private fun platforms(map: Map<*, *>, key: String): Set<String>? = (map[key] as? List<*>)?.filterIsInstance<String>()?.toSet()
    }
}
