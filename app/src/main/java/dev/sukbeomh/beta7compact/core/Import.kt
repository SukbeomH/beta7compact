package dev.sukbeomh.beta7compact.core

import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Only immutable, supported still-image objects enter the queue. No object/delete operations. */
data class CameraObject(val handle: Int, val storage: Int, val name: String, val size: Long,
                        val captured: Long, val parent: Int = 0, val cameraTime: String? = null) {
    val mime: String? get() = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "arw" -> "image/x-sony-arw"
        "hif", "heif" -> "image/heif"
        else -> null
    }
    val valid: Boolean get() = mime != null && size in 1..0xfffffff0L &&
        name.isNotBlank() && name.length <= 255 && name.none { it == '/' || it == '\\' || it.code < 32 }
    // null is reserved for v1 records, whose original camera clock was not persisted.
    val key: String get() = identity(cameraTime ?: captured.toString())
    private fun identity(time: String) = digest("$name\u0000$size\u0000$time")
    val pair: String get() = digest("$storage/$parent/${name.substringBeforeLast('.').lowercase()}/${cameraTime ?: captured}")
    val compatibleKeys: Set<String> by lazy {
        if (cameraTime != null) setOf(key) else setOf(key, "legacy:$key")
    }
    private val legacyKeys: Set<String> by lazy {
        val wall = runCatching { LocalDateTime.parse(cameraTime?.take(15), clock) }.getOrNull()
        val epochs = if (wall == null) setOf(0L) else buildSet {
            // v1 discarded the original clock and timezone. Compare all possible old interpretations.
            for (zone in ZoneId.getAvailableZoneIds()) {
                val rules = ZoneId.of(zone)
                add(wall.atZone(rules).toInstant().toEpochMilli())
                val transition = rules.rules.getTransition(wall)
                if (transition?.isGap == true) {
                    // Native mktime may resolve a nonexistent DST wall clock in either direction.
                    add(wall.minusSeconds(transition.duration.seconds).atZone(rules).toInstant().toEpochMilli())
                }
            }
        }
        epochs.map { identity(it.toString()) }.toSet()
    }
    fun isKnown(keys: Set<String>): Boolean = key in keys ||
        (cameraTime != null && keys.any { it.startsWith("legacy:") } && legacyKeys.any { "legacy:$it" in keys })
    fun matchesQueued(queued: CameraObject): Boolean = key == queued.key ||
        (queued.cameraTime == null && queued.key in legacyKeys)
    companion object {
        const val PATH = "DCIM/beta7compact/"
        private val clock = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
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
        objects.filter { it.valid && !it.isKnown(completed) && (selected == null || it.key in selected) }
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
        val current = camera.list().filter { it.valid }.distinctBy { it.key }
        temp.parentFile?.mkdirs()
        try {
            queue.forEachIndexed { index, queued ->
                check(active()) { "가져오기가 중단되었습니다" }
                val previous = journal.uri(queued.key)
                if (previous != null && store.recover(previous, queued.size)) {
                    journal.completed(queued.key, previous)
                } else {
                    if (previous != null) store.discard(previous)
                    val matches = current.filter { it.matchesQueued(queued) }
                    check(matches.size <= 1) { "기존 대기 사진의 촬영시각이 모호합니다. 대기열을 확인하세요" }
                    val item = checkNotNull(matches.singleOrNull()) { "카드에서 대기 사진을 찾을 수 없습니다" }
                    temp.delete()
                    camera.download(item, temp)
                    check(active()) { "가져오기가 중단되었습니다" }
                    check(temp.length() == item.size) { "원본 크기가 일치하지 않습니다" }
                    val uri = store.begin(item)
                    try { journal.staged(queued.key, uri) }
                    catch (e: Throwable) { store.discard(uri); throw e }
                    // On interruption the journal retains this URI. Retry validates or deletes it.
                    store.write(uri, temp)
                    check(active()) { "가져오기가 중단되었습니다" }
                    store.publish(uri, item.size)
                    journal.completed(queued.key, uri)
                }
                progress(index + 1, queue.size)
            }
        } finally { temp.delete() }
    }
}
