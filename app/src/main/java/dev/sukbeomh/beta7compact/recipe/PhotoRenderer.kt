package dev.sukbeomh.beta7compact.recipe

import android.content.ContentValues
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import dev.sukbeomh.beta7compact.App
import dev.sukbeomh.beta7compact.library.Photo
import dev.sukbeomh.beta7compact.library.RecipeJob
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

object PhotoRenderer {
    // A single full destination bitmap; bounded 512^2 decode/upload/readback tiles.
    // Serialization also prevents preview/export EGL contexts from interfering.
    val lock = Any()
    const val ALBUM = "DCIM/beta7compact/Recipes/"
    fun reason(t: Throwable): String = when (t) {
        is OutOfMemoryError -> "메모리가 부족해 저장하지 못했습니다. 다른 앱을 닫고 다시 시도하세요"
        is SecurityException -> "사진 읽기 또는 결과 저장 권한이 없습니다"
        else -> when (t.message) {
            "ARW에 지원되는 내장 JPEG가 없습니다" -> "ARW에 지원되는 내장 JPEG가 없습니다"
            "이미지가 메모리 상한을 초과했습니다" -> "원해상도 처리 메모리 상한을 초과했습니다. 다른 앱을 닫고 다시 시도하세요"
            "선택한 LUT가 없습니다" -> "선택한 LUT가 없습니다. 다시 선택하세요"
            else -> "사진 처리에 실패했습니다. 파일 형식·저장 공간·GPU 지원을 확인하세요"
        }
    }
    class Source(val file: File, val original: File, val raw: Boolean) : AutoCloseable {
        override fun close() { if (file != original) file.delete();original.delete() }
    }
    fun source(app: App, photo: Photo): Source {
        val original=File.createTempFile("recipe-source-",".bin",app.cacheDir)
        var jpeg: File? = null
        try {
            app.contentResolver.openInputStream(Uri.parse(checkNotNull(photo.uri)))?.use { input ->
                original.outputStream().use { input.copyTo(it) }
            } ?: error("원본을 읽을 수 없습니다")
            val raw=photo.name.endsWith(".arw",true)
            if (raw) { jpeg=File.createTempFile("recipe-preview-",".jpg",app.cacheDir);ArwPreview.extract(original,jpeg) }
            return Source(jpeg ?: original,original,raw)
        } catch (t: Throwable) { jpeg?.delete();original.delete();throw t }
    }
    fun orientation(source: Source): Int = runCatching {
        ExifInterface(source.original).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)
    }.getOrDefault(1).let { if (it in 1..8) it else 1 }
    fun matrix(orientation: Int, width: Int, height: Int): Matrix {
        val w=width.toFloat();val h=height.toFloat()
        val v=when(orientation) {
            2 -> floatArrayOf(-1f,0f,w,0f,1f,0f,0f,0f,1f)
            3 -> floatArrayOf(-1f,0f,w,0f,-1f,h,0f,0f,1f)
            4 -> floatArrayOf(1f,0f,0f,0f,-1f,h,0f,0f,1f)
            5 -> floatArrayOf(0f,1f,0f,1f,0f,0f,0f,0f,1f)
            6 -> floatArrayOf(0f,-1f,h,1f,0f,0f,0f,0f,1f)
            7 -> floatArrayOf(0f,-1f,h,-1f,0f,w,0f,0f,1f)
            8 -> floatArrayOf(0f,1f,0f,-1f,0f,w,0f,0f,1f)
            else -> floatArrayOf(1f,0f,0f,0f,1f,0f,0f,0f,1f)
        }
        return Matrix().apply { setValues(v) }
    }
    data class Preview(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int, val orientation: Int)
    fun preview(app: App, photo: Photo): Preview = source(app,photo).use { s ->
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(s.file.path,options)
        require(options.outWidth>0 && options.outHeight>0)
        val width=options.outWidth;val height=options.outHeight
        options.inSampleSize=1;while(maxOf(width,height)/options.inSampleSize>1000) options.inSampleSize*=2
        options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888
        options.inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB)
        val bitmap=BitmapFactory.decodeFile(s.file.path,options) ?: error("미리보기를 읽을 수 없습니다")
        Preview(bitmap,width,height,orientation(s))
    }
    fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation==1) return bitmap
        return Bitmap.createBitmap(if(orientation>=5) bitmap.height else bitmap.width,
            if(orientation>=5) bitmap.width else bitmap.height,Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply { concat(matrix(orientation,bitmap.width,bitmap.height));drawBitmap(bitmap,0f,0f,null) }
        }
    }
    /** [copyTags] false writes only the tags this app sets, for sources whose EXIF cannot be carried over. */
    private fun copyExif(source: File, output: File, width: Int, height: Int, copyTags: Boolean) {
        val from=runCatching { ExifInterface(source) }.getOrNull();val to=ExifInterface(output)
        // Copy readable platform EXIF tags, excluding offsets, dimensions, orientation and stale thumbnail.
        // The maker note is left out: Sony's is tens of kilobytes and pushed the segment past JPEG's 64 KB limit.
        val structural=setOf("MakerNote","ImageWidth","ImageLength","PixelXDimension","PixelYDimension","Orientation","Software",
            "JPEGInterchangeFormat","JPEGInterchangeFormatLength","StripOffsets","StripByteCounts","RowsPerStrip",
            "Compression","PhotometricInterpretation","SamplesPerPixel","BitsPerSample","PlanarConfiguration",
            "TransferFunction","WhitePoint","PrimaryChromaticities","YCbCrCoefficients","YCbCrSubSampling",
            "YCbCrPositioning","ReferenceBlackWhite","ColorSpace","DefaultCropSize","DNGVersion","NewSubfileType","SubfileType")
        if(copyTags && from!=null) ExifInterface::class.java.fields.filter { it.name.startsWith("TAG_") && !it.name.contains("THUMBNAIL") && it.type==String::class.java }.forEach { field ->
            val tag=field.get(null) as String
            if(tag !in structural) from.getAttribute(tag)?.takeIf { it.length<=JpegFile.MAX_TAG_CHARS }?.let { to.setAttribute(tag,it) }
        }
        to.setAttribute(ExifInterface.TAG_ORIENTATION,"1");to.setAttribute(ExifInterface.TAG_SOFTWARE,"beta7compact ${dev.sukbeomh.beta7compact.BuildConfig.VERSION_NAME}")
        to.setAttribute(ExifInterface.TAG_IMAGE_WIDTH,width.toString());to.setAttribute(ExifInterface.TAG_IMAGE_LENGTH,height.toString())
        to.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION,width.toString());to.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION,height.toString())
        to.setAttribute(ExifInterface.TAG_COLOR_SPACE,"1");to.saveAttributes()
    }
    suspend fun export(app: App, photo: Photo, initial: RecipeJob): RecipeJob {
        val dao=app.db.recipes();var job=dao.job(initial.id) ?: error("작업이 없습니다")
        if(job.encoded && job.uri!=null) {
            val resultUri=Uri.parse(job.uri)
            val projection=arrayOf(MediaStore.Images.Media._ID)
            val cursor=if(Build.VERSION.SDK_INT>=30) app.contentResolver.query(resultUri,projection,
                Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE) },null)
            else {
                @Suppress("DEPRECATION")
                app.contentResolver.query(MediaStore.setIncludePending(resultUri),projection,null,null,null)
            }
            val exists=cursor?.use { it.moveToFirst() } ?: error("결과를 조회할 수 없습니다")
            if(exists) { publish(app,resultUri);dao.complete(job);return job }
            job=job.copy(uri=null,encoded=false);dao.update(job)
        }
        // Only URIs allocated for this result are ever removed. Original URI is never writable.
        job.uri?.let { app.contentResolver.delete(Uri.parse(it),null,null) }
        job=job.copy(uri=null,encoded=false);dao.update(job)
        val recipe=Recipe.fromJson(job.recipeJson);val lut=RecipeStore(app).load(recipe.lutId)
        val output=File.createTempFile("recipe-output-",".jpg",app.cacheDir)
        try {
            source(app,photo).use { s ->
                val execution=coroutineContext
                synchronized(lock) {
                    @Suppress("DEPRECATION") val decoder=BitmapRegionDecoder.newInstance(s.file.path,false) ?: error("지원하지 않는 사진입니다")
                    try {
                        val orientation=orientation(s);val width=decoder.width;val height=decoder.height
                        // Reserve headroom for decoder/native JPEG/GPU. Reject without downsampling.
                        val required=width.toLong()*height*4
                        require(required <= Runtime.getRuntime().maxMemory()*0.60) { "이미지가 메모리 상한을 초과했습니다" }
                        val distortion=ManualDistortion(width,height,recipe.distortion)
                        val result=Bitmap.createBitmap(if(orientation>=5) height else width,if(orientation>=5) width else height,Bitmap.Config.ARGB_8888)
                        try {
                            val canvas=Canvas(result);canvas.concat(matrix(orientation,width,height))
                            GpuEngine(lut).use { gpu ->
                                val options=BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888;inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB) }
                                for(y in 0 until height step 512) for(x in 0 until width step 512) {
                                    execution.ensureActive()
                                    val tile=if(recipe.distortion==0f) decoder.decodeRegion(Rect(x,y,minOf(x+512,width),minOf(y+512,height)),options) ?: error("타일을 읽을 수 없습니다")
                                    else DistortionRenderer.tile(decoder,distortion,x,y,minOf(512,width-x),minOf(512,height-y),options) {execution.ensureActive()}
                                    try { val rendered=gpu.render(tile,recipe,x,y,width,height)
                                        try { canvas.drawBitmap(rendered,x.toFloat(),y.toFloat(),null) } finally { rendered.recycle() }
                                    } finally { tile.recycle() }
                                }
                            }
                            fun write(copyTags: Boolean) {
                                output.outputStream().use { check(result.compress(Bitmap.CompressFormat.JPEG,95,it));it.fd.sync() }
                                copyExif(s.original,output,result.width,result.height,copyTags)
                            }
                            write(true)
                            // An oversized EXIF segment makes the file unreadable elsewhere; fall back to this app's own tags.
                            if(!JpegFile.isWellFormed(output)) write(false)
                            check(JpegFile.isWellFormed(output)) { "저장한 JPEG 구조가 올바르지 않습니다" }
                        } finally { result.recycle() }
                    } finally { decoder.recycle() }
                }
            }
            coroutineContext.ensureActive()
            val uri=app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,"recipe-${job.id}.jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,ALBUM);put(MediaStore.Images.Media.IS_PENDING,1)
            }) ?: error("결과를 만들 수 없습니다")
            job=job.copy(uri=uri.toString());dao.update(job)
            app.contentResolver.openOutputStream(uri,"w")?.use { out -> output.inputStream().use { it.copyTo(out) } } ?: error("결과를 기록할 수 없습니다")
            job=job.copy(encoded=true,created=System.currentTimeMillis());dao.update(job)
            coroutineContext.ensureActive();publish(app,uri);dao.complete(job)
            return job
        } finally { output.delete() }
    }
    private fun publish(app: App,uri: Uri) { check(app.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) },null,null)==1) }
}
