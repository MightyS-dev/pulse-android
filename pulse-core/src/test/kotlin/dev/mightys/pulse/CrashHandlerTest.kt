package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

class CrashHandlerTest {
    private fun boom(): Throwable = IllegalStateException("crash for jane@example.com").also {
        it.stackTrace = arrayOf(StackTraceElement("com.example.Cart", "checkout", "Cart.kt", 42))
    }
    private class Recorder : Thread.UncaughtExceptionHandler {
        val calls = mutableListOf<Pair<Thread, Throwable>>()
        override fun uncaughtException(t: Thread, e: Throwable) { calls.add(t to e) }
    }
    private fun crashes(h: Harness) = h.allEvents.filter { it["type"] == "pulse.error" }

    @Test fun chainsToThePreviousHandlerWithTheSameArguments() {
        val h = Harness(); val previous = Recorder(); val error = boom()
        val handler = h.client.crashHandler(previous)
        handler.uncaughtException(Thread.currentThread(), error)
        assertEquals(1, previous.calls.size)
        assertSame(Thread.currentThread(), previous.calls[0].first); assertSame(error, previous.calls[0].second)
    }

    @Test fun writesACrashFileThatTheNextLaunchSendsAsCrashAndDeletes() {
        val storage = FakeStorage(); val first = Harness(storage = storage)
        first.send("before"); first.client.crashHandler(Recorder()).uncaughtException(Thread.currentThread(), boom())
        val written = storage.files["crash-report"]
        assertNotNull(written)
        assertEquals(0, crashes(first).size)
        val second = Harness(storage = storage); second.flush()
        val event = crashes(second).single(); val error = event["error"] as Map<*, *>
        assertEquals("crash", error["kind"]); assertEquals("java.lang.IllegalStateException", error["type"])
        assertEquals("crash for <filtered>", error["message"])
        assertEquals(listOf("com.example.Cart.checkout Cart.kt:42"), error["frames"])
        assertNull(storage.files["crash-report"])
        // Sent once only.
        val third = Harness(storage = storage); third.flush()
        assertTrue(crashes(third).isEmpty())
    }

    @Test fun crashKeepsTheSessionOfTheRunThatCrashed() {
        val storage = FakeStorage(); val first = Harness(storage = storage); first.send("before")
        val session = first.allEvents.first()["sessionId"]
        first.client.crashHandler(null).let { runCatching { it.uncaughtException(Thread.currentThread(), boom()) } }
        val second = Harness(storage = storage); second.send("after")
        assertEquals(session, crashes(second).single()["sessionId"])
        assertNotEquals(session, second.events.single()["sessionId"])
    }

    @Test fun typeOnlyAppliesToTheStoredCrashToo() {
        val storage = FakeStorage(); val first = Harness(storage = storage); first.send("x")
        first.client.crashHandler(Recorder()).uncaughtException(Thread.currentThread(), boom())
        val second = Harness(options = PulseOptions(errorDetail = ErrorDetail.TYPE_ONLY), storage = storage); second.flush()
        assertEquals(setOf("kind", "type"), (crashes(second).single()["error"] as Map<*, *>).keys)
    }

    @Test fun optOutDropsThePendingCrashFileAndStopsCapture() {
        val storage = FakeStorage(); val first = Harness(storage = storage); first.send("x")
        first.client.crashHandler(Recorder()).uncaughtException(Thread.currentThread(), boom())
        assertNotNull(storage.files["crash-report"])
        first.client.optOut = true; first.settle()
        assertNull(storage.files["crash-report"])
        first.client.crashHandler(Recorder()).uncaughtException(Thread.currentThread(), boom())
        assertNull(storage.files["crash-report"])
    }

    @Test fun optedOutLaunchDeletesTheFileWithoutSendingIt() {
        val storage = FakeStorage(); val first = Harness(storage = storage); first.send("x")
        first.client.crashHandler(Recorder()).uncaughtException(Thread.currentThread(), boom())
        storage.files["opt-out"] = byteArrayOf(1)
        val second = Harness(storage = storage); second.flush()
        assertTrue(crashes(second).isEmpty()); assertNull(storage.files["crash-report"])
    }

    @Test fun neverThrowsWhenTheSdkIsBrokenAndStillChains() {
        val storage = FakeStorage(); val h = Harness(storage = storage); h.send("x")
        storage.failWrites = true; storage.failReads = true
        val previous = Recorder()
        h.client.crashHandler(previous).uncaughtException(Thread.currentThread(), boom())
        assertEquals(1, previous.calls.size)
        val hostile = object : RuntimeException() {
            override val message: String? get() = throw IllegalStateException()
            override fun getStackTrace(): Array<StackTraceElement> = throw IllegalStateException()
        }
        h.client.crashHandler(previous).uncaughtException(Thread.currentThread(), hostile)
        assertEquals(2, previous.calls.size)
    }

