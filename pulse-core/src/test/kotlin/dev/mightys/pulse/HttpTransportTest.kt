package dev.mightys.pulse

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HttpTransportTest {
    private fun withServer(block: (HttpServer, PulseHttpTransport, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val transport = PulseHttpTransport()
        server.start()
        try { block(server, transport, "http://127.0.0.1:${server.address.port}") }
        finally { transport.close(); server.stop(0) }
    }
    private fun request(transport: PulseTransport, url: String, body: ByteArray = "[]".toByteArray(), gzip: Boolean = false): PulseResponse {
        val done = CountDownLatch(1); var result: PulseResponse? = null
        transport.send(PulseRequest(URL(url), "synthetic-key", body, gzip)) { result = it; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS)); return result!!
    }

    @Test fun actualHttpSendsOnlyFixedMetadataAndGzipBody() = withServer { server, transport, base ->
        val json = "[{\"type\":\"local\"}]".toByteArray(); val body = Gzip.encode(json)
        val observed = mutableMapOf<String, String>()
        var received = byteArrayOf()
        server.createContext("/api/v1/signals") { exchange ->
            listOf("User-Agent", "X-App-Key", "Content-Type", "Content-Encoding", "Cookie").forEach {
                observed[it] = exchange.requestHeaders.getFirst(it) ?: ""
            }
            received = exchange.requestBody.readBytes()
            val reply = "{\"recommendedSendIntervalSeconds\":30,\"pause\":false}".toByteArray()
            exchange.sendResponseHeaders(202, reply.size.toLong()); exchange.responseBody.use { it.write(reply) }
        }
        val response = request(transport, "$base/api/v1/signals", body, true)
        assertEquals(202, response.status); assertEquals(30.0, Advice.read(response.body).interval!!, 0.0)
        assertEquals("pulse-android/" + PulseInfo.VERSION, observed["User-Agent"])
        assertEquals("synthetic-key", observed["X-App-Key"]); assertEquals("application/json", observed["Content-Type"])
        assertEquals("gzip", observed["Content-Encoding"]); assertEquals("", observed["Cookie"])
        assertArrayEquals(body, received); assertArrayEquals(json, Gzip.decode(received))
    }

    @Test fun appWithGlobalCookieHandlerStillDeliversAndSendsNoForeignCookies() = withServer { server, transport, base ->
        val previous = java.net.CookieHandler.getDefault()
        val manager = java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)
        manager.cookieStore.add(java.net.URI("http://other.example"), java.net.HttpCookie("host-session", "private-cookie").apply { path = "/"; version = 0 })
        var observed: String? = "unset"
        server.createContext("/cookies") {
            observed = it.requestHeaders.getFirst("Cookie")
            it.requestBody.close(); it.sendResponseHeaders(202, -1); it.close()
        }
        try {
            java.net.CookieHandler.setDefault(manager)
            assertEquals(202, request(transport, "$base/cookies").status)
            assertNull(observed)
        } finally { java.net.CookieHandler.setDefault(previous) }
    }

    @Test fun redirectsNeverForwardApplicationKey() = withServer { server, transport, base ->
        val redirected = AtomicInteger()
        server.createContext("/redirect") { it.requestBody.close(); it.responseHeaders.add("Location", "$base/target"); it.sendResponseHeaders(302, -1); it.close() }
        server.createContext("/target") { redirected.incrementAndGet(); it.sendResponseHeaders(202, -1); it.close() }
        assertEquals(302, request(transport, "$base/redirect").status); assertEquals(0, redirected.get())
    }

    @Test fun errorAdviceAndRetryAfterAreReadAndOversizedAckStillSucceeds() = withServer { server, transport, base ->
        server.createContext("/busy") {
            it.requestBody.close(); it.responseHeaders.add("Retry-After", "60")
            val reply = "{\"pause\":false}".toByteArray(); it.sendResponseHeaders(429, reply.size.toLong())
            it.responseBody.use { output -> output.write(reply) }
        }
        server.createContext("/large") {
            it.requestBody.close(); val reply = ByteArray(70000) { 32 }; it.sendResponseHeaders(202, reply.size.toLong())
            it.responseBody.use { output -> output.write(reply) }
        }
        val busy = request(transport, "$base/busy"); assertEquals(429, busy.status); assertEquals("60", busy.retryAfter)
        assertEquals(false, Advice.read(busy.body).pause)
        val large = request(transport, "$base/large"); assertEquals(202, large.status); assertTrue(large.body.isEmpty())
    }

    @Test fun realExecutorClientSavesAndFlushesWithoutThrowingOrBlockingStateOnNetwork() = withServer { server, transport, base ->
        val root = java.nio.file.Files.createTempDirectory("pulse-live-client").toFile()
        val scheduler = PulseSerialScheduler()
        val client = PulseClient(scheduler = scheduler, transport = transport)
        server.createContext("/api/v1/signals") { it.requestBody.close(); it.sendResponseHeaders(202, -1); it.close() }
        try {
            client.initialize("key", PulseOptions(host = base), PulseFileStorage(root), PulseEnvironment { emptyMap() })
            client.track("real.transport")
            assertTrue(client.flush(5000)); assertTrue(client.backgroundSave()); assertTrue(client.stop())
            client.track(null); client.flush { throw IllegalStateException("host callback") }
        } finally { client.stop(); scheduler.close(); root.deleteRecursively() }
    }
}
