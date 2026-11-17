package dev.mightys.pulse

/** What the SDK knows when it decides. */
internal data class ControlInput(
    val rules: ControlRules?,
    val now: Long,
    val platform: String,
    val appVersion: String,
    val debug: Boolean = false,
    /** "sdkName@sdkVersion" exactly as the SDK sends system.sdkName and system.sdkVersion. */
    val sdk: String = "",
    val fromCache: Boolean = false,
    val cacheAgeHours: Double = 0.0,
    /** How many times the user pressed Update and came back on the same app version. */
    val updateReturns: Int = 0,
    val playUpdateNotAvailable: Boolean = false,
    /** The SDK can build a store link itself when storeUrl is missing. */
    val canBuildStoreLink: Boolean = true,
    val suggestDismissedHoursAgo: Double? = null,
    val shownAnnouncements: Set<String> = emptySet(),
    val justUpdated: Boolean = false,
    val whatsNewShown: Boolean = false,
)

/** One main state, whether the user may continue, banners (testBuild, maintenanceUpcoming) and at most one card. */
internal data class ControlOutcome(val state: String, val allowContinue: Boolean, val banners: List<String>, val card: String?) {
    companion object { val NORMAL = ControlOutcome("normal", true, emptyList(), null) }
}

/**
 * The App Control decision, the same in every SDK. Doubt always means no lock: an incomparable version is never below a
 * minimum and never blocked, locking rules from a cache older than 30 days are ignored, and the loop guard turns a
 * required update into a suggestion whenever there is no way out.
 */
internal object ControlDecision {
    const val CACHE_LOCK_HOURS = 30.0 * 24
    const val CACHE_MAINTENANCE_HOURS = 72L
    const val SUGGEST_QUIET_HOURS = 24.0
    const val UPDATE_RETURNS_LIMIT = 3
    private const val HOUR = 3_600_000L
    private const val MINUTE = 60_000L

    fun decide(input: ControlInput): ControlOutcome {
        val rules = input.rules
        if (rules == null || rules.format != 1 || rules.off || input.sdk in rules.disabledSdkVersions) return ControlOutcome.NORMAL
        val staleLocks = input.fromCache && input.cacheAgeHours > CACHE_LOCK_HOURS
        val platformRule = rules.update[input.platform]
        val storeVersion = platformRule?.storeVersion
        val aboveStore = storeVersion != null && ControlVersion.compare(input.appVersion, storeVersion) == 1
        val banners = mutableListOf<String>()
        var main = "normal"; var allowContinue = true

        // 2. Maintenance inside its window (from the cache never past 72 hours after the start), unless exempt.
        val maintenance = rules.maintenance
        if (maintenance != null && covers(maintenance.platforms, input.platform)) {
            val inWindow = maintenance.startsAt <= input.now && input.now < maintenance.endsAt &&
                (!input.fromCache || input.now < maintenance.startsAt + CACHE_MAINTENANCE_HOURS * HOUR)
            val exempt = maintenance.exemptAboveStore && aboveStore
            if (inWindow && !exempt) { main = "maintenance"; allowContinue = false }
            if (input.now < maintenance.startsAt && maintenance.startsAt - maintenance.headsUpMinutes * MINUTE <= input.now) banners.add("maintenanceUpcoming")
        }
        // 3. A blocked version.
        if (main == "normal" && !staleLocks) {
            for (entry in rules.blocked) if (ControlVersion.compare(input.appVersion, entry.version) == 0 && covers(entry.platforms, input.platform)) {
                main = "versionBlocked"; allowContinue = entry.allowContinue; break
            }
        }
        // 4. Below the minimum (review safe: a version above the store version never is), then the loop guard.
        val min = platformRule?.min
        if (main == "normal" && platformRule != null && min != null && ControlVersion.compare(input.appVersion, min) == -1 && !aboveStore) {
            when (platformRule.mode) {
                "required" -> if (!staleLocks) {
                    val noWayOut = storeVersion != null && (ControlVersion.compare(storeVersion, input.appVersion) ?: 1) <= 0 ||
                        input.updateReturns >= UPDATE_RETURNS_LIMIT || input.playUpdateNotAvailable ||
                        platformRule.storeUrl == null && !input.canBuildStoreLink
                    if (noWayOut) main = "updateSuggested" else { main = "updateRequired"; allowContinue = false }
                }
                "suggested" -> main = "updateSuggested"
            }
        }
        // 5. Suggested by latest.
        val latest = platformRule?.latest
        if (main == "normal" && platformRule != null && platformRule.mode == "suggested" && latest != null && ControlVersion.compare(input.appVersion, latest) == -1)
            main = "updateSuggested"
        val dismissed = input.suggestDismissedHoursAgo
        if (main == "updateSuggested" && dismissed != null && dismissed < SUGGEST_QUIET_HOURS) main = "normal"
        if (main == "updateSuggested") allowContinue = true
        // Debug builds: every locking screen shows the test banner and Continue.
        if (input.debug && (main == "maintenance" || main == "versionBlocked" || main == "updateRequired")) { banners.add("testBuild"); allowContinue = true }
        // 6. At most one card: What's new first, then the announcement.
        var card: String? = null
        val whatsNew = rules.whatsNew
        val announcement = rules.announcement
        if (input.justUpdated && !input.whatsNewShown && whatsNew != null && ControlVersion.compare(input.appVersion, whatsNew.version) == 0) card = "whatsNew"
        else if (announcement != null && input.now < announcement.until && covers(announcement.platforms, input.platform) && announcement.id !in input.shownAnnouncements)
            card = "announcement"
        return ControlOutcome(main, allowContinue, banners, card)
    }

    private fun covers(platforms: Set<String>?, platform: String) = platforms == null || platform in platforms
}
