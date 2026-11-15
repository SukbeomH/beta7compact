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
        assertEquals(1, LibraryDb.MIGRATION_1_2.startVersion)
        assertEquals(2, LibraryDb.MIGRATION_1_2.endVersion)
        assertTrue(statements.isNotEmpty())
        File("build/reports/m1-migration.sql").apply {
            checkNotNull(parentFile).mkdirs(); writeText(statements.joinToString(";\n", postfix = ";\n"))
        }
    }
    @Test fun migratedCompletedAndStagedRowsKeepTheirKeysUrisAndQueueOwnership() = runBlocking {
        val legacy = CameraObject(1, 2, "PUBLIC_TEST.JPG", 4, 1577901845000)
        val current = legacy.copy(handle = 99, cameraTime = "20200102T030405")
        for (imported in listOf(null, 123L)) {
            val original = Photo.from(legacy).copy(uri = "content://synthetic/1", imported = imported)
            val dao = Dao(listOf(original))
            dao.enqueue(listOf(Photo.from(current)))
            assertEquals(listOf(original), dao.rows.values.toList())
            val journal = RoomJournal(dao)
            if (imported == null) {
                assertEquals(legacy.key, journal.pending().single().key)
                journal.completed(legacy.key, original.uri!!)
                assertTrue(journal.pending().isEmpty())
            } else assertTrue(journal.pending().isEmpty())
            assertEquals(original.uri, journal.uri(legacy.key))
            assertEquals(setOf(legacy.key), dao.rows.keys)
        }
    }
}
