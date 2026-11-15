package dev.mightys.pulse

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object Gzip {
    fun encode(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
    }.toByteArray()

    fun decode(bytes: ByteArray): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= SignalJson.MAX_BODY)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (_: Exception) { null }
}

internal data class Batch(val ids: List<String>, val json: ByteArray, val wire: ByteArray, val gzip: Boolean,
    val failures: Int = 0, val retryAt: Long = 0)

internal data class Snapshot(val generation: String, val entries: List<Entry>, val pending: List<Batch>,
    val interval: Double, val pauseUntil: Long) {
    fun encode(): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(1); data.writeUTF(generation); data.writeDouble(interval); data.writeLong(pauseUntil)
            data.writeInt(entries.size)
            entries.forEach { data.writeUTF(it.id); data.writeLong(it.timestamp); data.blob(it.json) }
            data.writeInt(pending.size)
            pending.forEach { batch ->
                data.writeInt(batch.ids.size); batch.ids.forEach(data::writeUTF)
                data.blob(batch.json); data.blob(batch.wire); data.writeBoolean(batch.gzip)
                data.writeInt(batch.failures); data.writeLong(batch.retryAt)
            }
        }
        val payload = output.toByteArray()
        return MAGIC + Hash.bytes(payload) + payload
    }

    companion object {
        const val MAX_BYTES = 256 * 1024 * 1024
        private val MAGIC = byteArrayOf(77, 80, 75, 49)
        private fun DataOutputStream.blob(bytes: ByteArray) { writeInt(bytes.size); write(bytes) }
        private fun DataInputStream.blob(): ByteArray {
            val count = readInt()
            require(count in 0..SignalJson.MAX_BODY && count <= available())
            return ByteArray(count).also(::readFully)
        }
        private fun DataInputStream.count(max: Int): Int = readInt().also { require(it in 0..max) }

        fun decode(bytes: ByteArray): Snapshot? = try {
            require(bytes.size in 36..MAX_BYTES && bytes.copyOfRange(0, 4).contentEquals(MAGIC))
            val payload = bytes.copyOfRange(36, bytes.size)
            require(Hash.bytes(payload).contentEquals(bytes.copyOfRange(4, 36)))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == 1)
                val generation = input.readUTF()
                require(generation.isEmpty() || UUID.fromString(generation).toString() == generation)
                val interval = input.readDouble(); val pauseUntil = input.readLong()
                require(interval.isFinite() && interval > 0 && interval <= 3600)
                val entries = List(input.count(10000)) { Entry(input.readUTF(), input.readLong(), input.blob()) }
                val pending = List(input.count(1000)) {
                    Batch(List(input.count(1000)) { input.readUTF() }, input.blob(), input.blob(),
                        input.readBoolean(), input.readInt(), input.readLong())
                }
                require(input.available() == 0)
                Snapshot(generation, entries, pending, interval, pauseUntil).also { it.validate() }
            }
        } catch (_: Exception) { null }
    }

    private fun validate() {
        val byId = entries.associateBy { it.id }
        require(byId.size == entries.size)
        for (entry in entries) {
            require(entry.json.size + 2 <= SignalJson.MAX_BODY)
            val objectValue = JsonReader.utf8(entry.json)?.let(JsonReader::parse) as? Map<*, *>
            require(objectValue?.get("timestamp") is String && objectValue["type"] is String)
        }
        val seen = hashSetOf<String>()
        for (batch in pending) {
            require(batch.ids.isNotEmpty() && batch.failures in 0..1000000)
            val members = batch.ids.map { id -> require(seen.add(id)); requireNotNull(byId[id]) }
            require(SignalJson.batch(members).contentEquals(batch.json))
            require(batch.json.size <= SignalJson.MAX_BODY && batch.wire.size <= SignalJson.MAX_BODY)
            require((if (batch.gzip) Gzip.decode(batch.wire) else batch.wire)?.contentEquals(batch.json) == true)
        }
    }
}
