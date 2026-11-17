package dev.mightys.pulse

import java.net.URL
import java.net.URLEncoder

/** Permission to take and upload one picture of a view: which (screen, bucket, orientation) it is for. */
public class HeatmapShotTicket internal constructor(
    internal val screen: String,
    internal val bucket: String,
    internal val orientation: String,
)

/**
 * Debug screenshot of a screen: one picture per (screen, bucket, orientation, appVersion) per install, only from debug
 * builds. The platform takes and encodes the picture; this class decides whether one is wanted and uploads the raw bytes
 * through the transport. Used from the SDK worker only ([enqueue] brings a transport answer back onto it); never throws.
 */
internal class HeatmapShots(
    private val storage: () -> PulseStorage?,
    private val transport: PulseTransport,
    private val clock: PulseClock,
    private val target: () -> Target?,
    private val appVersion: () -> String,
    private val debugBuild: () -> Boolean,
    private val enqueue: (() -> Unit) -> Unit,
    private val warn: (String) -> Unit,
) {
    /** Where uploads go: the signals endpoint of the client and its key. */
    class Target(val signals: URL, val appKey: String)

    private val markers = ArrayList<String>()
    private val attempts = ArrayList<Long>()
    private val inFlight = HashSet<String>()
    private var retryUntil = 0L
    private var loaded = false

    private fun keyOf(screen: String, bucket: String, orientation: String): String =
        Hash.hex("$screen|$bucket|$orientation|${appVersion()}")

    /** Opt-out: forgets the markers and deletes the file. */
    fun forget() {
        markers.clear(); attempts.clear(); inFlight.clear(); loaded = true; retryUntil = 0L
        try { storage()?.remove(FILE) } catch (_: Exception) { /* privacy removal is retried by the client */ }
    }

    /** Whether a picture of this view is wanted now. Reads the marker file on first use. */
    fun ticket(screen: String?, widthDp: Double, heightDp: Double): HeatmapShotTicket? = try {
        if (screen.isNullOrEmpty() || !debugBuild() || !(widthDp > 0) || !(heightDp > 0)) null else {
            val bucket = HeatmapCapture.bucketOf(widthDp); val orientation = HeatmapCapture.orientationOf(widthDp, heightDp)
            if (wanted(keyOf(screen, bucket, orientation))) HeatmapShotTicket(screen, bucket, orientation) else null
        }
    } catch (_: Exception) { null }

    private fun wanted(key: String): Boolean {
        load()
        val now = clock.nowMillis()
        attempts.removeAll { now - it >= HOUR }
        return key !in markers && key !in inFlight && now >= retryUntil && attempts.size < MAX_PER_HOUR
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val bytes = storage()?.read(FILE) ?: return
            for (line in bytes.toString(Charsets.UTF_8).split('\n')) if (line.length == 64 && line !in markers) markers.add(line)
        } catch (_: Exception) { /* no markers: at worst one more picture */ }
    }

    private fun mark(key: String) {
        load()
        if (key !in markers) markers.add(key)
        while (markers.size > MAX_MARKERS) markers.removeAt(0)
        try { storage()?.replace(FILE, markers.joinToString("\n").toByteArray(Charsets.UTF_8)) } catch (_: Exception) { warn("Heatmap marker not saved.") }
    }

    /** Uploads one picture; [done] gets true only when the server stored it (201). */
    fun upload(ticket: HeatmapShotTicket?, bytes: ByteArray?, contentType: String, done: (Boolean) -> Unit = {}) {
        var key: String? = null
        try {
            val where = target()
            if (ticket == null || where == null || bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES || contentType !in RASTER || !debugBuild()) { done(false); return }
            val id = keyOf(ticket.screen, ticket.bucket, ticket.orientation)
            if (!wanted(id)) { done(false); return }
            key = id; inFlight.add(id); attempts.add(clock.nowMillis())
            val url = URL(where.signals.toString().removeSuffix("signals") + "heatmap/screenshot?screen=" + encode(ticket.screen) +
                "&bucket=" + ticket.bucket + "&orientation=" + ticket.orientation + "&appVersion=" + encode(appVersion()) + "&debug=true")
            transport.send(PulseRequest(url, where.appKey, bytes, false, contentType = contentType)) { response ->
                enqueue { answered(id, response, done) }
            }
        } catch (_: Exception) { key?.let(inFlight::remove); done(false) }
    }

    private fun answered(key: String, response: PulseResponse, done: (Boolean) -> Unit) {
        inFlight.remove(key)
        val status = response.status
        when {
            status == 201 -> { mark(key); done(true); return }
            status == 403 || status == 415 || status == 413 || status == 422 -> mark(key)
            status == 429 || status == 503 || status == 507 -> {
                val wait = RetryAfter.delay(response.retryAfter, clock.nowMillis()) ?: DEFAULT_RETRY_MILLIS
                retryUntil = clock.nowMillis() + wait
            }
        }
        done(false)
    }

    /** The upload never came back (a transport that does not answer): the view may be tried again on a later visit. */
    fun abandon(ticket: HeatmapShotTicket) { inFlight.remove(keyOf(ticket.screen, ticket.bucket, ticket.orientation)) }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        const val FILE = "heatmap-shots"
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_PER_HOUR = 20
        const val MAX_MARKERS = 1000
        private const val HOUR = 3_600_000L
        private const val DEFAULT_RETRY_MILLIS = 60_000L
        private val RASTER = setOf("image/png", "image/jpeg", "image/webp")
    }
}
