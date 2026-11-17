package dev.mightys.pulse

import java.math.BigInteger

/**
 * App Control version comparison. Spaces and a v or V prefix are removed, everything after the first plus is dropped, the
 * part after the first dash is a pre-release, the main part is dot separated numbers of any length (leading zeros are
 * ignored, a missing part is 0) and any part that is not a number makes the version not comparable.
 */
internal object ControlVersion {
    private class Parsed(val main: List<BigInteger>, val pre: List<String>?)

    /** -1, 0 or 1, or null when either version cannot be compared. */
    fun compare(a: String?, b: String?): Int? {
        val left = parse(a) ?: return null
        val right = parse(b) ?: return null
        for (i in 0 until maxOf(left.main.size, right.main.size)) {
            val order = (left.main.getOrElse(i) { BigInteger.ZERO }).compareTo(right.main.getOrElse(i) { BigInteger.ZERO })
            if (order != 0) return order
        }
        val x = left.pre; val y = right.pre
        if (x == null || y == null) return if (x == null && y == null) 0 else if (x == null) 1 else -1
        for (i in 0 until maxOf(x.size, y.size)) {
            if (i >= x.size) return -1
            if (i >= y.size) return 1
            val p = x[i]; val q = y[i]
            val pNumber = isDigits(p); val qNumber = isDigits(q)
            val order = when {
                pNumber && qNumber -> BigInteger(p).compareTo(BigInteger(q))
                pNumber -> -1
                qNumber -> 1
                else -> p.compareTo(q).coerceIn(-1, 1)
            }
            if (order != 0) return order
        }
        return 0
    }

    fun isComparable(version: String?): Boolean = parse(version) != null

    private fun isDigits(part: String) = part.isNotEmpty() && part.all { it in '0'..'9' }

    private fun parse(text: String?): Parsed? {
        if (text == null) return null
        var value = text.trim { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
        if (value.isNotEmpty() && (value[0] == 'v' || value[0] == 'V')) value = value.substring(1)
        val plus = value.indexOf('+'); if (plus >= 0) value = value.substring(0, plus)
        var pre: List<String>? = null
        val dash = value.indexOf('-')
        if (dash >= 0) {
            val preText = value.substring(dash + 1); value = value.substring(0, dash)
            pre = preText.split('.')
            if (pre.any { part -> part.isEmpty() || !part.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '-' } }) return null
        }
        if (value.isEmpty()) return null
        val parts = value.split('.')
        if (parts.any { !isDigits(it) }) return null
        return Parsed(parts.map { BigInteger(it) }, pre)
    }
}

/** The public id of an app's rules: the first 32 hex characters of SHA-256 over the key exactly as it was given. */
internal object ControlIds {
    fun forKey(key: String): String = Hash.hex(key).substring(0, 32)
}
