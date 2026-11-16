package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

class ConfigTest {
    private fun body(text: String) = PulseResponse(200, text.toByteArray())
    private fun full(version: Int = 1, screenViews: Boolean = true, automatic: Boolean = true, groups: String = "",
        disabled: String = "") =
        """{"version":$version,"screenViews":$screenViews,"automaticSignals":$automatic,"groups":{$groups},"disabledSignals":[$disabled]}"""
    private fun cached(text: String) = FakeStorage().also { it.files["config"] = text.toByteArray() }
    private fun types(h: Harness) = h.allEvents.map { it["type"] as String }
    private val allExtra = SystemKeys.EXTRA.associateWith { "1" } + mapOf("platform" to "mobile", "osName" to "Android", "bundleId" to "dev.x")

    // parsing
    @Test fun missingKeysAndEmptyObjectMeanEverythingOn() {
        val s = RemoteSettings.parse("{}".toByteArray())!!
        assertTrue(s.screenViews); assertTrue(s.automaticSignals); assertTrue(s.offGroups.isEmpty()); assertTrue(s.disabledSignals.isEmpty())
        assertNull(s.version)
    }

    @Test fun unknownKeysAreIgnoredAndUnknownSignalNamesAreHarmless() {
        val s = RemoteSettings.parse("""{"version":4,"future":1,"groups":{"device":false,"other":false},"disabledSignals":["nope","pulse.new_install"]}""".toByteArray())!!
        assertEquals(setOf("device"), s.offGroups); assertEquals(4L, s.version)
        assertTrue("pulse.new_install" in s.disabledSignals)
    }

    @Test fun wrongTypesMeanOn() {
        val s = RemoteSettings.parse("""{"version":"3","screenViews":"no","automaticSignals":0,"groups":{"device":null,"screen":"false","settings":0},"disabledSignals":[1,null,"pulse.app_updated"]}""".toByteArray())!!
        assertTrue(s.screenViews); assertTrue(s.automaticSignals); assertTrue(s.offGroups.isEmpty()); assertNull(s.version)
        assertEquals(setOf("pulse.app_updated"), s.disabledSignals)
        val t = RemoteSettings.parse("""{"groups":[1],"disabledSignals":"all"}""".toByteArray())!!
        assertTrue(t.offGroups.isEmpty()); assertTrue(t.disabledSignals.isEmpty())
    }

    @Test fun badBodiesNeverThrow() {
        listOf("", "not json", "[1,2]", "null", "\"x\"", "{\"a\":", "{\"screenViews\":false} trailing").forEach {
            assertNull(it, RemoteSettings.parse(it.toByteArray()))
        }
        assertNull(RemoteSettings.parse(byteArrayOf(-1, -2, 0x7b)))
    }

    @Test fun mappingCoversTheAllowlistOnceAndHasFortySixKeys() {
        val all = RemoteSettings.GROUPS.values.flatten()
        assertEquals(46, all.size); assertEquals(all.size, all.toSet().size)
        assertTrue(all.containsAll(SystemKeys.EXTRA))
        assertFalse("bundleId" in all); assertTrue(all.none { it in SystemKeys.BASE || it in SystemKeys.IDENTITY })
    }

    // fetch
    @Test fun fetchesOncePerLaunchWithKeyAndNoTagWhenNothingIsCached() {
        val storage = FakeStorage(); val h = Harness(storage = storage, configReplies = listOf(body(full(version = 7))))
        h.send(); h.advance(120_000); h.send()
        val r = h.transport.configRequests.single()
        assertEquals("https://in.mightys.dev/api/v1/config", r.url.toString()); assertTrue(r.isGet)
        assertEquals("synthetic-key", r.appKey); assertNull(r.ifNoneMatch); assertEquals(0, r.body.size)
        assertEquals(full(version = 7), String(storage.files["config"]!!))
    }

    @Test fun nextLaunchSendsTheCachedVersionAndA304KeepsTheCache() {
        val storage = cached(full(version = 3, screenViews = false))
        val h = Harness(storage = storage, configReplies = listOf(PulseResponse(304)))
        assertEquals("\"3\"", h.transport.configRequests.single().ifNoneMatch)
        assertEquals(full(version = 3, screenViews = false), String(storage.files["config"]!!))
    }

    @Test fun hostPathIsKept() {
        val h = Harness(PulseOptions(host = "http://127.0.0.1:9/base/"))
        assertEquals("http://127.0.0.1:9/base/api/v1/config", h.transport.configRequests.single().url.toString())
    }

