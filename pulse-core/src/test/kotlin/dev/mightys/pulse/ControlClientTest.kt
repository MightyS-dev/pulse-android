package dev.mightys.pulse

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The nine groups every SDK has (SDK_ZAJEDNICKO section 8), plus the client's own rules. */
class ControlClientTest {
    private val env = mapOf("platform" to "mobile", "osName" to "Android", "appVersion" to "2.3.0", "appName" to "Demo", "bundleId" to "com.example.demo")
    private val day = 24 * 3_600_000L
    private val sdk = PulseInfo.SDK_NAME + "@" + PulseInfo.VERSION

    private fun ok(body: String, status: Int = 200) = PulseResponse(status, body.toByteArray(Charsets.UTF_8))
    private fun rules(update: String = "{}", maintenance: String = "null", blocked: String = "[]", off: Boolean = false, version: Int = 7,
        announcement: String = "null", whatsNew: String = "null", disabled: String = "[]", custom: String = "{}") =
        """{"format":1,"version":$version,"off":$off,"update":$update,"blocked":$blocked,"maintenance":$maintenance,"announcement":$announcement,""" +
            """"whatsNew":$whatsNew,"texts":{"custom":$custom},"disabledSdkVersions":$disabled}"""
    private val required = """{"android":{"min":"2.4.0","latest":"2.4.0","mode":"required","storeVersion":null,"storeUrl":"https://play.google.com/store/apps/details?id=com.example.demo"}}"""

    private fun harness(storage: FakeStorage = FakeStorage(), clock: FakeClock = FakeClock(), options: PulseOptions = PulseOptions(), replies: List<PulseResponse> = emptyList(),
        init: Boolean = true): Harness {
        val h = Harness(options = options, storage = storage, clock = clock, initialize = false, env = env)
        h.client.control.fallbackStoreUrl = "https://play.google.com/store/apps/details?id=com.example.demo"
        h.transport.controlReplies.addAll(replies)
        if (init) h.initialize()
        return h
    }
    private fun Harness.kind() = client.control.state.kind

    // ---- group 3: seven failures ----

    private fun failure(name: String, reply: (PulseRequest, (PulseResponse) -> Unit) -> Unit, hangs: Boolean = false) {
        // A. no cache: normal, no screen.
        val a = harness(init = false); a.transport.controlHandler = reply; a.initialize()
        assertEquals("$name: init returns and the state is normal", ControlState.Kind.Normal, a.kind())
        assertEquals("$name: one request at init", 1, a.transport.controlRequests.size)
        if (hangs) { a.advance(5_000); assertTrue("$name: cancelled at 5 s", a.transport.controlCancellations.last().cancelled) }
        a.advance(10 * 60_000); assertEquals("$name: no retry loop", 1, a.transport.controlRequests.size)
        a.client.control.onForeground(); a.settle()
        assertEquals("$name: next foreground tries again", 2, a.transport.controlRequests.size)
        assertEquals(ControlState.Kind.Normal, a.kind())
        // B. last known state stays.
        val storage = FakeStorage(); val clock = FakeClock()
        val good = harness(storage, clock, replies = listOf(ok(rules(update = required))))
        assertEquals(ControlState.Kind.UpdateRequired, good.kind())
        val b = harness(storage, clock, init = false); b.transport.controlHandler = reply; b.initialize()
        assertEquals("$name: cached lock kept", ControlState.Kind.UpdateRequired, b.kind())
        if (hangs) b.advance(5_000)
        b.advance(10 * 60_000); assertEquals("$name: still kept", ControlState.Kind.UpdateRequired, b.kind())
        assertEquals("$name: no retry loop (B)", 1, b.transport.controlRequests.size)
    }

    @Test fun serverDown() = failure("down", { _, done -> done(PulseResponse(0)) })
    @Test fun dnsError() = failure("dns", { _, done -> done(PulseResponse(0)) })
    @Test fun tlsError() = failure("tls", { _, done -> done(PulseResponse(0)) })
    @Test fun serverError500() = failure("500", { _, done -> done(PulseResponse(500, "oops".toByteArray())) })
    @Test fun noAnswerFor30SecondsIsCutAfterFive() = failure("hang", { _, _ -> }, hangs = true)
    @Test fun bodyThatIsNotJson() = failure("notjson", { _, done -> done(ok("<html>maintenance</html>")) })
    @Test fun bodyOfTenMegabytes() = failure("big", { _, done -> done(PulseResponse(200, ByteArray(10 * 1024 * 1024) { 'a'.code.toByte() })) })
    @Test fun otherFailuresKeepTheState() {
        for (reply in listOf(ok("[]"), ok("""{"format":2,"version":1}"""), ok("", 404), ok("""{"format":1}""", 403), ok("{}", 200))) {
            val storage = FakeStorage(); val clock = FakeClock()
            harness(storage, clock, replies = listOf(ok(rules(update = required))))
            val h = harness(storage, clock, init = false); h.transport.controlHandler = { _, done -> done(reply) }; h.initialize()
            assertEquals(ControlState.Kind.UpdateRequired, h.kind())
        }
    }

