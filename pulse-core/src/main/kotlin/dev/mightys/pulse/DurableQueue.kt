package dev.mightys.pulse

import java.util.UUID

internal class DurableQueue(private val options: PulseOptions) {
    val entries = mutableListOf<Entry>()
    val pending = mutableListOf<Batch>()

    fun prune(now: Long): Boolean {
        val oldest = now - (options.maxQueueAgeDays * 86400000).toLong()
        entries.removeAll { it.timestamp < oldest || it.timestamp > now + 3600000 }
        if (entries.size > options.maxQueueSize) entries.subList(0, entries.size - options.maxQueueSize).clear()
        val live = entries.mapTo(hashSetOf()) { it.id }
        return pending.removeAll { !it.ids.all(live::contains) }
    }

    fun makeBatch(source: List<Entry>, compress: Boolean): Batch {
        var bytes = 2
        val chosen = source.take(options.maxBatchSize).takeWhile { entry ->
            val extra = entry.json.size + if (bytes == 2) 0 else 1
            (bytes + extra <= SignalJson.MAX_BODY).also { if (it) bytes += extra }
        }
        val json = SignalJson.batch(chosen)
        val wire = if (compress && json.size > 1024) Gzip.encode(json).takeIf { it.size <= SignalJson.MAX_BODY } else null
        return Batch(chosen.map { it.id }, json, wire ?: json, wire != null)
    }

    fun clear() { entries.clear(); pending.clear() }
}

internal class CaptureState {
    private var session = ""
    private var lastActivity = Long.MIN_VALUE
    private var userHash: String? = null
    /** Start of the current session, or zero before the first capture. */
    var sessionStart = 0L
        private set
    /** Latest known session end, including a background transition that could not send a signal. */
    var sessionEnd = 0L
        private set
    /** Time of the latest capture, or zero before the first capture. */
    val lastSeen: Long get() = if (lastActivity == Long.MIN_VALUE) 0L else lastActivity

    /** The session an event at [at] would join, or null when it would open a new one. */
    fun sessionAt(at: Long): String? = if (lastActivity == Long.MIN_VALUE || at - lastActivity >= 1800000) null else session

    /** Record the session end without extending the inactivity window or opening a session. */
    fun backgrounded(at: Long) { if (sessionStart > 0) sessionEnd = maxOf(sessionEnd, at) }

    fun setUser(identifier: String?) { userHash = identifier?.takeIf { it.isNotEmpty() }?.let(Hash::hex) }
    fun capture(at: Long, type: String?, value: Double?, props: Map<String, Any?>, local: Map<String, String> = emptyMap(),
        extra: Map<String, String>? = null): Input {
        val fresh = lastActivity == Long.MIN_VALUE || at - lastActivity >= 1800000
        var previousStart = 0L
        var previousEnd = 0L
        if (fresh) {
            if (lastActivity != Long.MIN_VALUE && sessionStart > 0) { previousStart = sessionStart; previousEnd = sessionEnd }
            session = UUID.randomUUID().toString(); sessionStart = at
        }
        lastActivity = at
        sessionEnd = at
        return Input(at, session, userHash, type, value, props, fresh, sessionStart, previousStart, previousEnd, local, extra)
    }
    fun reset() { userHash = null; session = UUID.randomUUID().toString(); lastActivity = Long.MIN_VALUE; sessionStart = 0L; sessionEnd = 0L }
}
