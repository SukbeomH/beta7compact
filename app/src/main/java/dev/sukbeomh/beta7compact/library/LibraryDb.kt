package dev.sukbeomh.beta7compact.library

import androidx.room.*
import dev.sukbeomh.beta7compact.core.CameraObject
import dev.sukbeomh.beta7compact.core.ImportJournal
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "photos")
data class Photo(
    @PrimaryKey val key: String,
    val name: String, val size: Long, val captured: Long,
    val handle: Int, val storage: Int, val parent: Int, val pair: String,
    val uri: String? = null, val imported: Long? = null,
    val recipe: String? = null // Reserved for M2; M1 never edits originals.
) {
    fun cameraObject() = CameraObject(handle, storage, name, size, captured, parent)
    companion object { fun from(obj: CameraObject) = Photo(obj.key, obj.name, obj.size, obj.captured,
        obj.handle, obj.storage, obj.parent, obj.pair) }
}
@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY captured DESC, name") fun observe(): Flow<List<Photo>>
    @Query("SELECT uri FROM photos WHERE uri IS NOT NULL") suspend fun uris(): List<String>
    @Query("SELECT * FROM photos WHERE imported IS NULL ORDER BY captured, name") suspend fun pending(): List<Photo>
    @Query("SELECT * FROM photos WHERE `key` = :key") suspend fun get(key: String): Photo?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueue(photos: List<Photo>)
    @Query("UPDATE photos SET uri = :uri WHERE `key` = :key") suspend fun staged(key: String, uri: String)
    @Query("UPDATE photos SET uri = :uri, imported = :now WHERE `key` = :key") suspend fun complete(key: String, uri: String, now: Long)
}
@Database(entities = [Photo::class], version = 1, exportSchema = true)
abstract class LibraryDb : RoomDatabase() { abstract fun photos(): PhotoDao }
class RoomJournal(private val dao: PhotoDao) : ImportJournal {
    override suspend fun pending() = dao.pending().map { it.cameraObject() }
    override suspend fun uri(key: String) = dao.get(key)?.uri
    override suspend fun staged(key: String, uri: String) = dao.staged(key, uri)
    override suspend fun completed(key: String, uri: String) = dao.complete(key, uri, System.currentTimeMillis())
}
