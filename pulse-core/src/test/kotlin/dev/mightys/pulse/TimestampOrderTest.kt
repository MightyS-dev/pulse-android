package dev.mightys.pulse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone

class TimestampOrderTest {
    private fun millis(event: Map<*, *>): Long {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }
        return format.parse(event["timestamp"] as String).time
    }
    private fun types(h: Harness) = h.allEvents.map { it["type"] as String }

    @Test fun threeSignalsInTheSameMillisecondGetPlusZeroPlusOnePlusTwo() {
        val h = Harness()
        h.client.track("a"); h.client.track("b"); h.client.track("c"); h.flush()
        val stamps = h.allEvents.map(::millis)
        assertEquals(listOf("a", "b", "c"), types(h))
        assertEquals(listOf(stamps[0], stamps[0] + 1, stamps[0] + 2), stamps)
    }

    @Test fun automaticSignalsComeBeforeTheTriggerInOrderAndTime() {
        val h = Harness(automatic = true)
        h.client.track("first"); h.client.track("second"); h.flush()
        val stamps = h.allEvents.map(::millis)
        assertEquals(listOf("pulse.new_install", "pulse.session_started", "first", "second"), types(h))
        for (i in 1 until stamps.size) assertEquals(stamps[i - 1] + 1, stamps[i])
    }

    @Test fun signalsTrackedBeforeInitializeKeepTheirCaptureOrder() {
        val h = Harness(initialize = false)
        h.client.track("a"); h.client.track("b"); h.initialize(); h.client.track("c"); h.flush()
        val stamps = h.allEvents.map(::millis)
        assertEquals(listOf("a", "b", "c"), types(h))
        assertEquals(listOf(stamps[0], stamps[0] + 1, stamps[0] + 2), stamps)
    }

    @Test fun aClockThatGoesBackwardsStillYieldsIncreasingTimestamps() {
        val h = Harness()
        h.client.track("a"); h.settle()
        h.clock.time -= 5000; h.client.track("b"); h.settle()
        h.client.track("c"); h.flush()
        val stamps = h.allEvents.map(::millis)
        assertEquals(listOf("a", "b", "c"), types(h))
        assertEquals(listOf(stamps[0], stamps[0] + 1, stamps[0] + 2), stamps)
    }

    @Test fun aLaterClockReadingIsKept() {
        val h = Harness()
        h.client.track("a"); h.settle(); h.clock.time += 2000; h.client.track("b"); h.flush()
        val stamps = h.allEvents.map(::millis)
        assertTrue(stamps[1] - stamps[0] == 2000L)
    }
}
