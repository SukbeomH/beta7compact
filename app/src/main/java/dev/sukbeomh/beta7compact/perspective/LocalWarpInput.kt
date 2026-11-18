package dev.sukbeomh.beta7compact.perspective

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Copies a bounded stream without retaining the complete encoded image in memory. */
internal fun copyLocalWarpInput(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
    checkpoint: () -> Unit = {},
): Long {
    require(maxBytes > 0)
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        checkpoint()
        val count = input.read(buffer)
        checkpoint()
        if (count < 0) return copied
        if (count == 0) continue
        if (count.toLong() > maxBytes - copied) throw IOException("Experiment input size limit exceeded")
        output.write(buffer, 0, count)
        copied += count
    }
}
