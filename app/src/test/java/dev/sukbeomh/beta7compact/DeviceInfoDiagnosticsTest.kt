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

    @Test fun reportsDescriptorAndCapabilityDataWithoutPrivateStrings() = kotlinx.coroutines.runBlocking {
        android.hardware.usb.Wire.reset()
        android.hardware.usb.Wire.deviceInfo = fixture()
        val report = dev.sukbeomh.beta7compact.usb.UsbAccess.collectDiagnostics(
            android.content.Context(android.hardware.usb.UsbManager()))
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticOutcome.SUCCESS, report.outcome)
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticStage.COMPLETE, report.stage)
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticUsbSelection(1, 0, 0), report.activeSelection)
        assertEquals(0x2001, report.ptpResponseCode)
        assertEquals(512, report.devices.single().interfaces.single().endpoints.first().maxPacketSize)
        val exported = report.copy(info = report.info!!.copy(manufacturer = "PRIVATE-MANUFACTURER",
            model = "PRIVATE-MODEL", version = "PRIVATE-VERSION")).safeText()
        assertFalse(exported.contains("PRIVATE"))
        assertTrue(exported.contains("0x500F", ignoreCase = true))
        assertEquals(listOf(0x1001), android.hardware.usb.Wire.operations)
        assertEquals(0, android.hardware.usb.Wire.setCalls)
        assertEquals(1, android.hardware.usb.Wire.closed)
        assertFalse(android.hardware.usb.Wire.session)
    }

    @Test fun discoveryAndPermissionFailuresNeverSendCommands() = kotlinx.coroutines.runBlocking {
        val manager = android.hardware.usb.UsbManager()
        suspend fun collect() = dev.sukbeomh.beta7compact.usb.UsbAccess.collectDiagnostics(android.content.Context(manager))
        android.hardware.usb.Wire.reset()
        manager.deviceList.clear()
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticOutcome.NO_DEVICE, collect().outcome)
        manager.deviceList["private-path-a"] = android.hardware.usb.UsbDevice()
        manager.deviceList["private-path-b"] = android.hardware.usb.UsbDevice()
        val multiple = collect()
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticOutcome.MULTIPLE_DEVICES, multiple.outcome)
        assertFalse(multiple.safeText().contains("private-path"))
        manager.deviceList.remove("private-path-b")
        android.hardware.usb.Wire.permission = false
        val denied = collect()
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticOutcome.NO_PERMISSION, denied.outcome)
        assertEquals(1, denied.devices.size)
        assertTrue(android.hardware.usb.Wire.operations.isEmpty())
        assertEquals(0, android.hardware.usb.Wire.controls)
    }

    @Test fun unsupportedInterfaceAndPtpRejectionAreDistinct() = kotlinx.coroutines.runBlocking {
        android.hardware.usb.Wire.reset()
        val unsupported = android.hardware.usb.UsbDevice(listOf(android.hardware.usb.UsbConfiguration(
            faces = listOf(android.hardware.usb.UsbInterface(interfaceClass = 255)))))
        val first = dev.sukbeomh.beta7compact.usb.UsbAccess.collectDiagnostics(
            android.content.Context(android.hardware.usb.UsbManager(unsupported)))
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticOutcome.UNSUPPORTED_INTERFACE, first.outcome)
        assertTrue(android.hardware.usb.Wire.operations.isEmpty())
        android.hardware.usb.Wire.infoResponse = 0x2005
        val second = dev.sukbeomh.beta7compact.usb.UsbAccess.collectDiagnostics(
            android.content.Context(android.hardware.usb.UsbManager()))
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticOutcome.PTP_REJECTED, second.outcome)
        assertEquals(0x2005, second.ptpResponseCode)
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticStage.DEVICE_INFO, second.stage)
        assertEquals(1, android.hardware.usb.Wire.closed)
    }

    @Test fun malformedDataAndControlFailureKeepDescriptors() = kotlinx.coroutines.runBlocking {
        suspend fun collect() = dev.sukbeomh.beta7compact.usb.UsbAccess.collectDiagnostics(
            android.content.Context(android.hardware.usb.UsbManager()))
        android.hardware.usb.Wire.reset()
        val malformed = collect()
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticOutcome.INVALID_DATA, malformed.outcome)
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticStage.PARSE, malformed.stage)
        assertEquals(1, malformed.devices.size)
        android.hardware.usb.Wire.reset(stall = true)
        val stalled = collect()
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticOutcome.TRANSPORT_FAILURE, stalled.outcome)
        assertEquals(dev.sukbeomh.beta7compact.usb.DiagnosticStage.INTERFACE, stalled.stage)
        assertEquals(1, stalled.devices.size)
        assertEquals(1, android.hardware.usb.Wire.closed)
    }
}
