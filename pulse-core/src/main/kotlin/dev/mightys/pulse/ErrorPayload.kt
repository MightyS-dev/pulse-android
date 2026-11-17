package dev.mightys.pulse

/** How much of an error is sent: everything (default), or only its kind and type. */
public enum class ErrorDetail {
    /** Type, filtered message and frames. */
    FULL,
    /** Only the error type. No message and no frames. */
    TYPE_ONLY,
}

/** Builds the `error` object of a `pulse.error` signal: type, filtered message, readable frames, within fixed limits. */
internal object ErrorPayload {
    const val MAX_FRAMES = 30
    const val MAX_FRAME_CHARS = 300
    const val MAX_MESSAGE_CHARS = 500
    const val MAX_TYPE_CHARS = 200
    const val MAX_BYTES = 16384
    private const val MAX_CAUSES = 5
    private const val RAW_LIMIT = 4000

    fun from(kind: String, error: Throwable): Map<String, Any?> {
        val type = try { error.javaClass.name } catch (_: Throwable) { "" }
        val message = try { error.message } catch (_: Throwable) { null }
        val (frames, minified) = frames(error)
        return normalize(kind, type, message, frames, !minified)
    }

    /** Applies filter and limits to values from any source, including a stored crash file. */
    fun normalize(kind: String, type: String, message: String?, frames: List<String>, symbolicated: Boolean): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>("kind" to kind, "type" to clean(type, MAX_TYPE_CHARS, false).ifEmpty { "unknown" })
        message?.let { clean(it, MAX_MESSAGE_CHARS, true) }?.takeIf { it.isNotEmpty() }?.let { result["message"] = it }
        result["frames"] = frames.take(MAX_FRAMES).map { clean(it, MAX_FRAME_CHARS, true, frame = true) }.filter { it.isNotEmpty() }
        result["symbolicated"] = symbolicated
        return fit(result)
    }

    fun typeOnly(error: Map<String, Any?>): Map<String, Any?> = linkedMapOf("kind" to error["kind"], "type" to error["type"])

    /** Drops frames from the bottom, then shortens the message, until the object serializes within [MAX_BYTES]. */
    fun fit(error: Map<String, Any?>): Map<String, Any?> {
        var frames = (error["frames"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        var message = error["message"] as? String
        fun build(): Map<String, Any?> = LinkedHashMap(error).also {
            if (message != null) it["message"] = message else it.remove("message")
            if (error.containsKey("frames")) it["frames"] = frames
        }
        fun size(): Int = JsonWriter.value(build()).toByteArray(Charsets.UTF_8).size
        while (size() > MAX_BYTES && frames.isNotEmpty()) frames = frames.dropLast(1)
        while (size() > MAX_BYTES && message != null) message = message!!.let { if (it.length <= 1) null else cut(it, it.length / 2) }
        return build()
    }

    /** `class.method File.kt:line`; file and line are left out when the platform does not give them. */
    fun frame(element: StackTraceElement): String = buildString {
        append(element.className).append('.').append(element.methodName)
        element.fileName?.takeIf { it.isNotEmpty() }?.let { append(' ').append(it); if (element.lineNumber > 0) append(':').append(element.lineNumber) }
    }

    /** True when every segment of the class name has one or two characters, as after code shrinking. */
    fun looksMinified(className: String): Boolean {
        val segments = className.split('.', '$').filter { it.isNotEmpty() }
        return segments.isNotEmpty() && segments.all { it.length <= 2 }
    }

    private class Link(val marker: String?, val trace: List<StackTraceElement>)

    /** Frames of the error then of each cause behind a `Caused by: <type>` frame, at most [MAX_FRAMES] in all, plus whether any looks minified. */
    private fun frames(error: Throwable): Pair<List<String>, Boolean> {
        val links = ArrayList<Link>()
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = error
        while (current != null && links.size < MAX_CAUSES && seen.put(current, true) == null) {
            val trace = try { current.stackTrace.toList() } catch (_: Throwable) { emptyList() }
            val marker = if (links.isEmpty()) null else "Caused by: " + clean(try { current.javaClass.name } catch (_: Throwable) { "unknown" }, MAX_TYPE_CHARS, false)
            links.add(Link(marker, trace))
            current = try { current.cause } catch (_: Throwable) { null }
        }
        // Share the budget: short traces keep all their frames, long ones split what is left, so a root cause is never pushed out.
        var remaining = MAX_FRAMES - links.count { it.marker != null }
        var left = links.size
        val quota = IntArray(links.size)
        for (index in links.indices.sortedBy { links[it].trace.size }) {
            quota[index] = minOf(links[index].trace.size, remaining / left)
            remaining -= quota[index]; left--
        }
        val out = ArrayList<String>(); var minified = false
        links.forEachIndexed { index, link ->
            link.marker?.let(out::add)
            for (element in link.trace.take(quota[index])) {
                out.add(try { frame(element) } catch (_: Throwable) { continue })
                if (looksMinified(element.className)) minified = true
            }
        }
        return out to minified
    }

    private fun clean(raw: String, limit: Int, filter: Boolean, frame: Boolean = false): String {
        val safe = replaceBrokenSurrogates(raw.take(RAW_LIMIT))
        return cut(if (filter) ErrorFilter.apply(safe, frame) else safe, limit)
    }

    private fun cut(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (text[limit - 1].isHighSurrogate()) limit - 1 else limit
        return text.substring(0, end)
    }

    private fun replaceBrokenSurrogates(text: String): String {
        if (JsonWriter.validUnicode(text)) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) { out.append(c).append(text[i + 1]); i += 2; continue }
            out.append(if (c.isSurrogate()) '�' else c); i++
        }
        return out.toString()
    }
}
