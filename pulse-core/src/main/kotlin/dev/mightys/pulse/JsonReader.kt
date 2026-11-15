package dev.mightys.pulse

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class JsonNumber(val text: String)

// A bounded strict reader for advice and the fixed SDK snapshot validation shape.
internal class JsonReader private constructor(private val text: String) {
    private var position = 0

    companion object {
        fun parse(text: String): Any? = try {
            if (text.length > 1048576) null else JsonReader(text).run {
                val result = read(0)
                space()
                require(position == text.length)
                result
            }
        } catch (_: IllegalArgumentException) { null }

        fun utf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { null }
    }

    private fun space() { while (position < text.length && text[position] in " \t\n\r") position++ }
    private fun take(c: Char): Boolean {
        space()
        if (position >= text.length || text[position] != c) return false
        position++
        return true
    }

    private fun read(depth: Int): Any? {
        require(depth <= 32)
        space()
        require(position < text.length)
        return when (text[position]) {
            '"' -> string()
            '{' -> obj(depth)
            '[' -> array(depth)
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> number()
        }
    }

    private fun literal(word: String, value: Any?): Any? {
        require(text.startsWith(word, position)); position += word.length
        return value
    }

    private fun obj(depth: Int): Map<String, Any?> {
        require(take('{'))
        val result = linkedMapOf<String, Any?>()
        if (take('}')) return result
        do {
            val key = string()
            require(!result.containsKey(key) && take(':'))
            result[key] = read(depth + 1)
            if (take('}')) return result
        } while (take(','))
        throw IllegalArgumentException("Incomplete object")
    }

    private fun array(depth: Int): List<Any?> {
        require(take('['))
        val result = mutableListOf<Any?>()
        if (take(']')) return result
        do {
            result.add(read(depth + 1))
            if (take(']')) return result
        } while (take(','))
        throw IllegalArgumentException("Incomplete array")
    }

    private fun string(): String {
        require(take('"'))
        val result = StringBuilder()
        while (position < text.length) {
            val c = text[position++]
            if (c == '"') return result.toString().also { require(JsonWriter.validUnicode(it)) }
            require(c >= ' ')
            if (c != '\\') { result.append(c); continue }
            require(position < text.length)
            result.append(escape(text[position++]))
        }
        throw IllegalArgumentException("Incomplete string")
    }

    private fun escape(c: Char): Char = when (c) {
        '"', '\\', '/' -> c
        'b' -> '\b'
        'f' -> '\u000c'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> {
            require(position + 4 <= text.length)
            val value = text.substring(position, position + 4)
            require(value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            position += 4
            value.toInt(16).toChar()
        }
        else -> throw IllegalArgumentException("Invalid escape")
    }

    private fun digits() {
        val start = position
        while (position < text.length && text[position] in '0'..'9') position++
        require(position > start)
    }

    private fun number(): JsonNumber {
        val start = position
        if (position < text.length && text[position] == '-') position++
        require(position < text.length)
        if (text[position] == '0') position++ else { require(text[position] in '1'..'9'); digits() }
        if (position < text.length && text[position] == '.') { position++; digits() }
        if (position < text.length && text[position] in "eE") {
            position++
            if (position < text.length && text[position] in "+-") position++
            digits()
        }
        return JsonNumber(text.substring(start, position))
    }
}

internal data class Advice(val interval: Double? = null, val pause: Boolean? = null) {
    companion object {
        fun read(bytes: ByteArray): Advice {
            if (bytes.isEmpty() || bytes.size > 65536) return Advice()
            val text = JsonReader.utf8(bytes) ?: return Advice()
            val objectValue = JsonReader.parse(text) as? Map<*, *> ?: return Advice()
            val number = (objectValue["recommendedSendIntervalSeconds"] as? JsonNumber)?.text?.toDoubleOrNull()
            return Advice(number?.takeIf { it.isFinite() && it > 0 }?.coerceIn(5.0, 3600.0), objectValue["pause"] as? Boolean)
        }
    }
}
