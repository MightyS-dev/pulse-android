package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DeliveryTest {
    @Test fun timeCountAndBackgroundTriggers() {
        val h = Harness(PulseOptions(maxBatchSize = 2))
        // The first batch after initialize leaves at once; later ones wait for the interval or the batch size.
        h.client.track("first"); h.settle(); assertEquals(listOf("first"), h.events.map { it["type"] })
        h.client.track("one"); h.settle(); assertEquals(1, h.events.size)
        h.advance(19999); assertEquals(1, h.events.size); h.advance(1); assertEquals(2, h.events.size)
        h.client.track("two"); h.client.track("three"); h.settle()
        assertEquals(3, h.transport.requests.size); assertEquals(2, Harness.events(h.transport.requests[2]).size)
        h.client.track("background"); h.client.background(); h.settle()
        assertEquals("background", h.events.last()["type"])
    }

    @Test fun retryStatusesKeepIdenticalBytesAndDeadline() {
        for (status in listOf(0, 302, 403, 408, 425, 429, 500, 502, 503, 599)) {
            val h = Harness(); h.transport.replies.add(PulseResponse(status))
            h.send("retry", props = mapOf("large" to "a".repeat(1000)))
            val first = h.transport.requests.single()
            h.flush(); h.client.background(); h.settle(); h.advance(999)
            assertEquals("status $status", 1, h.transport.requests.size)
            h.advance(1); assertEquals(2, h.transport.requests.size)
            assertArrayEquals(first.body, h.transport.requests.last().body)
            assertEquals(first.gzip, h.transport.requests.last().gzip)
        }
    }

    @Test fun exponentialJitterAndFiveMinuteCap() {
        for (sample in listOf(0.0, 1.0, Double.NaN)) {
            val h = Harness(random = PulseRandom { sample })
            repeat(12) { h.transport.replies.add(PulseResponse(500)) }
            h.send()
            repeat(12) { failure ->
                val jitter = if (sample.isFinite()) sample else 0.5
                val seconds = (minOf(300.0, Math.pow(2.0, minOf(9, failure).toDouble())) * (0.5 + jitter)).coerceIn(1.0, 300.0)
                val count = h.transport.requests.size
                h.advance((seconds * 1000).toLong() - 1); assertEquals(count, h.transport.requests.size)
                h.advance(1); assertEquals(count + 1, h.transport.requests.size)
            }
        }
    }

    @Test fun retryAfterSecondsHttpDateAndOneHourCap() {
        val epoch = 0L
        assertEquals(3600000L, RetryAfter.delay("999999999", epoch))
        assertEquals(120000L, RetryAfter.delay("Thu, 01 Jan 1970 00:02:00 GMT", epoch))
        assertEquals(3600000L, RetryAfter.delay("Fri, 02 Jan 1970 00:00:00 GMT", epoch))
        for (bad in listOf("bad", "-1", "Infinity", "NaN")) assertNull(RetryAfter.delay(bad, epoch))
        for (status in listOf(429, 503)) {
            val h = Harness()
            h.transport.replies.add(PulseResponse(status, "{\"recommendedSendIntervalSeconds\":30,\"pause\":false}".toByteArray(), "60"))
            h.send(); h.advance(59999); assertEquals(1, h.transport.requests.size)
            h.advance(1); assertEquals(2, h.transport.requests.size)
        }
    }

    @Test fun recursiveBadSignalIsolationTerminatesAndKeepsGoodSignals() {
        for (status in listOf(400, 413)) {
            val h = Harness(PulseOptions(maxBatchSize = 8, gzip = false))
            val delivered = mutableListOf<Int>()
            h.transport.handler = { request, complete ->
                val values = Harness.events(request).map { (it["value"] as JsonNumber).text.toDouble().toInt() }
                val bad = 2 in values || 5 in values
                if (!bad) delivered.addAll(values)
                complete(PulseResponse(if (bad) status else 202))
            }
            repeat(8) { h.client.track("event", it.toDouble()) }; h.flush()
            assertEquals(listOf(0, 1, 3, 4, 6, 7), delivered.sorted())
            assertTrue(h.transport.requests.size <= 15)
        }
    }

    @Test fun unauthorizedKeepsExactQueueAndStopsUntilRestart() {
        val storage = FakeStorage(); val h = Harness(storage = storage)
        h.transport.replies.add(PulseResponse(401)); h.send()
        h.client.track("new"); h.advance(3600000); h.flush(); assertEquals(1, h.transport.requests.size)
        val restarted = Harness(storage = storage, clock = FakeClock(h.clock.time)); restarted.flush()
        assertEquals(2, restarted.events.size)
        assertArrayEquals(h.transport.requests[0].body, restarted.transport.requests[0].body)
    }

    @Test fun gzip415IsProcessWideAnd413IsPerBatchFallback() {
        for (status in listOf(415, 413)) {
            val h = Harness(); h.transport.replies.add(PulseResponse(status))
            val props = mapOf("large" to "q".repeat(1000))
            h.send("one", props = props)
            assertTrue(h.transport.requests[0].gzip); assertFalse(h.transport.requests[1].gzip)
            assertArrayEquals(Harness.json(h.transport.requests[0]), h.transport.requests[1].body)
            h.send("two", props = props); assertEquals(status == 413, h.transport.requests.last().gzip)
        }
    }

    @Test fun exactInflightBytesSurviveRestartAndExcludeNewSignals() {
        val storage = FakeStorage(); val h = Harness(storage = storage)
        h.transport.replies.add(PulseResponse(500, retryAfter = "60"))
        h.send("original", props = mapOf("large" to "x".repeat(1000)))
        h.client.track("newer"); h.settle()
        val restarted = Harness(storage = storage, clock = FakeClock(h.clock.time)); restarted.flush()
        assertTrue(restarted.transport.requests.isEmpty()); restarted.advance(60000)
        assertEquals(2, restarted.transport.requests.size)
        assertArrayEquals(h.transport.requests.single().body, restarted.transport.requests[0].body)
        assertEquals("newer", Harness.events(restarted.transport.requests[1]).single()["type"])
    }

    @Test fun realFileQueueRestartsAndDamagedFileDoesNotCrash() {
        val root = Files.createTempDirectory("pulse-runtime").toFile()
        try {
            val h = Harness(storage = PulseFileStorage(root)); h.send("warmup") // uses up the immediate first batch
            h.client.track("saved"); h.settle()
            assertEquals(listOf("warmup"), h.events.map { it["type"] })
            val restored = Harness(storage = PulseFileStorage(root)); restored.flush()
            assertEquals("saved", restored.events.single()["type"])
            root.listFiles()!!.first { it.name.startsWith("queue-") }.writeText("corrupt")
            val healthy = Harness(storage = PulseFileStorage(root)); healthy.send("healthy")
            assertEquals("healthy", healthy.events.single()["type"])
        } finally { root.deleteRecursively() }
    }

    @Test fun saveFailureSuspendsSendingUntilDurable() {
        val storage = FakeStorage(); val h = Harness(storage = storage)
        storage.failWrites = true; h.send("saved.later"); assertTrue(h.events.isEmpty())
        storage.failWrites = false; h.advance(1000); assertEquals("saved.later", h.events.single()["type"])
    }

    @Test fun advicePauseOverridesFlushCountAndBackgroundAndPersists() {
        val storage = FakeStorage(); val h = Harness(PulseOptions(maxBatchSize = 2), storage)
        h.transport.replies.add(PulseResponse(202, "{\"recommendedSendIntervalSeconds\":120,\"pause\":true}".toByteArray()))
        h.send("first"); h.client.track("two"); h.client.track("three"); h.flush(); h.client.background(); h.settle()
        h.advance(119999); assertEquals(1, h.transport.requests.size)
        val restored = Harness(PulseOptions(maxBatchSize = 2), storage, FakeClock(h.clock.time))
        restored.flush(); assertTrue(restored.events.isEmpty()); restored.advance(1)
        assertEquals(2, restored.events.size)
        h.advance(1); assertEquals(2, h.transport.requests.size)
        h.client.track("next"); h.settle(); h.advance(119999); assertEquals(2, h.transport.requests.size)
        h.advance(1); assertEquals(3, h.transport.requests.size)
    }

    @Test fun queueAgeAndOverflowAndKeyIsolationWorkThroughClient() {
        val storage = FakeStorage(); val h = Harness(PulseOptions(maxQueueSize = 3), storage)
        h.send("warmup") // uses up the immediate first batch so the next five stay queued
        repeat(5) { h.client.track("bounded", it.toDouble()); h.settle() }
        val other = Harness(storage = storage, key = "other-key"); other.flush(); assertTrue(other.events.isEmpty())
        h.flush(); assertEquals(listOf(2.0, 3.0, 4.0), h.events.filter { it["type"] == "bounded" }.map { (it["value"] as JsonNumber).text.toDouble() })
        h.client.track("old"); h.settle()
        val expired = Harness(storage = storage, clock = FakeClock(h.clock.time + 8 * 86400000L)); expired.flush()
        assertTrue(expired.events.isEmpty())
    }

    @Test fun capturedDuringSaveIsPersistedBeforeSendAndCompletionCapturesOnlyPriorQueue() {
        val storage = FakeStorage(); val h = Harness(storage = storage)
        var inserted = false
        storage.afterReplace = { if (it.startsWith("queue-") && !inserted) { inserted = true; h.client.track("during.write") } }
        h.transport.handler = { request, done ->
            val snapshot = Snapshot.decode(storage.files[storage.queueName]!!)!!
            assertTrue(snapshot.pending.any { it.wire.contentEquals(request.body) })
            done(PulseResponse(202))
        }
        var completed = 0
        h.client.track("first"); h.client.flush { completed++ }; h.settle()
        assertEquals(1, completed); assertEquals(listOf("during.write", "first"), h.events.map { it["type"] as String }.sorted())
    }
}
