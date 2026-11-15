package dev.sukbeomh.beta7compact.recipe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import androidx.room.withTransaction
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.R
import dev.sukbeomh.beta7compact.library.RecipeJob
import kotlinx.coroutines.*
import java.util.UUID

class RecipeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app=applicationContext as App;val dao=app.db.recipes()
        val batch=inputData.getString("batch") ?: return@withContext Result.failure()
        val jobs=dao.jobs(batch);var done=jobs.count { it.status=="done" };var failed=0;var lastError: String?=null
        try { setForeground(info(done,jobs.size));setProgress(workDataOf("done" to done,"total" to jobs.size,"failed" to 0)) }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { return@withContext Result.failure(workDataOf("error" to "백그라운드 저장을 시작할 수 없습니다")) }
        for(initial in jobs) {
            ensureActive();if(initial.status=="done") continue
            var reason: String? = null
            try {
                val photo=app.db.photos().get(initial.photoKey) ?: error("원본이 없습니다")
                require(photo.imported!=null && photo.uri!=null)
                PhotoRenderer.export(app,photo,initial);done++
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { reason=PhotoRenderer.reason(e) }
            catch(e: OutOfMemoryError) { reason=PhotoRenderer.reason(e) }
            if(reason!=null) {
                failed++;lastError=reason
                val current=dao.job(initial.id) ?: initial
                if(!current.encoded) current.uri?.let { runCatching { app.contentResolver.delete(android.net.Uri.parse(it),null,null) } }
                dao.update(current.copy(status="failed",error=reason,uri=if(current.encoded) current.uri else null))
            }
            setProgress(workDataOf("done" to done,"failed" to failed,"total" to jobs.size,"error" to reason))
            setForeground(info(done,jobs.size))
        }
        if(failed==0) Result.success(workDataOf("done" to done,"total" to jobs.size))
        else Result.failure(workDataOf("done" to done,"failed" to failed,"total" to jobs.size,"error" to lastError))
    }
    private fun info(done: Int,total: Int): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("recipes","레시피 저장",NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(20,NotificationCompat.Builder(applicationContext,"recipes").setSmallIcon(R.drawable.ic_camera)
            .setContentTitle("레시피 저장").setContentText("$done / $total").setProgress(total,done,false).setOngoing(true)
            .addAction(0,"중단",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    companion object {
        const val NAME="recipe-export"
        suspend fun start(app: App, keys: List<String>, recipe: Recipe): String {
            require(keys.isNotEmpty())
            val batch=UUID.randomUUID().toString();val json=recipe.toJson();val now=System.currentTimeMillis()
            app.db.recipes().enqueue(keys.distinct().map { RecipeJob(UUID.randomUUID().toString(),batch,it,json,now) })
            schedule(app,batch);return batch
        }
        fun schedule(app: App,batch: String) = WorkManager.getInstance(app).enqueueUniqueWork(NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,OneTimeWorkRequestBuilder<RecipeWorker>().addTag("batch:$batch").setInputData(workDataOf("batch" to batch)).build())
        suspend fun imported(app: App, key: String, uri: String, now: Long) {
            val recipe = if (app.settings.getBoolean("autoRecipe", false))
                app.settings.getString("defaultRecipe", null)?.let { runCatching { Recipe.fromJson(it) }.getOrNull() }
                else null
            var batch: String? = null
            app.db.withTransaction {
                val prior = app.db.photos().get(key)
                app.db.photos().complete(key, uri, now)
                if (prior?.imported == null && recipe != null) {
                    batch = UUID.randomUUID().toString()
                    app.db.recipes().enqueue(listOf(RecipeJob(UUID.randomUUID().toString(),batch!!,key,recipe.toJson(),now)))
                }
            }
            // A scheduling failure cannot undo a completed original. Durable jobs can be resumed.
            batch?.let { runCatching { schedule(app,it) } }
        }
        suspend fun resume(app: App) {
            val manager=WorkManager.getInstance(app)
            val active=withContext(Dispatchers.IO) { manager.getWorkInfosForUniqueWork(NAME).get() }
            val scheduled=active.filter { !it.state.isFinished }.flatMap { it.tags }.toSet()
            val keys=app.db.photos().all().map { it.key }.toSet()
            app.db.recipes().unfinished().filter { it.photoKey in keys }.map { it.batchId }.distinct()
                .filter { "batch:$it" !in scheduled }.forEach { schedule(app,it) }
        }
    }
}
