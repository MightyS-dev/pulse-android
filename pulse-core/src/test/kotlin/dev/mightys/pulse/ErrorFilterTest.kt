package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

class ErrorFilterTest {
    /** One shared table: input to output. */
    private val vectors = listOf(
        "email" to ("failed for jane.doe+x@example.co.uk today" to "failed for <filtered> today"),
        "url with query" to ("GET https://other.example/v1/items?token=abc&id=7 failed" to "GET https://other.example/v1/items<filtered> failed"),
        "url with fragment" to ("see https://other.example/docs/page#section-2 now" to "see https://other.example/docs/page<filtered> now"),
        "uuid" to ("order 123e4567-e89b-12d3-a456-426614174000 missing" to "order <filtered> missing"),
        "ipv4" to ("connect to 192.168.1.20 refused" to "connect to <filtered> refused"),
        "ipv6 full" to ("peer 2001:0db8:85a3:0000:0000:8a2e:0370:7334 closed" to "peer <filtered> closed"),
        "ipv6 compressed" to ("peer fe80::1ff:fe23:4567:890a closed" to "peer <filtered> closed"),
        "six digit run" to ("account 1234567 not found" to "account <filtered> not found"),
        "hex address kept" to ("crash at MyApp+0x1234567 abs 0x00000071a2b3c4d5" to "crash at MyApp+0x1234567 abs 0x00000071a2b3c4d5"),
        "short hex kept" to ("at 0x1234 now" to "at 0x1234 now"),
        "decimal run next to hex" to ("order 1234567 at MyApp+0x1234567" to "order <filtered> at MyApp+0x1234567"),
        "five digit run kept" to ("code 12345 not found" to "code 12345 not found"),
        "unix path" to ("cannot read /data/user/0/com.example.app/files/state.db" to "cannot read state.db"),
        "windows path" to ("cannot read C:\\Users\\jane\\AppData\\state.db" to "cannot read state.db"),
        "frame with path and line" to ("com.example.Cart.checkout /home/jane/work/Cart.kt:42" to "com.example.Cart.checkout Cart.kt:42"),
    )

    @Test fun sharedVectorTable() {
        assertTrue(vectors.size >= 12)
        for ((name, pair) in vectors) assertEquals(name, pair.second, ErrorFilter.apply(pair.first))
    }

    @Test fun fileUrlKeepsOnlyTheFileName() {
        assertEquals("opened state.db ok", ErrorFilter.apply("opened file:///home/jane/data/state.db ok"))
    }

    @Test fun kotlinDoubleColonAndPlainTextAreLeftAlone() {
        assertEquals("Foo::bar and a/b and 10:30:45", ErrorFilter.apply("Foo::bar and a/b and 10:30:45"))
        assertEquals("a / b", ErrorFilter.apply("a / b"))
    }

    @Test fun filterIsStableWhenAppliedTwice() {
        for ((_, pair) in vectors) { val once = ErrorFilter.apply(pair.first); assertEquals(once, ErrorFilter.apply(once)) }
    }

    @Test fun emailBeforeUrlAndMultipleMatches() {
        assertEquals("<filtered> and <filtered>", ErrorFilter.apply("a@b.com and c@d.org"))
    }

    @Test fun frameKeepsLineAndColumnAfterFileName() {
        assertEquals("checkout app.js:1:234567", ErrorFilter.apply("checkout app.js:1:234567", frame = true))
        assertEquals("Cart.swift:1234567", ErrorFilter.apply("Cart.swift:1234567", frame = true))
        assertEquals("order <filtered> Cart.kt:42", ErrorFilter.apply("order 1234567 Cart.kt:42", frame = true))
    }

    @Test fun messageHasNoPositionExemption() {
        assertEquals("failed at app.js:1:<filtered>", ErrorFilter.apply("failed at app.js:1:234567"))
        val out = ErrorPayload.normalize("error", "E", "failed at app.js:1:234567", listOf("checkout app.js:1:234567"), true)
        assertEquals("failed at app.js:1:<filtered>", out["message"])
        assertEquals(listOf("checkout app.js:1:234567"), out["frames"])
    }
}
