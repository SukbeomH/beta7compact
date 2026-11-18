package dev.sukbeomh.beta7compact.library

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

object ExternalImagePolicy {
    const val MAX_BYTES = 64L * 1024 * 1024
    const val MAX_ITEMS = 50
    val extensions = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp",
        "image/heic" to "heic", "image/heif" to "heif", "image/avif" to "avif")
    fun name(displayName: String?, mime: String): String {
        val extension = extensions[mime] ?: error("지원하지 않는 이미지 형식입니다")
        val fileName = displayName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val base = fileName.substringBeforeLast('.',fileName).filterNot { it.isISOControl() || it=='/' || it=='\\' }
            .trim().take(120).ifBlank { "external-photo" }
        return "$base.$extension"
    }
}
val Photo.isExternal: Boolean get() = key.startsWith("external:")
data class ExternalImageBytes(val size: Long, val hash: String) { val key get() = "external:$hash" }
fun copyExternalImage(input: InputStream, output: OutputStream, maxBytes: Long = ExternalImagePolicy.MAX_BYTES,
    checkpoint: () -> Unit = {}): ExternalImageBytes {
    require(maxBytes > 0)
    val digest = MessageDigest.getInstance("SHA-256");val buffer = ByteArray(64*1024);var total = 0L
    while(true) {
        checkpoint()
        val count = input.read(buffer)
        if(count < 0) break
        require(count > 0) { "이미지 읽기가 진행되지 않습니다" }
        require(count <= maxBytes-total) { "이미지는 한 장당 64MiB까지 추가할 수 있습니다" }
        output.write(buffer,0,count);digest.update(buffer,0,count);total += count
    }
    require(total > 0) { "비어 있는 이미지는 추가할 수 없습니다" }
    return ExternalImageBytes(total,digest.digest().joinToString("") { "%02x".format(it) })
}

/** Only a newly allocated app-owned destination is cleaned up; never the selected source. */
suspend fun <T> publishExternalImage(create: () -> T, write: suspend (T) -> Unit,
    publish: suspend (T) -> Unit, record: suspend (T) -> Unit, delete: suspend (T) -> Unit): T {
    val destination = create();var committed = false
    try {
        write(destination);coroutineContext.ensureActive()
        withContext(NonCancellable) {
            publish(destination);record(destination);committed = true
        }
        return destination
    } finally {
        if(!committed) withContext(NonCancellable) { delete(destination) }
    }
}
