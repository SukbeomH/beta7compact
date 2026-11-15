package dev.sukbeomh.beta7compact

import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.Wire
import android.util.Log
import dev.sukbeomh.beta7compact.usb.UsbAccess
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

/** Ported from independent r2/UsbChecks.kt; executes the real native/raw adapters and codec. */
class UsbCameraContractTest {
    private fun transfer(persist: Boolean, stall: Boolean = false, response: Int = 0x2001) = runBlocking {
        Wire.reset(persist, stall); Wire.openResponse = response; Log.messages.clear()
        val file = Files.createTempFile("mtp-contract", ".part").toFile()
        try {
            UsbAccess.use(Context(UsbManager()), false) { camera ->
                val item = camera.list().single()
                if (!stall && response == 0x2001) assertEquals(Wire.date, item.cameraTime)
                else { assertNull(item.cameraTime); assertEquals(1L, item.captured) }
                assertContentEquals(byteArrayOf(1, 2), camera.thumbnail(item))
                camera.download(item, file)
                assertContentEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
            }
            assertEquals(1, Wire.imports); assertEquals(1, Wire.thumbnails)
            assertEquals(0, Wire.setCalls); assertFalse(Wire.nativeOwned)
            if (stall || response != 0x2001) {
                assertTrue(Log.messages.all { "보강 없이 MTP로 진행" in it })
                assertEquals(2, Log.messages.size)
            }
        } finally { file.delete() }
    }
    @Test fun persistentSessionSupportsNativeListThumbnailAndImport() = transfer(true)
    @Test fun clearedSessionSupportsNativeListThumbnailAndImport() = transfer(false)
    @Test fun getInterfaceStallLeavesNativeListThumbnailAndImportUsable() = transfer(true, stall = true)
    @Test fun rejectedRawSessionLeavesNativeListThumbnailAndImportUsable() = transfer(true, response = 0x2019)
}
