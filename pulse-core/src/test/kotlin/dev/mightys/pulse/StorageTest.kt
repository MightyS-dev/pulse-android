package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StorageTest {
    private val now = 1_794_556_800_000L
    private fun entry(id: String, at: Long = now): Entry = Entry(id, at,
        SignalJson.encode(Input(at, "session", null, "event.$id", null, emptyMap()), emptyMap(), false)!!)

    @Test fun snapshotPersistsExactPendingBytesAndAdvice() {
        val entries = listOf(entry("one"), entry("two"))
        val json = SignalJson.batch(entries)
        val batch = Batch(entries.map { it.id }, json, Gzip.encode(json), true, 5, now + 60000)
        val original = Snapshot("", entries, listOf(batch), 30.0, now + 30000)
        val restored = Snapshot.decode(original.encode())!!
        assertEquals(2, restored.entries.size); assertEquals(30.0, restored.interval, 0.0)
        assertEquals(now + 30000, restored.pauseUntil)
        assertArrayEquals(batch.wire, restored.pending.single().wire)
        assertArrayEquals(batch.json, restored.pending.single().json)
        assertEquals(5, restored.pending.single().failures)
        assertEquals(now + 60000, restored.pending.single().retryAt)
    }

    @Test fun corruptedTruncatedAndInconsistentSnapshotsAreDropped() {
        val good = Snapshot("", listOf(entry("one")), emptyList(), 20.0, 0).encode()
        for (size in listOf(0, 3, 35, good.size - 1)) assertNull(Snapshot.decode(good.copyOf(size)))
        val corrupt = good.clone(); corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
        assertNull(Snapshot.decode(corrupt))
        assertNull(Snapshot.decode(Snapshot("", listOf(entry("one"), entry("one")), emptyList(), 20.0, 0).encode()))
        val bad = Batch(listOf("missing"), "[]".toByteArray(), "[]".toByteArray(), false)
        assertNull(Snapshot.decode(Snapshot("", listOf(entry("one")), listOf(bad), 20.0, 0).encode()))
    }

    @Test fun realFileStorageAtomicReplaceIgnoresAbandonedTempAndRestarts() {
        val root = Files.createTempDirectory("pulse-storage-test").toFile()
        try {
            val storage = PulseFileStorage(root)
            assertNull(storage.read("queue.bin"))
            val snapshot = Snapshot("", listOf(entry("saved")), emptyList(), 20.0, 0)
            storage.replace("queue.bin", snapshot.encode())
            File(root, "queue.bin.tmp").writeText("torn")
            val restored = Snapshot.decode(PulseFileStorage(root).read("queue.bin")!!)!!
            assertEquals("saved", restored.entries.single().id)
            storage.replace("queue.bin", byteArrayOf(4)); assertArrayEquals(byteArrayOf(4), storage.read("queue.bin"))
            storage.remove("queue.bin"); storage.remove("queue.bin"); assertNull(storage.read("queue.bin"))
        } finally { root.deleteRecursively() }
    }

    @Test fun queueBoundsDropOldestAndExpireInvalidTimestamps() {
        val queue = DurableQueue(PulseOptions(maxQueueSize = 3))
        for (i in 0..4) queue.entries.add(entry("$i"))
        queue.prune(now)
        assertEquals(listOf("2", "3", "4"), queue.entries.map { it.id })
        queue.entries.add(entry("expired", now - 8 * 86400000L))
        queue.entries.add(entry("future", now + 3600001))
        queue.prune(now)
        assertEquals(listOf("2", "3", "4"), queue.entries.map { it.id })
    }

    @Test fun batchingRespectsCountAndOneMiBAndNeverMutatesAttemptedBodies() {
        val queue = DurableQueue(PulseOptions(maxBatchSize = 2))
        queue.entries.addAll((0..4).map { entry("$it") })
        val batch = queue.makeBatch(queue.entries, false)
        assertEquals(listOf("0", "1"), batch.ids)
        val huge = DurableQueue(PulseOptions(maxBatchSize = 1000))
        val props = (0..6).associate { "k$it" to "x".repeat(1024) }
        repeat(300) { i -> huge.entries.add(Entry("$i", now,
            SignalJson.encode(Input(now, "s", null, "large", null, props), emptyMap(), false)!!)) }
        val capped = huge.makeBatch(huge.entries, false)
        assertTrue(capped.json.size <= 1048576); assertTrue(capped.ids.size < 300)
        assertArrayEquals(capped.json, capped.wire)
    }

    @Test fun retiringPrunedBatchPreservesRemainingMembersForRebatching() {
        val queue = DurableQueue(PulseOptions(maxQueueSize = 2))
        queue.entries.addAll(listOf(entry("one"), entry("two")))
        queue.pending.add(queue.makeBatch(queue.entries, false))
        queue.entries.add(entry("three"))
        assertTrue(queue.prune(now)); assertTrue(queue.pending.isEmpty())
        assertEquals(listOf("two", "three"), queue.entries.map { it.id })
    }

    @Test fun sessionRotatesAtThirtyMinutesAndNewProcessAndUserNeverStoresRaw() {
        val state = CaptureState()
        state.setUser("private.raw")
        val first = state.capture(now, "one", null, emptyMap())
        val second = state.capture(now + 1799999, "two", null, emptyMap())
        assertEquals(first.session, second.session); assertEquals(Hash.hex("private.raw"), first.userHash)
        state.setUser(null)
        val third = state.capture(now + 3599999, "three", null, emptyMap())
        assertNotEquals(second.session, third.session); assertNull(third.userHash)
        assertNotEquals(third.session, CaptureState().capture(now, "new", null, emptyMap()).session)
    }

    @Test fun gzipIsDeterministicAndRoundTripsAtLimits() {
        for (size in listOf(0, 1025, 65536, 1048576)) {
            val input = ByteArray(size) { (it * 31).toByte() }
            val compressed = Gzip.encode(input)
            assertArrayEquals(compressed, Gzip.encode(input)); assertArrayEquals(input, Gzip.decode(compressed))
        }
        assertNull(Gzip.decode("bad".toByteArray()))
        val good = Gzip.encode(ByteArray(2048))
        assertNull(Gzip.decode(good.copyOf(good.size - 1)))
    }
}
