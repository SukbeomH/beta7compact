package dev.sukbeomh.beta7compact.cache

import java.io.File
import java.security.MessageDigest

/** App-private, disposable cache; failures never prevent camera browsing. */
class DiskThumbnailCache(private val directory: File, private val maxBytes: Long = 128L * 1024 * 1024,
    private val maxEntryBytes: Int = 4 * 1024 * 1024) {
    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun file(key: String) = File(directory, hash(key) + ".thumb")
    @Synchronized fun get(key: String): ByteArray? = runCatching {
        val target = file(key)
        if (!target.isFile || target.length() !in 1..maxEntryBytes.toLong()) return@runCatching null
        target.readBytes().also { target.setLastModified(System.currentTimeMillis()) }
    }.getOrNull()
    @Synchronized fun remove(key: String) { runCatching { file(key).delete() } }
    @Synchronized fun put(key: String, data: ByteArray) {
        if (data.isEmpty() || data.size > maxEntryBytes || data.size > maxBytes) return
        runCatching {
            directory.mkdirs()
            val target = file(key)
            val temp = File(directory, "pending.tmp")
            try {
                // Reserve space before writing, including the temporary file in the budget.
                trim((maxBytes - data.size).coerceAtLeast(0), target)
                temp.writeBytes(data)
                if (!temp.renameTo(target)) return@runCatching
                target.setLastModified(System.currentTimeMillis())
                trim(maxBytes, null)
            } finally { temp.delete() }
        }
    }
    private fun trim(budget: Long, replacing: File?) {
        replacing?.delete()
        val entries = directory.listFiles()?.filter { it.extension == "thumb" }?.sortedBy { it.lastModified() }.orEmpty()
        var bytes = entries.sumOf { it.length() }
        for (entry in entries) {
            if (bytes <= budget) break
            val size = entry.length()
            if (entry.delete()) bytes -= size
        }
    }
}
