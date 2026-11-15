package dev.sukbeomh.beta7compact.core

import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
    // v1 and native-only metadata have no original camera DateTime; retain conservative aliases.
    val key: String get() = identity(cameraTime ?: captured.toString())
    private fun identity(time: String) = digest("$name\u0000$size\u0000$time")
    val pair: String get() = digest("$storage/$parent/${name.substringBeforeLast('.').lowercase()}/${cameraTime ?: captured}")
    val compatibleKeys: Set<String> by lazy {
        // MTP aliases never alias two exact raw clocks through isKnown().
        if (cameraTime != null) setOf(key) + legacyKeys.map { "mtp:$it" }
        else setOf(key, "legacy:$key")
    }
    private val legacyKeys: Set<String> by lazy {
        val walls = if (cameraTime != null) listOfNotNull(
            runCatching { LocalDateTime.parse(cameraTime?.take(15), clock) }.getOrNull())
        else if (captured > 0) listOf(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(captured), ZoneId.systemDefault()),
            LocalDateTime.ofInstant(Instant.ofEpochMilli(captured), ZoneOffset.UTC))
        else emptyList()
        val epochs = if (walls.isEmpty()) setOf(0L) else buildSet {
            add(captured)
            // v1 discarded the original clock and timezone. Compare all possible old interpretations.
            for (wall in walls) for (zone in ZoneId.getAvailableZoneIds()) {
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
        (cameraTime == null && "mtp:$key" in keys) ||
        (keys.any { it.startsWith("legacy:") } && legacyKeys.any { "legacy:$it" in keys })
    fun matchesQueued(queued: CameraObject): Boolean = key == queued.key ||
        (queued.cameraTime == null && queued.key in legacyKeys) ||
        (cameraTime == null && queued.cameraTime != null && key in queued.legacyKeys)
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
    suspend fun completedItems(): List<CameraObject>
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
        if (queue.isEmpty()) return
        val current = camera.list().filter { it.valid }.distinct()
        // Resolve the entire queue before any download/publication. Different handles with the
        // same identity remain separate candidates: silently collapsing them would hide ambiguity.
        fun matches(queued: CameraObject) = current.filter { it.matchesQueued(queued) }
        val groups = queue.groupBy { queued ->
            val candidates = matches(queued)
            check(candidates.size <= 1) { "기존 대기 사진의 촬영시각이 모호합니다. 대기열을 확인하세요" }
            checkNotNull(candidates.singleOrNull()) { "카드에서 대기 사진을 찾을 수 없습니다" }
        }
        val completed = mutableMapOf<CameraObject, MutableList<CameraObject>>()
        for (record in journal.completedItems()) {
            val candidates = matches(record)
            if (candidates.any { it in groups }) {
                check(candidates.size == 1) { "기존 완료 사진의 촬영시각이 모호합니다. 대기열을 확인하세요" }
                completed.getOrPut(candidates.single()) { mutableListOf() }.add(record)
            }
        }
        temp.parentFile?.mkdirs()
        try {
            var done = 0
            for ((item, pending) in groups) {
                check(active()) { "가져오기가 중단되었습니다" }
                val finished = completed[item].orEmpty()
                val previous = (finished + pending).mapNotNull { record ->
                    val uri = journal.uri(record.key)
                    check(uri != null || record !in finished) { "완료 원본의 URI를 확인할 수 없습니다. 갤러리 상태를 확인하세요" }
                    uri?.let { record.key to it }
                }.toMap()
                val recovered = previous.values.distinct().filter { store.recover(it, item.size) }.toSet()
                check(finished.all { previous[it.key] in recovered }) {
                    "완료 원본을 확인할 수 없습니다. 갤러리 상태를 확인하세요"
                }
                // Only unpublished temporary rows may be discarded. Preserve every published URI.
                previous.values.distinct().filter { it !in recovered }.forEach(store::discard)
                val uri = recovered.firstOrNull() ?: run {
                    temp.delete()
                    camera.download(item, temp)
                    check(active()) { "가져오기가 중단되었습니다" }
                    check(temp.length() == item.size) { "원본 크기가 일치하지 않습니다" }
                    val uri = store.begin(item)
                    // All aliases own the same staged URI before publication, including crash retries.
                    try { pending.forEach { journal.staged(it.key, uri) } }
                    catch (e: Throwable) { store.discard(uri); throw e }
                    // On interruption the journal retains this URI. Retry validates or deletes it.
                    store.write(uri, temp)
                    check(active()) { "가져오기가 중단되었습니다" }
                    store.publish(uri, item.size)
                    uri
                }
                for (queued in pending) {
                    // A pending alias may already reference a different published original. Keep it.
                    journal.completed(queued.key, previous[queued.key]?.takeIf { it in recovered } ?: uri)
                    progress(++done, queue.size)
                }
            }
        } finally { temp.delete() }
    }
}