    // ---- group 4: cache ----

    @Test fun firstStartWithoutNetworkAndCacheIsNormal() {
        val h = harness(replies = listOf(PulseResponse(0)))
        assertEquals(ControlState.Kind.Normal, h.kind())
        assertTrue(h.client.control.state === ControlState.NORMAL || h.client.control.state == ControlState.NORMAL)
    }

    @Test fun cacheOlderThan30DaysDropsLockingRules() {
        val storage = FakeStorage(); val clock = FakeClock()
        harness(storage, clock, replies = listOf(ok(rules(update = required))))
        clock.time += 31 * day
        val h = harness(storage, clock, replies = listOf(PulseResponse(0)))
        assertEquals(ControlState.Kind.Normal, h.kind())
        val blockedStorage = FakeStorage(); val clock2 = FakeClock()
        harness(blockedStorage, clock2, replies = listOf(ok(rules(blocked = """[{"version":"2.3.0","platforms":null,"allowContinue":false}]"""))))
        assertEquals(ControlState.Kind.VersionBlocked, harness(blockedStorage, clock2, replies = listOf(PulseResponse(0))).kind())
        clock2.time += 31 * day
        assertEquals(ControlState.Kind.Normal, harness(blockedStorage, clock2, replies = listOf(PulseResponse(0))).kind())
    }

    @Test fun expiredMaintenanceFromTheCacheIsIgnored() {
        val storage = FakeStorage(); val clock = FakeClock()
        val m = """{"startsAt":"${ControlTime.format(clock.time - 3_600_000)}","endsAt":"${ControlTime.format(clock.time + 3_600_000)}","headsUpMinutes":0,"exemptAboveStore":false,"platforms":null}"""
        assertEquals(ControlState.Kind.Maintenance, harness(storage, clock, replies = listOf(ok(rules(maintenance = m)))).kind())
        clock.time += 2 * 3_600_000
        assertEquals(ControlState.Kind.Normal, harness(storage, clock, replies = listOf(PulseResponse(0))).kind())
    }

    @Test fun maintenanceFromTheCacheNeverLastsOver72HoursFromItsStart() {
        val storage = FakeStorage(); val clock = FakeClock()
        val m = """{"startsAt":"${ControlTime.format(clock.time)}","endsAt":"${ControlTime.format(clock.time + 10 * day)}","headsUpMinutes":0,"exemptAboveStore":false,"platforms":null}"""
        assertEquals(ControlState.Kind.Maintenance, harness(storage, clock, replies = listOf(ok(rules(maintenance = m)))).kind())
        clock.time += 73 * 3_600_000
        assertEquals(ControlState.Kind.Normal, harness(storage, clock, replies = listOf(PulseResponse(0))).kind())
    }

    @Test fun a304KeepsTheCacheAndRefreshesItsTime() {
        val storage = FakeStorage(); val clock = FakeClock()
        harness(storage, clock, replies = listOf(ok(rules(update = required))))
        val before = String(storage.files.entries.first { it.key.startsWith("control-") && !it.key.startsWith("control-local") }.value).lines()[1].toLong()
        clock.time += 5 * day
        val h = harness(storage, clock, replies = listOf(PulseResponse(304)))
        assertEquals("\"7\"", h.transport.controlRequests.single().ifNoneMatch)
        assertEquals(ControlState.Kind.UpdateRequired, h.kind())
        val after = String(storage.files.entries.first { it.key.startsWith("control-") && !it.key.startsWith("control-local") }.value).lines()[1].toLong()
        assertEquals(before + 5 * day, after)
        clock.time += 29 * day // 34 days after the answer, 29 after the 304: locks still apply
        assertEquals(ControlState.Kind.UpdateRequired, harness(storage, clock, replies = listOf(PulseResponse(0))).kind())
    }

    @Test fun cacheFileIsNamedWithTheFirst16HexOfTheKey() {
        val storage = FakeStorage(); harness(storage, replies = listOf(ok(rules())))
        assertTrue(storage.files.containsKey("control-" + Hash.hex("synthetic-key").substring(0, 16)))
    }

