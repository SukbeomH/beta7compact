package dev.sukbeomh.beta7compact.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recipes")
data class SavedRecipe(@PrimaryKey val id: String, val name: String, val json: String, val favorite: Boolean = false)
@Entity(tableName = "recipe_results", indices = [Index("photoKey")],
    foreignKeys = [ForeignKey(entity = Photo::class, parentColumns = ["key"], childColumns = ["photoKey"], onDelete = ForeignKey.NO_ACTION)])
data class RecipeResult(@PrimaryKey val id: String, val photoKey: String, val uri: String, val recipeJson: String, val created: Long)
@Entity(tableName = "recipe_jobs", indices = [Index("batchId")])
data class RecipeJob(@PrimaryKey val id: String, val batchId: String, val photoKey: String,
    val recipeJson: String, val created: Long, val status: String = "pending", val uri: String? = null,
    val encoded: Boolean = false, val error: String? = null)
@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY favorite DESC, name") fun observe(): Flow<List<SavedRecipe>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(recipe: SavedRecipe)
    @Query("UPDATE recipes SET favorite = :favorite WHERE id = :id") suspend fun favorite(id: String, favorite: Boolean)
    @Query("SELECT * FROM recipe_results WHERE photoKey = :key ORDER BY created DESC") fun results(key: String): Flow<List<RecipeResult>>
    @Insert suspend fun enqueue(jobs: List<RecipeJob>)
    @Query("SELECT * FROM recipe_jobs WHERE batchId = :batch ORDER BY created, id") suspend fun jobs(batch: String): List<RecipeJob>
    @Query("SELECT * FROM recipe_jobs WHERE status != 'done'") suspend fun unfinished(): List<RecipeJob>
    @Query("SELECT * FROM recipe_jobs WHERE id = :id") suspend fun job(id: String): RecipeJob?
    @Update suspend fun update(job: RecipeJob)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun result(result: RecipeResult)
    @Transaction suspend fun complete(job: RecipeJob) {
        result(RecipeResult(job.id,job.photoKey,checkNotNull(job.uri),job.recipeJson,job.created))
        update(job.copy(status = "done",error = null))
    }
}
