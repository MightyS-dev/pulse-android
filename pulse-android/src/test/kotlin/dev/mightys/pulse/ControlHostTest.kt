package dev.mightys.pulse

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

/** The lifecycle adapter: which Activity is current, when the control screen is started, and that it never starts twice. */
class ControlHostTest {
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
    private class Rig(rules: String?, mode: ControlMode = ControlMode.Automatic) {
        val queue = Queue()
        val client = PulseClient(scheduler = queue, transport = PulseTransport { request, done ->
            if (request.control && rules != null) done(PulseResponse(200, rules.toByteArray())) else done(PulseResponse(if (request.control) 404 else 202)); PulseCancellation {}
        })
        val posted = ArrayDeque<Runnable>()
        val launched = mutableListOf<Activity>()
        val host = ControlHost(client.control, { posted.add(it) }, { launched.add(it) })
        init {
            client.initialize("key", PulseOptions(gzip = false, control = mode), Memory(), PulseEnvironment { mapOf("platform" to "mobile", "appVersion" to "2.3.0", "appName" to "Demo") })
            queue.drain()
        }
        fun runPosted() { var n = 0; while (true) { (posted.poll() ?: break).run(); check(n++ < 1000) } }
    }
    private val locked = """{"format":1,"version":1,"off":false,"update":{"android":{"min":"9.0.0","latest":null,"mode":"required","storeVersion":null,"storeUrl":"https://play.google.com/p"}},"blocked":[],"maintenance":null,"announcement":null,"whatsNew":null,"texts":{"custom":{}},"disabledSdkVersions":[]}"""
    private val open = locked.replace(""""min":"9.0.0"""", """"min":null""")

    @Test fun stateComesFromTheClient() {
        assertEquals(ControlState.Kind.UpdateRequired, Rig(locked).client.control.state.kind)
        assertEquals(ControlState.Kind.Normal, Rig(open).client.control.state.kind)
        assertEquals(ControlState.Kind.Normal, Rig(null).client.control.state.kind)
    }

    @Test fun startsTheControlScreenOnceOverTheActivityOnScreen() {
        val rig = Rig(locked); val home = HomeActivity()
        rig.host.onActivityStarted(home); rig.host.reconcile(); rig.runPosted()
        assertEquals(listOf<Activity>(home), rig.launched)
    }

    @Test fun nothingStartsWhileTheAppIsInTheBackground() {
        val rig = Rig(locked); val home = HomeActivity()
        rig.host.reconcile(); rig.runPosted(); assertTrue(rig.launched.isEmpty())
        rig.host.onActivityStarted(home); rig.host.onActivityStopped(home); rig.runPosted()
        rig.launched.clear(); rig.host.reconcile(); rig.runPosted()
        assertTrue("a stopped Activity is not a place to start from", rig.launched.isEmpty())
        assertNull(rig.host.currentActivity())
    }

    @Test fun aSecondReconcileBeforeTheLaunchDoesNotStartItTwice() {
        val rig = Rig(locked); val home = HomeActivity()
        rig.host.onActivityStarted(home); rig.host.reconcile(); rig.host.reconcile(); rig.runPosted()
        assertEquals(1, rig.launched.size)
    }

    @Test fun nothingStartsForNormalOrWhileTheControlScreenIsAlive() {
        val calm = Rig(open); val home = HomeActivity()
        calm.host.onActivityStarted(home); calm.runPosted(); assertTrue(calm.launched.isEmpty())
        val rig = Rig(locked)
        ControlHost.activityCreated()
        try { rig.host.onActivityStarted(home); rig.runPosted(); assertTrue(rig.launched.isEmpty()) } finally { ControlHost.activityDestroyed() }
        rig.host.reconcile(); rig.runPosted(); assertEquals(1, rig.launched.size)
    }

    @Test fun theControlActivityItselfIsNeverTheActivityToStartFrom() {
        val rig = Rig(locked)
        rig.host.onActivityStarted(PulseControlActivity()); rig.runPosted()
        assertTrue(rig.launched.isEmpty()); assertNull(rig.host.currentActivity())
    }

    @Test fun aChangeOfStateStartsTheScreenWithoutAnotherForeground() {
        val rig = Rig(open); val home = HomeActivity()
        rig.host.start(); rig.host.onActivityStarted(home); rig.runPosted()
        assertTrue(rig.launched.isEmpty())
        rig.client.control.checkNow(); rig.queue.drain()
        // the answer is the same rules, so nothing changes and nothing is started
        rig.runPosted(); assertTrue(rig.launched.isEmpty())
        rig.host.stop()
    }

    @Test fun manualAndOffNeverHaveAStateToShowOnTheirOwnScreens() {
        assertEquals(ControlState.Kind.UpdateRequired, Rig(locked, ControlMode.Manual).client.control.state.kind)
        assertEquals(ControlState.Kind.Normal, Rig(locked, ControlMode.Off).client.control.state.kind)
    }
}
