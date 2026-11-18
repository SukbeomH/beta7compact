package dev.sukbeomh.beta7compact.library

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.room.withTransaction
import dev.sukbeomh.beta7compact.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.File
import kotlin.coroutines.coroutineContext

object ExternalPhotoImporter {
    enum class Result { ADDED, ALREADY_ADDED }
    private val lock = Mutex()
    suspend fun add(app: App, source: Uri): Pair<String,Result> = withContext(Dispatchers.IO) {
        require(source.scheme=="content") { "시스템 선택기로 이미지를 선택해 주세요" }
        lock.withLock {
            val execution=coroutineContext
            val directory=File(app.cacheDir,"external-images").apply {check(isDirectory || mkdirs())}
            directory.listFiles()?.filter {it.isFile}?.forEach {check(it.delete()) {"임시 사진을 정리할 수 없습니다"}}
            val file=File.createTempFile("image-",".bin",directory)
            try {
                val bytes=app.contentResolver.openInputStream(source)?.use { input ->
                    file.outputStream().use { output -> copyExternalImage(input,output) {execution.ensureActive()} }
                } ?: error("선택한 이미지를 읽을 수 없습니다")
                val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeFile(file.path,options)
                val mime=options.outMimeType ?: error("이미지 형식을 확인할 수 없습니다")
                require(mime in ExternalImagePolicy.extensions && options.outWidth>0 && options.outHeight>0) { "지원하지 않는 이미지 형식입니다" }
                @Suppress("DEPRECATION")
                val decoder=BitmapRegionDecoder.newInstance(file.path,false) ?: error("이 기기에서 편집할 수 없는 이미지입니다")
                try {
                    val probe=decoder.decodeRegion(Rect(0,0,minOf(8,decoder.width),minOf(8,decoder.height)),null)
                        ?: error("이미지 데이터를 읽을 수 없습니다")
                    probe.recycle()
                } finally { decoder.recycle() }
                execution.ensureActive()
                val dao=app.db.photos();val existing=dao.get(bytes.key)
                val matches=if(existing?.uri==null) false else try {
                    app.contentResolver.openInputStream(Uri.parse(existing.uri))?.use { input ->
                        val discard=object:OutputStream() {
                            override fun write(value:Int) {}
                            override fun write(data:ByteArray,offset:Int,length:Int) {}
                        }
                        copyExternalImage(input,discard) {execution.ensureActive()}.hash==bytes.hash
                    } ?: false
                } catch(e: kotlinx.coroutines.CancellationException) {throw e}
                catch(e: Exception) {false}
                if(matches) return@withLock bytes.key to Result.ALREADY_ADDED
                val displayName=runCatching {
                    app.contentResolver.query(source,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                        val column=it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if(column>=0 && it.moveToFirst()) it.getString(column) else null
                    }
                }.getOrNull()
                val name=ExternalImagePolicy.name(displayName,mime);val now=System.currentTimeMillis()
                publishExternalImage(
                    create={ app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,mime)
                        put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/beta7compact/External/")
                        put(MediaStore.Images.Media.IS_PENDING,1)
                    }) ?: error("사진 사본을 만들 수 없습니다") },
                    write={ target ->
                        app.contentResolver.openOutputStream(target,"w")?.use { output ->
                            file.inputStream().use { input -> copyExternalImage(input,output) {execution.ensureActive()} }
                        } ?: error("사진 사본을 기록할 수 없습니다")
                    },
                    publish={ target -> check(app.contentResolver.update(target,ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING,0)
                    },null,null)==1) {"사진 사본을 공개할 수 없습니다"} },
                    record={ target -> app.db.withTransaction {
                        dao.insert(listOf(Photo(bytes.key,name,bytes.size,now,0,0,0,bytes.key,
                            uri=target.toString(),imported=now,contentHash=bytes.hash)))
                        dao.complete(bytes.key,target.toString(),now)
                    } },
                    delete={ target -> app.contentResolver.delete(target,null,null);Unit }
                )
                bytes.key to Result.ADDED
            } finally { file.delete() }
        }
    }
}
