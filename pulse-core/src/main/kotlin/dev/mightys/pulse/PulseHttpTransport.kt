package dev.mightys.pulse

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object RetryAfter {
    fun delay(text: String?, now: Long): Long? {
        val trimmed = text?.trim() ?: return null
        trimmed.toDoubleOrNull()?.let { seconds ->
            return if (seconds.isFinite() && seconds >= 0) (seconds.coerceAtMost(3600.0) * 1000).toLong() else null
        }
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT"); format.isLenient = false
        val position = ParsePosition(0)
        val date = format.parse(trimmed, position) ?: return null
        if (position.index != trimmed.length) return null
        return (date.time - now).coerceIn(0, 3600000)
    }
}

/** HttpURLConnection transport with asynchronous network workers (a settings read never waits behind a delivery), fixed headers and bounded advice. */
public class PulseHttpTransport internal constructor(private val connectTimeoutMillis: Int, private val readTimeoutBaseMillis: Int) : PulseTransport {
    /** Default timeouts: 10 seconds to connect, 10 seconds plus one per 16 KiB to read. */
    public constructor() : this(10000, 10000)

    private val network = Executors.newFixedThreadPool(2) { action -> Thread(action, "pulse-http").apply { isDaemon = true } }

    override fun send(request: PulseRequest, completion: (PulseResponse) -> Unit): PulseCancellation {
        val cancelled = AtomicBoolean(false)
        val connection = AtomicReference<HttpURLConnection?>()
        val future = network.submit {
            var response = PulseResponse(0)
            try {
                if (!cancelled.get()) {
                    val http = request.url.openConnection() as HttpURLConnection
                    connection.set(http)
                    if (!cancelled.get()) response = perform(http, request)
                }
            } catch (_: Exception) { response = PulseResponse(0) }
            finally { connection.getAndSet(null)?.disconnect() }
            if (!cancelled.get()) try { completion(response) } catch (_: Exception) { /* Consumer failures stay off the app thread. */ }
        }
        return PulseCancellation {
            cancelled.set(true); connection.getAndSet(null)?.disconnect(); future.cancel(true)
        }
    }

    private fun perform(http: HttpURLConnection, request: PulseRequest): PulseResponse {
        // The SDK never sets or reads cookies and the ingest server issues none. An app-wide CookieHandler is
        // left untouched: refusing to send while one is installed would silently disable analytics in those apps.
        http.instanceFollowRedirects = false
        http.useCaches = false; http.doOutput = !request.isGet; http.requestMethod = if (request.isGet) "GET" else "POST"
        http.connectTimeout = connectTimeoutMillis
        http.readTimeout = minOf(90000, readTimeoutBaseMillis + request.body.size / 16384 * 1000)
        http.setRequestProperty("X-App-Key", request.appKey)
        http.setRequestProperty("User-Agent", PulseInfo.SDK_NAME + "/" + PulseInfo.VERSION)
        http.setRequestProperty("Accept-Encoding", "identity")
        if (request.isGet) {
            request.ifNoneMatch?.let { http.setRequestProperty("If-None-Match", it) }
            http.setRequestProperty("Accept", "application/json")
        } else {
            http.setFixedLengthStreamingMode(request.body.size)
            http.setRequestProperty("Content-Type", "application/json")
            if (request.gzip) http.setRequestProperty("Content-Encoding", "gzip")
            request.batchId?.let { http.setRequestProperty("X-Pulse-Batch", it) }
            http.outputStream.use { it.write(request.body) }
        }
        val status = http.responseCode
        val stream = if (status >= 400) http.errorStream else http.inputStream
        val output = ByteArrayOutputStream()
        if (stream != null) stream.use { input ->
            val buffer = ByteArray(8192)
            while (output.size() <= 65536) {
                val count = input.read(buffer, 0, minOf(buffer.size, 65537 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
        return PulseResponse(status, if (output.size() > 65536) byteArrayOf() else output.toByteArray(), http.getHeaderField("Retry-After"))
    }

    /** Stop this transport's own executor after cancelling the owning client. */
    public fun close() { network.shutdownNow() }
}
