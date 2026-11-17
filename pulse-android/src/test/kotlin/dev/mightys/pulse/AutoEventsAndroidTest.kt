package dev.mightys.pulse

import android.app.Activity
import android.app.ApplicationExitInfo
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class AutoEventsAndroidTest {
    // ---- foreground and background from the activity counter ----

    @Test fun firstStartedActivityIsTheOnlyForegroundAndLastStopIsTheBackground() {
        val log = mutableListOf<String>()
        val counter = ActivityCounter({ log.add("back") }, { log.add("front") })
        counter.started(); counter.started(); counter.stopped(false); counter.stopped(false)
        assertEquals(listOf("front", "back"), log)
    }

    @Test fun configurationChangeNeitherBackgroundsNorForegroundsAgain() {
        val log = mutableListOf<String>(); var now = 0L
        val counter = ActivityCounter({ log.add("back") }, { log.add("front") }, { now })
        counter.started(); counter.stopped(true); now += 300; counter.started()
        assertEquals(listOf("front"), log)
        counter.stopped(false); assertEquals(listOf("front", "back"), log)
    }

    @Test fun aStaleConfigurationChangeFlagDoesNotSwallowARealForeground() {
        val log = mutableListOf<String>(); var now = 0L
        val counter = ActivityCounter({ log.add("back") }, { log.add("front") }, { now })
        counter.started(); counter.stopped(true); now += 60_000; counter.started()
        assertEquals(listOf("front", "front"), log)
    }

    @Test fun backgroundThenForegroundAgainFiresBothAgain() {
        val log = mutableListOf<String>()
        val counter = ActivityCounter({ log.add("back") }, { log.add("front") })
        counter.started(); counter.stopped(false); counter.started(); counter.stopped(false)
        assertEquals(listOf("front", "back", "front", "back"), log)
    }

    // ---- launch timer ----

    @Test fun launchMsFreezesAtTheFirstResumeAndIgnoresLaterMarks() {
        var uptime = 5_000L; val timer = LaunchTimer(1_000L) { uptime }
        assertEquals(4_000L, timer.launchMs())            // before any frame: process start to now
        uptime = 6_500L; timer.mark(); uptime = 90_000L; timer.mark()
        assertEquals(5_500L, timer.launchMs())
    }

    @Test fun launchMsIsNullWhenTheProcessStartIsUnknownOrInTheFuture() {
        assertNull(LaunchTimer(null) { 10L }.launchMs())
        val timer = LaunchTimer(50L) { 10L }; timer.mark(); assertNull(timer.launchMs())
    }

    // ---- notification status: read-only mapping ----

    @Test fun belowApi33EnabledMeansGrantedAndDisabledMeansDenied() {
        assertEquals("granted", NotificationStatus.status(30, true, null, null))
        assertEquals("denied", NotificationStatus.status(30, false, null, null))
        assertNull(NotificationStatus.status(23, null, null, null))
    }

    @Test fun api33DistinguishesGrantedDeniedAndNotDetermined() {
        assertEquals("granted", NotificationStatus.status(33, true, true, false))
        assertEquals("denied", NotificationStatus.status(34, false, true, false))     // granted, then switched off in settings
        assertEquals("denied", NotificationStatus.status(34, false, false, true))     // refused once: rationale is shown
        assertEquals("notDetermined", NotificationStatus.status(34, false, false, false))
        assertEquals("notDetermined", NotificationStatus.status(34, false, false, null))
        assertEquals("granted", NotificationStatus.status(34, true, null, null))
    }

    @Test fun neverReturnsProvisionalEphemeralOrATrackingValue() {
        val all = listOf(true, false, null)
        val results = all.flatMap { e -> all.flatMap { g -> all.map { r -> NotificationStatus.status(34, e, g, r) } } }.filterNotNull().toSet()
        assertTrue(results.all { it in setOf("granted", "denied", "notDetermined") })
    }

    // ---- exit reasons ----

    private class Raw(val at: Long, val reason: Int, val trace: String? = null) : RawExit {
        override val timestamp: Long get() = at
        override val reasonCode: Int get() = reason
        override fun trace(): String? = trace
    }

    @Test fun reasonsMapExactlyAsSpecified() {
        assertEquals("anr", ExitReasons.type(ApplicationExitInfo.REASON_ANR))
        assertEquals("crash_native", ExitReasons.type(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("low_memory", ExitReasons.type(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("excessive_resource", ExitReasons.type(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE))
        assertEquals("initialization_failure", ExitReasons.type(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE))
        assertEquals("signaled", ExitReasons.type(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals("other", ExitReasons.type(ApplicationExitInfo.REASON_OTHER))
        assertEquals("other", ExitReasons.type(ApplicationExitInfo.REASON_UNKNOWN))
    }

    @Test fun normalAndJavaCrashExitsAreSkipped() {
        listOf(ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE, ApplicationExitInfo.REASON_FREEZER, 99, -1)
            .forEach { assertNull("reason $it", ExitReasons.type(it)) }
    }

    private val anrTrace = """
        ----- pid 4242 at 2026-11-16 10:00:00 -----
        Cmd line: com.example.app

        "Signal Catcher" daemon prio=10 tid=3 Runnable
          native: #00 pc 0000000000001 /apex/libart.so (art::Foo+1)

        "main" prio=5 tid=1 Blocked
          | group="main" sCount=1
          native: #00 pc 000000000009c1d8  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+28)
          at java.lang.Object.wait(Native method)
          at com.example.app.CartViewModel.checkout(CartViewModel.kt:42)
          at android.os.Handler.dispatchMessage(Handler.java:106)
          at /data/user/0/com.example.app/files/x.Y.run(Y.java:7)

        "OkHttp Dispatcher" prio=5 tid=11 Waiting
          at java.lang.Object.wait(Native method)
          at okhttp3.Dispatcher.run(Dispatcher.kt:1)
    """.trimIndent()

    @Test fun anrTraceHeadHasOnlyTheMainThreadFramesInOrder() {
        val frames = ExitReasons.mainThreadFrames(anrTrace)
        assertEquals(listOf("java.lang.Object.wait Native method", "com.example.app.CartViewModel.checkout CartViewModel.kt:42",
            "android.os.Handler.dispatchMessage Handler.java:106", "/data/user/0/com.example.app/files/x.Y.run Y.java:7"), frames)
    }

    @Test fun anrTraceHeadIsCappedAtThirtyFramesAndHandlesGarbage() {
        val long = "\"main\" prio=5 tid=1 Blocked\n" + (1..50).joinToString("\n") { "  at a.B.m$it(B.java:$it)" }
        assertEquals(30, ExitReasons.mainThreadFrames(long).size)
        assertTrue(ExitReasons.mainThreadFrames("").isEmpty()); assertTrue(ExitReasons.mainThreadFrames("no threads here").isEmpty())
        assertTrue(ExitReasons.mainThreadFrames("\"main\" prio=5\n  native: #00 pc 1 /lib.so").isEmpty())
    }

    @Test fun recordsKeepTimeReasonAndOnlyAnrGetsFrames() {
        val now = 2_000_000_000_000L
        val records = ExitReasons.records(listOf(Raw(now - 1000, ApplicationExitInfo.REASON_ANR, anrTrace),
            Raw(now - 2000, ApplicationExitInfo.REASON_CRASH_NATIVE, anrTrace), Raw(now - 3000, ApplicationExitInfo.REASON_EXIT_SELF),
            Raw(now - 4000, ApplicationExitInfo.REASON_LOW_MEMORY)), now)
        assertEquals(listOf("anr", "crash_native", "low_memory"), records.map { it.reason })
        assertEquals(now - 1000, records[0].timeMillis)
        assertTrue(records[0].frames.isNotEmpty()); assertTrue(records[1].frames.isEmpty()); assertTrue(records[2].frames.isEmpty())
    }

    @Test fun traceIsNotReadForOldRecordsAndAFailingTraceDoesNotLoseTheRecord() {
        val now = 2_000_000_000_000L; var reads = 0
        val old = object : RawExit {
            override val timestamp = now - 9 * 86_400_000L; override val reasonCode = ApplicationExitInfo.REASON_ANR
            override fun trace(): String? { reads++; return anrTrace }
        }
        val broken = object : RawExit {
            override val timestamp = now - 1000; override val reasonCode = ApplicationExitInfo.REASON_ANR
            override fun trace(): String? = throw java.io.IOException("gone")
        }
        val records = ExitReasons.records(listOf(old, broken), now)
        assertEquals(0, reads); assertEquals(2, records.size); assertTrue(records.all { it.frames.isEmpty() })
    }

    @Test fun hookReadsNothingBelowApi30OrWithCaptureErrorsOff() {
        var reads = 0; val source = ExitInfoSource { reads++; emptyList() }
        val sent = mutableListOf<List<PulseExitRecord>>()
        ExitHook.run(29, true, source, 1L) { sent.add(it) }
        ExitHook.run(34, false, source, 1L) { sent.add(it) }
        assertEquals(0, reads); assertTrue(sent.isEmpty())
        ExitHook.run(30, true, source, 1L) { sent.add(it) }
        assertEquals(1, reads); assertEquals(1, sent.size)
    }

    @Test fun hookNeverThrowsWhenTheSystemSourceFails() {
        ExitHook.run(34, true, ExitInfoSource { throw SecurityException("no") }, 1L) { fail("nothing to send") }
    }

    // ---- end to end with a real client: fixtures through the hook, marker means no duplicates ----

    private class Queue : PulseScheduler {
        val work = ArrayDeque<Runnable>()
        override fun execute(action: Runnable) { work.add(action) }
        override fun schedule(delayMillis: Long, action: Runnable): PulseCancellation = PulseCancellation {}
        fun drain() { var n = 0; while (true) { (work.poll() ?: break).run(); check(n++ < 10000) } }
    }
    private class Memory : PulseStorage {
        val files = HashMap<String, ByteArray>()
        override fun read(name: String): ByteArray? = files[name]
        override fun replace(name: String, bytes: ByteArray) { files[name] = bytes }
        override fun remove(name: String) { files.remove(name) }
    }

    private fun launchWithExits(storage: Memory, now: Long, raws: List<RawExit>): List<String> {
        val queue = Queue(); val bodies = mutableListOf<String>()
        val client = PulseClient(clock = PulseClock { now }, scheduler = queue, transport = PulseTransport { request, done ->
            if (!request.isGet) bodies.add(String(request.body)); done(PulseResponse(if (request.isGet) 404 else 202)); PulseCancellation {}
        })
        client.initialize("key", PulseOptions(gzip = false), storage, PulseEnvironment { mapOf("platform" to "mobile") })
        ExitHook.run(34, true, ExitInfoSource { raws }, now) { client.reportExits(it) }
        queue.drain(); client.flush(); queue.drain()
        return bodies
    }

    @Test fun fixturesBecomeExitErrorsOnceAndNeverTwice() {
        val storage = Memory(); val now = 1_800_000_000_000L
        val raws = listOf(Raw(now - 1000, ApplicationExitInfo.REASON_ANR, anrTrace), Raw(now - 2000, ApplicationExitInfo.REASON_LOW_MEMORY),
            Raw(now - 3000, ApplicationExitInfo.REASON_CRASH), Raw(now - 4000, ApplicationExitInfo.REASON_USER_REQUESTED))
        val first = launchWithExits(storage, now, raws).joinToString()
        assertEquals(2, Regex("\"kind\":\"exit\"").findAll(first).count())
        assertTrue(first.contains("\"type\":\"anr\"")); assertTrue(first.contains("\"type\":\"low_memory\""))
        assertTrue(first.contains("com.example.app.CartViewModel.checkout CartViewModel.kt:42"))
        assertFalse(first.contains("\"kind\":\"crash\""))
        assertEquals(0, Regex("\"kind\":\"exit\"").findAll(launchWithExits(storage, now + 60_000, raws).joinToString()).count())
    }

    // ---- launch and deep link callbacks ----

    private fun entry(identity: Any, data: String?) = EntryIntent(identity, data)

    @Test fun deepLinkOnCreateIsReportedOnceForANewLaunchButNotOnARestore() {
        val links = mutableListOf<String>(); val intent = Any(); val activity = Activity()
        val callbacks = EntryCallbacks(LaunchTimer(null) { 0 }, { links.add(it) }, { entry(intent, "myapp://x/1") })
        callbacks.onActivityCreated(activity, null); callbacks.onActivityResumed(activity)
        assertEquals(listOf("myapp://x/1"), links)
        val restored = EntryCallbacks(LaunchTimer(null) { 0 }, { links.add(it) }, { entry(intent, "myapp://x/1") })
        restored.onActivityCreated(activity, android.os.Bundle()); restored.onActivityResumed(activity)
        assertEquals(1, links.size)
    }

    @Test fun aNewIntentSeenOnResumeIsReportedOnceAndTheSameOneIsNot() {
        val links = mutableListOf<String>(); var current = entry(Any(), null); val activity = Activity()
        val callbacks = EntryCallbacks(LaunchTimer(null) { 0 }, { links.add(it) }, { current })
        callbacks.onActivityCreated(activity, null); callbacks.onActivityResumed(activity)
        assertTrue(links.isEmpty())
        current = entry(Any(), "myapp://new/2"); callbacks.onActivityResumed(activity); callbacks.onActivityResumed(activity)
        assertEquals(listOf("myapp://new/2"), links)
    }

    @Test fun nonLinkIntentsAndMissingIntentsReportNothing() {
        val links = mutableListOf<String>(); val activity = Activity()
        val callbacks = EntryCallbacks(LaunchTimer(null) { 0 }, { links.add(it) }, { null })
        callbacks.onActivityCreated(activity, null); callbacks.onActivityResumed(activity)
        EntryCallbacks(LaunchTimer(null) { 0 }, { links.add(it) }, { entry(Any(), null) }).apply { onActivityCreated(activity, null) }
        assertTrue(links.isEmpty())
    }

    @Test fun initializeFromAnActivityReportsItsLinkOnceEvenWhenCreateAlreadyRan() {
        val links = mutableListOf<String>(); val intent = Any(); val activity = Activity()
        val callbacks = EntryCallbacks(LaunchTimer(null) { 0 }, { links.add(it) }, { entry(intent, "myapp://x/3") })
        callbacks.initializedFrom(activity); callbacks.onActivityCreated(activity, null); callbacks.onActivityResumed(activity)
        assertEquals(listOf("myapp://x/3"), links)
    }

    @Test fun firstResumeMarksTheLaunchOnceAndAFailingReaderIsHarmless() {
        var uptime = 100L; val timer = LaunchTimer(0L) { uptime }
        val callbacks = EntryCallbacks(timer, {}, { throw IllegalStateException("x") })
        uptime = 700L; callbacks.onActivityResumed(Activity()); uptime = 9_000L; callbacks.onActivityResumed(Activity())
        assertEquals(700L, timer.launchMs())
    }
}
