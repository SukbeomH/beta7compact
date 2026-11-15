package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.core.*
import dev.sukbeomh.beta7compact.library.*
import dev.sukbeomh.beta7compact.usb.ObjectInfo
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TimeZone
import kotlin.test.*

/** R3 ExtraChecks.kt counterexamples, with fixed expectations. Real plan/DAO/journal/pipeline;
 * only camera bytes, MediaStore and the in-memory DAO boundary are synthetic. */
class ImportIdentityContractTest {
    private class Dao(initial: List<Photo> = emptyList()) : PhotoDao {
        var failHash = false
        val rows = initial.associateBy { it.key }.toMutableMap()
        override fun observe() = flowOf(rows.values.toList())
        override suspend fun all() = rows.values.toList()
        override suspend fun pending() = rows.values.filter { it.imported == null }
        override suspend fun uris() = rows.values.mapNotNull { it.uri }
        override suspend fun get(key: String) = rows[key]
        override suspend fun insert(photos: List<Photo>) { photos.forEach { rows.putIfAbsent(it.key, it) } }
        override suspend fun staged(key: String, uri: String) { rows[key] = rows.getValue(key).copy(uri = uri) }
        override suspend fun hashed(key: String, hash: String) {
            check(!failHash) { "synthetic hash journal failure" }
            rows[key] = rows.getValue(key).copy(contentHash = hash)
        }
        override suspend fun complete(key: String, uri: String, now: Long) {
            rows[key] = rows.getValue(key).copy(uri = uri, imported = now)
        }
        fun known() = rows.values.filter { it.imported != null }.flatMap { it.cameraObject().compatibleKeys }.toSet()
    }
    private class Store : OriginalStore {
        val bytes = mutableMapOf<String, ByteArray>()
        val published = mutableSetOf<String>()
        var publications = 0; var begins = 0; var hashReads = 0; var unreadable = false
        override fun begin(item: CameraObject) = "content://synthetic/new/${++begins}"
        override fun recover(uri: String, size: Long) = uri in published && bytes[uri]?.size?.toLong() == size
        override fun contentHash(uri: String): String? {
            hashReads++
            return if (unreadable) null else bytes[uri]?.inputStream()?.let(::sha256)
        }
        override fun write(uri: String, source: File) { bytes[uri] = source.readBytes() }
        override fun publish(uri: String, size: Long) {
            assertEquals(size, bytes[uri]?.size?.toLong()); published.add(uri); publications++
        }
        override fun discard(uri: String) { check(uri !in published); bytes.remove(uri) }
        fun seed(content: ByteArray) { bytes[OLD] = content; published.add(OLD) }
    }
    private class Cam(var objects: List<CameraObject>, val content: ByteArray = NEW_BYTES) : Camera {
        var downloads = 0
        override fun list() = objects
        override fun thumbnail(item: CameraObject): ByteArray? = null
        override fun download(item: CameraObject, destination: File) { downloads++; destination.writeBytes(content) }
        override fun close() {}
    }
    private fun raw(date: String = "20200102T030405", parent: Int = 8, storage: Int = 2,
                    name: String = "PUBLIC_TEST.ARW", handle: Int = 1) =
        ObjectInfo(handle, storage, 0xb101, 4, name, date, "", parent).cameraObject()
    private suspend fun run(dao: Dao, store: Store, cam: Cam) {
        val dir = Files.createTempDirectory("m1-fix3-identity").toFile()
        try {
            ImportPipeline(RoomJournal(dao), store, File(dir, "original.part")).run(cam)
            assertFalse(File(dir, "original.part").exists())
        } finally {
            assertFalse(File(dir, "original.part").exists())
            dir.deleteRecursively()
        }
    }
    private suspend fun receive(dao: Dao, store: Store, cam: Cam) {
        dao.enqueue(ImportPlan.create(cam.objects, dao.known()).map(Photo::from))
        run(dao, store, cam)
    }
    private suspend fun differentSource(a: CameraObject, b: CameraObject, sameBytes: Boolean = false) {
        val dao = Dao(listOf(Photo.from(a).copy(uri = OLD, imported = 123, recipe = "reserved")))
        val original = if (sameBytes) NEW_BYTES else OLD_BYTES
        val store = Store().apply { seed(original) }; val cam = Cam(listOf(b))
        assertFalse(b.isKnown(dao.known()))
        assertTrue(b.needsVerification(dao.known()))
        assertEquals(listOf(b), ImportPlan.create(listOf(b), dao.known()))
        // Selection and the all-new path both include verification items.
        assertEquals(listOf(b), ImportPlan.create(listOf(b), dao.known(), setOf(b.key)))
        receive(dao, store, cam)
        assertEquals(1, cam.downloads)
        assertEquals(if (sameBytes) 0 else 1, store.publications)
        assertEquals(if (sameBytes) 1 else 2, store.published.size)
        assertContentEquals(original, store.bytes[OLD])
        assertEquals(2, dao.rows.size)
        assertTrue(dao.rows.values.all { it.imported != null })
        val received = dao.rows.values.single { it.key != a.key }
        assertEquals(NEW_HASH, received.contentHash)
        assertEquals(if (sameBytes) OLD else "content://synthetic/new/1", received.uri)
        assertEquals(OLD, dao.rows.getValue(a.key).uri)
        assertEquals("reserved", dao.rows.getValue(a.key).recipe)
        assertNotNull(dao.rows.getValue(a.key).contentHash) // v1/v2 lazy hash backfill
    }
    @Test fun r3N1AAllThreeAliasDirectionsReceiveDifferentBytesAsNewPhotos() = runBlocking {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            for (direction in listOf("raw-to-fallback", "fallback-to-raw", "fallback-to-fallback")) {
                val a = raw(); val b = raw("20200102T120405", parent = 9, handle = 2)
                assertNotEquals(a.metadataKey, b.metadataKey)
                assertFalse(b.isKnown(a.compatibleKeys)) // raw/raw positive control
                for (parent in listOf(8, 9)) {
                    differentSource(if (direction.startsWith("fallback")) a.copy(cameraTime = null) else a,
                        if (direction.endsWith("fallback")) b.copy(parent = parent, cameraTime = null) else b.copy(parent = parent))
                }
            }
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun r3N1BMissingRawRecordCannotBeCompletedThroughOneFallbackCandidate() = runBlocking {
        val a = raw(); val b = raw("20200102T120405", parent = 9, handle = 2)
        val dao = Dao(listOf(Photo.from(a), Photo.from(b))); val store = Store()
        val cam = Cam(listOf(a.copy(cameraTime = null)))
        val failure = assertFails { run(dao, store, cam) }
        assertTrue(failure.message!!.contains("원문"))
        assertEquals(0, cam.downloads); assertEquals(0, store.publications)
        assertTrue(dao.rows.values.all { it.uri == null && it.imported == null })
    }
    @Test fun fallbackQueueCannotResolveThroughOnlyAnAlternateEpochHint() = runBlocking {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val a = raw().copy(cameraTime = null)
            val b = raw("20200102T120405").copy(cameraTime = null)
            assertTrue(b.needsVerification(a.compatibleKeys))
            val dao = Dao(listOf(Photo.from(a))); val store = Store(); val cam = Cam(listOf(b))
            val failure = assertFails { run(dao, store, cam) }
            assertTrue(failure.message!!.contains("원문"))
            assertEquals(0, cam.downloads); assertEquals(0, store.publications)
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun r3N2OtherParentAndOtherCardWithDifferentBytesArePublished() = runBlocking {
        val a = raw()
        differentSource(a, a.copy(handle = 2, parent = 9))
        differentSource(a, a.copy(handle = 2, storage = 3))
    }
    @Test fun sameBytesInOtherParentOrCardReuseExistingUriWithoutPublication() = runBlocking {
        val a = raw()
        differentSource(a, a.copy(handle = 2, parent = 9), sameBytes = true)
        differentSource(a, a.copy(handle = 2, storage = 3), sameBytes = true)
    }
    @Test fun missingRawDateIsAFallbackHintAndNeverAnExactKnownClock() = runBlocking {
        val a = raw(date = "")
        assertFalse(a.hasRawTime)
        assertFalse(a.isKnown(a.compatibleKeys))
        assertTrue(a.needsVerification(a.compatibleKeys))
        val dao = Dao(listOf(Photo.from(a).copy(uri = OLD, imported = 123)))
        val store = Store().apply { seed(OLD_BYTES) }
        val cam = Cam(listOf(a.copy(handle = 99)))
        receive(dao, store, cam)
        assertEquals(1, cam.downloads); assertEquals(1, store.publications)
        assertContentEquals(OLD_BYTES, store.bytes[OLD])
    }
    @Test fun fallbackWithSameMetadataAndLocationStillEnqueuesAndComparesBytes() = runBlocking {
        val a = raw().copy(cameraTime = null)
        val dao = Dao(listOf(Photo.from(a).copy(uri = OLD, imported = 123)))
        val store = Store().apply { seed(OLD_BYTES) }; val cam = Cam(listOf(a.copy(handle = 99)))
        receive(dao, store, cam)
        assertEquals(2, dao.rows.size); assertEquals(1, cam.downloads); assertEquals(1, store.publications)
        assertContentEquals(OLD_BYTES, store.bytes[OLD])
    }
    @Test fun fallbackReverificationPendingRowIsNotAddedTwice() = runBlocking {
        val a = raw().copy(cameraTime = null)
        val dao = Dao(listOf(Photo.from(a).copy(uri = OLD, imported = 123)))
        repeat(2) { dao.enqueue(listOf(Photo.from(a))) }
        assertEquals(2, dao.rows.size); assertEquals(1, dao.pending().size)
    }
    @Test fun exactRawAndLocationKnownAcrossHandleAndPhoneTimezoneChanges() {
        val a = raw(); val old = TimeZone.getDefault()
        try {
            for (zone in listOf("Asia/Seoul", "UTC", "America/New_York", "Asia/Kathmandu")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val b = raw(handle = 99)
                assertTrue(b.isKnown(a.compatibleKeys))
                assertTrue(ImportPlan.create(listOf(b), a.compatibleKeys).isEmpty())
            }
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun alternatingRawFallbackAndTimezonesDownloadsForVerificationButPublishesOnlyOnce() = runBlocking {
        val old = TimeZone.getDefault()
        try {
            for (startRaw in listOf(false, true)) {
                val dao = Dao(); val store = Store(); val cam = Cam(emptyList())
                for ((index, zone) in listOf("Asia/Seoul", "UTC", "America/New_York", "Asia/Kathmandu", "UTC").withIndex()) {
                    TimeZone.setDefault(TimeZone.getTimeZone(zone))
                    val a = raw()
                    cam.objects = listOf(if ((index % 2 == 0) == startRaw) a else a.copy(cameraTime = null))
                    receive(dao, store, cam)
                }
                assertTrue(cam.downloads > 1)
                assertEquals(1, store.publications); assertEquals(1, store.published.size)
                assertTrue(dao.rows.values.all { it.uri == "content://synthetic/new/1" && it.contentHash == NEW_HASH })
            }
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun burstWithDifferentFilenamesAndSameClockRemainsTwoPhotos() = runBlocking {
        val a = raw(name = "DSC00001.ARW"); val b = raw(name = "DSC00002.ARW", handle = 2)
        val plan = ImportPlan.create(listOf(a, b), emptySet())
        assertEquals(2, plan.size)
        val dao = Dao(); dao.enqueue(plan.map(Photo::from)); assertEquals(2, dao.pending().size)
        // Distinct contents so the content deduplicator must publish both.
        val camera = object : Camera {
            override fun list() = plan
            override fun thumbnail(item: CameraObject): ByteArray? = null
            override fun download(item: CameraObject, destination: File) {
                destination.writeBytes(if (item.name == a.name) NEW_BYTES else OLD_BYTES)
            }
            override fun close() {}
        }
        val store = Store(); val dir = Files.createTempDirectory("burst").toFile()
        try { ImportPipeline(RoomJournal(dao), store, File(dir, "part")).run(camera) }
        finally { dir.deleteRecursively() }
        assertEquals(2, store.publications)
    }
    @Test fun locationCollisionsRemainSeparateInOnePlanAndQueue() = runBlocking {
        val a = raw(); val b = a.copy(parent = 9); val c = a.copy(storage = 3)
        val plan = ImportPlan.create(listOf(a, b, c), emptySet())
        assertEquals(3, plan.size)
        val dao = Dao(); dao.enqueue(plan.map(Photo::from))
        assertEquals(3, dao.rows.size)
    }
    @Test fun missingQueuedSourceStopsEvenUnrelatedFirstPhotoBeforeAnyDownload() = runBlocking {
        val a = raw(name = "FIRST.ARW"); val b = raw(parent = 9)
        val dao = Dao(listOf(Photo.from(a), Photo.from(b))); val store = Store(); val cam = Cam(listOf(a))
        val failure = assertFails { run(dao, store, cam) }
        assertEquals("카드에서 대기 사진을 찾을 수 없습니다", failure.message)
        assertEquals(0, cam.downloads); assertEquals(0, store.publications)
    }
    @Test fun unreadableLegacyUriNeverConfirmsContentEquality() = runBlocking {
        val a = raw(); val b = a.copy(parent = 9)
        val dao = Dao(listOf(Photo.from(a).copy(uri = OLD, imported = 123)))
        val store = Store().apply { seed(NEW_BYTES); unreadable = true }
        val cam = Cam(listOf(b)); receive(dao, store, cam)
        assertEquals(1, store.publications); assertEquals(1, cam.downloads)
        assertNull(dao.rows.getValue(a.key).contentHash)
        assertContentEquals(NEW_BYTES, store.bytes[OLD])
    }
    @Test fun persistedCompletedHashAvoidsRehashingTheExistingOriginal() = runBlocking {
        val a = raw(); val b = a.copy(parent = 9)
        val dao = Dao(listOf(Photo.from(a).copy(uri = OLD, imported = 123, contentHash = NEW_HASH)))
        val store = Store().apply { seed(NEW_BYTES); unreadable = true }
        receive(dao, store, Cam(listOf(b)))
        assertEquals(0, store.publications); assertEquals(0, store.hashReads)
        assertTrue(dao.rows.values.all { it.uri == OLD })
    }
    @Test fun hashJournalFailureLeavesTheSourceUnpublishedAndRetryable() = runBlocking {
        val a = raw(); val dao = Dao().apply { failHash = true }
        val store = Store(); val cam = Cam(listOf(a))
        dao.enqueue(listOf(Photo.from(a)))
        assertFails { run(dao, store, cam) }
        assertEquals(0, store.begins); assertEquals(0, store.publications)
        assertNull(dao.rows.getValue(a.key).uri); assertNull(dao.rows.getValue(a.key).imported)
        dao.failHash = false
        run(dao, store, cam)
        assertEquals(1, store.publications)
        assertEquals(NEW_HASH, dao.rows.getValue(a.key).contentHash)
    }
    @Test fun streamingHashMatchesKnownReceivedByteVector() {
        assertEquals(NEW_HASH, sha256(NEW_BYTES.inputStream()))
    }
    companion object {
        private const val OLD = "content://synthetic/old"
        private val OLD_BYTES = byteArrayOf(9, 9, 9, 9)
        private val NEW_BYTES = byteArrayOf(1, 2, 3, 4)
        private const val NEW_HASH = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
    }
}
