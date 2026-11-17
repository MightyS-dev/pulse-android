package dev.mightys.pulse

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Storage seam. Missing files return null; unreadable files must throw so privacy fails closed. */
public interface PulseStorage {
    /** Read a bounded SDK file, or return null only when missing. */
    public fun read(name: String): ByteArray?
    /** Replace an entire file atomically; failures must throw. */
    public fun replace(name: String, bytes: ByteArray)
    /** Remove a file if present; failures must throw. */
    public fun remove(name: String)
}

/** Single-process storage using synced temporary files and atomic same-directory renames. */
public class PulseFileStorage(private val directory: File) : PulseStorage {
    private fun file(name: String): File {
        require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it in ".-_" } && !name.startsWith('.'))
        return File(directory, name)
    }

    override fun read(name: String): ByteArray? {
        val file = file(name)
        val limit = when (name) { "opt-out" -> 2; "privacy-generation" -> 37; "install-id" -> 33; "usage", "config" -> 65536; "crash-report" -> 32768; else -> if (name.startsWith("control-")) 70000 else Snapshot.MAX_BYTES + 1 }
        val input = try { FileInputStream(file) } catch (error: java.io.FileNotFoundException) {
            if (!file.exists() && (!directory.exists() || directory.isDirectory)) return null
            throw error
        }
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() < limit) {
                val count = it.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    override fun replace(name: String, bytes: ByteArray) {
        val destination = file(name)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("SDK storage unavailable")
        val temporary = File(directory, name + "." + UUID.randomUUID() + ".tmp")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            if (!temporary.renameTo(destination)) throw IOException("Atomic SDK replacement failed")
        } finally { temporary.delete() }
    }

    override fun remove(name: String) {
        val file = file(name)
        if (file.exists() && !file.delete()) throw IOException("SDK removal failed")
    }
}
