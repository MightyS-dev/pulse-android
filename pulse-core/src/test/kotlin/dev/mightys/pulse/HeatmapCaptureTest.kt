package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

/** Heatmap capture: point math, buffering, flush triggers, split, cap, rage and every never-capture rule. */
class HeatmapCaptureTest {
    private class Rig(var on: Boolean = true, var session: String? = "s1") {
        val sent = mutableListOf<Map<String, Any?>>()
        val capture = HeatmapCapture({ on }, { session }, { _, map -> sent.add(map) })
        fun tap(at: Long, x: Double, y: Double, w: Double = 400.0, h: Double = 800.0, keyboard: Boolean = false,
            input: Boolean = false, ignored: Boolean = false) = capture.onTap(at, x, y, w, h, keyboard, input, ignored)
        @Suppress("UNCHECKED_CAST") fun points(i: Int = 0) = (sent[i]["points"] as List<Map<String, Any?>>)
        fun text(i: Int = 0) = JsonWriter.value(sent[i])
    }

    @Test fun bucketAndOrientation() {
        assertEquals("phone", HeatmapCapture.bucketOf(599.9)); assertEquals("tablet", HeatmapCapture.bucketOf(600.0))
        assertEquals("tablet", HeatmapCapture.bucketOf(1023.9)); assertEquals("desktop", HeatmapCapture.bucketOf(1024.0))
        assertEquals("portrait", HeatmapCapture.orientationOf(400.0, 400.0)); assertEquals("landscape", HeatmapCapture.orientationOf(800.0, 400.0))
    }

    @Test fun pointMathAndRounding() {
        val r = Rig(); r.capture.setScreen("Home", 0)
        r.tap(0, 200.0, 340.04); r.tap(10_000, 133.3335, 1210.55); r.tap(20_000, -5.0, 200000.0); r.tap(30_000, 999.0, -1.0)
        r.capture.flush(40_000)
        assertEquals("""{"screen":"Home","bucket":"phone","orientation":"portrait","points":[{"x":0.5,"y":340,"kind":"tap"},{"x":0.333,"y":1210.6,"kind":"tap"},{"x":0,"y":100000,"kind":"tap"},{"x":1,"y":0,"kind":"tap"}]}""", r.text())
    }

    @Test fun offMeansNothingAndDropsTheBuffer() {
        val r = Rig(); r.capture.setScreen("Home", 0)
        r.tap(0, 10.0, 10.0); r.on = false; r.tap(5, 10.0, 10.0); r.on = true
        r.capture.flush(10)
        assertTrue(r.sent.isEmpty())
    }

    @Test fun switchedOffBetweenTapAndFlushSendsNothing() {
        val r = Rig(); r.capture.setScreen("Home", 0)
        r.tap(0, 10.0, 10.0); r.on = false; r.capture.flush(10)
        assertTrue(r.sent.isEmpty())
    }

    @Test fun noScreenNoCapture() {
        val r = Rig(); r.tap(0, 10.0, 10.0); r.capture.flush(1)
        assertTrue(r.sent.isEmpty())
        r.capture.setScreen("Home", 2); r.capture.setScreen(null, 3); r.tap(4, 10.0, 10.0); r.capture.flush(5)
        assertTrue(r.sent.isEmpty())
    }

    @Test fun neverCaptureRules() {
        val r = Rig(); r.capture.setScreen("Home", 0)
        r.tap(0, 10.0, 10.0, keyboard = true); r.tap(10_000, 10.0, 10.0, input = true); r.tap(20_000, 10.0, 10.0, ignored = true)
        r.tap(30_000, Double.NaN, 10.0); r.tap(40_000, 10.0, Double.POSITIVE_INFINITY); r.tap(50_000, 10.0, 10.0, w = 0.0)
        r.capture.flush(60_000)
        assertTrue(r.sent.isEmpty())
    }

    @Test fun bufferSentOnScreenChangeAndEmptyOnesNever() {
        val r = Rig(); r.capture.setScreen("A", 0); r.capture.setScreen("B", 1); r.capture.setScreen("B", 2)
        assertTrue(r.sent.isEmpty())
        r.tap(3, 10.0, 10.0); r.capture.setScreen("C", 4)
        assertEquals(1, r.sent.size); assertEquals("B", r.sent[0]["screen"])
        assertEquals("C", r.capture.screen)
    }

