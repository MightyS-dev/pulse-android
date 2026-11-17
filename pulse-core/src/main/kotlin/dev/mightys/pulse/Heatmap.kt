package dev.mightys.pulse

import java.math.BigDecimal

/**
 * Taps folded into one buffer per (screen, bucket, orientation), sent as `pulse.heatmap` objects. The platform reports a
 * tap with its position in logical px (dp) and three privacy flags; this class applies every rule. It holds no
 * locks and must be used from the SDK worker only. [enabled] says whether capture is allowed right now, [sessionAt] gives
 * the session a signal at that time would join (null when it would open a new one), [emit] queues one finished object.
 */
internal class HeatmapCapture(
    private val enabled: () -> Boolean,
    private val sessionAt: (Long) -> String?,
    private val emit: (Long, Map<String, Any?>) -> Unit,
) {
    private val points = ArrayList<Map<String, Any?>>()
    /** The current screen, or null when none is known. */
    var screen: String? = null
        private set
    private var bufferScreen = ""
    private var bufferBucket = ""
    private var bufferOrientation = ""
    private var sessionId: String? = null
    private var sessionCount = 0
    private var clusterCount = 0
    private var clusterTime = 0L
    private var clusterX = 0.0
    private var clusterY = 0.0

    /** Sets the current screen (null when none is known). The buffer of the old screen is sent first. */
    fun setScreen(name: String?, at: Long) {
        if (name == screen) return
        send(take(), at); screen = name; clusterCount = 0
    }

    /** One tap. [x] and [y] are logical px from the top left; [width] and [height] are the window size in the same unit. */
    fun onTap(at: Long, x: Double, y: Double, width: Double, height: Double, keyboardVisible: Boolean, inputTarget: Boolean, ignored: Boolean) {
        if (keyboardVisible || inputTarget || ignored) return
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) return
        if (!safeEnabled()) { drop(); return }
        val current = screen ?: return
        val session = try { sessionAt(at) ?: PENDING } catch (_: Exception) { return }
        val fraction = round(x / width, 1000.0).coerceIn(0.0, 1.0)
        val top = round(y.coerceIn(0.0, MAX_Y), 10.0)
        val bucket = bucketOf(width); val orientation = orientationOf(width, height)
        if (sessionId != session) { sessionId = session; sessionCount = 0 }
        if (sessionCount >= MAX_PER_SESSION) return
        if (points.isNotEmpty() && (bucket != bufferBucket || orientation != bufferOrientation)) send(take(), at)
        if (points.isEmpty()) { bufferScreen = current; bufferBucket = bucket; bufferOrientation = orientation }
        val px = fraction * width
        var kind = "tap"
        if (clusterCount > 0 && at - clusterTime <= RAGE_MILLIS && Math.hypot(px - clusterX, top - clusterY) <= RAGE_RADIUS) {
            clusterCount++
            if (clusterCount >= 3) kind = "rage"
        } else { clusterCount = 1; clusterTime = at; clusterX = px; clusterY = top }
        points.add(linkedMapOf("x" to number(fraction), "y" to number(top), "kind" to kind))
        sessionCount++
        if (points.size >= MAX_PER_SIGNAL) send(take(), at)
    }

    /** Sends the open buffer, if any. */
    fun flush(at: Long) { send(take(), at) }

    /** Forgets the open buffer without sending it (switched off or opt-out). */
    fun drop() { points.clear(); clusterCount = 0 }

    /** After a heatmap signal started or joined a session, the point count follows that session. */
    fun adoptSession(id: String?) { if (sessionId == PENDING && id != null) sessionId = id }

    private fun safeEnabled(): Boolean = try { enabled() } catch (_: Exception) { false }

    private fun take(): Map<String, Any?>? {
        if (points.isEmpty()) return null
        val result = linkedMapOf("screen" to bufferScreen, "bucket" to bufferBucket, "orientation" to bufferOrientation, "points" to points.toList())
        points.clear()
        return result
    }

    private fun send(payload: Map<String, Any?>?, at: Long) {
        if (payload == null) return
        try { if (safeEnabled()) emit(at, payload) } catch (_: Exception) { /* A heatmap never reaches the app. */ }
    }

    companion object {
        const val MAX_PER_SIGNAL = 200
        const val MAX_PER_SESSION = 1000
        const val RAGE_MILLIS = 1000L
        const val RAGE_RADIUS = 30.0
        const val MAX_Y = 100000.0
        private const val PENDING = "pending"

        fun bucketOf(width: Double): String = if (width < 600) "phone" else if (width < 1024) "tablet" else "desktop"
        fun orientationOf(width: Double, height: Double): String = if (height >= width) "portrait" else "landscape"
        /** Round half up to 1/[scale] (values here are never negative), like the C# and JS cores. */
        private fun round(value: Double, scale: Double): Double = Math.floor(value * scale + 0.5) / scale
        private fun number(value: Double): JsonNumber = JsonNumber(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString())
    }
}
