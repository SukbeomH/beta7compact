package dev.sukbeomh.beta7compact

import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.Wire
import dev.sukbeomh.beta7compact.core.Camera
import dev.sukbeomh.beta7compact.core.CameraObject
import dev.sukbeomh.beta7compact.usb.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DeviceFeedbackUsbTest {
    @Test fun nativeOpenAndListingFailuresRetryRawWithinOneCallerAndRememberIt() = runBlocking {
        for (failure in listOf("open", "info", "storage", "handles", "object")) {
            Wire.reset(); Wire.nativeFailure = failure
            val routes = mutableListOf<Boolean>()
            val reasons = mutableListOf<MtpFailure>()
            var calls = 0
            UsbAccess.use(Context(UsbManager()), false, routes::add, reasons::add) {
                calls++
                assertEquals(Wire.date, it.list().single().cameraTime)
                assertEquals(1, it.list().size)
            }
            assertEquals(1, calls)
            assertTrue(routes.all { it }); assertEquals(2, routes.size)
            assertEquals(listOf(if (failure in listOf("open", "info")) MtpFailure.OPEN else MtpFailure.LIST), reasons)
            assertEquals(0, Wire.setCalls); assertFalse(Wire.nativeOwned)
        }
    }
    @Test fun partialMtpDownloadIsTruncatedAndRetriedBeforeReturningToPipeline() = runBlocking {
        Wire.reset()
        val file = Files.createTempFile("fb012", ".part").toFile()
        val routes = mutableListOf<Boolean>()
        val reasons = mutableListOf<MtpFailure>()
        try {
            UsbAccess.use(Context(UsbManager()), false, routes::add, reasons::add) { camera ->
                val item = camera.list().single()
                Wire.nativeFailure = "download"
                camera.download(item, file)
                assertContentEquals(byteArrayOf(1,2,3,4), file.readBytes())
                camera.download(item, file)
            }
            assertEquals(listOf(false, true, true), routes)
            assertEquals(listOf(MtpFailure.DOWNLOAD), reasons)
            assertEquals(1, Wire.imports)
            assertEquals(2, Wire.operations.count { it == 0x1009 })
            assertEquals(0, Wire.setCalls); assertFalse(Wire.nativeOwned)
        } finally { file.delete() }
    }
    @Test fun failedThumbnailAlsoHandsOffToRawOnce() = runBlocking {
        Wire.reset()
        UsbAccess.use(Context(UsbManager()), false) { camera ->
            val item = camera.list().single()
            Wire.nativeFailure = "thumb"
            assertContentEquals(byteArrayOf(1,2), camera.thumbnail(item))
            assertContentEquals(byteArrayOf(1,2), camera.thumbnail(item))
        }
        assertEquals(1, Wire.thumbnails)
        assertEquals(0, Wire.setCalls); assertFalse(Wire.nativeOwned)
    }
    @Test fun bothTransportsFailWithoutLoopOrRememberingRawSuccess() = runBlocking {
        Wire.reset(); Wire.nativeFailure = "storage"; Wire.openResponse = 0x2019
        val routes = mutableListOf<Boolean>()
        val reasons = mutableListOf<MtpFailure>()
        assertFails {
            UsbAccess.use(Context(UsbManager()), false, routes::add, reasons::add) { it.list() }
        }
        assertEquals(listOf(MtpFailure.LIST), reasons)
        assertTrue(routes.isEmpty())
        // Optional metadata raw open + one fallback raw open, no third attempt.
        assertEquals(2, Wire.rawOpens)
        assertEquals(0, Wire.setCalls); assertFalse(Wire.nativeOwned)
    }
    @Test fun explicitlyRawDoesNotOpenMtp() = runBlocking {
        Wire.reset()
        val routes = mutableListOf<Boolean>()
        UsbAccess.use(Context(UsbManager()), true, routes::add) { it.list() }
        assertEquals(listOf(true), routes); assertEquals(0, Wire.nativeOpens)
    }
    @Test fun queuedUsbRequestsReadRememberedRouteOnlyAfterAcquiringOwnership() = runBlocking {
        Wire.reset()
        var raw = false
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            UsbAccess.use(Context(UsbManager()), { raw }, { raw = it }) { camera ->
                entered.complete(Unit)
                release.await()
                Wire.nativeFailure = "storage"
                camera.list()
            }
        }
        entered.await()
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            UsbAccess.use(Context(UsbManager()), { raw }, { raw = it }) { camera ->
                val before = Wire.nativeOpens
                camera.list()
                assertEquals(before, Wire.nativeOpens)
            }
        }
        release.complete(Unit)
        holder.join(); queued.join()
        assertTrue(raw); assertFalse(Wire.nativeOwned); assertEquals(0, Wire.setCalls)
    }
    @Test fun identityDiskSecurityAndCancellationFailuresNeverOpenFallback() {
        for (failure in listOf(IllegalStateException("카드 내용이 변경되었습니다"),
            java.io.IOException("disk"), SecurityException(), CancellationException())) {
            var rawOpens = 0
            val native = object : Camera {
                override fun list(): List<CameraObject> = throw failure
                override fun thumbnail(item: CameraObject): ByteArray? = throw failure
                override fun download(item: CameraObject, destination: File) { throw failure }
                override fun close() {}
            }
            FallbackCamera(false, { native }, { rawOpens++; native }, { fail("must not remember") },
                { fail("must not fall back") }).use { assertSame(failure, assertFails { it.list() }) }
            assertEquals(0, rawOpens)
        }
        assertFailsWith<SecurityException> { mtpRead(MtpFailure.LIST) { throw SecurityException() } }
        assertFailsWith<CancellationException> { mtpRead(MtpFailure.LIST) { throw CancellationException() } }
    }
}
