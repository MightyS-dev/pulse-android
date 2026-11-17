package dev.mightys.pulse

/**
 * The app's settings from the portal. Everything is on unless the portal says otherwise; a missing key, a value of the
 * wrong type or an unknown key never switches anything off.
 */
internal data class RemoteSettings(
    val version: Long? = null,
    val screenViews: Boolean = true,
    val automaticSignals: Boolean = true,
    /** Field groups switched off. */
    val offGroups: Set<String> = emptySet(),
    val disabledSignals: Set<String> = emptySet(),
    /** Opt-in types the portal switched on. Empty means none is sent. */
    val enabledSignals: Set<String> = emptySet(),
    /** Types the server paused (for example at 90 percent of the plan). Never sent while listed. */
    val pausedSignals: Set<String> = emptySet(),
    /** The portal switched heatmaps on for this app. Unlike everything else this is off by default and when missing. */
    val heatmaps: Boolean = false,
) {
    /** Wire keys of the field groups the portal switched off. */
    val hiddenKeys: Set<String> = offGroups.flatMapTo(hashSetOf()) { GROUPS[it].orEmpty() }

    companion object {
        const val FILE = "config"
        val AUTOMATIC_TYPES = setOf("pulse.session_started", "pulse.new_install", "pulse.app_updated", AutoSignals.PERMISSION_CHANGED,
            AutoSignals.DEEP_LINK, AutoSignals.APP_OPENED, AutoSignals.APP_BACKGROUNDED)
        const val SCREEN_VIEW = "pulse.screen_view"
        const val HEATMAP = "pulse.heatmap"
        val GROUPS: Map<String, Set<String>> = mapOf(
            "device" to setOf("deviceType", "deviceBrand", "architecture", "osApiLevel", "runtimeVersion", "scriptingBackend",
                "isSimulator", "targetEnvironment", "extensionId"),
            "screen" to setOf("screenWidth", "screenHeight", "screenScale", "screenWidthPx", "screenHeightPx", "screenDpi",
                "screenCount", "orientation"),
            "settings" to setOf("colorScheme", "layoutDirection", "timeZone", "utcOffsetMinutes", "language", "preferredLanguage",
                "region", "fontScale", "reduceMotion", "boldText", "invertColors", "increaseContrast", "reduceTransparency",
                "differentiateWithoutColor", "fontWeightAdjustment", "contentSizeCategory"),
            "network" to setOf("connectionType", "lowPowerMode"),
            "app" to setOf("appName", "installSource", "isDebugBuild"),
            "usage" to setOf("isNewInstall", "firstSessionDate", "totalSessionsCount", "distinctDaysUsed",
                "distinctDaysUsedLastMonth", "previousSessionSeconds", "averageSessionSeconds", "sessionSeconds", "launchMs",
                "foregroundsToday", "previousForegroundSeconds"),
            "permissions" to setOf("notificationPermission", "trackingPermission"),
        )

        /** Returns the settings, or null when the bytes are not a JSON object. Never throws. */
        fun parse(bytes: ByteArray): RemoteSettings? = try {
            val root = JsonReader.utf8(bytes)?.let(JsonReader::parse) as? Map<*, *>
            if (root == null) null else {
                val groups = root["groups"] as? Map<*, *>
                RemoteSettings(
                    version = (root["version"] as? JsonNumber)?.text?.toLongOrNull()?.takeIf { it >= 0 },
                    screenViews = root["screenViews"] != false,
                    automaticSignals = root["automaticSignals"] != false,
                    offGroups = GROUPS.keys.filterTo(hashSetOf()) { groups?.get(it) == false },
                    disabledSignals = (root["disabledSignals"] as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
                    enabledSignals = (root["enabledSignals"] as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
                    pausedSignals = (root["pausedSignals"] as? List<*>).orEmpty().filterIsInstance<String>().toSet(),
                    heatmaps = root["heatmaps"] == true,
                )
            }
        } catch (_: Exception) { null }
    }
}
