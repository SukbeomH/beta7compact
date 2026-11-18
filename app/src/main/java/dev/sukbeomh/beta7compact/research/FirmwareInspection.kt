package dev.sukbeomh.beta7compact.research

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class FirmwareInspection(val size: Long, val sha256: String, val headerHex: String)

/** Reads a caller-owned stream without interpreting or changing firmware. */
fun inspectFirmware(input: InputStream, checkCancelled: () -> Unit = {}): FirmwareInspection {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    val header = ByteArray(64)
    var headerSize = 0
    var total = 0L
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count == -1) break
        if (count <= 0 || count > buffer.size) throw IOException("File read made no progress")
        digest.update(buffer, 0, count)
        val keep = minOf(count, header.size - headerSize)
        buffer.copyInto(header, headerSize, 0, keep)
        headerSize += keep
        total = Math.addExact(total, count.toLong())
    }
    checkCancelled()
    return FirmwareInspection(total, digest.digest().hex(""), header.copyOf(headerSize).hex(" "))
}

private fun ByteArray.hex(separator: String) = joinToString(separator) { "%02x".format(it.toInt() and 255) }
