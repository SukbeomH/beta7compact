package dev.sukbeomh.beta7compact.core

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Only immutable, supported still-image objects enter the queue. No object/delete operations. */
data class CameraObject(val handle: Int, val storage: Int, val name: String, val size: Long,
                        val captured: Long, val parent: Int = 0, val cameraTime: String? = null,
                        val recordKey: String? = null) {
    val mime: String? get() = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "arw" -> "image/x-sony-arw"
        "hif", "heif" -> "image/heif"
        else -> null
    }
    val valid: Boolean get() = mime != null && size in 1..0xfffffff0L &&
        name.isNotBlank() && name.length <= 255 && name.none { it == '/' || it == '\\' || it.code < 32 }
    // Metadata hashes identify records, never the contents. Keep migrated primary keys verbatim.
    val hasRawTime: Boolean get() = !cameraTime.isNullOrBlank()
    val metadataKey: String get() = identity(cameraTime?.takeIf { hasRawTime } ?: captured.toString())
    private val scopedKey: String get() = digest("$storage/$parent/$metadataKey")
    val key: String get() = recordKey ?: scopedKey
    private fun identity(time: String) = digest("$name\u0000$size\u0000$time")
    val pair: String get() = digest("$storage/$parent/${name.substringBeforeLast('.').lowercase()}/${cameraTime?.takeIf { hasRawTime } ?: captured}")
    val compatibleKeys: Set<String> by lazy {
        // Only an exact raw clock AND location can suppress a planned download.
        (if (hasRawTime) setOf("known:$scopedKey") else emptySet()) +
            (legacyKeys + metadataKey).map { "hint:$it" }
    }
    private val legacyKeys: Set<String> by lazy {
        val walls = if (hasRawTime) listOfNotNull(
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
    fun isKnown(keys: Set<String>): Boolean = hasRawTime && "known:$scopedKey" in keys
    fun needsVerification(keys: Set<String>): Boolean = !isKnown(keys) &&
        compatibleKeys.any { it.startsWith("hint:") && it in keys }
    fun sameLocation(other: CameraObject): Boolean = storage == other.storage && parent == other.parent &&
        name == other.name && size == other.size
    fun matchesQueued(queued: CameraObject): Boolean = sameLocation(queued) &&
        if (hasRawTime && queued.hasRawTime) cameraTime == queued.cameraTime
        else compatibleKeys.any { it.startsWith("hint:") && it in queued.compatibleKeys }
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
    suspend fun contentHash(key: String): String?
    suspend fun hashed(key: String, hash: String)
}
interface OriginalStore {
    fun recover(uri: String, size: Long): Boolean
    fun contentHash(uri: String): String?
    fun begin(item: CameraObject): String
    fun write(uri: String, source: File)
    fun publish(uri: String, size: Long)
    fun discard(uri: String)
}

/** Streaming SHA-256 over received bytes or an existing published original. Never logged. */
fun sha256(input: InputStream): String = input.use {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = it.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
}

class ImportPipeline(private val journal: ImportJournal, private val store: OriginalStore,
                     private val temp: File) {
    suspend fun run(camera: Camera, active: () -> Boolean = { true }, progress: suspend (Int, Int) -> Unit = { _, _ -> }) {
        val queue = journal.pending()
        if (queue.isEmpty()) return
        val current = camera.list().filter { it.valid }.distinct()
        // Resolve the WHOLE queue before publication. A raw queued clock cannot be reconstructed
        // from a fallback listing. Legacy rows may use one unambiguous candidate at the same path.
        val groups = queue.groupBy { queued ->
            val candidates = current.filter { it.matchesQueued(queued) }
            check(candidates.size <= 1) { "기존 대기 사진의 촬영시각이 모호합니다. 대기열을 확인하세요" }
            val item = checkNotNull(candidates.singleOrNull()) { "카드에서 대기 사진을 찾을 수 없습니다" }
            check((!queued.hasRawTime || item.hasRawTime) &&
                (queued.hasRawTime || item.hasRawTime || queued.captured == item.captured)) {
                "대기 사진의 원문 촬영시각을 확인할 수 없습니다. 목록을 새로고침하세요"
            }
            item
        }
        for (pending in groups.values) {
            check(pending.filter { it.hasRawTime }.map { it.metadataKey }.distinct().size <= 1) {
                "서로 다른 원문 대기 사진입니다. 대기열을 확인하세요"
            }
        }
        // Read failures cannot confirm equality. Published originals remain untouched.
        fun publishedHash(uri: String, size: Long): String? = runCatching {
            if (store.recover(uri, size)) store.contentHash(uri) else null
        }.getOrNull()
        suspend fun completedHash(record: CameraObject): Pair<String, String>? {
            val uri = journal.uri(record.key) ?: return null
            val hash = journal.contentHash(record.key) ?: publishedHash(uri, record.size)?.also {
                journal.hashed(record.key, it)
            } ?: return null
            return if (runCatching { store.recover(uri, record.size) }.getOrDefault(false)) uri to hash else null
        }
        temp.parentFile?.mkdirs()
        try {
            var done = 0
            for ((item, pending) in groups) {
                check(active()) { "가져오기가 중단되었습니다" }
                val previous = pending.mapNotNull { row -> journal.uri(row.key)?.let { row.key to it } }.toMap()
                val published = previous.values.distinct().mapNotNull { uri ->
                    publishedHash(uri, item.size)?.let { uri to it }
                }.toMap()
                // Only hashes journaled before publication can recover a staged download without
                // rereading the camera. v1 URI/size/aliases alone are never content evidence.
                val recovered = pending.firstNotNullOfOrNull { row ->
                    val uri = previous[row.key]
                    val hash = journal.contentHash(row.key)
                    if (uri != null && hash != null && published[uri] == hash) uri to hash else null
                }
                val hash = recovered?.second ?: run {
                    temp.delete()
                    camera.download(item, temp)
                    check(active()) { "가져오기가 중단되었습니다" }
                    check(temp.length() == item.size) { "원본 크기가 일치하지 않습니다" }
                    sha256(temp.inputStream())
                }
                check(active()) { "가져오기가 중단되었습니다" }
                // Include previously completed groups, so legacy aliases publish only once even
                // across an interruption between row completions.
                var existing = recovered?.first ?: published.entries.firstOrNull { it.value == hash }?.key
                if (existing == null) {
                    for (record in journal.completedItems().filter { it.size == item.size }) {
                        val original = completedHash(record)
                        if (original?.second == hash) { existing = original.first; break }
                    }
                }
                // Persist the received hash BEFORE staging or publishing for crash recovery.
                pending.forEach { journal.hashed(it.key, hash) }
                previous.values.distinct().filter { uri ->
                    !runCatching { store.recover(uri, item.size) }.getOrDefault(true)
                }.forEach(store::discard)
                val uri = existing ?: run {
                    val uri = store.begin(item)
                    try { pending.forEach { journal.staged(it.key, uri) } }
                    catch (e: Throwable) { store.discard(uri); throw e }
                    store.write(uri, temp)
                    check(active()) { "가져오기가 중단되었습니다" }
                    store.publish(uri, item.size)
                    uri
                }
                for (queued in pending) {
                    // Preserve every already published URI; retain its row link if bytes agree.
                    val target = previous[queued.key]?.takeIf { published[it] == hash } ?: uri
                    journal.staged(queued.key, target)
                    journal.completed(queued.key, target)
                    progress(++done, queue.size)
                }
            }
        } finally { temp.delete() }
    }
}
