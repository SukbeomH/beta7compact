package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.core.*
import dev.sukbeomh.beta7compact.library.*
import dev.sukbeomh.beta7compact.usb.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.*

/** Ported from independent r2/IdentityChecks.kt: actual PhotoDao.enqueue + RoomJournal + pipeline. */
class LegacyQueueContractTest {
    private class Dao(initial: List<Photo>) : PhotoDao {
        var failOnComplete = 0; var completions = 0
        val rows = initial.associateBy { it.key }.toMutableMap()
        override fun observe() = flowOf(rows.values.toList())
        override suspend fun all() = rows.values.toList()
        override suspend fun uris() = rows.values.mapNotNull { it.uri }
        override suspend fun pending() = rows.values.filter { it.imported == null }.sortedBy { it.captured }
        override suspend fun get(key: String) = rows[key]
        override suspend fun insert(photos: List<Photo>) { photos.forEach { rows.putIfAbsent(it.key, it) } }
        override suspend fun hashed(key: String, hash: String) { rows[key] = rows.getValue(key).copy(contentHash = hash) }
        override suspend fun staged(key: String, uri: String) { rows[key] = rows.getValue(key).copy(uri = uri) }
        override suspend fun complete(key: String, uri: String, now: Long) {
            if (++completions == failOnComplete) error("synthetic completion interruption")
            rows[key] = rows.getValue(key).copy(uri = uri, imported = now)
        }
    }
    private class Store : OriginalStore {
        var failWrite = false; var count = 0
        val published = mutableSetOf<String>(); val bytes = mutableMapOf<String, ByteArray>()
        override fun recover(uri: String, size: Long) = uri in published && bytes[uri]?.size?.toLong() == size
        override fun contentHash(uri: String) = bytes[uri]?.inputStream()?.let(::sha256)
        override fun begin(item: CameraObject) = "content://synthetic/${++count}"
        override fun write(uri: String, source: File) {
            bytes[uri] = source.readBytes()
            if (failWrite) { failWrite = false; error("synthetic copy interruption") }
        }
        override fun publish(uri: String, size: Long) { check(bytes[uri]!!.size.toLong() == size); published.add(uri) }
        override fun discard(uri: String) { check(uri !in published); bytes.remove(uri) }
    }
    private class Cam(item: CameraObject) : Camera {
        var objects = listOf(item); var downloads = 0
        override fun list() = objects
        override fun thumbnail(item: CameraObject): ByteArray? = null
        override fun download(item: CameraObject, destination: File) {
            downloads++; destination.writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        override fun close() {}
    }