    // ---- group 5: kill switches ----

    @Test fun disabledSdkVersionAndOffEndAShownScreenAtTheNextCheck() {
        for (rulesOff in listOf(rules(update = required, disabled = "[\"$sdk\"]", version = 8), rules(update = required, off = true, version = 8))) {
            val h = harness(replies = listOf(ok(rules(update = required))))
            assertEquals(ControlState.Kind.UpdateRequired, h.kind())
            val seen = mutableListOf<ControlState.Kind>(); h.client.control.addListener { seen.add(it.kind) }
            h.transport.controlReplies.add(ok(rulesOff)); h.advance(61_000); h.client.control.onForeground(); h.settle()
            assertEquals(ControlState.Kind.Normal, h.kind()); assertEquals(listOf(ControlState.Kind.Normal), seen)
        }
        val other = harness(replies = listOf(ok(rules(update = required, disabled = """["pulse-swift@0.9.7"]"""))))
        assertEquals("another SDK's entry does not match", ControlState.Kind.UpdateRequired, other.kind())
    }

    // ---- group 6: the loop guard ----

    @Test fun storeVersionEqualToInstalledGivesASuggestion() {
        val u = """{"android":{"min":"2.4.0","latest":"2.4.0","mode":"required","storeVersion":"2.3.0","storeUrl":"https://play.google.com/p"}}"""
        val h = harness(replies = listOf(ok(rules(update = u))))
        assertEquals(ControlState.Kind.UpdateSuggested, h.kind()); assertTrue(h.client.control.state.allowContinue)
    }

    @Test fun threeReturnsOnTheSameVersionGiveASuggestion() {
        val h = harness(replies = listOf(ok(rules(update = required))))
        assertEquals(ControlState.Kind.UpdateRequired, h.kind())
        repeat(2) { h.client.control.noteUpdatePressed(); h.client.control.onForeground(); h.settle() }
        assertEquals("two returns still lock", ControlState.Kind.UpdateRequired, h.kind())
        h.client.control.noteUpdatePressed(); h.client.control.onForeground(); h.settle()
        assertEquals(ControlState.Kind.UpdateSuggested, h.kind())
    }

    @Test fun playUpdateNotAvailableGivesASuggestion() {
        val h = harness(replies = listOf(ok(rules(update = required))))
        h.client.control.notePlayUpdateUnavailable(); h.settle()
        assertEquals(ControlState.Kind.UpdateSuggested, h.kind())
    }

    @Test fun requiredWithoutAnyStoreLinkGivesASuggestion() {
        val noUrl = """{"android":{"min":"2.4.0","latest":null,"mode":"required","storeVersion":null,"storeUrl":null}}"""
        val h = harness(init = false); h.client.control.fallbackStoreUrl = null; h.transport.controlReplies.add(ok(rules(update = noUrl))); h.initialize()
        assertEquals(ControlState.Kind.UpdateSuggested, h.kind())
    }

    @Test fun anAppUpdatedPastTheMinimumIsNormalAndReturnsAreForgotten() {
        val storage = FakeStorage(); val clock = FakeClock()
        val h = harness(storage, clock, replies = listOf(ok(rules(update = required))))
        h.client.control.noteUpdatePressed(); h.client.control.onForeground(); h.settle()
        val newer = Harness(storage = storage, clock = clock, initialize = false, env = env + ("appVersion" to "2.4.0"))
        newer.transport.controlReplies.add(ok(rules(update = required))); newer.initialize()
        assertEquals(ControlState.Kind.Normal, newer.kind())
    }

    // ---- group 7 and 10: opt-out and identity ----

    @Test fun optOutDoesNotStopControlAndTheRequestCarriesNoIdentity() {
        val h = harness(init = false); h.client.optOut = true; h.transport.controlReplies.add(ok(rules(update = required))); h.initialize()
        assertEquals("control works while analytics is off", ControlState.Kind.UpdateRequired, h.kind())
        val request = h.transport.controlRequests.single()
        assertTrue(request.isGet && request.control); assertEquals("", request.appKey); assertEquals(0, request.body.size)
        assertEquals("/api/v1/control/" + ControlIds.forKey("synthetic-key"), request.url.path)
        assertFalse("the key is not in the address", request.url.toString().contains("synthetic-key"))
        assertNull(request.batchId); assertNull(request.contentType)
        assertTrue("no analytics request was made", h.transport.requests.isEmpty())
    }

