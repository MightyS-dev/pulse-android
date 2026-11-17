package dev.mightys.pulse

import java.text.DateFormat
import java.util.Locale
import java.util.TimeZone

/** What the screens need to know about the app and the device. */
internal data class ControlContext(
    val appName: String,
    val appVersion: String,
    val languageTags: List<String>,
    /** The store page the SDK can build itself (Android: from the package name), or null. */
    val fallbackStoreUrl: String?,
    val now: Long,
    val timeZone: TimeZone = TimeZone.getDefault(),
)

/** Turns a decision into the public state: texts in the right language, custom texts, times in the device zone. */
internal object ControlStateBuilder {
    fun build(outcome: ControlOutcome, rules: ControlRules, context: ControlContext, platform: String): ControlState {
        val language = ControlTexts.pick(context.languageTags)
        val testBuild = "testBuild" in outcome.banners
        val kind = when {
            outcome.state != "normal" -> outcome.state
            outcome.card != null -> outcome.card
            "maintenanceUpcoming" in outcome.banners -> "maintenanceUpcoming"
            else -> return ControlState.NORMAL
        }
        val custom = rules.custom[kind]
        val rule = rules.update[platform]
        val maintenance = rules.maintenance
        val storeUrl = rule?.storeUrl ?: context.fallbackStoreUrl
        val version = when (kind) {
            "whatsNew" -> rules.whatsNew?.version
            else -> rule?.storeVersion ?: rule?.latest ?: rule?.min
        }
        fun t(key: String) = ControlTexts.text(language, key) ?: ControlTexts.text("en", key).orEmpty()
        fun fill(text: String?): String? = text?.let { fillIn(it, context, language, version, maintenance) }
        fun pick(own: String?, key: String?): String? = fill(own ?: key?.let(::t))

        val secondary = if (outcome.allowContinue) fill(t(if (kind == "updateSuggested") "updateSuggested.dismiss" else "continue")) else null
        val state = when (kind) {
            "updateRequired" -> ControlState(ControlState.Kind.UpdateRequired, pick(custom?.title, "updateRequired.title"),
                pick(custom?.body, "updateRequired.body"), pick(custom?.buttonText, "updateRequired.button"), secondary, storeUrl)
            "updateSuggested" -> ControlState(ControlState.Kind.UpdateSuggested, pick(custom?.title, "updateSuggested.title"),
                pick(custom?.body, "updateSuggested.body"), pick(custom?.buttonText, "updateSuggested.button"), secondary, storeUrl)
            "versionBlocked" -> {
                val canUpdate = storeUrl != null
                ControlState(ControlState.Kind.VersionBlocked, pick(custom?.title, "versionBlocked.title"),
                    pick(custom?.body, if (canUpdate) "versionBlocked.body" else "versionBlocked.bodyNoUpdate"),
                    if (canUpdate) pick(custom?.buttonText, "versionBlocked.button") else null, secondary, storeUrl)
            }
            "maintenance" -> ControlState(ControlState.Kind.Maintenance, pick(custom?.title, "maintenance.title"),
                pick(custom?.body, "maintenance.body"), pick(custom?.buttonText, "maintenance.button"), secondary,
                startsAt = maintenance?.startsAt, endsAt = maintenance?.endsAt)
            "maintenanceUpcoming" -> ControlState(ControlState.Kind.MaintenanceUpcoming, null, pick(custom?.body, "maintenanceUpcoming.banner"),
                startsAt = maintenance?.startsAt, endsAt = maintenance?.endsAt)
            "announcement" -> {
                val a = rules.announcement
                ControlState(ControlState.Kind.Announcement, pick(custom?.title ?: a?.title, null), pick(custom?.body ?: a?.body, null),
                    pick(custom?.buttonText ?: a?.buttonText, "announcement.button"), null, a?.url, version = a?.id)
            }
            else -> ControlState(ControlState.Kind.WhatsNew, pick(custom?.title, "whatsNew.title"),
                pick(custom?.body ?: rules.whatsNew?.body, null), pick(custom?.buttonText, "whatsNew.button"), null, null, version = rules.whatsNew?.version)
        }
        val allowed = if (outcome.state == "normal") true else outcome.allowContinue
        val shown = if (allowed == state.allowContinue) state else state.copy(allowContinue = allowed)
        return if (testBuild) shown.copy(isTestBuild = true, testBuildText = fill(t("testBuild.banner"))) else shown
    }

    private fun fillIn(text: String, context: ControlContext, language: String, version: String?, maintenance: ControlMaintenance?): String {
        if (text.indexOf('{') < 0) return text
        val locale = Locale.forLanguageTag(context.languageTags.firstOrNull()?.replace('_', '-')?.takeIf { it.isNotBlank() } ?: language)
        return text
            .replace("{appName}", context.appName.ifBlank { "this app" })
            .replace("{version}", version ?: context.appVersion)
            .replace("{startTime}", maintenance?.let { time(it.startsAt, context, locale) }.orEmpty())
            .replace("{endTime}", maintenance?.let { time(it.endsAt, context, locale) }.orEmpty())
    }

    /** Short time in the device zone, with the date when it is not today. */
    fun time(at: Long, context: ControlContext, locale: Locale): String {
        val zone = context.timeZone
        val sameDay = dayOf(at, zone) == dayOf(context.now, zone)
        val format = if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT, locale) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
        format.timeZone = zone
        return format.format(java.util.Date(at))
    }

    private fun dayOf(at: Long, zone: TimeZone): Long = Math.floorDiv(at + zone.getOffset(at), 86_400_000L)
}