    @Test fun anErrorThrownByThePreviousHandlerIsNotSwallowed() {
        val h = Harness(); h.send("x")
        val failing = Thread.UncaughtExceptionHandler { _, _ -> throw IllegalArgumentException("previous failed") }
        try { h.client.crashHandler(failing).uncaughtException(Thread.currentThread(), boom()); fail() }
        catch (expected: IllegalArgumentException) { assertEquals("previous failed", expected.message) }
    }

    @Test fun hangingStorageIsWaitedForOnlyBrieflyThenTheCrashProceeds() {
        val release = java.util.concurrent.CountDownLatch(1)
        val hanging = object : PulseStorage by FakeStorage() {
            override fun replace(name: String, bytes: ByteArray) { if (name == "crash-report") release.await() }
        }
        val h = Harness(storage = hanging); h.send("x")
        val previous = Recorder()
        val started = System.nanoTime()
        h.client.crashHandler(previous, 150).uncaughtException(Thread.currentThread(), boom())
        release.countDown()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3000)
        assertEquals(1, previous.calls.size)
    }

    @Test fun reentryFromTheSameThreadReturnsInsteadOfLooping() {
        val h = Harness(); h.send("x")
        lateinit var handler: Thread.UncaughtExceptionHandler
        var depth = 0
        val previous = Thread.UncaughtExceptionHandler { t, e -> if (depth++ == 0) handler.uncaughtException(t, e) }
        handler = h.client.crashHandler(previous)
        handler.uncaughtException(Thread.currentThread(), boom())
        assertEquals(1, depth)
    }

    @Test fun damagedCrashFileIsDeletedAndNothingIsSent() {
        val storage = FakeStorage(); storage.files["crash-report"] = "not json".toByteArray()
        val h = Harness(storage = storage); h.flush()
        assertTrue(crashes(h).isEmpty()); assertNull(storage.files["crash-report"])
    }

    @Test fun crashBeforeTheSdkHasStartedWritesNothing() {
        val h = Harness(initialize = false); val previous = Recorder()
        h.client.crashHandler(previous).uncaughtException(Thread.currentThread(), boom())
        assertEquals(1, previous.calls.size)
    }

    private fun startedButNotSettled(storage: FakeStorage): Harness {
        val h = Harness(initialize = false)
        h.client.initialize("synthetic-key", h.options, storage, PulseEnvironment { h.env }, false, h.dynamic)
        return h // the worker has not run yet: storage and privacy are not loaded
    }

    @Test fun crashRightAfterInitializeIsKeptAndSentOnTheNextLaunch() {
        val storage = FakeStorage(); val previous = Recorder()
        val h = startedButNotSettled(storage)
        h.client.crashHandler(previous).uncaughtException(Thread.currentThread(), boom())
        assertEquals(1, previous.calls.size)
        assertNotNull(storage.files["crash-report"])
        val second = Harness(storage = storage); second.flush()
        val error = crashes(second).single()["error"] as Map<*, *>
        assertEquals("crash", error["kind"]); assertEquals("crash for <filtered>", error["message"])
        assertNull(storage.files["crash-report"])
    }

    @Test fun crashRightAfterInitializeWithStoredOptOutIsDeletedWithoutSending() {
        val storage = FakeStorage(); storage.files["opt-out"] = byteArrayOf(1)
        val h = startedButNotSettled(storage)
        h.client.crashHandler(Recorder()).uncaughtException(Thread.currentThread(), boom())
        assertNotNull(storage.files["crash-report"])
        val second = Harness(storage = storage); second.flush()
        assertTrue(crashes(second).isEmpty()); assertNull(storage.files["crash-report"])
    }

    @Test fun earlyCrashFileCarriesTheMarkerAndLaterOnesDoNot() {
        val storage = FakeStorage()
        startedButNotSettled(storage).client.crashHandler(null).let { runCatching { it.uncaughtException(Thread.currentThread(), boom()) } }
        assertTrue(String(storage.files["crash-report"]!!).contains("\"early\":true"))
        val settled = FakeStorage(); val h = Harness(storage = settled); h.send("x")
        h.client.crashHandler(Recorder()).uncaughtException(Thread.currentThread(), boom())
        assertFalse(String(settled.files["crash-report"]!!).contains("early"))
    }

    @Test fun optOutSetBeforeTheWorkerRanStillWritesNothing() {
        val storage = FakeStorage(); val h = startedButNotSettled(storage)
        h.client.optOut = true
        h.client.crashHandler(Recorder()).uncaughtException(Thread.currentThread(), boom())
        assertNull(storage.files["crash-report"])
    }
}