    @Test fun failuresKeepTheCacheAndNoCacheMeansEverythingOn() {
        listOf(PulseResponse(401), PulseResponse(500), PulseResponse(0), body("junk"), body("[]")).forEach { reply ->
            val storage = cached(full(version = 2, screenViews = false))
            val h = Harness(storage = storage, configReplies = listOf(reply), automatic = true)
            assertEquals(full(version = 2, screenViews = false), String(storage.files["config"]!!))
            h.client.screen("A"); h.send()
            assertFalse(types(h).contains("pulse.screen_view"))
            val empty = FakeStorage(); val g = Harness(storage = empty, configReplies = listOf(reply), automatic = true)
            assertNull(empty.files["config"]); g.client.screen("A"); g.send()
            assertTrue(types(g).containsAll(listOf("pulse.screen_view", "pulse.session_started", "pulse.new_install")))
        }
    }

    @Test fun anAnswerFetchedThisRunAppliesOnlyFromTheNextLaunch() {
        val storage = FakeStorage()
        val first = Harness(storage = storage, configReplies = listOf(body(full(version = 1, screenViews = false))), automatic = true)
        first.client.screen("A"); first.send()
        assertTrue(types(first).contains("pulse.screen_view"))
        assertTrue(first.client.screenViewHooksEnabled)
        val second = Harness(storage = storage, configReplies = listOf(PulseResponse(304)), automatic = true)
        second.client.screen("A"); second.send()
        assertFalse(types(second).contains("pulse.screen_view")); assertFalse(second.client.screenViewHooksEnabled)
    }

    @Test fun neverFetchesWhenOptedOutAndDoesNotSaveAfterOptOut() {
        val storage = FakeStorage(); storage.files["opt-out"] = byteArrayOf(1)
        val h = Harness(storage = storage, configReplies = listOf(body(full())))
        assertTrue(h.transport.configRequests.isEmpty()); assertNull(storage.files["config"])
        var answer: ((PulseResponse) -> Unit)? = null
        val s2 = FakeStorage()
        val g = Harness(storage = s2, configHandler = { _, completion -> answer = completion })
        g.client.optOut = true; g.settle(); answer!!(body(full())); g.settle()
        assertNull(s2.files["config"])
    }

    @Test fun optOutDeletesTheConfigFile() {
        val storage = cached(full(version = 5)); val h = Harness(storage = storage, configReplies = listOf(PulseResponse(304)))
        assertNotNull(storage.files["config"])
        h.client.optOut = true; h.settle()
        assertNull(storage.files["config"])
    }

    @Test fun aHungSettingsRequestDoesNotDelayTrackOrFlush() {
        val h = Harness(configHandler = { _, _ -> /* never answers */ })
        h.send("one"); h.send("two")
        assertEquals(listOf("one", "two"), h.events.map { it["type"] })
        h.advance(200_000); h.send("three")
        assertEquals(listOf("one", "two", "three"), h.events.map { it["type"] })
        assertEquals(1, h.transport.configRequests.size)
    }

    @Test fun anUnreadableConfigFileMeansEverythingOnAndASavingFailureIsSilent() {
        val storage = cached("garbage"); val h = Harness(storage = storage, configReplies = listOf(body(full(version = 9))), automatic = true)
        h.client.screen("A"); h.send(); assertTrue(types(h).contains("pulse.screen_view"))
        assertNull(RemoteSettings.parse(ByteArray(0)))
        val failing = FakeStorage(); failing.failWrites = true
        Harness(storage = failing, configReplies = listOf(body(full())))
    }

    // precedence and effects
    @Test fun screenViewsOffStopsHooksAndEverySentScreenViewIncludingManualOnes() {
        val h = Harness(storage = cached(full(screenViews = false)), automatic = true)
        assertFalse(h.client.screenViewHooksEnabled)
        h.client.screen("A"); h.send()
        assertFalse(types(h).contains("pulse.screen_view")); assertTrue(types(h).contains("pulse.session_started"))
    }

    @Test fun automaticSignalsOffSendsNoneOfTheFour() {
        val h = Harness(storage = cached(full(automatic = false)), automatic = true)
        h.client.screen("A"); h.send()
        assertTrue(types(h).none { it.startsWith("pulse.") }); assertEquals(1, h.events.size)
    }

    @Test fun oneDisabledTypeIsLeftOutAndTheOthersStay() {
        val h = Harness(storage = cached(full(disabled = "\"pulse.new_install\"")), automatic = true)
        h.client.screen("A"); h.send()
        val t = types(h); assertFalse(t.contains("pulse.new_install")); assertTrue(t.containsAll(listOf("pulse.session_started", "pulse.screen_view")))
    }

