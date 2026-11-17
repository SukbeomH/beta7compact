package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.usb.Ptp
import dev.sukbeomh.beta7compact.usb.buffer
import org.junit.Test
import kotlin.test.*

class DeviceInfoDiagnosticsTest {
    private fun string(value: String) = byteArrayOf((value.length + 1).toByte()) +
        (value + "\u0000").toByteArray(Charsets.UTF_16LE)
    private fun array(vararg codes: Int) = buffer(4 + codes.size * 2).putInt(codes.size).apply {
        codes.forEach { putShort(it.toShort()) }
    }.array()
    private fun fixture() = buffer(8).putShort(100).putInt(6).putShort(100).array() +
        byteArrayOf(0) + buffer(2).putShort(0).array() + array(0x1001, 0x1014, 0x9280) +
        array(0x4002) + array(0x5007, 0x500D, 0x500F) + array(0x3801) + array(0x3801, 0xB101) +
        string("Sony") + string("ILCE-7C") + string("2.03") + string("PRIVATE-SERIAL")

    @Test fun retainsPropertiesSeparatelyFromEventsAndFormatsAndExcludesSerial() {
        val info = Ptp.deviceInfo(fixture())
        assertContentEquals(intArrayOf(0x1001, 0x1014, 0x9280), info.operations)
        assertContentEquals(intArrayOf(0x5007, 0x500D, 0x500F), info.properties)
        assertEquals("Sony", info.manufacturer)
        assertEquals("ILCE-7C", info.model)
        assertEquals("2.03", info.version)
        assertFalse(info.toString().contains("PRIVATE-SERIAL"))
    }

    @Test fun diagnosticUsesOnlyDeviceInfoAndReleasesConnection() = kotlinx.coroutines.runBlocking {
        android.hardware.usb.Wire.reset()
        android.hardware.usb.Wire.deviceInfo = fixture()
        val result = dev.sukbeomh.beta7compact.usb.UsbAccess.inspect(
            android.content.Context(android.hardware.usb.UsbManager()))
        assertEquals("ILCE-7C", result.model)
        assertEquals(listOf(0x1001), android.hardware.usb.Wire.operations)
        assertEquals(0, android.hardware.usb.Wire.setCalls)
        assertEquals(1, android.hardware.usb.Wire.closed)
        assertFalse(android.hardware.usb.Wire.session)
    }

    @Test fun malformedDiagnosticReleasesConnection() = kotlinx.coroutines.runBlocking {
        android.hardware.usb.Wire.reset()
        assertFails {
            dev.sukbeomh.beta7compact.usb.UsbAccess.inspect(
                android.content.Context(android.hardware.usb.UsbManager()))
        }
        assertEquals(listOf(0x1001), android.hardware.usb.Wire.operations)
        assertEquals(1, android.hardware.usb.Wire.closed)
    }

    @Test fun rejectsTruncatedOrTrailingDeviceInfo() {
        val data = fixture()
        for (size in data.indices) assertFails { Ptp.deviceInfo(data.copyOf(size)) }
        assertFails { Ptp.deviceInfo(data + byteArrayOf(1)) }
    }
}
