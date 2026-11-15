package dev.mightys.pulse

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WorkerTest {
    @Test fun realHungHttpDoesNotBlockLocalBackgroundSaveOrOptOut() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        server.createContext("/api/v1/signals") {
            it.requestBody.readBytes(); started.countDown()
            release.await(10, TimeUnit.SECONDS)
            try { it.sendResponseHeaders(202, -1) } finally { it.close() }
        }
        server.start()
        val root = Files.createTempDirectory("pulse-hung-http").toFile()
        val scheduler = PulseSerialScheduler(); val transport = PulseHttpTransport()
        val client = PulseClient(scheduler = scheduler, transport = transport)
        try {
            client.initialize("key", PulseOptions(host = "http://127.0.0.1:${server.address.port}"), PulseFileStorage(root), PulseEnvironment { emptyMap() })
            client.track("in.flight"); client.flush()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            client.track("captured.during.http")
            val before = System.nanoTime()
            assertTrue(client.backgroundSave(2000))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2000)
            val file = root.listFiles()!!.first { it.name.startsWith("queue-") }
            assertEquals(2, Snapshot.decode(file.readBytes())!!.entries.size)
            client.optOut = true
            assertTrue(client.backgroundSave(2000)); assertTrue(client.optOut)
            assertArrayEquals(byteArrayOf(1), java.io.File(root, "opt-out").readBytes())
        } finally {
            release.countDown(); client.stop(); transport.close(); scheduler.close(); server.stop(0); root.deleteRecursively()
        }
    }

    @Test fun realSerialExecutorDeliversTenThousandConcurrentCapturesExactlyOnceInTest() {
        val scheduler = PulseSerialScheduler()
        val transport = FakeTransport()
        val storage = FakeStorage()
        val client = PulseClient(scheduler = scheduler, transport = transport)
        val producers = java.util.concurrent.Executors.newFixedThreadPool(10)
        try {
            client.initialize("key", PulseOptions(maxQueueSize = 10000, maxBatchSize = 1000, gzip = false), storage, PulseEnvironment { emptyMap() })
            val ready = CountDownLatch(1)
            scheduler.execute(Runnable { ready.countDown() })
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            repeat(10) { thread -> producers.submit { repeat(1000) { client.track("parallel", (thread * 1000 + it).toDouble()) } } }
            producers.shutdown(); assertTrue(producers.awaitTermination(10, TimeUnit.SECONDS))
            assertTrue(client.flush(20000))
            val values = transport.requests.flatMap(Harness::events).map { (it["value"] as JsonNumber).text.toDouble().toInt() }
            assertEquals((0..9999).toList(), values.sorted()); assertEquals(10000, values.toSet().size)
        } finally { producers.shutdownNow(); client.stop(); scheduler.close() }
    }

    @Test fun timingOutOrCancellingFlushDoesNotDropTheQueue() {
        val scheduler = PulseSerialScheduler()
        val transport = PulseTransport { _, _ -> PulseCancellation {} }
        val storage = FakeStorage(); val client = PulseClient(scheduler = scheduler, transport = transport)
        try {
            client.initialize("key", null, storage, PulseEnvironment { emptyMap() })
            client.track("offline")
            assertFalse(client.flush(25))
            val handle = client.flush { fail("Cancelled waiter must not complete") }
            handle.cancel()
            assertTrue(client.backgroundSave())
            val snapshot = Snapshot.decode(storage.files[storage.queueName]!!)!!
            assertEquals(1, snapshot.entries.size)
        } finally { client.stop(); scheduler.close() }
    }
}