    @Test fun screenViewIsSentOnlyWhenThePortalAllowsItAndTheOptionDoesNotBlockTheAutomaticOne() {
        // portal screenViews off: neither manual nor automatic, even with the option true
        val a = Harness(PulseOptions(automaticScreenViews = true), storage = cached(full(screenViews = false)), automatic = true)
        a.client.screen("M"); a.client.automaticScreen("H"); a.send()
        assertFalse(types(a).contains("pulse.screen_view"))
        // portal automaticSignals off: screen view gone too
        val b = Harness(storage = cached(full(automatic = false)), automatic = true)
        b.client.screen("M"); b.send(); assertFalse(types(b).contains("pulse.screen_view"))
        // type disabled by the portal
        val c = Harness(storage = cached(full(disabled = "\"pulse.screen_view\"")), automatic = true)
        c.client.screen("M"); c.send(); assertFalse(types(c).contains("pulse.screen_view"))
        // portal allows: manual sends, automatic sends
        val d = Harness(storage = cached(full()), automatic = true)
        d.client.screen("M"); d.client.automaticScreen("H"); d.send()
        assertEquals(2, types(d).count { it == "pulse.screen_view" })
        // code option false: automatic dropped, manual still sends
        val e = Harness(PulseOptions(automaticScreenViews = false), storage = cached(full()), automatic = true)
        e.client.automaticScreen("H"); e.client.screen("M"); e.send()
        assertEquals(listOf("M"), e.allEvents.filter { it["type"] == "pulse.screen_view" }.map { (it["props"] as Map<*, *>)["screen"] })
    }

    @Test fun theHookIsInstalledOnlyWhenTheOptionIsNotFalseAndThePortalAllowsScreenViews() {
        assertTrue(Harness(storage = cached(full()), automatic = true).client.screenViewHooksEnabled)
        assertTrue(Harness(PulseOptions(automaticScreenViews = true), storage = cached(full()), automatic = true).client.screenViewHooksEnabled)
        assertFalse(Harness(PulseOptions(automaticScreenViews = false), storage = cached(full()), automatic = true).client.screenViewHooksEnabled)
        assertFalse(Harness(PulseOptions(automaticScreenViews = true), storage = cached(full(screenViews = false)), automatic = true).client.screenViewHooksEnabled)
        assertFalse(Harness(storage = cached(full(automatic = false)), automatic = true).client.screenViewHooksEnabled)
        assertFalse(Harness(storage = cached(full(disabled = "\"pulse.screen_view\"")), automatic = true).client.screenViewHooksEnabled)
    }

    @Test fun theThreeAutomaticSignalsNeedThePortalAndTheOptionNotFalse() {
        val three = listOf("pulse.session_started", "pulse.new_install")
        val on = Harness(PulseOptions(automaticSignals = true), storage = cached(full()), automatic = true); on.send()
        assertTrue(types(on).containsAll(three))
        val optionOff = Harness(PulseOptions(automaticSignals = false), storage = cached(full()), automatic = true); optionOff.send()
        assertTrue(types(optionOff).none { it in three })
        val portalOff = Harness(PulseOptions(automaticSignals = true), storage = cached(full(automatic = false)), automatic = true); portalOff.send()
        assertTrue(types(portalOff).none { it in three })
        val disabled = Harness(storage = cached(full(disabled = "\"pulse.new_install\",\"pulse.session_started\"")), automatic = true); disabled.send()
        assertTrue(types(disabled).none { it in three })
    }

    @Test fun optionsDefaultToOnAndKeepTheirShape() {
        assertTrue(PulseOptions().automaticScreenViews); assertTrue(PulseOptions().automaticSignals)
        assertFalse(PulseOptions(automaticSignals = false).automaticSignals)
        assertEquals(PulseOptions(maxBatchSize = 5), PulseOptions().copy(maxBatchSize = 5))
    }

    @Test fun eachGroupOffRemovesExactlyItsKeysAndNothingElse() {
        val baseline = Harness(env = allExtra, automatic = true).let { it.send(); (it.allEvents.last()["system"] as Map<*, *>).keys.map { k -> k as String }.toSet() }
        assertTrue(baseline.containsAll(allExtra.keys))
        assertTrue(baseline.containsAll(setOf("timeZone", "utcOffsetMinutes", "isNewInstall", "sessionSeconds")))
        RemoteSettings.GROUPS.forEach { (group, keys) ->
            val h = Harness(env = allExtra, storage = cached(full(groups = "\"$group\":false")), automatic = true)
            h.send()
            val seen = (h.allEvents.last()["system"] as Map<*, *>).keys.map { it as String }.toSet()
            assertEquals(group, baseline - keys, seen)
            assertTrue(group, seen.containsAll(SystemKeys.BASE.filter { it in baseline } + listOf("sdkName", "sdkVersion")))
        }
    }

    @Test fun allGroupsOffStillKeepsTheBaseKeys() {
        val all = RemoteSettings.GROUPS.keys.joinToString(",") { "\"$it\":false" }
        val h = Harness(env = allExtra, storage = cached(full(groups = all)), automatic = true); h.send()
        val seen = (h.allEvents.last()["system"] as Map<*, *>).keys.map { it as String }
        assertTrue(seen.none { k -> RemoteSettings.GROUPS.values.any { k in it } }); assertTrue("bundleId" in seen)
    }
}
