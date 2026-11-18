package dev.sukbeomh.beta7compact.perspective

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import dev.sukbeomh.beta7compact.recipe.PhotoRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.coroutineContext

object LocalWarpPhotoIO {
    const val PREVIEW_MAX_SIDE = 1200
    const val OUTPUT_MAX_SIDE = 2048
    const val ALBUM = "Pictures/beta7compact/Experiments/"
    private const val MAX_PIXELS = 4_194_304L
    private const val MAX_INPUT_BYTES = 64L * 1024 * 1024
    private const val INPUT_CACHE_BYTES = 128L * 1024 * 1024
    private const val STALE_INPUT_MILLIS = 24L * 60 * 60 * 1000
    private val snapshotLock = Mutex()

    /** The caller deletes a successful snapshot on replacement/close. Active snapshots are not evicted for quota. */
    suspend fun snapshot(context: Context, uri: Uri): File {
        var owned: File? = null
        try {
            return withContext(Dispatchers.IO) {
                snapshotLock.withLock {
                    val execution = coroutineContext
                    execution.ensureActive()
                    val directory = File(context.cacheDir, "local-warp-inputs")
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create experiment input cache")
                    val cutoff = System.currentTimeMillis() - STALE_INPUT_MILLIS
                    directory.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
                    val used = directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() }
                        ?: throw IOException("Cannot inspect experiment input cache")
                    // Reserve the full per-input limit before starting, so encoded files stay within128MiB.
                    if (used > INPUT_CACHE_BYTES - MAX_INPUT_BYTES) throw IOException("Experiment input cache is full")
                    val file = File.createTempFile("local-input-", ".jpg", directory)
                    owned = file
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output ->
                            val size = copyLocalWarpInput(input, output, MAX_INPUT_BYTES) { execution.ensureActive() }
                            if (size == 0L) throw IOException("Empty experiment input")
                            output.flush()
                            output.fd.sync()
                        }
                    } ?: throw IOException("Cannot open experiment input")
                    execution.ensureActive()
                    file
                }
            }
        } catch (failure: Throwable) {
            // Includes prompt cancellation while delivering the successfully copied File to the UI.
            owned?.let { file ->
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    try {
                        if (file.exists() && !file.delete()) throw IOException("Cannot remove incomplete experiment input")
                    } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                }
            }
            throw failure
        }
    }

    /** The caller owns the returned bitmap; this function never recycles a displayed image. */
    suspend fun load(context: Context, uri: Uri): Bitmap {
        var decoded: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) { decode(context, uri, PREVIEW_MAX_SIDE).also { decoded = it } }
        } catch (failure: Throwable) {
            decoded?.recycle()
            throw failure
        }
    }

    suspend fun save(context: Context, uri: Uri, config: LocalWarpConfig): Uri {
        var completed: Uri? = null
        try {
            return withContext(Dispatchers.IO) { saveInBackground(context, uri, config).also { completed = it } }
        } catch (failure: Throwable) {
            // withContext may cancel while delivering its completed result back to the UI.
            completed?.let { output ->
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    try { context.contentResolver.delete(output, null, null) }
                    catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                }
            }
            throw failure
        }
    }

    private suspend fun saveInBackground(context: Context, uri: Uri, config: LocalWarpConfig): Uri {
        val source = decode(context, uri, OUTPUT_MAX_SIDE)
        var rendered: Bitmap? = null
        try {
            val execution = coroutineContext
            execution.ensureActive()
            val pixels = IntArray(source.width * source.height)
            source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            val warped = LocalWarp.render(pixels, source.width, source.height, config) { execution.ensureActive() }
            execution.ensureActive()
            val result = Bitmap.createBitmap(warped, source.width, source.height, Bitmap.Config.ARGB_8888)
            rendered = result
            val resolver = context.contentResolver
            return publishLocalWarpOutput(
                create = {
                    resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "beta7c-local-${UUID.randomUUID()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }) ?: throw IOException("Cannot create experiment output")
                },
                write = { output ->
                    resolver.openOutputStream(output, "w")?.use { stream ->
                        execution.ensureActive()
                        if (!result.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw IOException("JPEG encoding failed")
                        stream.flush()
                        execution.ensureActive()
                    } ?: throw IOException("Cannot open experiment output")
                },
                publish = { output ->
                    val count = resolver.update(output, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    if (count != 1) throw IOException("Cannot publish experiment output")
                },
                delete = { output -> resolver.delete(output, null, null); Unit },
            )
        } finally {
            rendered?.recycle()
            source.recycle()
        }
    }

    private suspend fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver
        coroutineContext.ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); Unit }
            ?: throw IOException("Cannot read image bounds")
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0) { "JPEG input required" }
        val sample = localWarpSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        val width = (bounds.outWidth.toLong() + sample - 1) / sample
        val height = (bounds.outHeight.toLong() + sample - 1) / sample
        val pixels = width * height
        require(pixels in 1..MAX_PIXELS && pixels * 20 <= Runtime.getRuntime().maxMemory() * 0.45) { "Experiment image memory limit exceeded" }
        coroutineContext.ensureActive()
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }?.takeIf { it in 1..8 } ?: ExifInterface.ORIENTATION_NORMAL
        coroutineContext.ensureActive()
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Cannot decode JPEG")
        var upright: Bitmap? = null
        var handedOff = false
        try {
            coroutineContext.ensureActive()
            require(decoded.width <= maxSide && decoded.height <= maxSide && decoded.width.toLong() * decoded.height <= MAX_PIXELS)
            upright = PhotoRenderer.upright(decoded, orientation)
            coroutineContext.ensureActive()
            handedOff = true
            return checkNotNull(upright)
        } finally {
            if (!handedOff && upright !== decoded) upright?.recycle()
            if (upright !== decoded || !handedOff) decoded.recycle()
        }
    }
}
