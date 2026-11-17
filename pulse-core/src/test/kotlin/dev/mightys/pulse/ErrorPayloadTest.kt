package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

class ErrorPayloadTest {
    private fun trace(count: Int, cls: String = "com.example.Deep") =
        Array(count) { StackTraceElement(cls, "step$it", "Deep.kt", 10 + it) }

    private fun thrown(message: String?, frames: Array<StackTraceElement>, cause: Throwable? = null): Throwable =
        IllegalStateException(message, cause).also { it.stackTrace = frames }

    @Test fun frameFormatIsClassMethodFileAndLine() {
        val e = thrown("boom", arrayOf(StackTraceElement("com.example.CartViewModel", "checkout", "CartViewModel.kt", 42)))
        val report = ErrorPayload.from("handled", e)
        assertEquals(listOf("com.example.CartViewModel.checkout CartViewModel.kt:42"), report["frames"])
        assertEquals("java.lang.IllegalStateException", report["type"])
        assertEquals("boom", report["message"])
        assertEquals("handled", report["kind"])
        assertEquals(true, report["symbolicated"])
    }

    @Test fun frameWithoutFileOrLineDegradesGracefully() {
        assertEquals("a.B.native0", ErrorPayload.frame(StackTraceElement("a.B", "native0", null, -2)))
        assertEquals("com.x.Y.run Y.kt", ErrorPayload.frame(StackTraceElement("com.x.Y", "run", "Y.kt", -1)))
    }

    @Test fun framesLimitedToThirtyAndTopFrameFirst() {
        val report = ErrorPayload.from("error", thrown("x", trace(80)))
        val frames = report["frames"] as List<*>
        assertEquals(30, frames.size)
        assertTrue((frames.first() as String).contains("step0 "))
    }

    @Test fun frameAndMessageAndTypeLengthLimits() {
        val long = "m".repeat(400)
        val e = thrown("q".repeat(900), arrayOf(StackTraceElement("com.example.$long", "run", "F.kt", 1)))
        val report = ErrorPayload.from("error", e)
        assertEquals(500, (report["message"] as String).length)
        assertEquals(300, ((report["frames"] as List<*>).single() as String).length)
        assertTrue((report["type"] as String).length <= 200)
    }

    @Test fun serializedErrorNeverExceeds16384Bytes() {
        // 30 frames of 300 multi-byte characters would be far over the limit.
        val wide = "\u00e9".repeat(270)
        val frames = Array(30) { StackTraceElement("com.example.$wide", "m$it", "F.kt", it + 1) }
        val report = ErrorPayload.from("error", thrown("\u00e9".repeat(480), frames))
        val bytes = JsonWriter.value(report).toByteArray(Charsets.UTF_8).size
        assertTrue("size $bytes", bytes <= 16384)
        assertTrue((report["frames"] as List<*>).isNotEmpty())
        // The bottom frames go first, the top frame stays.
        assertTrue(((report["frames"] as List<*>).first() as String).contains("m0"))
    }

    @Test fun messageIsTrimmedAfterFramesWhenStillTooLarge() {
        val fitted = ErrorPayload.fit(linkedMapOf("kind" to "error", "type" to "T", "message" to "x".repeat(20000),
            "frames" to emptyList<String>(), "symbolicated" to true))
        assertTrue(JsonWriter.value(fitted).toByteArray().size <= 16384)
    }

    @Test fun messageAndFramesAreFiltered() {
        val e = thrown("user jane@example.com id 99887766", arrayOf(StackTraceElement("com.example.A", "go", "A.kt", 3)))
        val report = ErrorPayload.from("handled", e)
        assertEquals("user <filtered> id <filtered>", report["message"])
    }

    @Test fun nullMessageIsOmitted() {
        val report = ErrorPayload.from("handled", thrown(null, trace(1)))
        assertFalse(report.containsKey("message"))
    }

    @Test fun causeChainAddsCausedByFramesWithinTheLimit() {
        val cause = IllegalArgumentException("root").also { it.stackTrace = trace(10, "com.example.Root") }
        val e = thrown("wrapper", trace(40), cause)
        val frames = ErrorPayload.from("handled", e)["frames"] as List<*>
        assertEquals(30, frames.size)
        val marker = frames.indexOf("Caused by: java.lang.IllegalArgumentException")
        assertTrue(marker > 0)
        assertEquals(10, frames.size - marker - 1)
        assertTrue((frames.last() as String).startsWith("com.example.Root.step9 "))
    }

    @Test fun shortChainKeepsEveryFrameInOrder() {
        val cause = IllegalArgumentException("root").also { it.stackTrace = trace(2, "com.example.Root") }
        val frames = ErrorPayload.from("handled", thrown("wrapper", trace(3), cause))["frames"] as List<*>
        assertEquals(6, frames.size)
        assertEquals("Caused by: java.lang.IllegalArgumentException", frames[3])
    }

    @Test fun causeLoopsDoNotHang() {
        val a = RuntimeException("a"); val b = RuntimeException("b", a)
        a.initCause(b)
        a.stackTrace = trace(2); b.stackTrace = trace(2)
        val frames = ErrorPayload.from("handled", a)["frames"] as List<*>
        assertTrue(frames.size <= 30)
    }

    @Test fun minifiedClassNamesAreNotSymbolicated() {
        val minified = thrown("x", arrayOf(StackTraceElement("a.b.c", "d", "SourceFile", 1), StackTraceElement("android.os.Handler", "dispatch", "Handler.java", 9)))
        assertEquals(false, ErrorPayload.from("error", minified)["symbolicated"])
        assertEquals(true, ErrorPayload.from("error", thrown("x", trace(3)))["symbolicated"])
        assertTrue(ErrorPayload.looksMinified("u3.a")); assertTrue(ErrorPayload.looksMinified("a.b\$c"))
        assertFalse(ErrorPayload.looksMinified("io.ktor.Client")); assertFalse(ErrorPayload.looksMinified("com.a.Bee"))
    }

    @Test fun typeOnlyDropsMessageFramesAndSymbolicated() {
        val full = ErrorPayload.from("crash", thrown("secret", trace(2)))
        val slim = ErrorPayload.typeOnly(full)
        assertEquals(setOf("kind", "type"), slim.keys)
        assertEquals("crash", slim["kind"])
    }

    @Test fun brokenThrowableNeverThrows() {
        val evil = object : RuntimeException() {
            override val message: String? get() = throw IllegalStateException("no")
            override fun getStackTrace(): Array<StackTraceElement> = throw IllegalStateException("no")
        }
        val report = ErrorPayload.from("handled", evil)
        assertEquals("handled", report["kind"])
        assertTrue((report["type"] as String).isNotEmpty())
    }
}