    @Test fun theRealTransportSendsNoIdentityHeaders() {
        val seen = mutableMapOf<String, String>(); val done = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/control") { exchange ->
            exchange.requestHeaders.forEach { (k, v) -> seen[k.lowercase()] = v.joinToString() }
            val body = rules().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }; done.countDown()
        }
        server.start()
        val transport = PulseHttpTransport()
        try {
            val url = java.net.URL("http://127.0.0.1:${server.address.port}/api/v1/control/" + ControlIds.forKey("synthetic-key"))
            val got = CountDownLatch(1); var status = 0
            transport.send(PulseRequest(url, "", byteArrayOf(), false, isGet = true, ifNoneMatch = "\"7\"", control = true)) { status = it.status; got.countDown() }
            assertTrue(got.await(5, TimeUnit.SECONDS)); assertEquals(200, status)
            assertTrue("no header with the key or an identity: $seen", seen.keys.none { it.startsWith("x-") || it == "authorization" || it == "cookie" })
            assertEquals("\"7\"", seen["if-none-match"]); assertEquals("application/json", seen["accept"])
        } finally { transport.close(); server.stop(0) }
    }

    // ---- group 8: off ----

    @Test fun offMakesNoRequestAndStaysNormal() {
        val h = harness(options = PulseOptions(control = ControlMode.Off), replies = listOf(ok(rules(update = required))))
        h.client.control.onForeground(); h.client.control.checkNow(); h.advance(10 * 60_000); h.client.control.onForeground(); h.settle()
        assertEquals(0, h.transport.controlRequests.size); assertEquals(ControlState.Kind.Normal, h.kind())
        assertTrue("no control file written", h.storage.let { (it as FakeStorage).files.keys.none { name -> name.startsWith("control-") } })
    }

    @Test fun manualStillChecksAndKeepsTheState() {
        val h = harness(options = PulseOptions(control = ControlMode.Manual), replies = listOf(ok(rules(update = required))))
        assertEquals(ControlState.Kind.UpdateRequired, h.kind())
    }

    // ---- group 9: the clock ----

    @Test fun scheduledMaintenanceStartsAndEndsByTheClockWithoutNetwork() {
        val clock = FakeClock()
        val starts = clock.time + 10 * 60_000; val ends = clock.time + 70 * 60_000
        val m = """{"startsAt":"${ControlTime.format(starts)}","endsAt":"${ControlTime.format(ends)}","headsUpMinutes":5,"exemptAboveStore":false,"platforms":null}"""
        val h = harness(clock = clock, replies = listOf(ok(rules(maintenance = m))))
        assertEquals(ControlState.Kind.Normal, h.kind())
        val seen = mutableListOf<ControlState.Kind>(); h.client.control.addListener { seen.add(it.kind) }
        h.transport.controlHandler = { _, done -> done(PulseResponse(0)) }
        h.advance(5 * 60_000 + 1_000)
        assertEquals("heads-up bar five minutes before", ControlState.Kind.MaintenanceUpcoming, h.kind())
        h.advance(302_000)
        assertEquals("maintenance within 5 s of the start", ControlState.Kind.Maintenance, h.kind())
        h.advance(60 * 60_000)
        assertEquals("ends by the clock", ControlState.Kind.Normal, h.kind())
        assertEquals(listOf(ControlState.Kind.MaintenanceUpcoming, ControlState.Kind.Maintenance, ControlState.Kind.Normal), seen)
        assertEquals("no network was needed", 1, h.transport.controlRequests.size)
    }

    // ---- dismiss and the cards ----

    @Test fun dismissingASuggestionHidesItFor24HoursThenItReturns() {
        val u = """{"android":{"min":null,"latest":"2.4.0","mode":"suggested","storeVersion":null,"storeUrl":"https://play.google.com/p"}}"""
        val h = harness(replies = listOf(ok(rules(update = u))))
        assertEquals(ControlState.Kind.UpdateSuggested, h.kind())
        h.client.control.dismiss(); h.settle()
        assertEquals(ControlState.Kind.Normal, h.kind())
        h.advance(23 * 3_600_000L); assertEquals(ControlState.Kind.Normal, h.kind())
        h.advance(2 * 3_600_000L); assertEquals("back after 24 h", ControlState.Kind.UpdateSuggested, h.kind())
    }

    @Test fun lockingScreensIgnoreDismissButDebugOrAllowContinueCanLeave() {
        val locked = harness(replies = listOf(ok(rules(update = required))))
        locked.client.control.dismiss(); locked.settle()
        assertEquals(ControlState.Kind.UpdateRequired, locked.kind())
        val blocked = harness(replies = listOf(ok(rules(blocked = """[{"version":"2.3.0","platforms":null,"allowContinue":true}]"""))))
        assertEquals(ControlState.Kind.VersionBlocked, blocked.kind()); assertNotNull(blocked.client.control.state.secondaryButtonText)
        blocked.client.control.dismiss(); blocked.settle()
        assertEquals(ControlState.Kind.Normal, blocked.kind())
    }

    @Test fun announcementIsShownOnceAndWhatsNewAfterAnUpdateOnce() {
        val a = """{"id":"a7","title":"Winter event","body":"Come play","url":null,"buttonText":null,"until":"${ControlTime.format(FakeClock().time + 5 * day)}","platforms":null}"""
        val storage = FakeStorage(); val clock = FakeClock()
        val h = harness(storage, clock, replies = listOf(ok(rules(announcement = a))))
        assertEquals(ControlState.Kind.Announcement, h.kind()); assertEquals("Winter event", h.client.control.state.title)
        h.client.control.dismiss(); h.settle(); assertEquals(ControlState.Kind.Normal, h.kind())
        assertEquals("not shown again, also after a restart", ControlState.Kind.Normal, harness(storage, clock, replies = listOf(ok(rules(announcement = a)))).kind())
        // an update: the next launch is on 2.4.0 and what's new shows once
        val wn = """{"version":"2.4.0","body":"New levels"}"""
        val next = Harness(storage = storage, clock = clock, initialize = false, env = env + ("appVersion" to "2.4.0"))
        next.transport.controlReplies.add(ok(rules(whatsNew = wn))); next.initialize()
        assertEquals(ControlState.Kind.WhatsNew, next.kind()); assertEquals("What's new in 2.4.0", next.client.control.state.title)
        next.client.control.dismiss(); next.settle()
        assertEquals(ControlState.Kind.Normal, next.kind())
    }

    @Test fun listenersAreRemovableAndABrokenListenerIsHarmless() {
        val h = harness(); var calls = 0
        val l = ControlListener { calls++ }
        h.client.control.addListener { throw IllegalStateException("boom") }; h.client.control.addListener(l)
        h.transport.controlReplies.add(ok(rules(update = required))); h.client.control.checkNow(); h.settle()
        assertEquals(1, calls)
        h.client.control.removeListener(l)
        h.transport.controlReplies.add(ok(rules())); h.client.control.checkNow(); h.settle()
        assertEquals(ControlState.Kind.Normal, h.kind()); assertEquals(1, calls)
    }

    @Test fun checkAtForegroundIsThrottledToOncePerMinute() {
        val h = harness(replies = listOf(ok(rules())))
        h.client.control.onForeground(); h.settle(); assertEquals(1, h.transport.controlRequests.size)
        h.advance(59_000); h.client.control.onForeground(); h.settle(); assertEquals(1, h.transport.controlRequests.size)
        h.advance(2_000); h.client.control.onForeground(); h.settle(); assertEquals(2, h.transport.controlRequests.size)
        h.client.control.checkNow(); h.settle(); assertEquals("checkNow ignores the throttle", 3, h.transport.controlRequests.size)
    }

    @Test fun checkNowDuringARequestAsksOnceMoreWhenItIsAnswered() {
        val h = harness(init = false); val waiting = mutableListOf<(PulseResponse) -> Unit>()
        h.transport.controlHandler = { _, done -> waiting.add(done) }
        h.initialize()
        assertEquals(1, waiting.size)
        h.client.control.checkNow(); h.client.control.checkNow(); h.settle()
        assertEquals("still one request out", 1, waiting.size)
        waiting[0](ok(rules())); h.settle()
        assertEquals("one more, not one per call", 2, waiting.size)
        waiting[1](ok(rules(update = required, version = 8))); h.settle()
        assertEquals(ControlState.Kind.UpdateRequired, h.kind()); assertEquals("no loop", 2, waiting.size)
    }

    // ---- init stays instant ----

    @Test fun initializeWithAHangingTransportReturnsInUnderFiveMillisecondsOnAverage() {
        val total = LongArray(20) {
            val client = PulseClient(scheduler = PulseSerialScheduler(), transport = { _, _ -> PulseCancellation {} })
            val begin = System.nanoTime()
            client.initialize("synthetic-key", PulseOptions(), FakeStorage(), PulseEnvironment { env })
            System.nanoTime() - begin
        }
        val averageMillis = total.average() / 1_000_000.0
        assertTrue("average $averageMillis ms", averageMillis < 5.0)
    }
}
