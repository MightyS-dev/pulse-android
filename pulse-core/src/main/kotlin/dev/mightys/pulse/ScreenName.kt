package dev.mightys.pulse

/** Turns a screen name into a safe, short, identifier-free name. Pure and platform independent. */
internal object ScreenName {
    const val MAX_LENGTH = 128
    private val NUMERIC = Regex("^[0-9]+$")
    private val UUID_LIKE = Regex("^[0-9a-fA-F]{8}(-?[0-9a-fA-F]{4}){3}-?[0-9a-fA-F]{12}$")

    /** Returns the cleaned name, or null when nothing usable is left. */
    fun clean(raw: String?): String? {
        if (raw == null || !JsonWriter.validUnicode(raw)) return null
        var text = raw.trim()
        text = text.substringBefore('#').substringBefore('?').trim()
        if (text.contains('/')) {
            text = text.split('/').joinToString("/") { if (NUMERIC.matches(it) || UUID_LIKE.matches(it)) ":id" else it }
        }
        if (text.length > MAX_LENGTH) {
            text = text.take(MAX_LENGTH)
            if (text.last().isHighSurrogate()) text = text.dropLast(1)
        }
        return text.takeIf { it.isNotBlank() }
    }
}
