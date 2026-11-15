package dev.mightys.pulse

import java.util.Locale

/** Wire keys allowed inside the signal `system` object. */
internal object SystemKeys {
    val BASE = setOf("platform", "osName", "osVersion", "appVersion", "appBuildNumber", "deviceModel", "locale", "bundleId")
    val IDENTITY = setOf("signingCertSha256")
    val EXTRA = setOf(
        "deviceType", "deviceBrand", "architecture", "osApiLevel", "appName", "installSource", "isSimulator", "isDebugBuild",
        "screenWidth", "screenHeight", "screenScale", "screenWidthPx", "screenHeightPx", "screenDpi", "screenCount",
        "orientation", "colorScheme", "layoutDirection", "timeZone", "utcOffsetMinutes", "language", "preferredLanguage",
        "region", "fontScale", "reduceMotion", "boldText", "invertColors", "increaseContrast", "reduceTransparency",
        "differentiateWithoutColor", "fontWeightAdjustment", "connectionType", "lowPowerMode",
    )
    const val MAX_EXTRA_LENGTH = 64
}

/** Persisted session statistics kept in one small file next to the install id. Immutable; every update returns a copy. */
internal data class UsageStats(
    val firstSessionDate: String,
    val totalSessions: Long,
    /** Local days (days since the epoch) with a session, only the most recent 30. */
    val days: List<Long>,
    val distinctDays: Long,
    val lastDay: Long,
    /** Completed sessions as (day it ended, seconds), at most the last 90 days. */
    val sessions: List<Pair<Long, Long>>,
    val previousSeconds: Long?,
    val version: String?,
    val build: String?,
    val openStart: Long,
    val openLast: Long,
) {
    /** Values that go on every signal of the session that has just started. */
    data class Started(val usage: UsageStats, val fields: Map<String, String>, val previousVersion: String?, val previousBuild: String?,
        val updated: Boolean)

    fun startSession(at: Long, offsetMillis: Long, previousStart: Long, previousEnd: Long, appVersion: String, appBuild: String): Started {
        val today = dayOf(at, offsetMillis)
        // Close the session that just ended: the one seen in this process, otherwise the one left open by an earlier launch.
        val endedStart = if (previousStart > 0) previousStart else openStart
        val endedEnd = if (previousStart > 0) previousEnd else openLast
        var completed = sessions
        var previous = previousSeconds
        if (endedStart > 0 && endedEnd >= endedStart) {
            val seconds = (endedEnd - endedStart) / 1000
            completed = completed + (dayOf(endedEnd, offsetMillis) to seconds)
            previous = seconds
        }
        completed = completed.filter { it.first > today - 90 }.takeLast(2000)
        val known = days.toSet()
        val newDay = today !in known && today != lastDay
        val recent = (days + today).distinct().filter { it > today - 30 && it <= today + 1 }.sorted().takeLast(31)
        val updated = version != null && (version != appVersion || (build ?: "") != appBuild)
        val next = copy(
            firstSessionDate = firstSessionDate.ifEmpty { dateString(today) }, totalSessions = totalSessions + 1, days = recent,
            distinctDays = distinctDays + if (newDay) 1 else 0, lastDay = today, sessions = completed, previousSeconds = previous,
            version = appVersion, build = appBuild, openStart = at, openLast = at,
        )
        val fields = linkedMapOf(
            "firstSessionDate" to next.firstSessionDate, "totalSessionsCount" to next.totalSessions.toString(),
            "distinctDaysUsed" to next.distinctDays.toString(),
            "distinctDaysUsedLastMonth" to recent.count { it > today - 30 && it <= today }.toString(),
        )
        previous?.let { fields["previousSessionSeconds"] = it.toString() }
        if (completed.isNotEmpty()) {
            val total = completed.sumOf { it.second }
            fields["averageSessionSeconds"] = ((total + completed.size / 2) / completed.size).toString()
        }
        return Started(next, fields, version, build, updated)
    }

    fun touched(lastSeen: Long): UsageStats = if (openStart > 0 && lastSeen > openLast) copy(openLast = lastSeen) else this

    fun encode(): ByteArray {
        val map = linkedMapOf<String, Any?>(
            "v" to 1, "first" to firstSessionDate, "total" to totalSessions, "days" to days, "distinct" to distinctDays,
            "lastDay" to lastDay, "sessions" to sessions.map { listOf(it.first, it.second) }, "previous" to (previousSeconds ?: -1L),
            "version" to (version ?: ""), "build" to (build ?: ""), "openStart" to openStart, "openLast" to openLast,
        )
        return JsonWriter.value(map).toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fresh(): UsageStats = UsageStats("", 0, emptyList(), 0, Long.MIN_VALUE, emptyList(), null, null, null, 0, 0)

        private fun number(value: Any?): Long? = (value as? JsonNumber)?.text?.toLongOrNull()

        /** Strict decode; any damage gives null so the caller starts from a fresh state. */
        fun decode(bytes: ByteArray): UsageStats? = try {
            val map = JsonReader.utf8(bytes)?.let(JsonReader::parse) as Map<*, *>
            require(number(map["v"]) == 1L)
            val first = map["first"] as String
            require(first.length <= 10)
            val days = (map["days"] as List<*>).map { requireNotNull(number(it)) }
            require(days.size <= 64)
            val sessions = (map["sessions"] as List<*>).map { entry ->
                val pair = entry as List<*>
                require(pair.size == 2)
                requireNotNull(number(pair[0])) to requireNotNull(number(pair[1]))
            }
            require(sessions.size <= 4000)
            val previous = requireNotNull(number(map["previous"]))
            val version = map["version"] as String
            val build = map["build"] as String
            UsageStats(first, requireNotNull(number(map["total"])), days, requireNotNull(number(map["distinct"])),
                requireNotNull(number(map["lastDay"])), sessions, previous.takeIf { it >= 0 },
                version.takeIf { it.isNotEmpty() }, build.takeIf { version.isNotEmpty() },
                requireNotNull(number(map["openStart"])), requireNotNull(number(map["openLast"])))
        } catch (_: Exception) { null }

        fun dayOf(at: Long, offsetMillis: Long): Long = Math.floorDiv(at + offsetMillis, 86400000L)

        /** Civil date YYYY-MM-DD for a day count since 1970-01-01. */
        fun dateString(day: Long): String {
            val z = day + 719468
            val era = Math.floorDiv(z, 146097L)
            val dayOfEra = z - era * 146097
            val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val mp = (5 * dayOfYear + 2) / 153
            val date = dayOfYear - (153 * mp + 2) / 5 + 1
            val month = if (mp < 10) mp + 3 else mp - 9
            val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
            return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, date)
        }
    }
}
