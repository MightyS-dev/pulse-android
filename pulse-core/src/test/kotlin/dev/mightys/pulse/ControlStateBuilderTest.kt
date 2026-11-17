package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ControlStateBuilderTest {
    private val now = ControlTime.parse("2026-12-01T10:00:00Z")!!
    private val utc = TimeZone.getTimeZone("UTC")
    private fun context(vararg tags: String, url: String? = "https://play.google.com/store/apps/details?id=x") =
        ControlContext("Demo", "2.3.0", tags.toList(), url, now, utc)
    private fun rules(json: String) = ControlRules.parse(json.toByteArray())!!
    private val base = """"format":1,"version":3,"off":false,"blocked":[],"announcement":null,"whatsNew":null,"disabledSdkVersions":[]"""
    private fun required(custom: String = "{}", url: String = "null") =
        rules("""{$base,"update":{"android":{"min":"9.0.0","latest":null,"mode":"required","storeVersion":null,"storeUrl":$url}},"maintenance":null,"texts":{"custom":$custom}}""")
    private fun outcome(rules: ControlRules, version: String = "2.3.0", debug: Boolean = false) =
        ControlDecision.decide(ControlInput(rules, now, "android", version, debug, "pulse-android@0.9.7"))

    @Test fun builtInTextsFillTheAppNameAndFollowTheLanguage() {
        val r = required()
        val en = ControlStateBuilder.build(outcome(r), r, context("en-US"), "android")
        assertEquals("Update required", en.title); assertEquals("Update", en.buttonText)
        assertEquals("This version of Demo is no longer supported. Please update to keep using the app.", en.body)
        assertNull("a locking screen has no way out", en.secondaryButtonText); assertFalse(en.allowContinue)
        val de = ControlStateBuilder.build(outcome(r), r, context("de-AT"), "android")
        assertEquals("Update erforderlich", de.title)
        val ja = ControlStateBuilder.build(outcome(r), r, context("ja"), "android")
        assertEquals(ControlTexts.text("ja", "updateRequired.title"), ja.title)
    }

    @Test fun customTextsReplaceOnlyTheFieldsThatAreSet() {
        val r = required("""{"updateRequired":{"title":null,"body":"Please move to {appName} 9.","buttonText":"Get it"}}""")
        val s = ControlStateBuilder.build(outcome(r), r, context("fr"), "android")
        assertEquals(ControlTexts.text("fr", "updateRequired.title"), s.title)
        assertEquals("Please move to Demo 9.", s.body); assertEquals("Get it", s.buttonText)
    }

    @Test fun languagePickFallsBackThroughTheAppThenTheSystemThenEnglish() {
        assertEquals("en", ControlTexts.pick(emptyList())); assertEquals("en", ControlTexts.pick(listOf("xx", "")))
        assertEquals("pt-BR", ControlTexts.pick(listOf("pt-PT"))); assertEquals("pt-BR", ControlTexts.pick(listOf("pt_BR")))
        assertEquals("zh-Hans", ControlTexts.pick(listOf("zh-CN"))); assertEquals("sr-Latn", ControlTexts.pick(listOf("sr-RS")))
        assertEquals("tr", ControlTexts.pick(listOf("xx", "tr-TR", "de"))); assertEquals("ko", ControlTexts.pick(listOf("ko-KR")))
    }

    @Test fun everyLanguageHasEveryTextAndKeepsTheSamePlaceholders() {
        val keys = ControlTexts.table("en").keys
        for (language in ControlTexts.languages) {
            assertEquals(language, keys, ControlTexts.table(language).keys)
            for (key in keys) {
                val english = Regex("\\{[a-zA-Z]+}").findAll(ControlTexts.text("en", key)!!).map { it.value }.toSet()
                val own = Regex("\\{[a-zA-Z]+}").findAll(ControlTexts.text(language, key)!!).map { it.value }.toSet()
                assertEquals("$language $key", english, own)
            }
        }
    }

    @Test fun storeLinkComesFromTheRulesThenFromThePlatformAndBlockedWithoutOneHasNoButton() {
        val withUrl = required(url = "\"https://play.google.com/p\"")
        assertEquals("https://play.google.com/p", ControlStateBuilder.build(outcome(withUrl), withUrl, context("en"), "android").url)
        val fallback = required()
        assertEquals("https://play.google.com/store/apps/details?id=x", ControlStateBuilder.build(outcome(fallback), fallback, context("en"), "android").url)
        val blocked = rules("""{$base,"update":{},"maintenance":null,"texts":{"custom":{}}}""".replace(""""blocked":[]""", """"blocked":[{"version":"2.3.0","platforms":null,"allowContinue":false}]"""))
        val s = ControlStateBuilder.build(outcome(blocked), blocked, context("en", url = null), "android")
        assertEquals(ControlState.Kind.VersionBlocked, s.kind); assertNull(s.buttonText); assertEquals("This version of Demo has a known problem. A fix is on the way.", s.body)
    }

    @Test fun debugBuildAddsTheTestBannerAndContinue() {
        val r = required()
        val s = ControlStateBuilder.build(outcome(r, debug = true), r, context("en"), "android")
        assertTrue(s.isTestBuild); assertEquals("Test build: App Control preview", s.testBuildText)
        assertEquals("Continue", s.secondaryButtonText); assertTrue(s.allowContinue)
    }

    @Test fun maintenanceTimesUseTheDeviceZoneWithADateWhenNotToday() {
        val m = """{"startsAt":"2026-12-01T13:00:00Z","endsAt":"2026-12-02T14:00:00Z","headsUpMinutes":0,"exemptAboveStore":false,"platforms":null}"""
        val r = rules("""{$base,"update":{},"maintenance":$m,"texts":{"custom":{}}}""")
        val during = ControlInput(r, ControlTime.parse("2026-12-01T13:30:00Z")!!, "android", "2.3.0", false, "x")
        val s = ControlStateBuilder.build(ControlDecision.decide(during), r, ControlContext("Demo", "2.3.0", listOf("en-US"), null, during.now, utc), "android")
        assertEquals(ControlState.Kind.Maintenance, s.kind)
        assertTrue(s.body!!, s.body!!.contains("12/2/26") || s.body!!.contains("2/12/26") || s.body!!.contains("Dec"))
        assertEquals(ControlTime.parse("2026-12-02T14:00:00Z"), s.endsAt)
    }

    @Test fun timesParseWithZonesAndRejectGarbage() {
        assertEquals(1_796_130_000_000, ControlTime.parse("2026-12-01T13:00:00Z"))
        assertEquals(ControlTime.parse("2026-12-01T13:00:00Z"), ControlTime.parse("2026-12-01T15:00:00+02:00"))
        assertEquals(ControlTime.parse("2026-12-01T13:00:00Z")!! + 250, ControlTime.parse("2026-12-01T13:00:00.25Z"))
        for (bad in listOf("", "tomorrow", "2026-13-01T00:00:00Z", "2026-02-30T00:00:00Z", "2026-12-01T25:00:00Z", "2026-12-01")) assertNull(bad, ControlTime.parse(bad))
        assertEquals("2026-02-28T23:59:59Z", ControlTime.format(ControlTime.parse("2026-02-28T23:59:59Z")!!))
    }

    @Test fun rulesWithUnknownOrWrongTypedFieldsNeverLock() {
        val odd = rules("""{"format":1,"version":"x","off":"yes","update":{"android":"nope"},"blocked":[1,"a",{"version":5}],"maintenance":{"startsAt":5},"announcement":[],"whatsNew":"s","texts":7,"future":{"a":1}}""")
        val d = ControlDecision.decide(ControlInput(odd, now, "android", "2.3.0"))
        assertEquals("normal", d.state)
        assertNull(ControlRules.parse("""{"format":2}""".toByteArray())); assertNull(ControlRules.parse("[]".toByteArray())); assertNull(ControlRules.parse(ByteArray(65537) { 32 }))
    }
}