    @Test fun bucketOrOrientationChangeStartsANewBuffer() {
        val r = Rig(); r.capture.setScreen("A", 0)
        r.tap(0, 10.0, 10.0, w = 400.0, h = 800.0); r.tap(5_000, 10.0, 10.0, w = 500.0, h = 300.0)
        r.tap(10_000, 10.0, 10.0, w = 700.0, h = 1000.0); r.capture.flush(20_000)
        assertEquals(listOf("phone/portrait", "phone/landscape", "tablet/portrait"), r.sent.map { it["bucket"].toString() + "/" + it["orientation"] })
        assertTrue(r.sent.all { (it["points"] as List<*>).size == 1 })
    }

    @Test fun splitsAt200AndCapsAt1000PerSession() {
        val r = Rig(); r.capture.setScreen("A", 0)
        for (i in 0 until 1100) r.tap(i * 5_000L, (i % 400).toDouble(), 50.0)
        r.capture.flush(10_000_000)
        assertEquals(5, r.sent.size)
        assertTrue(r.sent.all { (it["points"] as List<*>).size == 200 })
    }

    @Test fun capIsPerSessionAndSessionIdsOfPendingAreAdopted() {
        val r = Rig(session = null)
        r.capture.setScreen("A", 0)
        for (i in 0 until 1000) r.tap(i * 5_000L, 5.0, 5.0)
        r.tap(6_000_000, 5.0, 5.0)
        r.capture.flush(6_000_001)
        assertEquals(5, r.sent.size)
        r.session = "s2"; r.capture.adoptSession("s2")
        r.tap(7_000_000, 5.0, 5.0); r.capture.flush(7_000_001)
        assertEquals(5, r.sent.size) // the count follows the session the pending one became
        r.session = "s3"
        r.tap(8_000_000, 5.0, 5.0); r.capture.flush(8_000_001)
        assertEquals(6, r.sent.size)
    }

    @Test fun rageIsTheThirdTapWithinOneSecondAndThirtyPx() {
        val r = Rig(); r.capture.setScreen("A", 0)
        r.tap(0, 100.0, 100.0); r.tap(300, 110.0, 105.0); r.tap(600, 105.0, 98.0); r.tap(900, 100.0, 100.0)
        r.capture.flush(5_000)
        assertEquals(listOf("tap", "tap", "rage", "rage"), r.points().map { it["kind"] })
    }

    @Test fun noRageWhenSlowOrFarApart() {
        val r = Rig(); r.capture.setScreen("A", 0)
        r.tap(0, 100.0, 100.0); r.tap(600, 100.0, 100.0); r.tap(1_200, 100.0, 100.0)
        r.tap(5_000, 100.0, 100.0); r.tap(5_100, 200.0, 100.0); r.tap(5_200, 300.0, 100.0)
        r.capture.flush(9_000)
        assertTrue(r.points().all { it["kind"] == "tap" })
    }

    @Test fun clusterIsMeasuredFromTheFirstTapOfTheCluster() {
        val r = Rig(); r.capture.setScreen("A", 0)
        r.tap(0, 100.0, 100.0); r.tap(200, 125.0, 100.0); r.tap(400, 150.0, 100.0)
        r.capture.flush(5_000)
        assertEquals(listOf("tap", "tap", "tap"), r.points().map { it["kind"] })
    }

    @Test fun rageClusterRestartsOnAScreenChange() {
        val r = Rig(); r.capture.setScreen("A", 0)
        r.tap(0, 100.0, 100.0); r.tap(100, 100.0, 100.0); r.capture.setScreen("B", 150); r.tap(200, 100.0, 100.0)
        r.capture.flush(1_000)
        assertEquals("tap", r.points(1)[0]["kind"])
    }

    @Test fun jsonNumbersAreShortAndValid() {
        val r = Rig(); r.capture.setScreen("A", 0)
        r.tap(0, 0.4, 3.0, w = 400.0); r.capture.flush(1)
        assertTrue(r.text(), r.text().contains("""{"x":0.001,"y":3,"kind":"tap"}"""))
    }

    @Test fun enabledThatThrowsMeansOff() {
        val sent = mutableListOf<Map<String, Any?>>()
        val c = HeatmapCapture({ throw IllegalStateException() }, { "s" }, { _, map -> sent.add(map) })
        c.setScreen("A", 0); c.onTap(0, 1.0, 1.0, 400.0, 800.0, false, false, false); c.flush(1)
        assertTrue(sent.isEmpty())
    }

    @Test fun emitThatThrowsNeverEscapes() {
        val c = HeatmapCapture({ true }, { "s" }, { _, _ -> throw IllegalStateException() })
        c.setScreen("A", 0); c.onTap(0, 1.0, 1.0, 400.0, 800.0, false, false, false); c.flush(1)
    }
}
