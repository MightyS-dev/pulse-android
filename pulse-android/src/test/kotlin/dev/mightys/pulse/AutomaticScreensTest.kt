package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class AutomaticScreensTest {
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
    private class Rig(val options: PulseOptions = PulseOptions(), val storage: Memory = Memory()) {
        val queue = Queue()
        val bodies = mutableListOf<String>()
        val client = PulseClient(scheduler = queue, transport = PulseTransport { request, done ->
            bodies.add(String(request.body)); done(PulseResponse(202)); PulseCancellation {}
        })
        val screens = AutomaticScreens(client)
        /** What Pulse.initialize does: the init step goes on the worker, the hooks are live at once. */
        fun initialize() {
            queue.execute(Runnable {
                client.initialize("key", options.copy(gzip = false), storage, PulseEnvironment { mapOf("platform" to "mobile") })
            })
        }
        fun finish(): List<String> { queue.drain(); client.flush(); queue.drain(); return bodies }
        fun count(): Int = bodies.sumOf { Regex("pulse\\.screen_view").findAll(it).count() }
        fun names(): List<String> = bodies.flatMap { Regex("\"screen\":\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
    }

    @Test fun initializeFromActivityOnCreateGivesExactlyOneScreenViewAfterOnResume() {
        val rig = Rig(); val activity = HomeActivity()
        rig.screens.callbacks.onActivityCreated(activity, null)
        rig.initialize(); rig.screens.initializedFrom(activity)
        rig.screens.callbacks.onActivityResumed(activity)
        rig.finish()
        assertEquals(listOf("HomeActivity"), rig.names())
    }

    @Test fun manualScreensRightAfterInitializeFollowTheActivityInCallOrder() {
        val rig = Rig(); val activity = HomeActivity()
        rig.initialize(); rig.screens.initializedFrom(activity)
        rig.client.screen("home"); rig.client.screen("detail")
        rig.screens.callbacks.onActivityResumed(activity)
        rig.finish()
        assertEquals(listOf("HomeActivity", "home", "detail"), rig.names())
        assertEquals(listOf("HomeActivity", "home"), Regex("\"previousScreen\":\"([^\"]+)\"").findAll(rig.bodies.joinToString()).map { it.groupValues[1] }.toList())
    }

    @Test fun resumeAfterAnOnCreateInitializeIsCoveredButALaterReturnCounts() {
        val rig = Rig(); val activity = HomeActivity()
        rig.initialize(); rig.screens.initializedFrom(activity); rig.client.screen("home")
        rig.screens.callbacks.onActivityResumed(activity)
        rig.screens.callbacks.onActivityPaused(activity); rig.client.screen("other"); rig.screens.callbacks.onActivityResumed(activity)
        rig.finish()
        assertEquals(listOf("HomeActivity", "home", "other", "HomeActivity"), rig.names())
    }

    @Test fun initializeAfterTheResumeAlreadyFiredDoesNotSwallowTheNextRealResume() {
        val rig = Rig(); val activity = HomeActivity()
        rig.initialize(); rig.screens.initializedFrom(activity)
        rig.screens.callbacks.onActivityPaused(activity); rig.client.screen("away"); rig.screens.callbacks.onActivityResumed(activity)
        rig.finish()
        assertEquals(listOf("HomeActivity", "away", "HomeActivity"), rig.names())
    }

    @Test fun resumeEventsAreCapturedInOrderWithManualCallsAfterInitialization() {
        val rig = Rig(); rig.initialize(); rig.finish()
        rig.screens.callbacks.onActivityResumed(HomeActivity()); rig.client.screen("sheet"); rig.screens.callbacks.onActivityResumed(SettingsActivity())
        rig.finish()
        assertEquals(listOf("HomeActivity", "sheet", "SettingsActivity"), rig.names())
    }

    @Test fun initializeFromActivityOnResumeWhoseResumeAlreadyFiredStillGivesOneScreenView() {
        val rig = Rig(); val activity = HomeActivity()
        rig.screens.callbacks.onActivityResumed(activity) // resumed before the hooks knew about the SDK
        rig.initialize(); rig.screens.initializedFrom(activity)
        rig.finish()
        assertEquals(listOf("HomeActivity"), rig.names())
    }

    @Test fun initializeInApplicationOnCreateGivesOneScreenViewPerActivity() {
        val rig = Rig(); rig.initialize()
        rig.screens.callbacks.onActivityResumed(HomeActivity()); rig.screens.callbacks.onActivityResumed(SettingsActivity())
        rig.screens.callbacks.onActivityResumed(HomeActivity())
        rig.finish()
        assertEquals(listOf("HomeActivity", "SettingsActivity", "HomeActivity"), rig.names())
    }

    @Test fun codeOptionOffDropsAutomaticViewsButNotManualOnes() {
        val rig = Rig(PulseOptions(automaticScreenViews = false)); val activity = HomeActivity()
        rig.initialize(); rig.screens.initializedFrom(activity); rig.screens.callbacks.onActivityResumed(activity)
        rig.client.screen("Manual")
        rig.finish()
        assertEquals(listOf("Manual"), rig.names())
    }

    @Test fun portalScreenViewsOffDropsAutomaticViews() {
        val storage = Memory(); storage.files["config"] = """{"version":1,"screenViews":false}""".toByteArray()
        val rig = Rig(storage = storage); val activity = HomeActivity()
        rig.initialize(); rig.screens.initializedFrom(activity); rig.screens.callbacks.onActivityResumed(activity)
        rig.finish()
        assertEquals(0, rig.count())
    }

    @Test fun nonActivityContextAddsNothing() {
        val rig = Rig(); rig.initialize(); rig.screens.initializedFrom(android.app.Application())
        rig.finish(); assertEquals(0, rig.count())
    }
}
