package dev.sukbeomh.beta7compact.core

import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only immutable, supported still-image objects enter the queue. No object/delete operations. */
data class CameraObject(val handle: Int, val storage: Int, val name: String, val size: Long,
                        val captured: Long, val parent: Int = 0) {
    val mime: String? get() = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "arw" -> "image/x-sony-arw"
        "hif", "heif" -> "image/heif"
        else -> null
    }
    val valid: Boolean get() = mime != null && size in 1..0xfffffff0L &&
        name.isNotBlank() && name.length <= 255 && name.none { it == '/' || it == '\\' || it.code < 32 }
    val key: String get() = digest("$name\u0000$size\u0000$captured")
    val pair: String get() = digest("$storage/$parent/${name.substringBeforeLast('.').lowercase()}/$captured")
    companion object {
        const val PATH = "DCIM/beta7compact/"
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun ptpTime(value: String): Long = if (value.isBlank()) 0 else runCatching {
            LocalDateTime.parse(value.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrDefault(0)
    }
}

interface Camera : Closeable {
    fun list(): List<CameraObject>
    fun thumbnail(item: CameraObject): ByteArray?
    fun download(item: CameraObject, destination: File)
}

object ImportPlan {
    fun create(objects: List<CameraObject>, completed: Set<String>, selected: Set<String>? = null) =
        objects.filter { it.valid && it.key !in completed && (selected == null || it.key in selected) }
            .distinctBy { it.key }.sortedWith(compareBy<CameraObject> { it.captured }.thenBy { it.name })
}

enum class LinkState { DISCONNECTED, DISCOVERED, CONNECTING, SESSION, FAILED }
enum class LinkEvent { FOUND, OPEN, READY, LOST, ERROR }
fun transition(state: LinkState, event: LinkEvent): LinkState = when (event) {
    LinkEvent.LOST -> LinkState.DISCONNECTED
    LinkEvent.ERROR -> LinkState.FAILED
    LinkEvent.FOUND -> if (state == LinkState.DISCONNECTED || state == LinkState.FAILED) LinkState.DISCOVERED else state
    LinkEvent.OPEN -> { check(state == LinkState.DISCOVERED); LinkState.CONNECTING }
    LinkEvent.READY -> { check(state == LinkState.CONNECTING); LinkState.SESSION }
}

/** The Android implementation journals pending URIs before any publication. */
interface ImportJournal {
    suspend fun pending(): List<CameraObject>
    suspend fun uri(key: String): String?
    suspend fun staged(key: String, uri: String)
    suspend fun completed(key: String, uri: String)
}
interface OriginalStore {
    fun recover(uri: String, size: Long): Boolean
    fun begin(item: CameraObject): String
    fun write(uri: String, source: File)
    fun publish(uri: String, size: Long)
    fun discard(uri: String)
}

class ImportPipeline(private val journal: ImportJournal, private val store: OriginalStore,
                     private val temp: File) {
    suspend fun run(camera: Camera, active: () -> Boolean = { true }, progress: suspend (Int, Int) -> Unit = { _, _ -> }) {
        val queue = journal.pending()
        val current = camera.list().filter { it.valid }.associateBy { it.key }
        temp.parentFile?.mkdirs()
        try {
            queue.forEachIndexed { index, queued ->
                check(active()) { "가져오기가 중단되었습니다" }
                val previous = journal.uri(queued.key)
                if (previous != null && store.recover(previous, queued.size)) {
                    journal.completed(queued.key, previous)
                } else {
                    if (previous != null) store.discard(previous)
                    val item = checkNotNull(current[queued.key]) { "카드에서 대기 사진을 찾을 수 없습니다" }
                    temp.delete()
                    camera.download(item, temp)
                    check(active()) { "가져오기가 중단되었습니다" }
                    check(temp.length() == item.size) { "원본 크기가 일치하지 않습니다" }
                    val uri = store.begin(item)
                    try { journal.staged(item.key, uri) }
                    catch (e: Throwable) { store.discard(uri); throw e }
                    // On interruption the journal retains this URI. Retry validates or deletes it.
                    store.write(uri, temp)
                    check(active()) { "가져오기가 중단되었습니다" }
                    store.publish(uri, item.size)
                    journal.completed(item.key, uri)
                }
                progress(index + 1, queue.size)
            }
        } finally { temp.delete() }
    }
}
