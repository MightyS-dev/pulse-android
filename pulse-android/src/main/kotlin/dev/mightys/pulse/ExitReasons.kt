package dev.mightys.pulse

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** One entry of the system's process exit history, reduced to what the SDK may use. */
internal interface RawExit {
    val timestamp: Long
    val reasonCode: Int
    /** The system trace text (only ANR has one), or null. Bounded by the reader. */
    fun trace(): String?
}

/** Where the exit history comes from; the system implementation is the only one outside tests. */
internal fun interface ExitInfoSource { fun history(): List<RawExit> }

/** Maps ApplicationExitInfo reasons to the wire names and cuts the ANR trace head. */
internal object ExitReasons {
    private const val MAX_FRAMES = 30
    private const val MAX_AGE_MILLIS = 7L * 86_400_000L

    /**
     * ApplicationExitInfo.REASON_*: ANR 6, CRASH_NATIVE 5, LOW_MEMORY 3, EXCESSIVE_RESOURCE_USAGE 9, INITIALIZATION_FAILURE 7,
     * SIGNALED 2, OTHER 13, UNKNOWN 0. Everything else (EXIT_SELF, USER_REQUESTED, USER_STOPPED, CRASH, PERMISSION_CHANGE,
     * DEPENDENCY_DIED, PACKAGE_UPDATED, PACKAGE_STATE_CHANGE, FREEZER) is a normal exit or a Java crash the crash handler
     * already sent, and gives null.
     */
    fun type(reason: Int): String? = when (reason) {
        6 -> "anr"
        5 -> "crash_native"
        3 -> "low_memory"
        9 -> "excessive_resource"
        7 -> "initialization_failure"
        2 -> "signaled"
        13, 0 -> "other"
        else -> null
    }

    fun records(raws: List<RawExit>, now: Long): List<PulseExitRecord> = raws.mapNotNull { raw ->
        try {
            val type = type(raw.reasonCode) ?: return@mapNotNull null
            val frames = if (raw.reasonCode == 6 && raw.timestamp >= now - MAX_AGE_MILLIS) {
                try { raw.trace()?.let(::mainThreadFrames).orEmpty() } catch (_: Exception) { emptyList() }
            } else emptyList()
            PulseExitRecord(raw.timestamp, type, frames)
        } catch (_: Exception) { null }
    }

    /** The first [MAX_FRAMES] `at` frames of the thread named "main" as `class.method File.java:line`; native frames are skipped. */
    fun mainThreadFrames(trace: String): List<String> {
        val frames = ArrayList<String>()
        var inMain = false
        for (line in trace.lineSequence()) {
            val text = line.trim()
            if (text.startsWith("\"")) {
                if (inMain) break
                inMain = text.startsWith("\"main\"")
                continue
            }
            if (!inMain) continue
            if (text.isEmpty()) { if (frames.isNotEmpty()) break; continue }
            if (!text.startsWith("at ")) continue
            val body = text.removePrefix("at ")
            val open = body.lastIndexOf('(')
            if (open <= 0 || !body.endsWith(")")) continue
            frames.add(body.substring(0, open) + " " + body.substring(open + 1, body.length - 1))
            if (frames.size == MAX_FRAMES) break
        }
        return frames
    }
}

/** Reads the exit history once per launch on the SDK worker and hands the records to the client. */
internal object ExitHook {
    fun run(sdk: Int, captureErrors: Boolean, source: ExitInfoSource, now: Long, send: (List<PulseExitRecord>) -> Unit) {
        if (sdk < 30 || !captureErrors) return
        try { send(ExitReasons.records(source.history(), now)) } catch (_: Exception) { /* Never reaches the app. */ }
    }
}

/** ActivityManager.getHistoricalProcessExitReasons for this package, the newest 16. API 30+ only. */
internal class SystemExitInfoSource(private val app: Context) : ExitInfoSource {
    override fun history(): List<RawExit> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        return manager.getHistoricalProcessExitReasons(app.packageName, 0, 16).map { info ->
            object : RawExit {
                override val timestamp: Long = info.timestamp
                override val reasonCode: Int = info.reason
                override fun trace(): String? = try {
                    info.traceInputStream?.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (buffer.size() < MAX_TRACE_BYTES) {
                            val count = stream.read(chunk)
                            if (count < 0) break
                            buffer.write(chunk, 0, count)
                        }
                        buffer.toString(Charsets.UTF_8.name())
                    }
                } catch (_: Exception) { null }
            }
        }
    }
    private companion object { const val MAX_TRACE_BYTES = 262_144 }
}
