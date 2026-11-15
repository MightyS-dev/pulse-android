package dev.mightys.pulse

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HangingConnectionTest {
    private fun hangingServer(release: CountDownLatch, started: CountDownLatch = CountDownLatch(1)): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/signals") {
            it.requestBody.readBytes(); started.countDown(); release.await(20, TimeUnit.SECONDS)
            try { it.sendResponseHeaders(202, -1) } finally { it.close() }
        }
        server.start(); return server
    }

    @Test fun hungRequestIsBoundedByTheReadTimeoutAndReportsAFailure() {
        val release = CountDownLatch(1); val server = hangingServer(release)
        val transport = PulseHttpTransport(500, 500)
        try {
            val done = CountDownLatch(1); var result: PulseResponse? = null
            val begin = System.nanoTime()
            transport.send(PulseRequest(URL("http://127.0.0.1:${server.address.port}/api/v1/signals"), "k", "[]".toByteArray(), false)) { result = it; done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(0, result!!.status)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 4000)
        } finally { release.countDown(); transport.close(); server.stop(0) }
    }

    @Test fun batchStaysOnDiskWhileRequestHangsAndIsDeliveredOnceAfterRecovery() {
        val storage = FakeStorage(); val h = Harness(storage = storage)
        var hang = true; var calls = 0
        h.transport.handler = { _, completion -> calls++; if (!hang) completion(PulseResponse(202)) }
        h.client.track("one"); h.client.track("two"); h.client.flush(); h.settle()
        assertEquals(1, calls)
        val saved = Snapshot.decode(storage.files[storage.queueName]!!)!!
        assertEquals(2, saved.entries.size); assertEquals(1, saved.pending.size)
        h.advance(149_999); assertEquals(1, calls)
        h.advance(1); h.settle()                      // the deadline passes: failure, backoff scheduled
        assertEquals(1, calls)
        hang = false
        h.advance(60_000); h.settle()
        assertEquals(2, calls)
        assertEquals(listOf("one", "two"), Harness.events(h.transport.requests.last()).map { it["type"] })
        h.advance(600_000); h.client.flush(); h.settle()
        assertEquals(2, calls)                        // nothing sent twice
        assertTrue(Snapshot.decode(storage.files[storage.queueName]!!)!!.entries.isEmpty())
    }

    @Test fun repeatedHangsBackOffAndThenDeliverWithoutLoss() {
        val h = Harness(); var mode = 0; val seen = mutableListOf<Long>()
        h.transport.handler = { _, completion -> seen.add(h.clock.time); if (mode == 1) completion(PulseResponse(202)) }
        h.client.track("a"); h.client.flush(); h.settle()
        repeat(3) { h.advance(150_000); h.advance(310_000) }
        assertTrue("retried after each hang", seen.size >= 3)
        mode = 1; h.advance(310_000)
        assertEquals(listOf("a"), h.events.map { it["type"] }.distinct())
    }

    @Test fun trackAndFlushNeverBlockWhileARequestHangsAndTimedFlushReturnsFalseOnTime() {
        val release = CountDownLatch(1); val started = CountDownLatch(1); val server = hangingServer(release, started)
        val root = Files.createTempDirectory("pulse-hang").toFile()
        val scheduler = PulseSerialScheduler(); val transport = PulseHttpTransport(2000, 2000)
        val client = PulseClient(scheduler = scheduler, transport = transport).also { it.automaticSignals = false }
        try {
            client.initialize("key", PulseOptions(host = "http://127.0.0.1:${server.address.port}"), PulseFileStorage(root), PulseEnvironment { emptyMap() })
            client.track("first"); client.flush()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val begin = System.nanoTime()
            repeat(200) { client.track("while.hung", it.toDouble()) }
            client.flush(); client.flush(Runnable { })
            assertTrue("calls return at once", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 500)
            val timed = System.nanoTime()
            assertFalse(client.flush(300))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - timed) < 1500)
        } finally { release.countDown(); client.stop(); transport.close(); scheduler.close(); server.stop(0); root.deleteRecursively() }
    }

    @Test fun restartWhileARequestHangsKeepsTheQueuedSignalsForTheNextProcess() {
        val release = CountDownLatch(1); val started = CountDownLatch(1); val server = hangingServer(release, started)
        val root = Files.createTempDirectory("pulse-restart").toFile()
        val host = "http://127.0.0.1:${server.address.port}"
        val scheduler = PulseSerialScheduler(); val transport = PulseHttpTransport(2000, 2000)
        val client = PulseClient(scheduler = scheduler, transport = transport).also { it.automaticSignals = false }
        try {
            client.initialize("key", PulseOptions(host = host), PulseFileStorage(root), PulseEnvironment { emptyMap() })
            client.track("kept.1"); client.track("kept.2"); client.flush()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            client.track("kept.3")
            assertTrue(client.backgroundSave(2000))
            client.stop(); transport.close(); scheduler.close()   // the process ends while the request hangs
            val next = Harness(storage = PulseFileStorage(root), options = PulseOptions(host = host), key = "key", clock = FakeClock(System.currentTimeMillis()))
            next.client.flush(); next.settle()
            assertEquals(listOf("kept.1", "kept.2", "kept.3"), next.events.map { it["type"] })
        } finally { release.countDown(); server.stop(0); root.deleteRecursively() }
    }
}
