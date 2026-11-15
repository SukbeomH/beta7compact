package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.usb.readExclusiveMetadata
import org.junit.Test
import java.io.Closeable
import kotlin.test.*

class MetadataSessionTest {
    private class Owner {
        var owner: String? = "native"
        val calls = mutableListOf<String>()
        fun closeNative() { calls.add("close-native"); owner = null }
        fun openRaw(): Closeable {
            check(owner == null); owner = "raw"; calls.add("open-raw")
            return Closeable { check(owner == "raw"); owner = null; calls.add("close-raw") }
        }
        fun reopenNative() { check(owner == null); owner = "native"; calls.add("open-native") }
    }
    @Test fun metadataReadClosesNativeThenRawBeforeReopeningNative() {
        val usb = Owner()
        val value = readExclusiveMetadata(usb::closeNative, usb::openRaw, usb::reopenNative) {
            assertEquals("raw", usb.owner); usb.calls.add("read"); "20200308T023000.1Z"
        }
        assertEquals("20200308T023000.1Z", value)
        assertEquals(listOf("close-native", "open-raw", "read", "close-raw", "open-native"), usb.calls)
        assertEquals("native", usb.owner)
    }
    @Test fun failedMetadataReadClosesRawAndRestoresNative() {
        val usb = Owner()
        assertFails {
            readExclusiveMetadata(usb::closeNative, usb::openRaw, usb::reopenNative) { error("USB disconnected") }
        }
        assertEquals("native", usb.owner)
        assertEquals(listOf("close-native", "open-raw", "close-raw", "open-native"), usb.calls)
    }
    @Test fun rawOpenAndNativeReopenFailuresNeverLeaveRawOwned() {
        val usb = Owner()
        val failRaw: () -> Closeable = { error("raw open failed") }
        assertFails {
            readExclusiveMetadata(usb::closeNative, failRaw, usb::reopenNative) { }
        }
        assertEquals("native", usb.owner)
        assertFails {
            readExclusiveMetadata(usb::closeNative, usb::openRaw, { error("native reopen failed") }) { "metadata" }
        }
        assertNull(usb.owner)
    }
}
