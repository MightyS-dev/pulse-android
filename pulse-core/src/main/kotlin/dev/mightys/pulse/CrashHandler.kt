package dev.mightys.pulse

import java.util.UUID

/** What the crashing process leaves for the next launch: when, which session, and the finished error object. */
internal class CrashRecord(val at: Long, val session: String, val error: Map<String, Any?>, val early: Boolean = false) {
    /** [early]: written before the stored privacy choice was read; the next launch checks that choice before sending. */
    fun encode(): ByteArray = JsonWriter.value(linkedMapOf<String, Any?>("at" to at, "session" to session, "error" to error)
        .also { if (early) it["early"] = true }).toByteArray(Charsets.UTF_8)

    companion object {
        const val FILE = "crash-report"

        /** Null for anything that is not a well-formed record. Values are filtered and limited again. */
        fun decode(bytes: ByteArray, now: Long): CrashRecord? = try {
            val root = JsonReader.utf8(bytes)?.let(JsonReader::parse) as? Map<*, *>
            val at = (root?.get("at") as? JsonNumber)?.text?.toLongOrNull()
            val error = root?.get("error") as? Map<*, *>
            val type = error?.get("type") as? String
            if (root == null || at == null || error == null || type == null) null else {
                val session = (root["session"] as? String)?.takeIf { it.isNotBlank() && it.length <= 64 } ?: UUID.randomUUID().toString()
                val frames = (error["frames"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                CrashRecord(minOf(at, now), session, ErrorPayload.normalize("crash", type, error["message"] as? String, frames, error["symbolicated"] != false))
            }
        } catch (_: Exception) { null }
    }
}

/**
 * Default uncaught-exception handler: writes a small crash file (bounded wait, no network), then always hands the
 * error to the handler that was there before. It never throws on its own account and never keeps a dying process alive.
 */
internal class CrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    /** The storage to write to (and whether privacy was still unknown), or null while the SDK is not collecting. */
    private val target: () -> Pair<PulseStorage, Boolean>?,
    private val clock: PulseClock,
    private val session: () -> String?,
    private val timeoutMillis: Long,
) : Thread.UncaughtExceptionHandler {
    private val inside = ThreadLocal<Boolean>()

    override fun uncaughtException(thread: Thread, error: Throwable) {
        if (inside.get() == true) return // our own work, or a loop through another handler, came back here
        inside.set(true)
        try {
            try { record(error) } catch (_: Throwable) { /* a crash report must never cause a second crash */ }
            if (previous != null) previous.uncaughtException(thread, error) else defaultReport(thread, error)
        } finally { inside.set(false) }
    }

    private fun record(error: Throwable) {
        val (store, early) = target() ?: return
        val bytes = CrashRecord(clock.nowMillis(), session().orEmpty(), ErrorPayload.from("crash", error), early).encode()
        val writer = Thread({ try { store.replace(CrashRecord.FILE, bytes) } catch (_: Throwable) { /* nothing to do */ } }, "pulse-crash")
        writer.isDaemon = true
        writer.start()
        writer.join(timeoutMillis)
    }

    /** With no previous handler the JVM would print the error; the thread group would call back into this handler. */
    private fun defaultReport(thread: Thread, error: Throwable) {
        try { System.err.print("Exception in thread \"" + thread.name + "\" "); error.printStackTrace() } catch (_: Throwable) { /* ignore */ }
    }
}
