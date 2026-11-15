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
    @ColumnInfo(defaultValue = "NULL") val cameraTime: String? = null,
    @ColumnInfo(defaultValue = "NULL") val contentHash: String? = null
) {
    fun cameraObject() = CameraObject(handle, storage, name, size, captured, parent, cameraTime, recordKey = key)
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
        val rows = all().toMutableList()
        val known = rows.flatMap { it.cameraObject().compatibleKeys }.toMutableSet()
        for (photo in photos.distinctBy { it.key }) {
            val obj = photo.cameraObject()
            if (obj.isKnown(known)) continue
            if (rows.any { it.imported == null && it.cameraObject().let { queued ->
                    queued.sameLocation(obj) && queued.metadataKey == obj.metadataKey } }) continue
            // A fallback record can be selected again. Reverify bytes rather than letting IGNORE
            // on an old metadata primary key silently hide a possible new photo.
            val fresh = if (rows.any { it.key == photo.key })
                photo.copy(key = java.util.UUID.randomUUID().toString()) else photo
            insert(listOf(fresh))
            rows.add(fresh)
            known.addAll(obj.compatibleKeys)
        }
    }
    @Query("UPDATE photos SET contentHash = :hash WHERE `key` = :key") suspend fun hashed(key: String, hash: String)
    @Query("UPDATE photos SET uri = :uri WHERE `key` = :key") suspend fun staged(key: String, uri: String)
    @Query("UPDATE photos SET uri = :uri, imported = :now WHERE `key` = :key") suspend fun complete(key: String, uri: String, now: Long)
}
@Database(entities = [Photo::class, SavedRecipe::class, RecipeResult::class, RecipeJob::class], version = 4, exportSchema = true)
abstract class LibraryDb : RoomDatabase() {
    abstract fun photos(): PhotoDao
    abstract fun recipes(): RecipeDao
    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS recipes (id TEXT NOT NULL, name TEXT NOT NULL, json TEXT NOT NULL, favorite INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS recipe_results (id TEXT NOT NULL, photoKey TEXT NOT NULL, uri TEXT NOT NULL, recipeJson TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(photoKey) REFERENCES photos(`key`) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recipe_results_photoKey ON recipe_results(photoKey)")
                db.execSQL("CREATE TABLE IF NOT EXISTS recipe_jobs (id TEXT NOT NULL, batchId TEXT NOT NULL, photoKey TEXT NOT NULL, recipeJson TEXT NOT NULL, created INTEGER NOT NULL, status TEXT NOT NULL, uri TEXT, encoded INTEGER NOT NULL, error TEXT, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_recipe_jobs_batchId ON recipe_jobs(batchId)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN contentHash TEXT DEFAULT NULL")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Preserve v1 keys, pending URIs and completion records; metadata aliases remain hints only.
                db.execSQL("ALTER TABLE photos ADD COLUMN cameraTime TEXT DEFAULT NULL")
            }
        }
    }
}
class RoomJournal(private val dao: PhotoDao,
    private val onComplete: (suspend (String, String, Long) -> Unit)? = null) : ImportJournal {
    override suspend fun pending() = dao.pending().map { it.cameraObject() }
    override suspend fun completedItems() = dao.all().filter { it.imported != null }.map { it.cameraObject() }
    override suspend fun contentHash(key: String) = dao.get(key)?.contentHash
    override suspend fun hashed(key: String, hash: String) = dao.hashed(key, hash)
    override suspend fun uri(key: String) = dao.get(key)?.uri
    override suspend fun staged(key: String, uri: String) = dao.staged(key, uri)
    override suspend fun completed(key: String, uri: String) {
        val now = System.currentTimeMillis()
        onComplete?.invoke(key, uri, now) ?: dao.complete(key, uri, now)
    }
}
