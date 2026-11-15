package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test

class InstallIdTest {
    private fun id(storage: FakeStorage) = storage.files["install-id"]!!.toString(Charsets.UTF_8)

    @Test fun installIdIsCreatedOnceAndReusedAfterReinitialization() {
        val storage = FakeStorage(); val first = Harness(storage = storage); first.send("one")
        val saved = id(storage)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(saved))
        val second = Harness(storage = storage); second.send("two")
        assertEquals(saved, id(storage))
        assertEquals(Hash.hex(saved), second.events.single()["userHash"])
    }

    @Test fun userHashIsPresentWithoutSetUserAndEqualsHashOfInstallId() {
        val storage = FakeStorage(); val h = Harness(storage = storage)
        h.send("event"); h.send("again")
        assertEquals(setOf(Hash.hex(id(storage))), h.events.map { it["userHash"] }.toSet())
    }

    @Test fun signalsCapturedBeforeInitializeAlsoCarryTheInstallIdHash() {
        val storage = FakeStorage(); val h = Harness(storage = storage, initialize = false)
        h.client.track("early"); h.initialize(); h.flush()
        assertEquals(Hash.hex(id(storage)), h.events.single()["userHash"])
    }

    @Test fun setUserOverridesAndNullRestoresInstallIdHash() {
        val storage = FakeStorage(); val h = Harness(storage = storage)
        h.send("anonymous"); val anonymous = h.events.last()["userHash"]
        h.client.setUser("own.id"); h.send("own"); assertEquals(Hash.hex("own.id"), h.events.last()["userHash"])
        h.client.setUser(null); h.send("restored"); assertEquals(anonymous, h.events.last()["userHash"])
        h.client.setUser(""); h.send("empty"); assertEquals(anonymous, h.events.last()["userHash"])
    }

    @Test fun optOutDeletesIdAndOptOutOffCreatesADifferentOne() {
        val storage = FakeStorage(); val h = Harness(storage = storage)
        h.send("one"); val before = id(storage)
        h.client.optOut = true; h.settle()
        assertNull(storage.files["install-id"])
        h.client.optOut = false; h.settle()
        assertNull(storage.files["install-id"])
        h.send("two")
        assertNotEquals(before, id(storage))
        assertEquals(Hash.hex(id(storage)), h.events.last()["userHash"])
    }

    @Test fun unreadableOrUnwritableStorageStillGivesAUserHashWithoutThrowing() {
        val unwritable = FakeStorage(); val one = Harness(storage = unwritable)
        unwritable.failWrites = true
        one.client.track("a"); one.settle()
        unwritable.failWrites = false
        one.advance(1000); one.flush()
        assertTrue((one.events.single()["userHash"] as String).matches(Regex("^[0-9a-f]{64}$")))
        val broken = FakeStorage(); val h = Harness(storage = broken)
        broken.files["install-id"] = "not a valid id".toByteArray()
        h.send("b")
        assertTrue((h.events.single()["userHash"] as String).matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(id(broken)))
    }

    @Test fun systemContextFieldsPassThroughNonEmpty() {
        val storage = FakeStorage(); val h = Harness(storage = storage, initialize = false)
        val fields = mapOf("platform" to "mobile", "osName" to "Android", "osVersion" to "16", "appVersion" to "1.0",
            "deviceModel" to "Pixel", "locale" to "en-US")
        h.client.initialize("synthetic-key", h.options, storage, PulseEnvironment { fields }); h.settle(); h.send("x")
        val system = h.events.single()["system"] as Map<*, *>
        for (key in fields.keys) assertTrue(key, !(system[key] as? String).isNullOrEmpty())
    }

    @Test fun signalsTrackedBeforeAndRightAfterInitializeCarryFullSystemValues() {
        val storage = FakeStorage(); val h = Harness(storage = storage, initialize = false)
        val fields = mapOf("platform" to "mobile", "osName" to "Android", "osVersion" to "16", "appVersion" to "1.0",
            "deviceModel" to "Pixel", "locale" to "en-US")
        h.client.track("before")
        h.client.initialize("synthetic-key", h.options, storage, PulseEnvironment { fields })
        h.client.track("right.after"); h.flush()
        assertEquals(2, h.events.size)
        for (event in h.events) {
            val system = event["system"] as Map<*, *>
            for (key in fields.keys) assertTrue(key, !(system[key] as? String).isNullOrEmpty())
        }
    }
}
