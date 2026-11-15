package dev.mightys.pulse

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object Hash {
    fun bytes(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    fun hex(value: String): String = bytes(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}

internal data class Input(val timestamp: Long, val session: String, val userHash: String?, val type: String?,
    val value: Double?, val props: Map<String, Any?>)

internal object JsonWriter {
    fun validUnicode(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (c.isHighSurrogate()) {
                if (i == value.length || !value[i++].isLowSurrogate()) return false
            } else if (c.isLowSurrogate()) return false
        }
        return true
    }

    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (c in value) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
        append('"')
    }

    fun value(item: Any?): String = when (item) {
        null -> "null"
        is String -> quote(item)
        is JsonNumber -> item.text
        is Boolean -> item.toString()
        is Number -> item.toString()
        is Map<*, *> -> item.entries.joinToString(",", "{", "}") { quote(it.key as String) + ":" + value(it.value) }
        is List<*> -> item.joinToString(",", "[", "]") { value(it) }
        else -> error("Unsupported internal JSON value")
    }
}

internal object SignalJson {
    const val MAX_BODY = 1048576

    fun sanitize(props: Map<String, *>?, warn: () -> Unit = {}): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        for ((key, raw) in (props ?: emptyMap<String, Any?>()).entries.sortedBy { it.key }) {
            if (result.size == 32) break
            if (key.length > 128 || !JsonWriter.validUnicode(key)) { warn(); continue }
            val safe = property(raw)
            if (safe == null) { warn(); continue }
            result[key] = safe
            if (JsonWriter.value(result).toByteArray(Charsets.UTF_8).size > 8192) { result.remove(key); warn() }
        }
        return result
    }

    private fun property(raw: Any?): Any? = when (raw) {
        is String -> raw.takeIf { it.length <= 1024 && JsonWriter.validUnicode(it) }
        is Boolean -> raw.toString()
        is Int, is Long -> raw
        is Float -> raw.takeIf { it.isFinite() }
        is Double -> raw.takeIf { it.isFinite() }
        else -> null
    }

    fun encode(input: Input, system: Map<String, String>, debug: Boolean): ByteArray? {
        val type = input.type ?: return null
        if (type.isBlank() || type.length > 200 || !JsonWriter.validUnicode(type) || input.value?.isFinite() == false) return null
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        val value = linkedMapOf<String, Any?>("timestamp" to formatter.format(Date(input.timestamp)),
            "sessionId" to input.session, "type" to type, "debug" to debug, "system" to system,
            "props" to sanitize(input.props))
        input.value?.let { value["value"] = it }
        input.userHash?.let { value["userHash"] = it }
        return JsonWriter.value(value).toByteArray(Charsets.UTF_8).takeIf { it.size + 2 <= MAX_BODY }
    }

    fun batch(entries: List<Entry>): ByteArray = entries.joinToString(",", "[", "]") { it.json.toString(Charsets.UTF_8) }.toByteArray(Charsets.UTF_8)
}

internal data class Entry(val id: String, val timestamp: Long, val json: ByteArray)
