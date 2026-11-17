package dev.sukbeomh.beta7compact

import android.content.Context
import android.hardware.usb.*
import dev.sukbeomh.beta7compact.remote.AndroidSonyRemoteWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import kotlin.test.*

/** Android descriptor/request contract doubles, not a camera or phone test. */
class AndroidSonyRemoteWireTest {
    private fun face(id: Int = 0, alternate: Int = 0, events: Boolean = true) = UsbInterface(
        id = id, alternateSetting = alternate, endpoints = listOf(UsbEndpoint(2, 128, 0x81), UsbEndpoint(2, 0, 0x02)) +
            if (events) listOf(UsbEndpoint(3, 128, 0x83, 64)) else emptyList())
    private fun context(vararg faces: UsbInterface) = Context(UsbManager(UsbDevice(listOf(UsbConfiguration(1, faces.toList())))))

    @Test fun selectsActiveConfigurationAndAlternateWithoutSetRequests() {
        Wire.reset()
        RemoteUsbFake.activeConfig = 2
        RemoteUsbFake.activeAlternate = 1
        val ctx = Context(UsbManager(UsbDevice(listOf(UsbConfiguration(1, listOf(face())),
            UsbConfiguration(2, listOf(face(3, 0), face(3, 1)))))))
        val stages = mutableListOf<String>()
        val adapter = AndroidSonyRemoteWire.open(ctx) { stages += it }
        assertEquals(2, adapter.selection.config)
        assertEquals(3, adapter.selection.interfaceId)
        assertEquals(1, adapter.selection.alternate)
        assertEquals(0x83, adapter.selection.eventEndpoint)
        assertEquals(listOf("DISCOVERY", "PERMISSION", "CONFIGURATION", "INTERFACE", "CLAIM"), stages)
        assertEquals(0, Wire.setCalls)
        adapter.close(); adapter.close()
        assertEquals(1, Wire.closed)
        assertEquals(1, RemoteUsbFake.releases)
    }

    @Test fun discoveryAndPermissionFailuresDoNotClaimOrOpenConnection() {
        Wire.reset()
        val manager = UsbManager()
        manager.deviceList.clear()
        assertFailsWith<IllegalStateException> { AndroidSonyRemoteWire.open(Context(manager)) }
        manager.deviceList["one"] = UsbDevice()
        manager.deviceList["two"] = UsbDevice()
        assertFailsWith<IllegalStateException> { AndroidSonyRemoteWire.open(Context(manager)) }
        manager.deviceList.remove("two")
        Wire.permission = false
        assertFailsWith<IllegalStateException> { AndroidSonyRemoteWire.open(Context(manager)) }
        assertEquals(0, Wire.rawOpens)
        assertEquals(0, Wire.closed)
    }

    @Test fun nonSonyDevicesAreNotSelected() {
        Wire.reset()
        val context = Context(UsbManager(UsbDevice(vendorId = 0x1234)))
        assertFailsWith<IllegalStateException> { AndroidSonyRemoteWire.open(context) }
        assertEquals(0, Wire.controls)
    }

    @Test fun interfaceFailuresCloseConnectionAndNeverSwitchConfigurations() {
        for (failure in listOf("configuration", "alternate", "ambiguous", "claim")) {
            Wire.reset()
            val ctx = if (failure == "ambiguous") context(face(0), face(1)) else context(face())
            when (failure) {
                "configuration" -> RemoteUsbFake.activeConfig = 0
                "alternate" -> Wire.stallAlternate = true
                "claim" -> RemoteUsbFake.claimSucceeds = false
            }
            assertFailsWith<IllegalStateException> { AndroidSonyRemoteWire.open(ctx) }
            assertEquals(1, Wire.closed)
            assertEquals(0, RemoteUsbFake.releases)
            assertEquals(0, Wire.setCalls)
        }
    }

    @Test fun missingInterruptEndpointIsReportedAndCannotBePolled(): Unit = runBlocking {
        Wire.reset()
        val adapter = AndroidSonyRemoteWire.open(context(face(events = false)))
        assertNull(adapter.selection.eventEndpoint)
        assertFailsWith<IllegalStateException> { adapter.readEvent(500) }
        assertEquals(0, RemoteUsbFake.queues)
        adapter.close()
    }

    @Test fun interruptEventUsesUsbRequestAndRetiresBeforeClosing(): Unit = runBlocking {
        Wire.reset()
        val adapter = AndroidSonyRemoteWire.open(context(face()))
        val packet = byteArrayOf(12, 0, 0, 0, 4, 0, 3, 0xc2.toByte(), 0, 0, 0, 0)
        RemoteUsbFake.event = packet
        assertContentEquals(packet, adapter.readEvent(500))
        assertEquals(1, RemoteUsbFake.queues)
        assertEquals(1, RemoteUsbFake.requestCloses)
        assertEquals(0, RemoteUsbFake.cancels)
        assertNull(RemoteUsbFake.pending)
        assertTrue(Wire.operations.isEmpty())
        adapter.close()
    }

    @Test fun noEventTimeoutCancelsAndDrainsWithinBudget(): Unit = runBlocking {
        Wire.reset()
        val adapter = AndroidSonyRemoteWire.open(context(face()))
        assertNull(adapter.readEvent(500))
        assertEquals(1, RemoteUsbFake.cancels)
        assertEquals(1, RemoteUsbFake.requestCloses)
        assertNull(RemoteUsbFake.pending)
        assertEquals(0, Wire.closed)
        assertTrue(RemoteUsbFake.timeouts.sum() < 500)
        adapter.close()
    }

    @Test fun unconfirmedCancellationClosesConnectionInsteadOfReusingPendingRequest(): Unit = runBlocking {
        for (drainFailure in listOf(false, true)) {
            Wire.reset()
            val adapter = AndroidSonyRemoteWire.open(context(face()))
            if (drainFailure) RemoteUsbFake.drainSucceeds = false else RemoteUsbFake.cancelSucceeds = false
            assertFailsWith<IOException> { adapter.readEvent(500) }
            assertEquals(1, Wire.closed)
            assertEquals(1, RemoteUsbFake.releases)
            assertEquals(1, RemoteUsbFake.requestCloses)
            assertNull(RemoteUsbFake.pending)
            assertFailsWith<IllegalStateException> { adapter.readEvent(500) }
            adapter.close()
            assertEquals(1, Wire.closed)
        }
    }

    @Test fun disconnectedEventIsFailureNotNoEvent(): Unit = runBlocking {
        Wire.reset()
        val adapter = AndroidSonyRemoteWire.open(context(face()))
        RemoteUsbFake.disconnected = true
        assertFailsWith<IOException> { adapter.readEvent(500) }
        assertEquals(1, Wire.closed)
        assertNull(RemoteUsbFake.pending)
    }

    @Test fun cancellationDuringQueuedWaitReleasesEverything(): Unit = runBlocking {
        Wire.reset()
        val adapter = AndroidSonyRemoteWire.open(context(face()))
        RemoteUsbFake.waitHook = { throw CancellationException("synthetic cancellation") }
        assertFailsWith<CancellationException> { adapter.readEvent(500) }
        assertEquals(1, Wire.closed)
        assertEquals(1, RemoteUsbFake.releases)
        assertEquals(1, RemoteUsbFake.requestCloses)
        assertNull(RemoteUsbFake.pending)
    }
}
