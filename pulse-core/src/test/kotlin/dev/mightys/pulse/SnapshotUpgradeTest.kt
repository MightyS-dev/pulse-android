package dev.mightys.pulse

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Queue files written by the previous release (format 1, no batch id) must still load and send. */
class SnapshotUpgradeTest {
    private val now = 1_794_556_800_000L
    private fun entry(id: String): Entry = Entry(id, now,
        SignalJson.encode(Input(now, "session", null, "event.$id", null, emptyMap()), emptyMap(), false)!!)

    /** The previous release's encoder, copied verbatim apart from the test-local helper. */
    private fun encodeFormat1(entries: List<Entry>, pending: List<Batch>): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            fun blob(bytes: ByteArray) { data.writeInt(bytes.size); data.write(bytes) }
            data.writeInt(1); data.writeUTF(""); data.writeDouble(20.0); data.writeLong(0)
            data.writeInt(entries.size)
            entries.forEach { data.writeUTF(it.id); data.writeLong(it.timestamp); blob(it.json) }
            data.writeInt(pending.size)
            pending.forEach { batch ->
                data.writeInt(batch.ids.size); batch.ids.forEach(data::writeUTF)
                blob(batch.json); blob(batch.wire); data.writeBoolean(batch.gzip)
                data.writeInt(batch.failures); data.writeLong(batch.retryAt)
            }
        }
        val payload = output.toByteArray()
        return byteArrayOf(77, 80, 75, 49) + Hash.bytes(payload) + payload
    }

    private fun fixture(): Triple<List<Entry>, Batch, ByteArray> {
        val entries = listOf(entry("a"), entry("b"), entry("c"))
        val json = SignalJson.batch(entries.take(2))
        val batch = Batch(listOf("a", "b"), json, json, false, 2, now)
        return Triple(entries, batch, encodeFormat1(entries, listOf(batch)))
    }

    @Test fun formatOneSnapshotDecodesWithPendingBatchGettingAFreshValidId() {
        val (entries, batch, bytes) = fixture()
        val restored = Snapshot.decode(bytes)!!
        assertEquals(3, restored.entries.size)
        val pending = restored.pending.single()
        assertEquals(listOf("a", "b"), pending.ids); assertArrayEquals(batch.json, pending.json)
        assertEquals(2, pending.failures); assertTrue(BatchId.valid(pending.batchId))
        assertEquals(entries.map { it.id }, restored.entries.map { it.id })
    }

    @Test fun formatOneQueueIsLoadedAndEverySignalIsSentAfterUpgrade() {
        val (_, batch, bytes) = fixture()
        val storage = FakeStorage()
        val h = Harness(storage = storage, initialize = false)
        storage.files["queue-" + Hash.hex("synthetic-key") + ".bin"] = bytes
        h.initialize(); h.flush(); h.flush()
        val sent = h.transport.requests
        assertArrayEquals(batch.json, Harness.json(sent.first()))
        assertTrue(BatchId.valid(sent.first().batchId!!))
        val types = h.allEvents.map { it["type"] }
        for (name in listOf("event.a", "event.b", "event.c")) assertTrue(name, name in types)
        assertEquals(sent.size, sent.map { it.batchId }.toSet().size)
    }
}
