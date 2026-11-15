package dev.mightys.pulse

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class UsageTest {
    private val savedZone: TimeZone = TimeZone.getDefault()
    private val day = 86400000L

    @Before fun utc() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restore() { TimeZone.setDefault(savedZone) }

    private fun auto(storage: FakeStorage = FakeStorage(), clock: FakeClock = FakeClock(), env: Map<String, String> = mapOf("platform" to "mobile", "osName" to "Android"),
        dynamic: PulseDynamicValues = PulseDynamicValues { emptyMap() }) =
        Harness(storage = storage, clock = clock, automatic = true, env = env, dynamic = dynamic)
    private fun types(h: Harness) = h.allEvents.map { it["type"] as String }
    private fun system(event: Map<*, *>) = event["system"] as Map<*, *>
    private fun named(h: Harness, type: String) = h.allEvents.filter { it["type"] == type }

    @Test fun newInstallSendsTheThreeAutomaticSignalsOnceAndStats() {
        val h = auto(); h.send("a")
        assertEquals(listOf("pulse.new_install", "pulse.session_started", "a"), types(h))
        val system = system(named(h, "a").single())
        assertEquals("2026-11-13", system["firstSessionDate"]); assertEquals("1", system["totalSessionsCount"])
        assertEquals("1", system["distinctDaysUsed"]); assertEquals("1", system["distinctDaysUsedLastMonth"])
        assertEquals("true", system["isNewInstall"]); assertNull(system["previousSessionSeconds"]); assertNull(system["averageSessionSeconds"])
        h.send("b")
        assertEquals(1, named(h, "pulse.session_started").size); assertEquals(1, named(h, "pulse.new_install").size)
    }

    @Test fun secondSessionHasStatsPreviousAndAverageAndNoNewInstall() {
        val storage = FakeStorage(); val first = auto(storage = storage)
        first.send("a"); first.advance(60000); first.send("b"); first.client.background(); first.settle()
        val second = auto(storage = storage, clock = FakeClock(first.clock.time + 3600000)); second.send("c")
        assertEquals(listOf("pulse.session_started", "c"), types(second))
        val system = system(named(second, "c").single())
        assertEquals("2", system["totalSessionsCount"]); assertEquals("60", system["previousSessionSeconds"])
        assertEquals("60", system["averageSessionSeconds"]); assertNull(system["isNewInstall"])
        assertEquals("2026-11-13", system["firstSessionDate"])
    }

    @Test fun idleThirtyMinutesStartsANewSessionWithPreviousDuration() {
        val h = auto(); h.send("a"); h.advance(100000); h.send("a2"); h.advance(1_860_000); h.send("b")
        assertEquals(2, named(h, "pulse.session_started").size)
        val system = system(named(h, "b").single())
        assertEquals("2", system["totalSessionsCount"]); assertEquals("100", system["previousSessionSeconds"])
        assertEquals("100", system["averageSessionSeconds"])
    }

    @Test fun dayChangeAndThirtyDayWindow() {
        val storage = FakeStorage(); val start = FakeClock().time
        var last: Map<*, *> = emptyMap<Any, Any>()
        for (offset in listOf(0L, 1L, 1L, 5L, 40L)) {
            val h = auto(storage = storage, clock = FakeClock(start + offset * day + (if (offset == 1L) 7200000 else 0))); h.send("x")
            last = system(named(h, "x").single())
        }
        assertEquals("5", last["totalSessionsCount"]); assertEquals("4", last["distinctDaysUsed"])
        assertEquals("1", last["distinctDaysUsedLastMonth"])
        val fifth = auto(storage = FakeStorage()); fifth.send("y")
        assertEquals("1", system(named(fifth, "y").single())["distinctDaysUsed"])
    }

    @Test fun appUpdateIsDetectedOnceWithPreviousValues() {
        val storage = FakeStorage()
        val old = mapOf("platform" to "mobile", "appVersion" to "1.0", "appBuildNumber" to "1")
        auto(storage = storage, env = old).send("a")
        val updated = auto(storage = storage, clock = FakeClock(FakeClock().time + 3600000), env = mapOf("platform" to "mobile", "appVersion" to "1.1", "appBuildNumber" to "2"))
        updated.send("b")
        val change = named(updated, "pulse.app_updated").single()
        assertEquals("1.0", (change["props"] as Map<*, *>)["previousVersion"]); assertEquals("1", (change["props"] as Map<*, *>)["previousBuild"])
        assertTrue(named(updated, "pulse.new_install").isEmpty())
        val again = auto(storage = storage, clock = FakeClock(FakeClock().time + 7200000), env = mapOf("platform" to "mobile", "appVersion" to "1.1", "appBuildNumber" to "2"))
        again.send("c"); assertTrue(named(again, "pulse.app_updated").isEmpty())
    }

    @Test fun optOutSuppressesAutomaticSignalsAndDeletesUsage() {
        val storage = FakeStorage(); val h = auto(storage = storage); h.send("a")
        assertNotNull(storage.files["usage"])
        h.client.optOut = true; h.settle()
        assertNull(storage.files["usage"]); assertNull(storage.files["install-id"])
        val count = h.allEvents.size; h.send("b"); assertEquals(count, h.allEvents.size)
        h.client.optOut = false; h.settle(); h.send("c")
        assertEquals(2, named(h, "pulse.new_install").size)
        assertEquals("1", system(named(h, "c").single())["totalSessionsCount"])
    }

    @Test fun storageFailureKeepsWorkingInMemory() {
        val storage = FakeStorage(); val h = auto(storage = storage)
        storage.failWrites = true
        h.client.track("a"); h.settle(); h.client.track("b"); h.settle()
        storage.failWrites = false; h.advance(1000); h.flush()
        assertEquals(listOf("a", "b"), h.events.map { it["type"] })
        assertEquals(1, named(h, "pulse.session_started").size)
        assertEquals("1", system(h.events.first())["totalSessionsCount"])
        val damaged = FakeStorage(); damaged.files["usage"] = "garbage".toByteArray()
        val d = auto(storage = damaged); d.send("z")
        assertEquals("1", system(named(d, "z").single())["totalSessionsCount"])
    }

    @Test fun dynamicValuesChangeBetweenSignalsAndAreBounded() {
        var values = mapOf("orientation" to "portrait", "fontScale" to "1", "unknownKey" to "x", "appName" to "n".repeat(100))
        val h = auto(dynamic = PulseDynamicValues { values }); h.send("a")
        values = mapOf("orientation" to "landscape", "fontScale" to "1.15", "connectionType" to "wifi")
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Belgrade")); h.send("b")
        val a = system(named(h, "a").single()); val b = system(named(h, "b").single())
        assertEquals("portrait", a["orientation"]); assertEquals("landscape", b["orientation"])
        assertEquals("1.15", b["fontScale"]); assertEquals("wifi", b["connectionType"]); assertNull(a["connectionType"])
        assertNull(a["unknownKey"]); assertEquals(64, (a["appName"] as String).length)
        assertEquals("UTC", a["timeZone"]); assertEquals("0", a["utcOffsetMinutes"])
        assertEquals("Europe/Belgrade", b["timeZone"]); assertEquals("60", b["utcOffsetMinutes"])
    }

    @Test fun sessionSecondsGrowsWithinASession() {
        val h = auto(); h.send("a"); h.advance(95000); h.send("b")
        assertEquals("0", system(named(h, "a").single())["sessionSeconds"])
        assertEquals("95", system(named(h, "b").single())["sessionSeconds"])
    }

    @Test fun firstSignalsBeforeAndRightAfterInitializeCarryTheFullSet() {
        val storage = FakeStorage()
        val h = Harness(storage = storage, initialize = false, automatic = true, dynamic = PulseDynamicValues { mapOf("orientation" to "portrait", "colorScheme" to "dark") })
        h.client.track("before")
        h.initialize(); h.client.track("right.after"); h.flush()
        for (type in listOf("before", "right.after")) {
            val system = system(named(h, type).single())
            for (key in listOf("timeZone", "utcOffsetMinutes", "orientation", "colorScheme", "sessionSeconds", "totalSessionsCount", "firstSessionDate", "distinctDaysUsed", "distinctDaysUsedLastMonth"))
                assertNotNull("$type $key", system[key])
        }
    }

    @Test fun usageStatsFormatsDatesAndSurvivesRoundTrip() {
        assertEquals("1970-01-01", UsageStats.dateString(0)); assertEquals("2026-11-15", UsageStats.dateString(20772))
        assertEquals("2024-02-29", UsageStats.dateString(19782))
        val started = UsageStats.fresh().startSession(1000, 0, 0, 0, "1", "2").usage
        val again = UsageStats.decode(started.encode())
        assertEquals(started, again)
        assertNull(UsageStats.decode("{}".toByteArray())); assertNull(UsageStats.decode(ByteArray(0)))
    }

    @Test fun averageRoundsAndOldSessionsAreForgotten() {
        var usage = UsageStats.fresh().startSession(1000, 0, 0, 0, "1", "1").usage
        usage = usage.startSession(10 * day, 0, 1000, 11_000, "1", "1").usage                          // 10 s
        val third = usage.startSession(20 * day, 0, 10 * day, 10 * day + 15_000, "1", "1")             // 15 s
        assertEquals("13", third.fields["averageSessionSeconds"]); assertEquals("15", third.fields["previousSessionSeconds"])
        val far = third.usage.startSession(200 * day, 0, 20 * day, 20 * day + 5000, "1", "1")
        assertTrue(far.usage.sessions.isEmpty()); assertNull(far.fields["averageSessionSeconds"]); assertEquals("5", far.fields["previousSessionSeconds"])
    }
}
