package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

/** The debug screenshot: only debug builds, one per (screen, bucket, orientation, version), retry rules, never on release. */
class HeatmapShotsTest {
    private val on = """{"version":1,"heatmaps":true}"""
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0)

    private class Rig(debug: Boolean = true, config: String? = """{"version":1,"heatmaps":true}""", storage: FakeStorage = FakeStorage(),
        val clock: FakeClock = FakeClock()) {
        val replies = java.util.ArrayDeque<PulseResponse>()
        val h: Harness
        val shotRequests get() = h.transport.requests.filter { it.contentType != null }
        init {
            config?.let { storage.files["config"] = it.toByteArray() }
            h = Harness(PulseOptions(gzip = false, appVersion = "1.2.3 b"), storage, clock, initialize = false, automatic = true)
            h.transport.handler = { request, done -> done(if (request.contentType != null) (replies.poll() ?: PulseResponse(201)) else PulseResponse(202)) }
            h.client.initialize("synthetic-key", h.options, storage, PulseEnvironment { h.env }, debug, h.dynamic, h.launch)
            h.settle()
            h.client.screen("Shop"); h.settle()
        }
        fun ticket(w: Double = 400.0, hh: Double = 800.0): HeatmapShotTicket? {
            var result: HeatmapShotTicket? = null; var called = false
            h.client.heatmapShotTicket(w, hh) { result = it; called = true }; h.settle(); assertTrue(called)
            return result
        }
        fun upload(t: HeatmapShotTicket, bytes: ByteArray = byteArrayOf(1, 2, 3), type: String = "image/png"): Boolean {
            var stored: Boolean? = null
            h.client.heatmapScreenshot(t, bytes, type) { stored = it }; h.settle()
            return stored == true
        }
    }

    @Test fun releaseBuildsNeverGetATicketOrUpload() {
        val r = Rig(debug = false)
        assertNull(r.ticket()); assertTrue(r.shotRequests.isEmpty())
    }

    @Test fun heatmapsOffMeansNoTicket() {
        assertNull(Rig(config = null).ticket())
        assertNull(Rig(config = """{"version":1,"heatmaps":false}""").ticket())
    }

    @Test fun uploadsRawBytesWithTheDocumentedUrlAndHeaders() {
        val r = Rig(); val t = r.ticket()!!
        assertTrue(r.upload(t, png))
        val q = r.shotRequests.single()
        assertEquals("https://in.mightys.dev/api/v1/heatmap/screenshot?screen=Shop&bucket=phone&orientation=portrait&appVersion=1.2.3%20b&debug=true", q.url.toString())
        assertEquals("image/png", q.contentType); assertFalse(q.gzip); assertFalse(q.isGet)
        assertEquals("synthetic-key", q.appKey); assertArrayEquals(png, q.body)
    }

    @Test fun oneStoredPicturePerViewAndVersionAndTheMarkerSurvivesARestart() {
        val storage = FakeStorage(); val r = Rig(storage = storage)
        assertTrue(r.upload(r.ticket()!!)); assertNull(r.ticket())
        assertNotNull(r.ticket(900.0, 500.0)) // another bucket and orientation is another view
        assertNotNull(r.ticket(400.0, 300.0))
        val again = Rig(storage = storage)
        assertNull(again.ticket())
        assertTrue(String(storage.files["heatmap-shots"]!!).length == 64)
    }

    @Test fun anotherAppVersionIsAnotherView() {
        val storage = FakeStorage(); val r = Rig(storage = storage); assertTrue(r.upload(r.ticket()!!))
        val h = Harness(PulseOptions(gzip = false, appVersion = "2.0"), storage, FakeClock(), initialize = false, automatic = true)
        h.client.initialize("synthetic-key", h.options, storage, PulseEnvironment { h.env }, true, h.dynamic, h.launch); h.settle()
        h.client.screen("Shop"); h.settle()
        var t: HeatmapShotTicket? = null; h.client.heatmapShotTicket(400.0, 800.0) { t = it }; h.settle()
        assertNotNull(t)
    }

    @Test fun finalRefusalsAreMarkedDoneAndNotRetried() {
        listOf(403, 415, 413, 422).forEach { code ->
            val r = Rig(); r.replies.add(PulseResponse(code))
            assertFalse(r.upload(r.ticket()!!)); assertNull(code.toString(), r.ticket())
        }
    }

    @Test fun busyAnswersRetryLaterAndHonourRetryAfter() {
        val r = Rig(); r.replies.add(PulseResponse(429, retryAfter = "120"))
        assertFalse(r.upload(r.ticket()!!))
        assertNull(r.ticket()); r.clock.time += 119_000; assertNull(r.ticket())
        r.clock.time += 2_000
        assertNotNull(r.ticket())
        listOf(503, 507, 0).forEach { code ->
            val x = Rig(); x.replies.add(PulseResponse(code, retryAfter = if (code == 0) null else "5"))
            assertFalse(x.upload(x.ticket()!!))
            x.clock.time += 6_000; assertNotNull(code.toString(), x.ticket())
        }
    }

    @Test fun neverMoreThanTwentyAnHour() {
        val r = Rig()
        repeat(20) { i -> r.replies.add(PulseResponse(0)); assertFalse(r.upload(r.ticket()!!)) }
        assertNull(r.ticket())
        r.clock.time += 3_600_000
        assertNotNull(r.ticket())
    }

    @Test fun inputsAreChecked() {
        val r = Rig(); val t = r.ticket()!!
        assertFalse(r.upload(t, ByteArray(0))); assertFalse(r.upload(t, ByteArray(2 * 1024 * 1024 + 1)))
        assertFalse(r.upload(t, byteArrayOf(1), "image/svg+xml")); assertFalse(r.upload(t, byteArrayOf(1), "text/html"))
        assertTrue(r.shotRequests.isEmpty()); assertNotNull(r.ticket())
        assertNull(r.ticket(0.0, 800.0))
    }

    @Test fun optOutForgetsMarkersAndDeletesTheFile() {
        val storage = FakeStorage(); val r = Rig(storage = storage); assertTrue(r.upload(r.ticket()!!))
        assertNotNull(storage.files["heatmap-shots"])
        r.h.client.optOut = true; r.h.settle()
        assertNull(storage.files["heatmap-shots"])
    }

    @Test fun inFlightPictureIsNotRequestedTwice() {
        val r = Rig(); var answer: ((PulseResponse) -> Unit)? = null
        r.h.transport.handler = { request, done -> if (request.contentType != null) answer = done else done(PulseResponse(202)) }
        val t = r.ticket()!!
        r.h.client.heatmapScreenshot(t, byteArrayOf(1), "image/png"); r.h.settle()
        assertNull(r.ticket())
        answer!!(PulseResponse(201)); r.h.settle()
        assertNull(r.ticket())
    }
}
