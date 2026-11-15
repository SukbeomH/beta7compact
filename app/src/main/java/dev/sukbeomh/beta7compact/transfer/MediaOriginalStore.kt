package dev.sukbeomh.beta7compact.transfer

import android.content.ContentValues
import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import dev.sukbeomh.beta7compact.core.CameraObject
import dev.sukbeomh.beta7compact.core.OriginalStore
import java.io.File

class MediaOriginalStore(context: Context) : OriginalStore {
    private val resolver = context.contentResolver
    private val owner = context.packageName
    override fun begin(item: CameraObject): String {
        require(item.valid)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, item.name)
            put(MediaStore.Images.Media.MIME_TYPE, item.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, CameraObject.PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            if (item.captured > 0) put(MediaStore.Images.Media.DATE_TAKEN, item.captured)
        }
        return checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)) {
            "갤러리 임시 항목을 만들 수 없습니다"
        }.toString()
    }
    override fun recover(uri: String, size: Long): Boolean {
        val target = Uri.parse(uri)
        return resolver.query(target, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)?.use {
            if (!it.moveToFirst() || it.getInt(0) != 0) false else {
                check(length(target) == size) { "게시된 원본이 변경되었습니다. 갤러리 상태를 확인하세요" }
                true
            }
        } ?: false
    }
    private fun length(uri: Uri) = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    override fun write(uri: String, source: File) {
        checkNotNull(resolver.openOutputStream(Uri.parse(uri), "wt")) { "갤러리 쓰기 실패" }.use { out ->
            source.inputStream().use { it.copyTo(out) }
        }
    }
    override fun publish(uri: String, size: Long) {
        val target = Uri.parse(uri)
        check(length(target) == size) { "갤러리 파일 크기가 일치하지 않습니다" }
        check(resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) {
            "갤러리 게시에 실패했습니다"
        }
    }
    override fun discard(uri: String) {
        val target = Uri.parse(uri)
        resolver.query(target, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)?.use {
            if (it.moveToFirst()) {
                check(it.getInt(0) == 1) { "게시된 원본은 삭제하지 않습니다" }
                resolver.delete(target, null, null)
            }
        }
    }
    /** Repair a process death between MediaStore insert and Room URI journaling. */
    fun cleanOrphans(known: Set<String>) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.IS_PENDING} = 1 AND ${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        val arguments = arrayOf(owner, CameraObject.PATH)
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            resolver.query(collection, projection, Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arguments)
            }, null)
        } else {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(collection), projection, selection, arguments, null)
        }
        cursor?.use {
            while (cursor.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)).toString()
                if (uri !in known) discard(uri)
            }
        }
    }
}
