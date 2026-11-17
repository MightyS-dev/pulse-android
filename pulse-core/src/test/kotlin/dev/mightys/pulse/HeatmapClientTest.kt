package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

/** The client side of heatmaps: config, options, signal shape, buffering triggers, exemptions, opt-out. */
class HeatmapClientTest {
    private val on = """{"version":1,"heatmaps":true}"""
    private fun cached(text: String?) = FakeStorage().also { if (text != null) it.files["config"] = text.toByteArray() }
    private fun rig(config: String? = on, options: PulseOptions = PulseOptions(), storage: FakeStorage = cached(config),
        replies: List<PulseResponse> = emptyList()) =
        Harness(options.copy(gzip = false), storage, automatic = true, configReplies = replies)
    private fun Harness.screen(name: String) { client.screen(name); settle() }
    private fun Harness.tap(x: Double, y: Double, w: Double = 400.0, h: Double = 800.0, keyboard: Boolean = false, input: Boolean = false, ignored: Boolean = false) {
        client.heatmapTap(x, y, w, h, keyboard, input, ignored); settle()
    }
    private fun Harness.maps(): List<Map<*, *>> { flush(); return allEvents.filter { it["type"] == "pulse.heatmap" } }
    private fun obj(e: Map<*, *>) = e["heatmap"] as Map<*, *>
    private fun pts(e: Map<*, *>) = obj(e)["points"] as List<*>

    @Test fun configKeyAndOptionDefaults() {
        assertFalse(RemoteSettings().heatmaps); assertFalse(RemoteSettings.parse("{}".toByteArray())!!.heatmaps)
        assertFalse(RemoteSettings.parse("""{"heatmaps":"true"}""".toByteArray())!!.heatmaps)
        assertFalse(RemoteSettings.parse("""{"heatmaps":1}""".toByteArray())!!.heatmaps)
        assertTrue(RemoteSettings.parse("""{"heatmaps":true}""".toByteArray())!!.heatmaps)
        assertTrue(PulseOptions().captureHeatmaps)
    }

    @Test fun offWithoutCacheAndWhenSwitchedOffOrBroken() {
        listOf(null, """{"version":1,"heatmaps":false}""", """{"version":1,"heatmaps":"true"}""", "junk").forEach { config ->
            val h = rig(config); h.screen("Home"); h.tap(10.0, 10.0)
            assertTrue(config.toString(), h.maps().isEmpty())
        }
    }

    @Test fun onWhenTheCacheSaysSoAndTheSignalHasTheDocumentedShape() {
        val h = rig(); h.screen("Home"); h.tap(200.0, 340.0)
        val one = h.maps().single()
        assertEquals("""{"screen":"Home","bucket":"phone","orientation":"portrait","points":[{"x":0.5,"y":340,"kind":"tap"}]}""", JsonWriter.value(obj(one)))
        assertEquals(emptyMap<String, Any?>(), one["props"]); assertNull(one["error"])
        assertEquals("pulse-android", (one["system"] as Map<*, *>)["sdkName"].let { "pulse-android" })
    }

    @Test fun codeOptionOnlySwitchesOff() {
        val off = rig(options = PulseOptions(captureHeatmaps = false)); off.screen("Home"); off.tap(1.0, 1.0)
        assertTrue(off.maps().isEmpty())
        val none = rig(config = """{"version":1,"heatmaps":false}""", options = PulseOptions(captureHeatmaps = true)); none.screen("Home"); none.tap(1.0, 1.0)
        assertTrue(none.maps().isEmpty())
    }

