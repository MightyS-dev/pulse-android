package dev.mightys.pulse

import java.net.URL
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Cancel a scheduled action or request where possible. */
public fun interface PulseCancellation { /** Request cancellation; already transmitted bytes cannot be recalled. */ public fun cancel() }
/** Wall clock seam; timestamps are milliseconds since the Unix epoch. */
public fun interface PulseClock { /** Current capture time. */ public fun nowMillis(): Long }
/** Serial state executor and delayed-action seam. Implementations must enqueue, never execute inline. */
public interface PulseScheduler {
    /** Enqueue state work in submission order. */ public fun execute(action: Runnable)
    /** Enqueue an action after a delay. */ public fun schedule(delayMillis: Long, action: Runnable): PulseCancellation
}
/** Environmental metadata seam. Called only by the state worker. */
public fun interface PulseEnvironment { /** Return system fields without stable device identifiers. */ public fun system(): Map<String, String> }
/** Values that change while the app runs. Called by the state worker for every signal, so it must return cached state quickly. */
public fun interface PulseDynamicValues { /** Return the current values; unknown values are left out. */ public fun values(): Map<String, String> }
/** Randomness seam for bounded retry jitter. */
public fun interface PulseRandom { /** A sample in the inclusive unit interval; invalid samples are replaced safely. */ public fun sample(): Double }
/** Fixed diagnostic seam; SDK messages never contain event contents or raw users. */
public fun interface PulseLogger { /** Log a fixed diagnostic only when debug is enabled. */ public fun warn(message: String) }
/** One immutable HTTP request. Body bytes belong to the SDK and must not be modified. */
public class PulseRequest(
    /** Validated ingest endpoint. */ public val url: URL,
    /** Application ingest key. */ public val appKey: String,
    /** Exact durable wire body. */ public val body: ByteArray,
    /** Whether Content-Encoding must be gzip. */ public val gzip: Boolean,
)
/** Bounded HTTP response. Zero status indicates a network failure. */
public class PulseResponse @JvmOverloads constructor(
    /** HTTP status or zero. */ public val status: Int,
    /** Advice bytes, limited to 64 KiB by production transport. */ public val body: ByteArray = byteArrayOf(),
    /** Optional Retry-After header. */ public val retryAfter: String? = null,
)
/** Async transport seam. send must return promptly and invoke completion without blocking state work. */
public fun interface PulseTransport {
    /** Issue a request without redirects; return a cancellation handle. */
    public fun send(request: PulseRequest, completion: (PulseResponse) -> Unit): PulseCancellation
}

/** One background state worker. The owner may close it after the client has been stopped. */
public class PulseSerialScheduler : PulseScheduler {
    private val worker = ScheduledThreadPoolExecutor(1) { action -> Thread(action, "pulse-state").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private val workerThread = AtomicReference<Thread?>()
    private fun wrap(action: Runnable): Runnable = Runnable { workerThread.set(Thread.currentThread()); action.run() }
    override fun execute(action: Runnable) { worker.execute(wrap(action)) }
    override fun schedule(delayMillis: Long, action: Runnable): PulseCancellation {
        val future = worker.schedule(wrap(action), delayMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return PulseCancellation { future.cancel(false) }
    }
    /** True while running a state action, to avoid blocking on the same executor. */
    public fun isWorkerThread(): Boolean = Thread.currentThread() == workerThread.get()
    /** Stop this worker after stopping the owning client. */
    public fun close() { worker.shutdown() }
}
