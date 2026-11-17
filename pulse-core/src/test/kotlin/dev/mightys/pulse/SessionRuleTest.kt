package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

/** A new session id starts when the next signal arrives 30 minutes or more after the previous signal. */
class SessionRuleTest {
    private val minute = 60_000L
    private fun auto(storage: FakeStorage = FakeStorage(), clock: FakeClock = FakeClock()) = Harness(storage = storage, clock = clock, automatic = true)
    private fun ids(h: Harness, type: String) = h.allEvents.filter { it["type"] == type }.map { it["sessionId"] as String }
    private fun started(h: Harness) = h.allEvents.count { it["type"] == "pulse.session_started" }

    @Test fun sameIdWithin29MinutesAndNewIdAtThirty() {
        val h = auto(); h.send("a")
        h.advance(29 * minute + 59_000); h.send("b")
        assertEquals(1, (ids(h, "a") + ids(h, "b")).toSet().size)
        h.advance(30 * minute); h.send("c")
        assertNotEquals(ids(h, "b")[0], ids(h, "c")[0])
        assertEquals(2, started(h))
    }

    @Test fun exactlyThirtyMinutesRotates() {
        val h = auto(); h.send("a"); h.advance(30 * minute); h.send("b")
        assertNotEquals(ids(h, "a")[0], ids(h, "b")[0]); assertEquals(2, started(h))
    }

    @Test fun fourteenHourGapInsideOneRunningProcessRotatesOnce() {
        val h = auto(); h.send("a")
        h.advance(14 * 60 * minute); h.send("b"); h.send("c")
        assertNotEquals(ids(h, "a")[0], ids(h, "b")[0]); assertEquals(ids(h, "b"), ids(h, "c"))
        assertEquals(2, started(h))
    }

    @Test fun restartAfter31MinutesGivesNewIdAndOneSessionStarted() {
        val storage = FakeStorage(); val first = auto(storage = storage); first.send("a")
        val second = auto(storage = storage, clock = FakeClock(first.clock.time + 31 * minute)); second.send("b")
        assertNotEquals(ids(first, "a")[0], ids(second, "b")[0]); assertEquals(1, started(second))
    }

    @Test fun flushBackgroundAndTimersAloneDoNotExtendASession() {
        val h = auto(); h.send("a")
        repeat(40) { h.advance(minute); h.flush(); h.client.background(); h.settle() }
        h.send("b")
        assertNotEquals(ids(h, "a")[0], ids(h, "b")[0]); assertEquals(2, started(h))
    }

    @Test fun idleIsMeasuredFromTheLastSignalNotTheSessionStart() {
        val h = auto(); h.send("a")
        h.advance(20 * minute); h.send("b"); h.advance(20 * minute); h.send("c")
        assertEquals(1, (ids(h, "a") + ids(h, "b") + ids(h, "c")).toSet().size)
        h.advance(40 * minute); h.flush(); h.advance(5 * minute); h.send("d")
        assertNotEquals(ids(h, "c")[0], ids(h, "d")[0]); assertEquals(2, started(h))
    }

    @Test fun signalsTrackedBeforeInitializeUseTheirOwnCaptureTime() {
        val h = Harness(initialize = false, automatic = true)
        h.client.track("early1"); h.settle()
        h.clock.time += 14 * 60 * minute
        h.client.track("early2"); h.settle()
        h.initialize(); h.flush()
        assertNotEquals(ids(h, "early1")[0], ids(h, "early2")[0]); assertEquals(2, started(h))
    }
}