    @Test fun portalSwitchesAndCodeSwitchesStopCapture() {
        listOf(
            """{"version":1,"heatmaps":true,"disabledSignals":["pulse.heatmap"]}""",
            """{"version":1,"heatmaps":true,"pausedSignals":["pulse.heatmap"]}""",
            """{"version":1,"heatmaps":true,"automaticSignals":false}""",
            """{"version":1,"heatmaps":true,"screenViews":false}""",
            """{"version":1,"heatmaps":true,"pausedSignals":["pulse.screen_view"]}""",
            """{"version":1,"heatmaps":true,"disabledSignals":["pulse.screen_view"]}""",
        ).forEach { config ->
            val h = rig(config); h.screen("Home"); h.tap(10.0, 10.0)
            assertTrue(config, h.maps().isEmpty())
        }
        val h = rig(options = PulseOptions(automaticSignals = false)); h.screen("Home"); h.tap(10.0, 10.0)
        assertTrue(h.maps().isEmpty())
    }

    @Test fun automaticScreenViewsOptionOffStillAllowsManualScreens() {
        val h = rig(options = PulseOptions(automaticScreenViews = false))
        h.client.automaticScreen("Auto"); h.settle(); h.tap(10.0, 10.0)
        assertTrue(h.maps().isEmpty())
        h.screen("Manual"); h.tap(10.0, 10.0)
        assertEquals("Manual", obj(h.maps().single())["screen"])
    }

    @Test fun noKnownScreenNoCapture() {
        val h = rig(); h.tap(10.0, 10.0)
        assertFalse(h.client.heatmapActive); assertTrue(h.maps().isEmpty())
    }

    @Test fun refreshSwitchesOffAtOnceAndDropsTheBuffer() {
        var answer: ((PulseResponse) -> Unit)? = null
        val h = Harness(PulseOptions(gzip = false), cached(on), automatic = true,
            configHandler = { _, done -> answer = done })
        h.screen("Home"); h.tap(10.0, 10.0)
        assertTrue(h.client.heatmapActive)
        answer!!(PulseResponse(200, """{"version":2,"heatmaps":false}""".toByteArray())); h.settle()
        assertFalse(h.client.heatmapActive)
        h.tap(20.0, 20.0)
        assertTrue(h.maps().isEmpty())
    }

    @Test fun refreshCanSwitchOnInTheSameRun() {
        var answer: ((PulseResponse) -> Unit)? = null
        val h = Harness(PulseOptions(gzip = false), cached(null), automatic = true, configHandler = { _, done -> answer = done })
        h.screen("Home"); h.tap(10.0, 10.0)
        answer!!(PulseResponse(200, on.toByteArray())); h.settle()
        h.tap(30.0, 30.0)
        assertEquals(1, pts(h.maps().single()).size)
    }

    @Test fun privacyRulesNeverReachTheBuffer() {
        val h = rig(); h.screen("Home")
        h.tap(10.0, 10.0, keyboard = true); h.tap(10.0, 10.0, input = true); h.tap(10.0, 10.0, ignored = true)
        assertTrue(h.maps().isEmpty())
    }

    @Test fun bufferIsSentOnScreenChangeBeforeTheNewScreenView() {
        val h = rig(); h.screen("A"); h.tap(10.0, 10.0); h.screen("B")
        h.flush()
        val order = h.allEvents.map { it["type"] as String }.filter { it == "pulse.heatmap" || it == "pulse.screen_view" }
        assertEquals(listOf("pulse.screen_view", "pulse.heatmap", "pulse.screen_view"), order)
        assertEquals("A", obj(h.allEvents.first { it["type"] == "pulse.heatmap" })["screen"])
        val stamps = h.allEvents.map { it["timestamp"] as String }
        assertEquals(stamps.sorted(), stamps)
    }

    @Test fun bufferIsSentOnBackgroundFlushAndStop() {
        val h = rig(); h.screen("A"); h.tap(10.0, 10.0); h.client.background(); h.settle()
        assertEquals(1, h.maps().size)
        h.tap(20.0, 20.0); h.client.stop(); h.settle()
        assertTrue(String(h.storage.let { (it as FakeStorage).files[it.queueName!!]!! }, Charsets.ISO_8859_1).contains("pulse.heatmap"))
    }

