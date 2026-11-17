package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

class ErrorCaptureTest {
    private fun boom(message: String = "boom", type: Int = 0): Throwable =
        (if (type == 0) IllegalStateException(message) else IllegalArgumentException(message)).also {
            it.stackTrace = arrayOf(StackTraceElement("com.example.Cart", "checkout", "Cart.kt", 42))
        }
    private fun errors(h: Harness) = h.allEvents.filter { it["type"] == "pulse.error" }
    private fun error(event: Map<*, *>) = event["error"] as Map<*, *>

    @Test fun trackErrorSendsAHandledErrorSignal() {
        val h = Harness(); h.client.trackError(boom(), mapOf("screen" to "cart")); h.flush()
        val event = errors(h).single()
        val error = error(event)
        assertEquals("handled", error["kind"]); assertEquals("java.lang.IllegalStateException", error["type"])
        assertEquals("boom", error["message"]); assertEquals(listOf("com.example.Cart.checkout Cart.kt:42"), error["frames"])
        assertEquals(true, error["symbolicated"])
        assertEquals("cart", (event["props"] as Map<*, *>)["screen"])
        assertNotNull(event["userHash"]); assertNotNull(event["sessionId"]); assertNotNull(event["system"])
    }

    @Test fun errorSignalsStayOutOfOrdinaryEvents() {
        val h = Harness(); h.client.trackError(boom()); h.send("tap")
        assertEquals(1, errors(h).size); assertEquals(listOf("tap"), h.events.map { it["type"] })
    }

    @Test fun nullErrorIsIgnored() {
        val h = Harness(); h.client.trackError(null); h.flush()
        assertTrue(errors(h).isEmpty())
    }

    @Test fun typeOnlyOmitsMessageFramesAndSymbolicated() {
        val h = Harness(options = PulseOptions(errorDetail = ErrorDetail.TYPE_ONLY)); h.client.trackError(boom()); h.flush()
        val error = error(errors(h).single())
        assertEquals(setOf("kind", "type"), error.keys)
    }

    @Test fun typeOnlyAlsoAppliesToErrorsCapturedBeforeInitialize() {
        val h = Harness(options = PulseOptions(errorDetail = ErrorDetail.TYPE_ONLY), initialize = false)
        h.client.trackError(boom()); h.initialize(); h.flush()
        assertEquals(setOf("kind", "type"), error(errors(h).single()).keys)
    }

    @Test fun tenPerTypePerSessionThenDroppedSilently() {
        val h = Harness()
        repeat(15) { h.client.trackError(boom()) }
        repeat(3) { h.client.trackError(boom(type = 1)) }
        h.flush()
        val byType = errors(h).groupingBy { error(it)["type"] }.eachCount()
        assertEquals(10, byType["java.lang.IllegalStateException"]); assertEquals(3, byType["java.lang.IllegalArgumentException"])
    }

    @Test fun rateLimitStartsAgainInANewSession() {
        val h = Harness()
        repeat(12) { h.client.trackError(boom()) }; h.flush()
        h.advance(31 * 60 * 1000)
        repeat(12) { h.client.trackError(boom()) }; h.flush()
        assertEquals(20, errors(h).size)
    }

    @Test fun optOutSuppressesErrors() {
        val h = Harness(); h.client.optOut = true; h.settle()
        h.client.trackError(boom()); h.flush()
        assertTrue(errors(h).isEmpty())
    }

    @Test fun portalFieldGroupsApplyAndAutomaticSignalsOffDoesNotSuppress() {
        val settings = """{"version":1,"groups":{"settings":false},"disabledSignals":[]}"""
        val h = Harness(configReplies = listOf(PulseResponse(200, settings.toByteArray())))
        h.client.trackError(boom()); h.flush()
        val again = Harness(storage = h.storage, configReplies = emptyList())
        again.client.trackError(boom()); again.flush()
        val system = errors(again).single()["system"] as Map<*, *>
        assertFalse(system.containsKey("timeZone"))
        val automaticOff = Harness(storage = FakeStorage().also { it.files["config"] = """{"version":1,"automaticSignals":false}""".toByteArray() })
        automaticOff.client.trackError(boom()); automaticOff.flush()
        assertEquals(1, errors(automaticOff).size)
    }

    @Test fun brokenStorageNeverThrowsOnTrackError() {
        val storage = FakeStorage(); val h = Harness(storage = storage)
        storage.failReads = true; storage.failWrites = true
        h.client.trackError(boom()); h.settle()
        storage.failReads = false; storage.failWrites = false
        h.advance(2000); h.flush()
        assertEquals(1, errors(h).size)
    }

    @Test fun errorObjectIsAtMost16384Bytes() {
        val frames = Array(60) { StackTraceElement("com.example.${"é".repeat(250)}", "m$it", "F.kt", it + 1) }
        val e = IllegalStateException("é".repeat(500)).also { it.stackTrace = frames }
        val h = Harness(); h.client.trackError(e); h.flush()
        val json = JsonWriter.value(error(errors(h).single())).toByteArray(Charsets.UTF_8)
        assertTrue(json.size <= 16384)
    }
}
