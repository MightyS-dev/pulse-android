package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private fun env(version: String, build: String) = mapOf("platform" to "mobile", "osName" to "Android", "appVersion" to version, "appBuildNumber" to build)
    private fun launch(storage: FakeStorage, version: String, build: String, atMinutes: Long, options: PulseOptions = PulseOptions()) =
        Harness(options, storage = storage, clock = FakeClock(FakeClock().time + atMinutes * 60000), automatic = true, env = env(version, build))
    private fun updates(h: Harness) = h.allEvents.filter { it["type"] == "pulse.app_updated" }
    private fun config(text: String, storage: FakeStorage) { storage.files["config"] = text.toByteArray() }

    @Test fun restartWithinTheIdleWindowWithANewVersionSendsExactlyOneAppUpdated() {
        val storage = FakeStorage()
        val first = launch(storage, "1.0", "1", 0); first.send("a"); first.client.background(); first.settle()
        val second = launch(storage, "1.1", "2", 5); second.send("b"); second.send("c")
        val change = updates(second).single()
        assertEquals("1.0", (change["props"] as Map<*, *>)["previousVersion"]); assertEquals("1", (change["props"] as Map<*, *>)["previousBuild"])
        val third = launch(storage, "1.1", "2", 8); third.send("d")
        assertTrue(updates(third).isEmpty())
    }

    @Test fun codeSwitchOffKeepsTheOldStoredVersionSoALaterLaunchSendsTheUpdate() {
        val storage = FakeStorage()
        launch(storage, "1.0", "1", 0).send("a")
        val off = launch(storage, "1.1", "2", 60, PulseOptions(automaticSignals = false)); off.send("b")
        assertTrue(updates(off).isEmpty())
        val on = launch(storage, "1.1", "2", 120); on.send("c")
        assertEquals("1.0", (updates(on).single()["props"] as Map<*, *>)["previousVersion"])
    }

    @Test fun portalAutomaticSignalsOffKeepsTheOldStoredVersion() {
        val storage = FakeStorage()
        launch(storage, "1.0", "1", 0).send("a")
        config("""{"version":1,"automaticSignals":false}""", storage)
        val off = launch(storage, "1.1", "2", 60); off.send("b")
        assertTrue(updates(off).isEmpty())
        config("""{"version":2,"automaticSignals":true}""", storage)
        val on = launch(storage, "1.1", "2", 120); on.send("c")
        assertEquals("1", (updates(on).single()["props"] as Map<*, *>)["previousBuild"])
    }

    @Test fun appUpdatedInDisabledSignalsKeepsTheOldStoredVersion() {
        val storage = FakeStorage()
        launch(storage, "1.0", "1", 0).send("a")
        config("""{"version":1,"disabledSignals":["pulse.app_updated"]}""", storage)
        val off = launch(storage, "1.1", "2", 60); off.send("b")
        assertTrue(updates(off).isEmpty()); assertEquals(1, off.allEvents.count { it["type"] == "pulse.session_started" })
        config("""{"version":2,"disabledSignals":[]}""", storage)
        val on = launch(storage, "1.1", "2", 120); on.send("c")
        assertEquals(1, updates(on).size)
    }
}
