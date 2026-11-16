package dev.mightys.pulse

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BatchIdTest {
    private val format = Regex("[0-9a-f]{32}")
    private val big = mapOf("large" to "x".repeat(1000))

    @Test fun idHasThirtyTwoLowercaseHexCharactersAndIsRandom() {
        val ids = List(50) { BatchId.create() }
        assertTrue(ids.all { format.matches(it) }); assertEquals(50, ids.toSet().size)
        val h = Harness(); h.send()
        assertTrue(format.matches(h.transport.requests.single().batchId!!))
    }

    @Test fun retryAfterNetworkErrorServerErrorAndThrottleKeepsIdAndContent() {
        for (status in listOf(0, 500, 503, 429)) {
            val h = Harness(); h.transport.replies.add(PulseResponse(status, retryAfter = "1"))
            h.send("retry", props = big)
            h.advance(1000)
            assertEquals("status $status", 2, h.transport.requests.size)
            val (a, b) = h.transport.requests
            assertEquals(a.batchId, b.batchId); assertArrayEquals(Harness.json(a), Harness.json(b))
        }
    }

    @Test fun retryAfterRestartUsesTheSavedIdAndSameBytes() {
        val storage = FakeStorage(); val h = Harness(storage = storage)
        h.transport.replies.add(PulseResponse(500, retryAfter = "60"))
        h.send("original", props = big)
        val first = h.transport.requests.single()
        val restarted = Harness(storage = storage, clock = FakeClock(h.clock.time)); restarted.flush()
        restarted.advance(60000)
        val again = restarted.transport.requests.first()
        assertEquals(first.batchId, again.batchId); assertArrayEquals(first.body, again.body)
    }

    @Test fun signalAddedBeforeRetryGoesInALaterBatchWithAnotherId() {
        val h = Harness(); h.transport.replies.add(PulseResponse(500, retryAfter = "60"))
        h.send("original"); h.client.track("newer"); h.settle(); h.advance(60000)
        val ids = h.transport.requests.map { it.batchId }
        assertEquals(ids[0], ids[1]); assertEquals(1, Harness.events(h.transport.requests[1]).size)
        h.flush()
        assertNotEquals(ids[0], h.transport.requests.last().batchId)
    }

    @Test fun splitAfterRejectionGivesEachPartANewId() {
        val h = Harness(PulseOptions(maxBatchSize = 4, gzip = false))
        var first = true
        h.transport.handler = { _, complete -> complete(PulseResponse(if (first) 413 else 202)); first = false }
        repeat(4) { h.client.track("event", it.toDouble()) }; h.flush()
        val ids = h.transport.requests.map { it.batchId }
        assertEquals(3, ids.size); assertEquals(3, ids.toSet().size)
    }

    @Test fun gzipFallbackAfterRejectionKeepsTheId() {
        val h = Harness(); h.transport.replies.add(PulseResponse(415))
        h.send("one", props = big)
        assertEquals(h.transport.requests[0].batchId, h.transport.requests[1].batchId)
    }

    @Test fun consecutiveBatchesHaveDifferentIdsAndAcceptedBatchIsDropped() {
        val h = Harness(); h.send("a"); h.send("b")
        val ids = h.transport.requests.map { it.batchId }
        assertEquals(2, ids.toSet().size)
    }

    @Test fun snapshotKeepsBatchIdAndRejectsMalformedOne() {
        val entry = Entry("one", 1_794_556_800_000L,
            SignalJson.encode(Input(1_794_556_800_000L, "s", null, "e", null, emptyMap()), emptyMap(), false)!!)
        val json = SignalJson.batch(listOf(entry))
        val batch = Batch(listOf("one"), json, json, false)
        val restored = Snapshot.decode(Snapshot("", listOf(entry), listOf(batch), 20.0, 0).encode())!!
        assertEquals(batch.batchId, restored.pending.single().batchId)
        val bad = batch.copy(batchId = "ABC")
        assertNull(Snapshot.decode(Snapshot("", listOf(entry), listOf(bad), 20.0, 0).encode()))
    }

    @Test fun httpTransportSendsTheHeaderOnlyWhenAnIdIsGiven() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val transport = PulseHttpTransport(); val seen = mutableListOf<String>()
        server.createContext("/s") { x ->
            seen.add(x.requestHeaders.getFirst("X-Pulse-Batch") ?: "none"); x.requestBody.readBytes()
            x.sendResponseHeaders(202, -1); x.close()
        }
        server.start()
        try {
            val id = BatchId.create()
            for (request in listOf(PulseRequest(URL("http://127.0.0.1:${server.address.port}/s"), "k", "[]".toByteArray(), false, batchId = id),
                PulseRequest(URL("http://127.0.0.1:${server.address.port}/s"), "k", "[]".toByteArray(), false))) {
                val done = CountDownLatch(1)
                transport.send(request) { done.countDown() }; assertTrue(done.await(5, TimeUnit.SECONDS))
            }
            assertEquals(listOf(id, "none"), seen)
        } finally { transport.close(); server.stop(0) }
    }

    @Test fun finalSavePathSendsTheHeaderToo() {
        val h = Harness(); h.client.track("last"); h.client.background(); h.settle()
        assertTrue(format.matches(h.transport.requests.last().batchId!!))
    }
}