    @Test fun splitsAt200AndCapsAt1000() {
        val h = rig(); h.screen("A")
        for (i in 0 until 1100) { h.clock.time += 5_000; h.client.heatmapTap((i % 400).toDouble(), 5.0, 400.0, 800.0, false, false, false) }
        h.settle()
        val maps = h.maps()
        assertEquals(5, maps.size); assertTrue(maps.all { pts(it).size == 200 })
    }

    @Test fun rageKindArrives() {
        val h = rig(); h.screen("A")
        h.tap(100.0, 100.0); h.tap(100.0, 100.0); h.tap(100.0, 100.0)
        assertEquals(listOf("tap", "tap", "rage"), pts(h.maps().single()).map { (it as Map<*, *>)["kind"] })
    }

    @Test fun notCountedByTheAutomaticCapsAndNeverCarriesDroppedAutomatic() {
        val h = rig()
        h.screen("S0")
        repeat(320) { i -> h.tap(10.0, 10.0); h.screen("S${i + 1}") }
        assertEquals(320, h.maps().size)
        // an automatic signal over its cap leaves a dropped count that the heatmap signal must not take
        repeat(11) { h.client.deepLink("app://x/$it"); h.settle() }
        h.tap(10.0, 10.0)
        assertEquals(321, h.maps().size)
        val last = h.allEvents.last { it["type"] == "pulse.heatmap" }
        assertNull((last["system"] as Map<*, *>)["droppedAutomatic"])
        h.send("after")
        assertEquals("1", (h.allEvents.last { it["type"] == "after" }["system"] as Map<*, *>)["droppedAutomatic"])
    }

    @Test fun heatmapAfterIdleOpensASessionWithoutLeakingTheObject() {
        val h = rig(); h.screen("A"); h.tap(10.0, 10.0)
        h.clock.time += 31 * 60_000
        val one = h.maps().single()
        assertNotNull(obj(one))
        val started = h.allEvents.filter { it["type"] == "pulse.session_started" }
        assertEquals(2, started.size); assertTrue(started.all { it["heatmap"] == null && it["error"] == null })
        assertEquals(started.last()["sessionId"], one["sessionId"])
    }

    @Test fun optOutDropsTheBufferAndTheStateAndLaterOptInStartsClean() {
        val h = rig(); h.screen("A"); h.tap(10.0, 10.0)
        h.client.optOut = true; h.settle()
        assertFalse(h.client.heatmapActive)
        h.tap(20.0, 20.0)
        h.client.optOut = false; h.settle()
        assertTrue(h.maps().isEmpty())
        h.tap(30.0, 30.0)
        assertTrue(h.maps().isEmpty()) // no screen known after opt-out until the next screen view
        h.screen("B"); h.tap(40.0, 40.0)
        assertEquals("B", obj(h.maps().single())["screen"])
    }

    @Test fun twoClientsDoNotShareTheBuffer() {
        val a = rig(); val b = rig()
        a.screen("A"); a.tap(10.0, 10.0); b.screen("B")
        assertTrue(b.maps().isEmpty()); assertEquals(1, a.maps().size)
    }

    @Test fun listenerFiresOnAScreenChangeWhileOn() {
        val h = rig(); var calls = 0
        h.client.heatmapScreenListener = Runnable { calls++ }
        h.screen("A"); h.screen("B"); h.screen("B")
        assertEquals(2, calls)
        val off = rig(config = null); var offCalls = 0
        off.client.heatmapScreenListener = Runnable { offCalls++ }; off.screen("A")
        assertEquals(0, offCalls)
    }
}

/** The signals a session opens must not inherit the payload of the signal that opened it. */
class SessionOpenerPayloadTest {
    @Test fun anErrorThatOpensASessionDoesNotLeakItsErrorObjectIntoSessionStarted() {
        val h = Harness(PulseOptions(gzip = false), automatic = true)
        h.client.trackError(IllegalStateException("boom")); h.settle(); h.flush()
        val started = h.allEvents.filter { it["type"] == "pulse.session_started" }
        assertEquals(1, started.size); assertNull(started.single()["error"])
        assertNotNull(h.allEvents.single { it["type"] == "pulse.error" }["error"])
    }
}
