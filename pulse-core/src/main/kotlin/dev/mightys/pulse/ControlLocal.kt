package dev.mightys.pulse

/** What App Control remembers on the device between launches. Small, local, never sent. */
internal data class ControlLocal(
    /** The app version of the last launch. */
    val appVersion: String? = null,
    /** The version the app was updated to, until a later update replaces it. */
    val updatedTo: String? = null,
    /** How many times the user pressed Update and came back on the same version. */
    val updateReturns: Int = 0,
    /** The app version when Update was pressed and the user has not come back yet. */
    val pressPending: String? = null,
    /** The app version for which the Play update flow answered that no update is available. */
    val playUnavailableFor: String? = null,
    val dismissedVersion: String? = null,
    val dismissedAt: Long = 0,
    val shownAnnouncements: List<String> = emptyList(),
    val whatsNewShown: String? = null,
) {
    fun encode(): ByteArray = JsonWriter.value(linkedMapOf(
        "appVersion" to appVersion, "updatedTo" to updatedTo, "updateReturns" to updateReturns, "pressPending" to pressPending,
        "playUnavailableFor" to playUnavailableFor, "dismissedVersion" to dismissedVersion, "dismissedAt" to dismissedAt,
        "shownAnnouncements" to shownAnnouncements.takeLast(MAX_IDS), "whatsNewShown" to whatsNewShown,
    )).toByteArray(Charsets.UTF_8)

    companion object {
        const val MAX_IDS = 20

        /** The saved state, or an empty one when the bytes are missing or damaged. */
        fun decode(bytes: ByteArray?): ControlLocal {
            val root = try { bytes?.let { JsonReader.utf8(it) }?.let(JsonReader::parse) as? Map<*, *> } catch (_: Exception) { null } ?: return ControlLocal()
            fun text(key: String) = root[key] as? String
            fun number(key: String) = (root[key] as? JsonNumber)?.text?.toLongOrNull()
            return ControlLocal(text("appVersion"), text("updatedTo"), (number("updateReturns") ?: 0).coerceIn(0, 1000).toInt(), text("pressPending"),
                text("playUnavailableFor"), text("dismissedVersion"), number("dismissedAt") ?: 0,
                (root["shownAnnouncements"] as? List<*>).orEmpty().filterIsInstance<String>().takeLast(MAX_IDS), text("whatsNewShown"))
        }
    }
}
