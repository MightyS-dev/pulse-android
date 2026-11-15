package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PrivacyTest {
    @Test fun invalidPropsWarnOnlyInDebugAndNeverLogRawValues() {
        for (debug in listOf(true, false)) {
            val logs = mutableListOf<String>()
            val h = Harness(PulseOptions(debug = debug), logger = PulseLogger { logs.add(it) })
            h.send("event", props = mapOf("private-key" to listOf("private-value"), "long" to "secret".repeat(1000)))
            assertEquals(debug, logs.isNotEmpty())
            assertFalse(logs.joinToString().contains("private")); assertFalse(logs.joinToString().contains("secret"))
        }
    }

    @Test fun preinitQueueIsBoundedAndInvalidPublicCallsNeverThrow() {
        val h = Harness(initialize = false)
        h.client.setUser("early.user")
        repeat(105) { h.client.track("early", it.toDouble()) }
        h.client.track(null); h.client.track("x".repeat(1000000)); h.client.track("nan", Double.NaN)
        h.client.initialize("\r\n", null, h.storage, PulseEnvironment { emptyMap() })
        h.initialize(); h.flush()
        assertEquals((5..104).map(Int::toDouble), h.events.map { (it["value"] as JsonNumber).text.toDouble() })
        assertTrue(h.events.all { it["userHash"] == Hash.hex("early.user") })
        h.initialize("ignored"); h.send("still.first"); assertEquals("synthetic-key", h.transport.requests.last().appKey)
    }

    @Test fun userIsHashedOnlyOnDeviceAndClearedForFutureSignals() {
        val storage = FakeStorage(); val h = Harness(storage = storage)
        h.client.setUser("private.synthetic.raw"); h.client.track("one"); h.settle()
        assertTrue(storage.files.values.none { it.toString(Charsets.UTF_8).contains("private.synthetic.raw") })
        h.flush(); assertEquals(Hash.hex("private.synthetic.raw"), h.events.single()["userHash"])
        h.client.setUser(null); h.send("cleared"); assertNotEquals(Hash.hex("private.synthetic.raw"), h.events.last()["userHash"])
        assertNotNull(h.events.last()["userHash"])
        h.client.setUser(""); h.send("empty"); assertNotNull(h.events.last()["userHash"])
    }

    @Test fun optOutPersistsClearsCancelsAndIgnoresLateCompletion() {
        val storage = FakeStorage(); val h = Harness(storage = storage)
        var late: ((PulseResponse) -> Unit)? = null
        h.transport.handler = { _, complete -> late = complete }
        h.send("active"); h.client.optOut = true; assertTrue(h.client.optOut)
        h.client.track("ignored"); h.settle()
        assertTrue(h.transport.cancellations.single().cancelled)
        late!!(PulseResponse(202)); h.settle()
        assertArrayEquals(byteArrayOf(1), storage.files["opt-out"]); assertNull(storage.queueName)
        val restarted = Harness(storage = storage); assertTrue(restarted.client.optOut)
        restarted.send("ignored"); assertTrue(restarted.events.isEmpty())
        restarted.client.optOut = false; restarted.settle(); assertFalse(restarted.client.optOut)
        restarted.send("anonymous"); assertNotNull(restarted.events.single()["userHash"])
    }

    @Test fun privacyReadsAndWritesFailClosedThenRecover() {
        for (bad in listOf(byteArrayOf(), byteArrayOf(2), byteArrayOf(0, 0))) {
            val storage = FakeStorage(); storage.files["opt-out"] = bad
            val h = Harness(storage = storage); h.send(); assertTrue(h.client.optOut); assertTrue(h.events.isEmpty())
        }
        val unreadable = FakeStorage(); unreadable.failReads = true
        val closed = Harness(storage = unreadable); closed.send(); assertTrue(closed.client.optOut); assertTrue(closed.events.isEmpty())
        val storage = FakeStorage(); val h = Harness(storage = storage)
        storage.failPrivacy = true; h.client.optOut = true; h.settle(); h.client.optOut = false; h.settle()
        assertTrue(h.client.optOut); h.send(); assertTrue(h.events.isEmpty())
        storage.failPrivacy = false; h.advance(1000); assertFalse(h.client.optOut)
        h.send("recovered"); assertEquals(1, h.events.size)
    }

    @Test fun preinitPrivacyInvalidatesEveryAppKeyQueue() {
        val storage = FakeStorage(); val one = Harness(storage = storage); one.client.track("old.one"); one.settle()
        val two = Harness(storage = storage, key = "other"); two.client.track("old.two"); two.settle()
        val settings = Harness(storage = storage, initialize = false)
        settings.client.optOut = true; settings.client.optOut = false; settings.initialize(); settings.settle()
        val restoredOne = Harness(storage = storage); restoredOne.flush()
        val restoredTwo = Harness(storage = storage, key = "other"); restoredTwo.flush()
        assertTrue(restoredOne.events.isEmpty()); assertTrue(restoredTwo.events.isEmpty())
    }

    @Test fun concurrentOptOutNeverRestoresCollection() {
        val h = Harness()
        val threads = Executors.newFixedThreadPool(10)
        try {
            repeat(1000) { i -> threads.submit { if (i % 5 == 0) h.client.optOut = true else h.client.track("race") } }
            threads.shutdown(); assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS)); h.settle()
            assertTrue(h.client.optOut); assertTrue(h.events.isEmpty())
            h.client.track("ignored"); h.flush(); assertTrue(h.events.isEmpty())
        } finally { threads.shutdownNow() }
    }

    @Test fun tenThreadsCaptureTenThousandUniqueSignals() {
        val h = Harness(PulseOptions(maxQueueSize = 10000, maxBatchSize = 1000, gzip = false))
        val threads = Executors.newFixedThreadPool(10)
        try {
            repeat(10) { thread -> threads.submit { repeat(1000) { h.client.track("parallel", (thread * 1000 + it).toDouble()) } } }
            threads.shutdown(); assertTrue(threads.awaitTermination(20, TimeUnit.SECONDS)); h.flush()
            val values = h.events.map { (it["value"] as JsonNumber).text.toDouble().toInt() }
            assertEquals((0..9999).toList(), values.sorted()); assertEquals(10000, values.toSet().size)
            assertTrue(h.transport.requests.all { Harness.events(it).size <= 1000 })
        } finally { threads.shutdownNow() }
    }

    @Test fun privacyAndBackgroundDoNotWaitForNetwork() {
        val h = Harness(); h.transport.handler = { _, _ -> }
        h.send("in.flight"); h.client.track("last.capture"); h.client.background(); h.settle()
        val storage = h.storage as FakeStorage
        assertEquals(2, Snapshot.decode(storage.files[storage.queueName]!!)!!.entries.size)
        var completed = false
        h.client.flush { completed = true }; h.settle(); assertFalse(completed)
        h.client.optOut = true; h.settle(); assertTrue(completed)
    }
}
