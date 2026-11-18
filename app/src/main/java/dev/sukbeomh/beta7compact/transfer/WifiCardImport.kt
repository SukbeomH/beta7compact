package dev.sukbeomh.beta7compact.transfer

import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.core.Camera
import dev.sukbeomh.beta7compact.core.CameraObject
import dev.sukbeomh.beta7compact.core.ImportPipeline
import dev.sukbeomh.beta7compact.core.PublishedState
import dev.sukbeomh.beta7compact.core.sha256
import dev.sukbeomh.beta7compact.library.Photo
import dev.sukbeomh.beta7compact.library.RoomJournal
import dev.sukbeomh.beta7compact.recipe.RecipeWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Gives the existing import pipeline one fully received Wi-Fi object at a time. */
internal suspend fun importWifiCardPhotos(app: App, client: SonyWifiCard, photos: List<WifiCardPhoto>,
                                         progress: suspend (Int) -> Unit) = withContext(Dispatchers.IO) {
    val context = currentCoroutineContext()
    val dao = app.db.photos()
    val store = MediaOriginalStore(app)
    val staged = File.createTempFile("wifi-card-", ".part", app.cacheDir)
    val publish = File(app.cacheDir, "${staged.name}.publish")
    try {
        for ((index, photo) in photos.withIndex()) {
            context.ensureActive()
            val bytes = client.download(photo, staged) { context.isActive }
            context.ensureActive()
            check(staged.length() == bytes) { "수신한 사진 크기가 일치하지 않습니다" }
            val key = "wifi:${sha256(staged.inputStream())}"
            val previous = dao.get(key)
            val uri = previous?.uri
            val state = if (previous?.imported != null && uri != null) store.publishedState(uri, bytes) else null
            check(state != PublishedState.UNVERIFIABLE) { "기존 사진의 저장 상태를 확인할 수 없습니다" }
            if (state != PublishedState.AVAILABLE) {
                val recordKey = if (state == PublishedState.ABSENT) "$key:${UUID.randomUUID()}" else key
                val item = previous?.takeIf { it.imported == null }?.cameraObject() ?: CameraObject(
                    photo.id.hashCode(), 0, photo.name, bytes, photo.captured, cameraTime = photo.cameraTime, recordKey = recordKey)
                dao.insert(listOf(Photo.from(item)))
                val camera = object : Camera {
                    override fun list() = listOf(item)
                    override fun thumbnail(item: CameraObject): ByteArray? = null
                    override fun download(item: CameraObject, destination: File) {
                        check(staged.renameTo(destination)) { "수신한 사진을 저장할 수 없습니다" }
                    }
                    override fun close() = Unit
                }
                val journal = RoomJournal(dao, wifiKey = item.key) { photoKey, result, now ->
                    RecipeWorker.imported(app, photoKey, result, now)
                }
                ImportPipeline(journal, store, publish).run(camera, active = { context.isActive })
            }
            staged.delete()
            progress(index + 1)
        }
    } finally {
        staged.delete()
        publish.delete()
    }
}
