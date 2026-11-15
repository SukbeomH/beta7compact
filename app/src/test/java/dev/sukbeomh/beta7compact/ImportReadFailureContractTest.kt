package dev.sukbeomh.beta7compact

import android.content.Context
import android.os.Build
import dev.sukbeomh.beta7compact.core.*
import dev.sukbeomh.beta7compact.library.*
import dev.sukbeomh.beta7compact.transfer.MediaOriginalStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** R4 FocusChecks/IntegratedChecks: real plan, DAO enqueue, journal, pipeline and MediaStore
 * adapter; only Android I/O, camera bytes and the DAO persistence boundary are synthetic. */
class ImportReadFailureContractTest {
    private class Dao(initial: List<Photo>) : PhotoDao {
        val rows = initial.associateBy { it.key }.toMutableMap()
        override fun observe() = flowOf(rows.values.toList())
        override suspend fun all() = rows.values.toList()
        override suspend fun pending() = rows.values.filter { it.imported == null }
        override suspend fun uris() = rows.values.mapNotNull { it.uri }
        override suspend fun get(key: String) = rows[key]
        override suspend fun insert(photos: List<Photo>) { photos.forEach { rows.putIfAbsent(it.key, it) } }
        override suspend fun hashed(key: String, hash: String) { rows[key] = rows.getValue(key).copy(contentHash = hash) }
        override suspend fun staged(key: String, uri: String) { rows[key] = rows.getValue(key).copy(uri = uri) }
        override suspend fun complete(key: String, uri: String, now: Long) {
            rows[key] = rows.getValue(key).copy(uri = uri, imported = now)
        }
    }
    private class Cam(val item: CameraObject) : Camera {
        var downloads = 0
        override fun list() = listOf(item)
        override fun thumbnail(item: CameraObject): ByteArray? = null
        override fun download(item: CameraObject, destination: File) { downloads++; destination.writeBytes(BYTES) }
        override fun close() {}
    }
    private class Store : OriginalStore {
        var fault = ""
        var begins = 0
        val bytes = mutableMapOf(OLD to BYTES)
        override fun recover(uri: String, size: Long): Boolean {
            if (fault == "query-throws") throw java.io.IOException("synthetic query failure")
            return bytes[uri]?.size?.toLong() == size
        }
        override fun contentHash(uri: String): String? = when (fault) {
            "hash-null" -> null
            "hash-throws" -> throw java.io.IOException("synthetic stream failure")
            else -> bytes[uri]?.inputStream()?.let(::sha256)
        }
        override fun begin(item: CameraObject): String { begins++; error("must not begin before verification") }
        override fun write(uri: String, source: File) = error("unexpected write")
        override fun publish(uri: String, size: Long) = error("unexpected publication")
        override fun discard(uri: String) = error("must preserve existing URI")
    }
    private fun item() = CameraObject(1, 2, "PUBLIC_TEST.ARW", 4, 1577901845000, 8, "20200102T030405")
    private suspend fun run(dao: Dao, store: OriginalStore, camera: Cam) {
        val dir = Files.createTempDirectory("m1-fix4").toFile()
        val temp = File(dir, "original.part")
        try { ImportPipeline(RoomJournal(dao), store, temp).run(camera) }
        finally {
            assertFalse(temp.exists(), "received temporary file must be removed on failure and success")
            dir.deleteRecursively()
        }
    }
    private fun assertRetryReason(failure: Throwable) {
        assertEquals("완료 원본을 비교할 수 없습니다. 갤러리 상태를 확인하고 다시 시도하세요", failure.message)
        assertFalse(failure.message!!.contains('\n'))
    }
    @Test fun focusNullHashStreamExceptionAndQueryExceptionBlockThenReuseExistingUri() = runBlocking {
        for (fault in listOf("hash-null", "hash-throws", "query-throws")) {
            val original = Photo.from(item()).copy(key = item().metadataKey, uri = OLD, imported = 123, recipe = "reserved")
            val dao = Dao(listOf(original)); val store = Store().apply { this.fault = fault }
            val camera = Cam(item().copy(parent = 9))
            dao.enqueue(ImportPlan.create(camera.list(), original.cameraObject().compatibleKeys).map(Photo::from))
            val before = dao.rows.toMap()
            assertRetryReason(assertFails { run(dao, store, camera) })
            assertEquals(before, dao.rows); assertEquals(1, dao.pending().size)
            assertEquals(0, store.begins); assertEquals(1, camera.downloads)
            assertContentEquals(BYTES, store.bytes[OLD])
            store.fault = ""
            run(dao, store, camera)
            assertTrue(dao.pending().isEmpty()); assertEquals(0, store.begins)
            assertTrue(dao.rows.values.all { it.uri == OLD && it.contentHash == HASH })
            assertEquals(original.copy(contentHash = HASH), dao.rows.getValue(original.key))
        }
    }
    @Test fun everyRequiredOriginalMustBeVerifiableEvenAfterASameMatch() = runBlocking {
        for (sameFirst in listOf(false, true)) {
            val known = Photo.from(item()).copy(key = "known", uri = OLD, imported = 123)
            val unknown = known.copy(key = "unknown", uri = "content://synthetic/unreadable")
            val different = known.copy(key = "different", uri = "content://synthetic/different")
            val dao = Dao((if (sameFirst) listOf(known, unknown) else listOf(unknown, known)) + different)
            val store = Store(); val camera = Cam(item().copy(parent = 9))
            val otherBytes = byteArrayOf(9, 9, 9, 9)
            store.bytes[different.uri!!] = otherBytes
            dao.enqueue(listOf(Photo.from(camera.item)))
            assertRetryReason(assertFails { run(dao, store, camera) })
            assertEquals(0, store.begins); assertEquals(1, dao.pending().size)
            assertEquals(known.copy(contentHash = HASH), dao.rows[known.key])
            assertEquals(different.copy(contentHash = sha256(otherBytes.inputStream())), dao.rows[different.key])
            assertEquals(unknown, dao.rows[unknown.key])
            store.bytes[unknown.uri!!] = BYTES
            run(dao, store, camera)
            assertTrue(dao.pending().isEmpty()); assertEquals(0, store.begins)
            assertEquals(HASH, dao.rows.getValue(unknown.key).contentHash)
        }
    }
    @Test fun stagedPublishedUriCannotCompleteWhileAnotherRequiredOriginalIsUnverifiable() = runBlocking {
        val original = Photo.from(item()).copy(uri = "content://synthetic/unreadable", imported = 123)
        val camera = Cam(item().copy(parent = 9))
        val pending = Photo.from(camera.item).copy(uri = OLD, contentHash = HASH)
        val dao = Dao(listOf(original, pending)); val store = Store()
        assertRetryReason(assertFails { run(dao, store, camera) })
        assertEquals(pending, dao.rows[pending.key]); assertEquals(original, dao.rows[original.key])
        assertEquals(0, camera.downloads); assertEquals(0, store.begins)
        store.bytes[original.uri!!] = BYTES
        run(dao, store, camera)
        assertTrue(dao.pending().isEmpty()); assertEquals(OLD, dao.rows.getValue(pending.key).uri)
        assertEquals(0, camera.downloads); assertEquals(0, store.begins)
    }
    @Test fun integratedSingleReadFailurePreservesLegacyAndRawRowsThenResumesWithoutNewPublication() = runBlocking {
        val api = Build.VERSION.SDK_INT
        try {
            for (version in listOf(29, 30, 35)) for (legacySamePath in listOf(false, true)) {
                for (fault in listOf("open-throws", "read-throws", "hash-null", "query-throws", "query-null")) {
                    Build.VERSION.SDK_INT = version
                    val resolver = MediaOriginalStoreContractTest.Resolver()
                    val store = MediaOriginalStore(Context(resolver, "synthetic.app"))
                    val a = item(); val oldUri = store.begin(a)
                    resolver.rows.getValue(oldUri).apply { pending = 0; bytes = BYTES.copyOf() }
                    val previous = if (legacySamePath) a.copy(cameraTime = null) else a
                    val original = Photo.from(previous).copy(key = previous.metadataKey, uri = oldUri,
                        imported = 123, recipe = "reserved")
                    val dao = Dao(listOf(original))
                    val camera = Cam(a.copy(handle = 2, parent = if (legacySamePath) 8 else 9))
                    dao.enqueue(ImportPlan.create(camera.list(), previous.compatibleKeys).map(Photo::from))
                    val before = dao.rows.toMap()
                    resolver.inputFailure = when (fault) {
                        "open-throws" -> "throws"
                        "read-throws" -> "read-throws"
                        "hash-null" -> "null"
                        else -> ""
                    }
                    resolver.queryFailure = when (fault) {
                        "query-throws" -> "throws"
                        "query-null" -> "null"
                        else -> ""
                    }
                    assertRetryReason(assertFails { run(dao, store, camera) })
                    assertEquals(before, dao.rows); assertEquals(1, dao.pending().size)
                    assertEquals(1, camera.downloads); assertEquals(1L, resolver.nextId)
                    assertEquals(setOf(oldUri), resolver.rows.keys)
                    assertTrue(resolver.deleted.isEmpty()); assertContentEquals(BYTES, resolver.rows.getValue(oldUri).bytes)
                    resolver.inputFailure = ""; resolver.queryFailure = ""
                    run(dao, store, camera)
                    assertTrue(dao.pending().isEmpty())
                    assertTrue(dao.rows.values.all { it.uri == oldUri && it.contentHash == HASH })
                    assertEquals(original.copy(contentHash = HASH), dao.rows[original.key])
                    assertEquals(1L, resolver.nextId) // Initial publication only; no new URI on failure or retry.
                    assertEquals(1, resolver.rows.values.count { it.pending == 0 })
                    assertTrue(resolver.deleted.isEmpty())
                }
            }
        } finally { Build.VERSION.SDK_INT = api }
    }
    @Test fun onlyConfirmedAbsentUriAllowsReplacementWhilePendingRowsRemainUnverifiable() = runBlocking {
        val api = Build.VERSION.SDK_INT
        try {
            for (version in listOf(29, 30, 35)) for (absent in listOf(false, true)) {
                Build.VERSION.SDK_INT = version
                val resolver = MediaOriginalStoreContractTest.Resolver()
                val store = MediaOriginalStore(Context(resolver, "synthetic.app"))
                val a = item(); val oldUri = store.begin(a)
                if (absent) resolver.rows.remove(oldUri) // Gallery deletion, confirmed by an empty row query.
                val original = Photo.from(a).copy(uri = oldUri, imported = 123, recipe = "reserved", contentHash = HASH)
                val dao = Dao(listOf(original)); val camera = Cam(a.copy(parent = 9))
                dao.enqueue(listOf(Photo.from(camera.item)))
                if (absent) {
                    assertEquals(PublishedState.ABSENT, store.publishedState(oldUri, 4))
                    run(dao, store, camera)
                    assertTrue(dao.pending().isEmpty()); assertEquals(2L, resolver.nextId)
                    assertEquals(1, resolver.rows.values.count { it.pending == 0 })
                    assertNotEquals(oldUri, dao.rows.getValue(camera.item.key).uri)
                } else {
                    assertEquals(PublishedState.UNVERIFIABLE, store.publishedState(oldUri, 4))
                    assertRetryReason(assertFails { run(dao, store, camera) })
                    assertEquals(1, dao.pending().size); assertEquals(1L, resolver.nextId)
                }
                assertEquals(original, dao.rows[original.key]); assertTrue(resolver.deleted.isEmpty())
            }
        } finally { Build.VERSION.SDK_INT = api }
    }
    companion object {
        private const val OLD = "content://synthetic/existing"
        private val BYTES = byteArrayOf(1, 2, 3, 4)
        private val HASH = sha256(BYTES.inputStream())
    }
}
