package dev.mightys.pulse

import java.util.ArrayDeque

class FakeCancellation : PulseCancellation {
    var cancelled = false
    override fun cancel() { cancelled = true }
}
class FakeClock(var time: Long = 1_794_556_800_000) : PulseClock { override fun nowMillis(): Long = time }
class FakeScheduler(private val clock: FakeClock) : PulseScheduler {
    private val work = ArrayDeque<() -> Unit>()
    private val timed = mutableListOf<Triple<Long, FakeCancellation, () -> Unit>>()
    override fun execute(action: Runnable) { synchronized(work) { work.add { action.run() } } }
    override fun schedule(delayMillis: Long, action: Runnable): PulseCancellation {
        val cancellation = FakeCancellation()
        timed.add(Triple(clock.time + delayMillis, cancellation) { action.run() })
        return cancellation
    }
    fun drain() {
        var runs = 0
        while (true) {
            val action = synchronized(work) { work.poll() } ?: break
            check(runs++ < 100000) { "Infinite scheduler loop" }; action()
        }
    }
    fun advance(millis: Long) {
        clock.time += millis
        val due = timed.filter { it.first <= clock.time && !it.second.cancelled }
        timed.removeAll { it.first <= clock.time || it.second.cancelled }
        due.forEach { it.third() }; drain()
    }
}
class FakeStorage : PulseStorage {
    val files = linkedMapOf<String, ByteArray>()
    var failReads = false
    var failWrites = false
    var failPrivacy = false
    var afterReplace: ((String) -> Unit)? = null
    override fun read(name: String): ByteArray? {
        check(!failReads); return files[name]?.clone()
    }
    override fun replace(name: String, bytes: ByteArray) {
        check(!failWrites && !(failPrivacy && name == "opt-out"))
        files[name] = bytes.clone(); afterReplace?.invoke(name)
    }
    override fun remove(name: String) { check(!failWrites); files.remove(name) }
    val queueName: String? get() = files.keys.firstOrNull { it.startsWith("queue-") }
}
class FakeTransport : PulseTransport {
    val requests = mutableListOf<PulseRequest>()
    val replies = ArrayDeque<PulseResponse>()
    val cancellations = mutableListOf<FakeCancellation>()
    var handler: ((PulseRequest, (PulseResponse) -> Unit) -> Unit)? = null
    override fun send(request: PulseRequest, completion: (PulseResponse) -> Unit): PulseCancellation {
        requests.add(request)
        val cancellation = FakeCancellation(); cancellations.add(cancellation)
        handler?.invoke(request, completion) ?: completion(replies.poll() ?: PulseResponse(202))
        return cancellation
    }
}
class Harness(val options: PulseOptions = PulseOptions(), val storage: PulseStorage = FakeStorage(),
    val clock: FakeClock = FakeClock(), initialize: Boolean = true, key: String = "synthetic-key",
    random: PulseRandom = PulseRandom { 0.5 }, logger: PulseLogger = PulseLogger {},
    automatic: Boolean = false, val dynamic: PulseDynamicValues = PulseDynamicValues { emptyMap() },
    val env: Map<String, String> = mapOf("platform" to "mobile", "osName" to "Android")) {
    val scheduler = FakeScheduler(clock)
    val transport = FakeTransport()
    val client = PulseClient(clock, scheduler, transport, random, logger).also { it.automaticSignals = automatic }
    init { if (initialize) initialize(key) }
    fun initialize(key: String = "synthetic-key") {
        client.initialize(key, options, storage, PulseEnvironment { env }, false, dynamic)
        settle()
    }
    fun settle() { scheduler.drain() }
    fun advance(millis: Long) { scheduler.advance(millis) }
    fun flush() { client.flush(); settle() }
    fun send(type: String? = "event", value: Double? = null, props: Map<String, *>? = null) {
        client.track(type, value, props); flush()
    }
    val allEvents: List<Map<*, *>> get() = transport.requests.flatMap(::events)
    /** Events without the automatic pulse.* signals. */
    val events: List<Map<*, *>> get() = allEvents.filter { !(it["type"] as String).startsWith("pulse.") }
    companion object {
        fun json(request: PulseRequest): ByteArray = if (request.gzip) Gzip.decode(request.body)!! else request.body
        fun events(request: PulseRequest): List<Map<*, *>> = (JsonReader.parse(json(request).toString(Charsets.UTF_8)) as List<*>).map { it as Map<*, *> }
    }
}
