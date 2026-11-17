package dev.mightys.pulse

/** Reads one `input` object of decision-cases.json into the decision input. */
internal object ControlTableRows {
    fun input(row: Map<*, *>): ControlInput = ControlInput(
        rules = (row["rules"] as? Map<*, *>)?.let(ControlRules::fromMap),
        now = ControlTime.parse(row["now"] as String)!!,
        platform = row["platform"] as String,
        appVersion = row["appVersion"] as String,
        debug = row["debug"] as Boolean,
        sdk = row["sdk"] as String,
        fromCache = row["fromCache"] as Boolean,
        cacheAgeHours = (row["cacheAgeHours"] as JsonNumber).text.toDouble(),
        updateReturns = (row["updateReturns"] as JsonNumber).text.toInt(),
        playUpdateNotAvailable = row["playUpdateNotAvailable"] as Boolean,
        canBuildStoreLink = row["canBuildStoreLink"] as Boolean,
        suggestDismissedHoursAgo = (row["suggestDismissedHoursAgo"] as? JsonNumber)?.text?.toDouble(),
        shownAnnouncements = (row["shownAnnouncements"] as List<*>).map { it as String }.toSet(),
        justUpdated = row["justUpdated"] as Boolean,
        whatsNewShown = row["whatsNewShown"] as Boolean,
    )
}
