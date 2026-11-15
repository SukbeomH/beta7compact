package dev.sukbeomh.beta7compact.recipe

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.test.InstrumentationTestCase
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.library.Photo
import dev.sukbeomh.beta7compact.library.RecipeJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Writes and cleans up ONLY newly created synthetic fixtures in the target app. */
@Suppress("DEPRECATION")
class ExportContractTest : InstrumentationTestCase() {
    fun testFullResolutionNewJpegExifSnapshotOriginalHashAndRecovery() = runBlocking {
        val app=instrumentation.targetContext.applicationContext as App
        val key="public-test-${UUID.randomUUID()}";val id=UUID.randomUUID().toString()
        val file=File.createTempFile("public-fixture-",".jpg",app.cacheDir)
        var originalUri: Uri?=null;var resultUri: Uri?=null
        try {
            val bitmap=Bitmap.createBitmap(24,16,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.rgb(63,117,189))
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) } } finally {bitmap.recycle()}
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION,"6")
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,"2020:01:02 03:04:05")
                saveAttributes()
            }
            originalUri=app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,"$key.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,"DCIM/beta7compact/TestFixtures/")
            })!!
            app.contentResolver.openOutputStream(originalUri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            fun hash(uri: Uri)=app.contentResolver.openInputStream(uri)!!.use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()) }
            val before=hash(originalUri)
            val photo=Photo(key,"PUBLIC.JPG",file.length(),0,1,1,1,"public",originalUri.toString(),1)
            app.db.photos().insert(listOf(photo))
            val recipe=Recipe("portra400",.7f,grain=Grain(2f,.5f,71))
            val job=RecipeJob(id,"public-batch-$id",key,recipe.toJson(),1234)
            app.db.recipes().enqueue(listOf(job))
            PhotoRenderer.export(app,photo,job)
            val completed=app.db.recipes().job(id)!!;resultUri=Uri.parse(completed.uri)
            assertEquals("done",completed.status);assertTrue(completed.encoded)
            assertFalse(resultUri==originalUri);assertTrue(before.contentEquals(hash(originalUri)))
            app.contentResolver.openInputStream(resultUri)!!.use {
                val exif=ExifInterface(it)
                assertEquals(1,exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,0))
                assertEquals(16,exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH,0))
                assertEquals(24,exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH,0))
                assertEquals("2020:01:02 03:04:05",exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
                assertEquals("beta7compact 0.3.0",exif.getAttribute(ExifInterface.TAG_SOFTWARE))
            }
            // Simulate interruption after encoding but before publication/Room completion.
            app.contentResolver.update(resultUri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,1) },null,null)
            app.db.recipes().update(completed.copy(status="pending"))
            PhotoRenderer.export(app,photo,completed)
            assertEquals(completed.uri,app.db.recipes().job(id)!!.uri)
            val db=app.db.openHelper.readableDatabase
            db.query("SELECT uri, recipeJson, created FROM recipe_results WHERE id = ?",arrayOf(id)).use { c ->
                assertTrue(c.moveToFirst());assertEquals(completed.uri,c.getString(0));assertEquals(recipe.toJson(),c.getString(1));assertEquals(completed.created,c.getLong(2));assertTrue(completed.created>1234L);assertFalse(c.moveToNext())
            }
            assertEquals(originalUri.toString(),app.db.photos().get(key)!!.uri)
        } finally {
            resultUri?.let { app.contentResolver.delete(it,null,null) }
            originalUri?.let { app.contentResolver.delete(it,null,null) }
            val db=app.db.openHelper.writableDatabase
            db.execSQL("DELETE FROM recipe_results WHERE id = ?",arrayOf(id))
            db.execSQL("DELETE FROM recipe_jobs WHERE id = ?",arrayOf(id))
            db.execSQL("DELETE FROM photos WHERE `key` = ?",arrayOf(key))
            file.delete()
        }
    }
}