    private val date = "20200102T030405"
    private val current = ObjectInfo(1, 2, 0x3801, 4, "PUBLIC_TEST.JPG", date, "", 8).cameraObject()
    private fun legacy(zone: String) = current.copy(cameraTime = null, captured =
        LocalDateTime.parse(date, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            .atZone(ZoneId.of(zone)).toInstant().toEpochMilli())
    private val a get() = Photo.from(legacy("Asia/Seoul")).copy(key = legacy("Asia/Seoul").metadataKey)
    private val b get() = Photo.from(legacy("UTC")).copy(key = legacy("UTC").metadataKey)
    private val oldUri = "content://synthetic/old"
    private fun Store.seed(uri: String = oldUri) { published.add(uri); bytes[uri] = byteArrayOf(1, 2, 3, 4) }
    private suspend fun run(dao: Dao, store: Store, camera: Cam) {
        val directory = Files.createTempDirectory("legacy-queue").toFile()
        try { ImportPipeline(RoomJournal(dao), store, File(directory, "original.part")).run(camera) }
        finally { directory.deleteRecursively() }
    }
    @Test fun completedAliasRequiresHashComparisonAndLinksWithoutPublication() = runBlocking {
        val original = a.copy(uri = oldUri, imported = 123L, recipe = "reserved")
        val dao = Dao(listOf(original, b)); val store = Store().apply { seed() }; val camera = Cam(current)
        dao.enqueue(listOf(Photo.from(current)))
        assertEquals(3, dao.rows.size)
        run(dao, store, camera)
        assertEquals(original.copy(contentHash = sha256(byteArrayOf(1, 2, 3, 4).inputStream())), dao.rows.getValue(original.key))
        assertEquals(oldUri, dao.rows.getValue(b.key).uri)
        assertTrue(dao.rows.values.all { it.imported != null })
        assertEquals(1, camera.downloads); assertEquals(0, store.count)
        assertEquals(setOf(oldUri), store.published)
    }
    @Test fun pendingAliasesPublishOnceAndCompleteEveryOriginalKey() = runBlocking {
        val dao = Dao(listOf(a, b)); val store = Store(); val camera = Cam(current)
        dao.enqueue(listOf(Photo.from(current)))
        assertEquals(3, dao.rows.size)
        run(dao, store, camera)
        assertEquals(setOf(a.key, b.key, current.key), dao.rows.keys)
        assertTrue(dao.rows.values.all { it.imported != null })
        assertEquals(1, dao.rows.values.map { it.uri }.distinct().size)
        assertEquals(1, camera.downloads); assertEquals(1, store.count); assertEquals(1, store.published.size)
        run(dao, store, camera)
        assertEquals(1, camera.downloads); assertEquals(1, store.count)
    }
    @Test fun publishedPendingAliasIsRecoveredEvenWhenItIsLastInQueue() = runBlocking {
        val dao = Dao(listOf(a, b.copy(uri = oldUri))); val store = Store().apply { seed() }; val camera = Cam(current)
        run(dao, store, camera)
        assertEquals(1, camera.downloads); assertEquals(0, store.count)
        assertTrue(dao.rows.values.all { it.imported != null && it.uri == oldUri })
    }
    @Test fun interruptionBetweenAliasCompletionsNeverRepublishes() = runBlocking {
        val dao = Dao(listOf(a, b)).apply { failOnComplete = 2 }; val store = Store(); val camera = Cam(current)
        assertFails { run(dao, store, camera) }
        assertEquals(1, store.published.size)
        assertEquals(1, dao.rows.values.count { it.imported != null })
        run(dao, store, camera)
        assertEquals(1, store.count); assertEquals(1, camera.downloads)
        assertTrue(dao.rows.values.all { it.imported != null })
        assertEquals(1, dao.rows.values.map { it.uri }.distinct().size)
    }
    @Test fun interruptedCopyStagesEveryAliasThenRetriesOnceForTheGroup() = runBlocking {
        val dao = Dao(listOf(a, b)); val store = Store().apply { failWrite = true }; val camera = Cam(current)
        assertFails { run(dao, store, camera) }
        assertTrue(dao.rows.values.all { it.uri != null && it.imported == null })
        assertEquals(1, dao.rows.values.map { it.uri }.distinct().size)
        run(dao, store, camera)
        assertEquals(1, store.published.size); assertEquals(1, store.bytes.size)
        assertTrue(dao.rows.values.all { it.imported != null })
    }
    @Test fun existingPublishedUrisAreNeverDeletedOrOverwritten() = runBlocking {
        val otherUri = "content://synthetic/other"
        val dao = Dao(listOf(a.copy(uri = oldUri, imported = 123), b.copy(uri = otherUri)))
        val store = Store().apply { seed(); seed(otherUri) }; val camera = Cam(current)
        run(dao, store, camera)
        assertEquals(oldUri, dao.rows.getValue(a.key).uri); assertEquals(otherUri, dao.rows.getValue(b.key).uri)
        assertEquals(setOf(oldUri, otherUri), store.published)
        assertEquals(1, camera.downloads); assertEquals(0, store.count)
    }
    @Test fun unreadableCompletedOriginalCannotConfirmEqualityAndKeepsTheOldRecord() = runBlocking {
        val dao = Dao(listOf(a.copy(uri = oldUri, imported = 123), b)); val store = Store(); val camera = Cam(current)
        run(dao, store, camera)
        assertEquals(1, camera.downloads); assertEquals(1, store.count)
        assertEquals(oldUri, dao.rows.getValue(a.key).uri)
        assertNotNull(dao.rows.getValue(b.key).imported)
        assertNotEquals(oldUri, dao.rows.getValue(b.key).uri)
    }
    @Test fun ambiguousPendingSourceStopsTheWholeQueueBeforePublication() = runBlocking {
        val distinct = current.copy(name = "OTHER.JPG")
        val dao = Dao(listOf(Photo.from(distinct), a, b)); val store = Store()
        val camera = Cam(current).apply { objects = listOf(distinct, current, current.copy(handle = 99)) }
        val failure = assertFails { run(dao, store, camera) }
        assertTrue(failure.message!!.contains("모호"))
        assertEquals(0, store.count); assertEquals(0, camera.downloads)
    }
    @Test fun ambiguousCompletedMetadataOnlySuppliesHintsAndHashConfirmsTheSource() = runBlocking {
        val other = current.copy(handle = 99, cameraTime = "20200102T120405")
        val dao = Dao(listOf(b.copy(uri = oldUri, imported = 123), Photo.from(current)))
        val store = Store().apply { seed() }; val camera = Cam(current).apply { objects = listOf(current, other) }
        run(dao, store, camera)
        assertEquals(0, store.count); assertEquals(1, camera.downloads)
        assertEquals(setOf(oldUri), store.published)
    }
}
