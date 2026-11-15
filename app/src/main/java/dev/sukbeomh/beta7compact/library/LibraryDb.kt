package dev.sukbeomh.beta7compact.library

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.sukbeomh.beta7compact.core.CameraObject
import dev.sukbeomh.beta7compact.core.ImportJournal
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "photos")
data class Photo(
    @PrimaryKey val key: String,
    val name: String, val size: Long, val captured: Long,
    val handle: Int, val storage: Int, val parent: Int, val pair: String,
    val uri: String? = null, val imported: Long? = null,
    val recipe: String? = null, // Reserved for M2; M1 never edits originals.
    @ColumnInfo(defaultValue = "NULL") val cameraTime: String? = null
) {
    fun cameraObject() = CameraObject(handle, storage, name, size, captured, parent, cameraTime)
    companion object { fun from(obj: CameraObject) = Photo(obj.key, obj.name, obj.size, obj.captured,
        obj.handle, obj.storage, obj.parent, obj.pair, cameraTime = obj.cameraTime) }
}
@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY captured DESC, name") fun observe(): Flow<List<Photo>>
    @Query("SELECT uri FROM photos WHERE uri IS NOT NULL") suspend fun uris(): List<String>
    @Query("SELECT * FROM photos WHERE imported IS NULL ORDER BY captured, name") suspend fun pending(): List<Photo>
    @Query("SELECT * FROM photos WHERE `key` = :key") suspend fun get(key: String): Photo?
    @Query("SELECT * FROM photos") suspend fun all(): List<Photo>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(photos: List<Photo>)
    @Transaction suspend fun enqueue(photos: List<Photo>) {
        val existing = all().flatMap { it.cameraObject().compatibleKeys }.toSet()
        insert(photos.filter { !it.cameraObject().isKnown(existing) }.distinctBy { it.key })
    }
    @Query("UPDATE photos SET uri = :uri WHERE `key` = :key") suspend fun staged(key: String, uri: String)
    @Query("UPDATE photos SET uri = :uri, imported = :now WHERE `key` = :key") suspend fun complete(key: String, uri: String, now: Long)
}
@Database(entities = [Photo::class], version = 2, exportSchema = true)
abstract class LibraryDb : RoomDatabase() {
    abstract fun photos(): PhotoDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserve v1 keys, pending URIs and completion records; compatibleKeys supplies aliases.
                db.execSQL("ALTER TABLE photos ADD COLUMN cameraTime TEXT DEFAULT NULL")
            }
        }
    }
}
class RoomJournal(private val dao: PhotoDao) : ImportJournal {
    override suspend fun pending() = dao.pending().map { it.cameraObject() }
    override suspend fun uri(key: String) = dao.get(key)?.uri
    override suspend fun staged(key: String, uri: String) = dao.staged(key, uri)
    override suspend fun completed(key: String, uri: String) = dao.complete(key, uri, System.currentTimeMillis())
}
