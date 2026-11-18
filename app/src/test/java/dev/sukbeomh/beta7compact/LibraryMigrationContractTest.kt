package dev.sukbeomh.beta7compact

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.sukbeomh.beta7compact.core.CameraObject
import dev.sukbeomh.beta7compact.library.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.*

class LibraryMigrationContractTest {
    private class Dao(initial: List<Photo>) : PhotoDao {
        val rows = initial.associateBy { it.key }.toMutableMap()
        override fun observe() = flowOf(rows.values.toList())
        override suspend fun all() = rows.values.toList()
        override suspend fun uris() = rows.values.mapNotNull { it.uri }
        override suspend fun pending() = rows.values.filter { it.imported == null }
        override suspend fun get(key: String) = rows[key]
        override suspend fun insert(photos: List<Photo>) { photos.forEach { rows.putIfAbsent(it.key, it) } }
        override suspend fun hashed(key: String, hash: String) { rows[key] = rows.getValue(key).copy(contentHash = hash) }
        override suspend fun staged(key: String, uri: String) { rows[key] = rows.getValue(key).copy(uri = uri) }
        override suspend fun complete(key: String, uri: String, now: Long) {
            rows[key] = rows.getValue(key).copy(uri = uri, imported = now)
        }
    }
    @Test fun migrationEmitsAdditiveSqlForSQLiteValidation() {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            check(method.name == "execSQL") { "Unexpected migration operation: ${method.name}" }
            statements.add(args!![0] as String)
            null
        } as SupportSQLiteDatabase
        LibraryDb.MIGRATION_1_2.migrate(db)
        LibraryDb.MIGRATION_2_3.migrate(db)
        assertEquals(2, LibraryDb.MIGRATION_2_3.startVersion)
        assertEquals(3, LibraryDb.MIGRATION_2_3.endVersion)
        assertEquals(1, LibraryDb.MIGRATION_1_2.startVersion)
        assertEquals(2, LibraryDb.MIGRATION_1_2.endVersion)
        assertTrue(statements.isNotEmpty())
        File("build/reports/m1-migration.sql").apply {
            checkNotNull(parentFile).mkdirs(); writeText(statements.joinToString(";\n", postfix = ";\n"))
        }
    }
    @Test fun migratedRecordsKeepTheirKeysUrisAndHintsDoNotSuppressEnqueue() = runBlocking {
        val legacy = CameraObject(1, 2, "PUBLIC_TEST.JPG", 4, 1577901845000)
        val current = legacy.copy(handle = 99, cameraTime = "20200102T030405")
        for (imported in listOf(null, 123L)) {
            val original = Photo.from(legacy).copy(key = legacy.metadataKey,
                uri = "content://synthetic/1", imported = imported, recipe = "reserved")
            val dao = Dao(listOf(original))
            dao.enqueue(listOf(Photo.from(current)))
            assertEquals(original, dao.rows.getValue(original.key))
            assertEquals(2, dao.rows.size)
            val journal = RoomJournal(dao)
            assertEquals(original.key, original.cameraObject().key)
            assertEquals(original.uri, journal.uri(original.key))
            assertNull(journal.contentHash(original.key))
            assertEquals(1, journal.pending().count { it.key == current.key })
        }
    }
    @Test fun migratedV2RawPrimaryKeyIsPreservedAndExactLocationAllowsHandleChange() = runBlocking {
        val current = CameraObject(1, 2, "PUBLIC_TEST.JPG", 4, 1577901845000,
            cameraTime = "20200102T030405")
        val original = Photo.from(current).copy(key = current.metadataKey, uri = "content://synthetic/1", imported = 123)
        val dao = Dao(listOf(original))
        dao.enqueue(listOf(Photo.from(current.copy(handle = 99))))
        assertEquals(listOf(original), dao.all())
        val elsewhere = current.copy(parent = 9)
        dao.enqueue(listOf(Photo.from(elsewhere)))
        assertEquals(setOf(original.key, elsewhere.key), dao.rows.keys)
    }
    @Test fun recipeCompletionHookRunsOnceAndDefaultJournalStillPreservesOriginalFields() = runBlocking {
        val photo = Photo.from(CameraObject(1, 2, "PUBLIC.JPG", 4, 123)).copy(contentHash = "synthetic")
        val dao = Dao(listOf(photo))
        var calls = 0
        val journal = RoomJournal(dao) { key, uri, now ->
            calls++
            assertEquals(photo.key, key)
            assertTrue(now > 0)
            dao.complete(key, uri, now)
        }
        journal.completed(photo.key, "content://synthetic/original")
        assertEquals(1, calls)
        val completed = dao.get(photo.key)!!
        assertEquals(photo.contentHash, completed.contentHash)
        assertEquals(photo.cameraTime, completed.cameraTime)
        assertEquals(photo.recipe, completed.recipe)
        assertEquals("content://synthetic/original", completed.uri)
    }

    @Test fun wifiRecoverySelectsOnlyItsOwnRowAndUsbSkipsWifiPending() = runBlocking {
        val usb = Photo.from(CameraObject(1, 2, "USB.JPG", 4, 123))
        val wifi = usb.copy(key = "wifi:synthetic", name = "WIFI.JPG")
        val otherWifi = wifi.copy(key = "wifi:other")
        val dao = Dao(listOf(usb, wifi, otherWifi))
        assertEquals(listOf(usb.key), RoomJournal(dao).pending().map { it.key })
        assertEquals(listOf(wifi.key), RoomJournal(dao, wifiKey = wifi.key).pending().map { it.key })
        dao.complete(wifi.key, "content://synthetic/wifi", 123)
        assertTrue(RoomJournal(dao, wifiKey = wifi.key).pending().isEmpty())
        assertEquals(listOf(wifi.key), RoomJournal(dao).completedItems().map { it.key })
    }

}
