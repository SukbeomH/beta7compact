package dev.sukbeomh.beta7compact

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.sukbeomh.beta7compact.library.LibraryDb
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.*

class RecipeMigrationTest {
    @Test fun emitsActualM2MigrationForSQLiteValidation() {
        val sql=mutableListOf<String>()
        val db=Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,arrayOf(SupportSQLiteDatabase::class.java)) { _, method,args ->
            assertEquals("execSQL",method.name);sql.add(args!![0] as String);null
        } as SupportSQLiteDatabase
        LibraryDb.MIGRATION_3_4.migrate(db)
        assertEquals(3,LibraryDb.MIGRATION_3_4.startVersion);assertEquals(4,LibraryDb.MIGRATION_3_4.endVersion)
        File("build/reports/m2-migration.sql").apply { checkNotNull(parentFile).mkdirs();writeText(sql.joinToString(";\n",postfix=";\n")) }
    }
}
