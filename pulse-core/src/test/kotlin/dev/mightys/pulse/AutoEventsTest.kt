package dev.mightys.pulse

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class AutoEventsTest {
    private val savedZone: TimeZone = TimeZone.getDefault()
    @Before fun utc() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restore() { TimeZone.setDefault(savedZone) }

    private fun config(json: String): FakeStorage = FakeStorage().also { it.files["config"] = json.toByteArray() }
    private fun enabled(vararg types: String) = config("""{"version":1,"enabledSignals":[${types.joinToString(",") { "\"$it\"" }}]}""")
    private val both = arrayOf("pulse.app_opened", "pulse.app_backgrounded")
    private fun harness(storage: FakeStorage = FakeStorage(), clock: FakeClock = FakeClock(), options: PulseOptions = PulseOptions(),
        dynamic: PulseDynamicValues = PulseDynamicValues { emptyMap() }, launch: PulseLaunch = PulseLaunch { null }) =
        Harness(storage = storage, clock = clock, options = options, automatic = true, dynamic = dynamic, launch = launch)
    private fun named(h: Harness, type: String) = h.allEvents.filter { it["type"] == type }
    private fun system(event: Map<*, *>) = event["system"] as Map<*, *>
    private fun props(event: Map<*, *>) = event["props"] as Map<*, *>
    private fun types(h: Harness) = h.allEvents.map { it["type"] as String }
    private fun cycle(h: Harness, seconds: Long = 5) { h.client.appForegrounded(); h.settle(); h.advance(seconds * 1000); h.client.appBackgrounded(); h.settle() }

    @Test fun configClientParsesEnabledAndPausedSignalsDefensively() {
        val s = RemoteSettings.parse("""{"enabledSignals":["pulse.app_opened",1,null],"pausedSignals":["pulse.screen_view"],"pauseExpensiveAt90":true}""".toByteArray())!!
        assertEquals(setOf("pulse.app_opened"), s.enabledSignals); assertEquals(setOf("pulse.screen_view"), s.pausedSignals)
        val bad = RemoteSettings.parse("""{"enabledSignals":"all","pausedSignals":{"a":1}}""".toByteArray())!!
        assertTrue(bad.enabledSignals.isEmpty()); assertTrue(bad.pausedSignals.isEmpty())
        val none = RemoteSettings.parse("{}".toByteArray())!!
        assertTrue(none.enabledSignals.isEmpty()); assertTrue(none.pausedSignals.isEmpty())
    }

    // ---- opt-in app_opened and app_backgrounded ----

    @Test fun optInTypesAreNotSentWithoutACachedConfig() {
        val h = harness(); cycle(h); h.flush()
        assertTrue(named(h, "pulse.app_opened").isEmpty()); assertTrue(named(h, "pulse.app_backgrounded").isEmpty())
    }

    @Test fun optInTypesAreNotSentWhenTheConfigReadFailsOrIsDamaged() {
        val storage = FakeStorage(); storage.files["config"] = "{not json".toByteArray()
        val h = harness(storage); cycle(h); h.flush()
        assertTrue(named(h, "pulse.app_opened").isEmpty())
        val failing = FakeStorage().also { it.files["config"] = """{"enabledSignals":["pulse.app_opened"]}""".toByteArray() }
        failing.failReads = true
        val g = harness(failing); cycle(g); g.flush()
        assertTrue(named(g, "pulse.app_opened").isEmpty())
    }

    @Test fun enabledSignalsSwitchBothOn() {
        val h = harness(enabled(*both)); cycle(h, 7); h.flush()
        assertEquals("true", props(named(h, "pulse.app_opened").single())["coldStart"])
        val back = props(named(h, "pulse.app_backgrounded").single())
        assertEquals("7", (back["foregroundSeconds"] as JsonNumber).text)
    }

    @Test fun backgroundAfterThirtyIdleMinutesIsDroppedAndOpensNoSession() {
        val h = harness(enabled(*both)); h.client.appForegrounded(); h.settle()
        h.advance(31 * 60_000L); h.client.appBackgrounded(); h.settle(); h.flush()
        assertEquals(1, named(h, "pulse.session_started").size); assertTrue(named(h, "pulse.app_backgrounded").isEmpty())
        h.client.appForegrounded(); h.settle(); h.advance(1000); h.client.appBackgrounded(); h.settle(); h.flush()
        assertEquals(1, named(h, "pulse.app_backgrounded").size)
        assertEquals("1860", system(named(h, "pulse.app_backgrounded").single())["previousForegroundSeconds"])
    }

    @Test fun aSingleTypeCanBeEnabledAlone() {
        val h = harness(enabled("pulse.app_opened")); cycle(h); h.flush()
        assertEquals(1, named(h, "pulse.app_opened").size); assertTrue(named(h, "pulse.app_backgrounded").isEmpty())
    }

    @Test fun coldStartIsTrueOnlyForTheFirstForegroundOfTheProcess() {
        val h = harness(enabled(*both)); cycle(h); cycle(h); h.flush()
        assertEquals(listOf("true", "false"), named(h, "pulse.app_opened").map { props(it)["coldStart"] })
    }

    @Test fun eachRealTransitionFiresOnceAndRepeatedCallsDoNot() {
        val h = harness(enabled(*both))
        h.client.appForegrounded(); h.client.appForegrounded(); h.settle(); h.advance(1000)
        h.client.appBackgrounded(); h.client.appBackgrounded(); h.settle(); h.flush()
        assertEquals(1, named(h, "pulse.app_opened").size); assertEquals(1, named(h, "pulse.app_backgrounded").size)
    }

    @Test fun pausedSignalsDropTheTypeEvenWhenEnabled() {
        val h = harness(config("""{"version":3,"enabledSignals":["pulse.app_opened","pulse.app_backgrounded"],"pausedSignals":["pulse.app_opened"]}"""))
        cycle(h); h.flush()
        assertTrue(named(h, "pulse.app_opened").isEmpty()); assertEquals(1, named(h, "pulse.app_backgrounded").size)
    }

    @Test fun pausedScreenViewIsNotSentButManualSignalsGoOn() {
        val h = harness(config("""{"pausedSignals":["pulse.screen_view"]}"""))
        h.client.screen("Home"); h.client.automaticScreen("Other"); h.send("tap")
        assertTrue(named(h, "pulse.screen_view").isEmpty()); assertEquals(1, h.events.size)
        assertFalse(h.client.screenViewHooksEnabled)
    }

    @Test fun portalAutomaticSignalsOffDropsEveryAutomaticType() {
        val h = harness(config("""{"automaticSignals":false,"enabledSignals":["pulse.app_opened","pulse.app_backgrounded"]}"""))
        cycle(h); h.client.deepLink("myapp://x/y"); h.flush()
        assertTrue(types(h).none { it.startsWith("pulse.") })
    }

    @Test fun disabledSignalsDropTheListedNewTypes() {
        val h = harness(config("""{"enabledSignals":["pulse.app_opened"],"disabledSignals":["pulse.app_opened","pulse.deep_link"]}"""))
        cycle(h); h.client.deepLink("myapp://x/y"); h.flush()
        assertTrue(named(h, "pulse.app_opened").isEmpty()); assertTrue(named(h, "pulse.deep_link").isEmpty())
    }

    @Test fun codeOptionAutomaticSignalsFalseSwitchesTheNewTypesOff() {
        val h = harness(enabled(*both), options = PulseOptions(automaticSignals = false))
        cycle(h); h.client.deepLink("myapp://x/y"); h.flush()
        assertTrue(types(h).none { it.startsWith("pulse.") })
    }

    @Test fun debugFlagIsSetOnTheNewSignals() {
        val h = harness(enabled(*both), options = PulseOptions(debug = true)); cycle(h); h.client.deepLink("myapp://x/y"); h.flush()
        assertTrue(named(h, "pulse.app_opened").single()["debug"] == true)
        assertTrue(named(h, "pulse.deep_link").single()["debug"] == true)
    }

    @Test fun optOutSendsNothingAndForegroundDoesNothing() {
        val h = harness(enabled(*both)); h.client.optOut = true; h.settle(); cycle(h); h.flush()
        assertTrue(h.allEvents.isEmpty())
    }

    @Test fun optInTypesStillNeedTheConfigAfterAnUnreadableThenFreshLaunch() {
        val storage = FakeStorage(); val first = harness(storage); cycle(first); first.flush()
        assertTrue(named(first, "pulse.app_opened").isEmpty())
        storage.files["config"] = """{"enabledSignals":["pulse.app_opened"]}""".toByteArray()
        val second = harness(storage); cycle(second); second.flush()
        assertEquals(1, named(second, "pulse.app_opened").size)
    }

    // ---- foreground counters, launchMs ----

    @Test fun foregroundCountersAreOnEverySignalEvenWhenAppOpenedIsNotEnabled() {
        val h = harness(); cycle(h, 30); h.client.appForegrounded(); h.settle(); h.advance(1000); h.send("a")
        val a = system(h.events.single())
        assertEquals("2", a["foregroundsToday"]); assertEquals("30", a["previousForegroundSeconds"])
    }

    @Test fun foregroundsTodayResetsOnTheNextLocalDayAndSurvivesRelaunch() {
        val storage = FakeStorage(); val clock = FakeClock()
        val first = harness(storage, clock); cycle(first); cycle(first)
        val second = harness(storage, clock); second.client.appForegrounded(); second.settle(); second.send("b")
        assertEquals("3", system(second.events.single())["foregroundsToday"])
        clock.time += 86_400_000L
        val third = harness(storage, clock); third.client.appForegrounded(); third.settle(); third.send("c")
        assertEquals("1", system(third.events.single())["foregroundsToday"])
    }

    @Test fun usageGroupOffHidesTheForegroundKeysAndLaunchMs() {
        val h = harness(config("""{"groups":{"usage":false}}"""), launch = PulseLaunch { 800L })
        cycle(h); h.client.appForegrounded(); h.settle(); h.send("a")
        val keys = h.allEvents.flatMap { system(it).keys.map { k -> k as String } }.toSet()
        assertFalse("foregroundsToday" in keys); assertFalse("previousForegroundSeconds" in keys); assertFalse("launchMs" in keys)
    }

    @Test fun launchMsIsOnlyOnTheSessionStartedOfTheColdStart() {
        val clock = FakeClock(); val h = harness(clock = clock, launch = PulseLaunch { 1234L })
        h.send("a"); h.advance(1_860_000); h.send("b")
        val started = named(h, "pulse.session_started")
        assertEquals(2, started.size)
        assertEquals("1234", system(started[0])["launchMs"]); assertNull(system(started[1])["launchMs"])
        assertTrue(h.allEvents.filter { it["type"] != "pulse.session_started" }.all { system(it)["launchMs"] == null })
    }

    @Test fun launchMsIsLeftOutWhenUnknownOrAbsurd() {
        val none = harness(launch = PulseLaunch { null }); none.send("a")
        assertNull(system(named(none, "pulse.session_started").single())["launchMs"])
        val negative = harness(launch = PulseLaunch { -5L }); negative.send("a")
        assertNull(system(named(negative, "pulse.session_started").single())["launchMs"])
        val huge = harness(launch = PulseLaunch { 4_000_000_000L }); huge.send("a")
        assertNull(system(named(huge, "pulse.session_started").single())["launchMs"])
        val throwing = harness(launch = PulseLaunch { error("no") }); throwing.send("a")
        assertNull(system(named(throwing, "pulse.session_started").single())["launchMs"])
    }

    // ---- permissions ----

    private class Permission(var value: String?) : PulseDynamicValues {
        override fun values(): Map<String, String> = value?.let { mapOf("notificationPermission" to it) } ?: emptyMap()
    }

    @Test fun firstObservationSendsNoChangeAndTheKeyIsInTheSystemObject() {
        val permission = Permission("granted"); val h = harness(dynamic = permission); h.send("a")
        assertTrue(named(h, "pulse.permission_changed").isEmpty())
        assertEquals("granted", system(h.events.single())["notificationPermission"])
    }

    @Test fun aChangeIsSentOnceOnTheFirstSignalAfterIt() {
        val storage = FakeStorage(); val permission = Permission("notDetermined")
        val first = harness(storage, dynamic = permission); first.send("a")
        permission.value = "granted"
        val second = harness(storage, dynamic = permission); second.send("b"); second.send("c")
        val changed = named(second, "pulse.permission_changed").single()
        assertEquals(mapOf("permission" to "notification", "from" to "notDetermined", "to" to "granted"), props(changed))
        val third = harness(storage, dynamic = permission); third.send("d")
        assertTrue(named(third, "pulse.permission_changed").isEmpty())
    }

    @Test fun permissionChangeOrderIsBeforeTheSignalThatNoticedIt() {
        val storage = FakeStorage(); val permission = Permission("granted")
        harness(storage, dynamic = permission).send("a")
        permission.value = "denied"
        val h = harness(storage, dynamic = permission); h.send("tap")
        val order = types(h)
        assertTrue(order.indexOf("pulse.permission_changed") < order.indexOf("tap"))
    }

    @Test fun permissionGroupOffHidesTheKeyButNotTheChangeSignal() {
        val storage = config("""{"groups":{"permissions":false}}"""); val permission = Permission("granted")
        harness(storage, dynamic = permission).send("a")
        permission.value = "denied"
        val h = harness(storage, dynamic = permission); h.send("b")
        assertTrue(h.allEvents.all { system(it)["notificationPermission"] == null })
        assertEquals(1, named(h, "pulse.permission_changed").size)
    }

    @Test fun permissionChangeRespectsPortalSwitchesAndOptOut() {
        val storage = config("""{"disabledSignals":["pulse.permission_changed"]}"""); val permission = Permission("granted")
        harness(storage, dynamic = permission).send("a")
        permission.value = "denied"
        val h = harness(storage, dynamic = permission); h.send("b")
        assertTrue(named(h, "pulse.permission_changed").isEmpty())
    }

    @Test fun noStatusMeansNoChangeAndNothingStored() {
        val permission = Permission(null); val h = harness(dynamic = permission); h.send("a")
        assertTrue(named(h, "pulse.permission_changed").isEmpty())
        assertNull(h.storage.let { (it as FakeStorage).files["permissions"] })
    }

    @Test fun permissionChangeCapIsFivePerSession() {
        val storage = FakeStorage(); val permission = Permission("granted"); val h = harness(storage, dynamic = permission)
        h.send("a")
        repeat(8) { permission.value = if (permission.value == "granted") "denied" else "granted"; h.send("t$it") }
        assertEquals(5, named(h, "pulse.permission_changed").size)
    }

    // ---- deep links ----

    @Test fun deepLinkSendsSchemeHostAndCleanPathOnly() {
        val h = harness()
        h.client.deepLink("https://other.example:8443/orders/12345/items/6f1c2b3a-1111-2222-3333-444455556666?token=abc&email=a@b.co#frag"); h.flush()
        val link = props(named(h, "pulse.deep_link").single())
        assertEquals(mapOf("scheme" to "https", "host" to "other.example", "path" to "/orders/:id/items/:id"), link)
    }

    @Test fun deepLinkKeepsOnlyTheThreeUtmTagsFilteredAndShort() {
        val h = harness()
        h.client.deepLink("myapp://promo/summer?utm_source=news%20letter&utm_medium=a@b.co&utm_campaign=${"x".repeat(100)}&utm_term=no&id=7"); h.flush()
        val link = props(named(h, "pulse.deep_link").single())
        assertEquals("news letter", link["utmSource"]); assertEquals("<filtered>", link["utmMedium"])
        assertEquals(64, (link["utmCampaign"] as String).length)
        assertEquals(setOf("scheme", "host", "path", "utmSource", "utmMedium", "utmCampaign"), link.keys)
    }

    @Test fun deepLinkPathIsCappedAt128AndWithoutAuthorityHasNoHost() {
        val h = harness()
        h.client.deepLink("myapp://h/" + "a".repeat(300)); h.client.deepLink("myapp:settings/9"); h.flush()
        val links = named(h, "pulse.deep_link").map(::props)
        assertEquals(128, (links[0]["path"] as String).length)
        assertNull(links[1]["host"]); assertEquals("settings/:id", links[1]["path"])
    }

    @Test fun deepLinkIgnoresGarbageAndNull() {
        val h = harness()
        h.client.deepLink(null); h.client.deepLink(""); h.client.deepLink("not a url"); h.client.deepLink("   "); h.flush()
        assertTrue(named(h, "pulse.deep_link").isEmpty())
    }

    @Test fun deepLinkNeverCarriesCredentialsOrQuery() {
        val h = harness(); h.client.deepLink("myapp://user:pw@host/p?secret=1"); h.flush()
        val body = String(Harness.json(h.transport.requests.first()))
        assertFalse(body.contains("secret")); assertFalse(body.contains("pw")); assertFalse(body.contains("user:"))
    }

    // ---- quota guards ----

    @Test fun appOpenedAndBackgroundedAreCappedAtTwentyPerSessionAndDroppedAutomaticIsSentOnce() {
        val h = harness(enabled(*both)); repeat(21) { cycle(h, 1) }; h.send("manual")
        assertEquals(20, named(h, "pulse.app_opened").size); assertEquals(20, named(h, "pulse.app_backgrounded").size)
        val manual = h.events.single()
        assertEquals("2", system(manual)["droppedAutomatic"])
        h.send("again")
        assertNull(system(h.events.last())["droppedAutomatic"])
    }

    @Test fun droppedAutomaticIsAlsoCarriedByTheNextAutomaticSignalThatGetsThrough() {
        val h = harness(); repeat(11) { h.client.deepLink("myapp://x/$it") }; h.flush()
        assertEquals(10, named(h, "pulse.deep_link").size)
        h.client.deepLink("myapp://x/over"); h.send("tap")
        assertEquals("2", system(h.events.single())["droppedAutomatic"])
    }

    @Test fun deepLinkCapIsTenPerSessionAndANewSessionStartsFresh() {
        val h = harness(); repeat(12) { h.client.deepLink("myapp://x/p$it") }; h.flush()
        assertEquals(10, named(h, "pulse.deep_link").size)
        h.advance(1_860_000); repeat(3) { h.client.deepLink("myapp://x/q$it") }; h.flush()
        assertEquals(13, named(h, "pulse.deep_link").size)
    }

    @Test fun screenViewCapIsOneHundredPerSessionForAutomaticViewsOnly() {
        val h = harness(); repeat(130) { h.client.automaticScreen("S$it") }; h.flush()
        assertEquals(100, named(h, "pulse.screen_view").size)
        repeat(5) { h.client.screen("Manual$it") }; h.flush()
        assertEquals(105, named(h, "pulse.screen_view").size)
    }

    @Test fun dayCapIsThreeHundredAutomaticSignalsAndManualOnesAreNeverCapped() {
        val storage = FakeStorage(); val clock = FakeClock(); val h = harness(storage, clock)
        // Each session sends 1 session_started plus up to 10 deep links; 28 sessions would exceed 300.
        repeat(30) { s -> h.advance(1_860_000L); repeat(10) { h.client.deepLink("myapp://x/s${s}p$it") }; h.settle() }
        h.flush()
        val automatic = h.allEvents.count { (it["type"] as String).let { t -> t.startsWith("pulse.") && t != "pulse.error" } }
        assertEquals(300, automatic)
        repeat(5) { h.send("manual$it") }; h.client.trackError(IllegalStateException("e")); h.flush()
        assertEquals(5, h.events.size); assertEquals(1, named(h, "pulse.error").size)
        assertTrue(h.events.any { system(it)["droppedAutomatic"] != null })
    }

    @Test fun dayCapRollsOverAtLocalMidnightAndPersistsAcrossLaunches() {
        val storage = FakeStorage(); val clock = FakeClock()
        val first = harness(storage, clock)
        repeat(29) { s -> first.advance(1_860_000L); repeat(10) { first.client.deepLink("myapp://x/s${s}p$it") }; first.settle() }
        first.flush()
        val second = harness(storage, clock)
        repeat(5) { second.client.deepLink("myapp://y/$it") }; second.flush()
        assertEquals(0, second.allEvents.count { it["type"] == "pulse.deep_link" })
        clock.time += 86_400_000L
        val third = harness(storage, clock); repeat(5) { third.client.deepLink("myapp://z/$it") }; third.flush()
        assertEquals(5, third.allEvents.count { it["type"] == "pulse.deep_link" })
    }

    @Test fun unreadableQuotaFileDoesNotBreakSendingAndOptOutDeletesTheFiles() {
        val storage = FakeStorage(); val h = harness(storage, dynamic = Permission("granted"))
        cycle(h); h.client.deepLink("myapp://x/y"); h.flush()
        assertTrue(storage.files.keys.containsAll(listOf("foreground", "auto-quota", "permissions")))
        h.client.optOut = true; h.settle()
        assertFalse(storage.files.containsKey("foreground")); assertFalse(storage.files.containsKey("auto-quota"))
        assertFalse(storage.files.containsKey("permissions"))
    }

    @Test fun storageWriteFailuresNeverBreakTheNewSignals() {
        val storage = FakeStorage(); val h = harness(storage); storage.failNames = setOf("foreground", "auto-quota", "permissions", "exit-marker")
        cycle(h); h.client.deepLink("myapp://x/y"); h.flush()
        assertEquals(1, named(h, "pulse.deep_link").size)
    }

    // ---- E2 exit reasons ----

    private fun exit(at: Long, reason: String = "anr", vararg frames: String) = PulseExitRecord(at, reason, frames.toList())
    private fun exits(h: Harness) = named(h, "pulse.error").filter { (it["error"] as Map<*, *>)["kind"] == "exit" }
    private fun error(event: Map<*, *>) = event["error"] as Map<*, *>
    private val dayMs = 86_400_000L

    @Test fun exitRecordIsSentAsPulseErrorWithKindExitAndTheOsTime() {
        val clock = FakeClock(); val h = harness(clock = clock)
        val at = clock.time - 3_600_000
        h.client.reportExits(listOf(exit(at, "anr", "com.app.Main.onCreate Main.kt:12", "android.os.Handler.dispatch Handler.java:99"))); h.settle(); h.flush()
        val event = exits(h).single()
        val error = error(event)
        assertEquals("anr", error["type"]); assertNull(error["message"]); assertEquals(false, error["symbolicated"])
        assertEquals(listOf("com.app.Main.onCreate Main.kt:12", "android.os.Handler.dispatch Handler.java:99"), error["frames"])
        assertEquals(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(at)), event["timestamp"])
        assertNotNull(event["userHash"]); assertNotNull(event["sessionId"])
    }

    @Test fun everyReasonTypeIsPassedAsGiven() {
        val clock = FakeClock(); val h = harness(clock = clock)
        val reasons = listOf("anr", "crash_native", "low_memory", "excessive_resource", "initialization_failure", "signaled", "other")
        h.client.reportExits(reasons.mapIndexed { i, r -> exit(clock.time - 1000L * (i + 1), r) }); h.settle(); h.flush()
        assertEquals(reasons.toSet(), exits(h).map { error(it)["type"] }.toSet())
        assertTrue(exits(h).all { error(it)["frames"] == emptyList<String>() })
    }

    @Test fun frameFilterAndCapApplyToExitFrames() {
        val clock = FakeClock(); val h = harness(clock = clock)
        val frames = (1..40).map { "com.app.C$it.m C.kt:$it" } + "/data/user/0/com.app/files/mail a@b.co"
        h.client.reportExits(listOf(PulseExitRecord(clock.time - 1000, "anr", frames))); h.settle(); h.flush()
        assertEquals(30, (error(exits(h).single())["frames"] as List<*>).size)
    }

    @Test fun recordsAreSentOnceAcrossLaunchesWithAMarker() {
        val storage = FakeStorage(); val clock = FakeClock()
        val first = harness(storage, clock); val record = exit(clock.time - 1000)
        first.client.reportExits(listOf(record)); first.settle(); first.flush()
        assertEquals(1, exits(first).size)
        val second = harness(storage, clock); second.client.reportExits(listOf(record)); second.settle(); second.flush()
        assertEquals(0, exits(second).size)
        val newer = exit(clock.time - 500, "low_memory")
        val third = harness(storage, clock); third.client.reportExits(listOf(record, newer)); third.settle(); third.flush()
        assertEquals(listOf("low_memory"), exits(third).map { error(it)["type"] })
    }

    @Test fun recordsOlderThanSevenDaysAreSkipped() {
        val clock = FakeClock(); val h = harness(clock = clock)
        h.client.reportExits(listOf(exit(clock.time - 8 * dayMs), exit(clock.time - 6 * dayMs + 1000, "signaled"))); h.settle(); h.flush()
        assertEquals(listOf("signaled"), exits(h).map { error(it)["type"] })
    }

    @Test fun atMostTenPerLaunchNewestKeptAndTheRestAreNotSentLater() {
        val storage = FakeStorage(); val clock = FakeClock(); val h = harness(storage, clock)
        h.client.reportExits((1..14).map { exit(clock.time - 100_000L + it * 1000, "other") }); h.settle(); h.flush()
        val times = exits(h).map { it["timestamp"] as String }
        assertEquals(10, times.size); assertEquals(times.sorted(), times)
        val next = harness(storage, clock); next.client.reportExits((1..14).map { exit(clock.time - 100_000L + it * 1000, "other") }); next.settle(); next.flush()
        assertEquals(0, exits(next).size)
    }

    @Test fun futureTimestampsAreIgnoredAndDoNotPoisonTheMarker() {
        val storage = FakeStorage(); val clock = FakeClock(); val h = harness(storage, clock)
        h.client.reportExits(listOf(exit(clock.time + 10 * dayMs))); h.settle(); h.flush()
        assertEquals(0, exits(h).size)
        val later = harness(storage, clock); later.client.reportExits(listOf(exit(clock.time - 1000))); later.settle(); later.flush()
        assertEquals(1, exits(later).size)
    }

    @Test fun captureErrorsFalseSendsAndMarksNothing() {
        val storage = FakeStorage(); val clock = FakeClock()
        val h = harness(storage, clock, PulseOptions(captureErrors = false))
        h.client.reportExits(listOf(exit(clock.time - 1000))); h.settle(); h.flush()
        assertEquals(0, exits(h).size); assertFalse(storage.files.containsKey("exit-marker"))
    }

    @Test fun typeOnlyDropsFramesOfExitSignals() {
        val clock = FakeClock(); val h = harness(clock = clock, options = PulseOptions(errorDetail = ErrorDetail.TYPE_ONLY))
        h.client.reportExits(listOf(exit(clock.time - 1000, "anr", "a.B.c B.kt:1"))); h.settle(); h.flush()
        assertEquals(setOf("kind", "type"), error(exits(h).single()).keys)
    }

    @Test fun optOutSendsNoExitsAndAfterOptInOldRecordsStayUnsent() {
        val storage = FakeStorage(); val clock = FakeClock(); val h = harness(storage, clock)
        h.client.optOut = true; h.settle()
        h.client.reportExits(listOf(exit(clock.time - 1000))); h.settle(); h.flush()
        assertEquals(0, exits(h).size)
        h.client.optOut = false; h.settle()
        h.client.reportExits(listOf(exit(clock.time - 1000))); h.settle(); h.flush()
        assertEquals(0, exits(h).size)
    }

    @Test fun portalDisablingPulseErrorStopsExitSignalsToo() {
        val clock = FakeClock(); val h = harness(config("""{"disabledSignals":["pulse.error"]}"""), clock)
        h.client.reportExits(listOf(exit(clock.time - 1000))); h.settle(); h.flush()
        assertEquals(0, exits(h).size)
    }

    @Test fun exitsAreCountedAsErrorsNotAsAutomaticSignalsForTheDayCap() {
        val clock = FakeClock(); val h = harness(clock = clock)
        h.client.reportExits((1..10).map { exit(clock.time - 1000L * it, "other") }); h.settle(); h.flush()
        assertEquals(10, exits(h).size); assertNull(h.allEvents.firstNotNullOfOrNull { system(it)["droppedAutomatic"] })
    }

    @Test fun exitMarkerWriteFailureSendsNothingToAvoidDuplicates() {
        val storage = FakeStorage(); val clock = FakeClock(); val h = harness(storage, clock); storage.failWrites = true
        h.client.reportExits(listOf(exit(clock.time - 1000))); h.settle(); h.flush()
        assertEquals(0, exits(h).size)
    }
}
