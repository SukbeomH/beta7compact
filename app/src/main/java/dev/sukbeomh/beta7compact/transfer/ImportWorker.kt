package dev.sukbeomh.beta7compact.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.MainActivity
import dev.sukbeomh.beta7compact.R
import dev.sukbeomh.beta7compact.core.ImportPipeline
import dev.sukbeomh.beta7compact.library.RoomJournal
import dev.sukbeomh.beta7compact.usb.UsbAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext as App
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setForeground(info(0, 0))
            UsbAccess.use(app, app.settings.getBoolean("raw", false)) { camera ->
                val store = MediaOriginalStore(app)
                store.cleanOrphans(app.db.photos().uris().toSet())
                ImportPipeline(RoomJournal(app.db.photos()), store, File(app.filesDir, "import.part"))
                    .run(camera, { !isStopped }) { done, total ->
                        setProgress(workDataOf("done" to done, "total" to total))
                        setForeground(info(done, total))
                    }
            }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Error messages are fixed UI text; never persist transport dumps or device identifiers.
            setProgress(workDataOf("error" to reason(e)))
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to reason(e)))
        }
    }
    private fun info(done: Int, total: Int): ForegroundInfo {
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("import", "원본 가져오기", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, "import").setSmallIcon(R.drawable.ic_camera)
            .setContentTitle("원본 가져오기").setContentText("$done / $total · USB 연결을 유지하세요")
            .setContentIntent(open).setOngoing(true).setProgress(total, done, total == 0)
            .addAction(0, "중단", WorkManager.getInstance(app).createCancelPendingIntent(id)).build()
        return ForegroundInfo(10, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    companion object {
        const val NAME = "usb-import"
        fun start(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ImportWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build())
        fun reason(e: Exception): String = when {
            e is SecurityException -> "권한이 없습니다. 앱에서 권한을 허용하세요"
            e.message?.contains("크기") == true -> "원본 크기가 일치하지 않습니다. 다시 받으세요"
            e.message?.startsWith("카메라 설정") == true -> "카메라 설정 → USB 연결 → MTP"
            else -> "USB 연결·MTP 설정·카드 상태를 확인하고 다시 받으세요"
        }
    }
}
