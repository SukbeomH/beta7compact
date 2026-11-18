package android.hardware.usb

import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/** Narrow asynchronous USB request double; existing default MTP doubles remain unchanged. */
object RemoteUsbFake {
    var activeConfig = 1
    var activeAlternate = 0
    var claimSucceeds = true
    var releases = 0
    var pending: UsbRequest? = null
    var requestCloses = 0
    var cancels = 0
    var queues = 0
    var event: ByteArray? = null
    var waitHook: () -> Unit = {}
    var cancelSucceeds = true
    var drainSucceeds = true
    var disconnected = false
    val timeouts = mutableListOf<Long>()
    fun reset() {
        activeConfig = 1; activeAlternate = 0; claimSucceeds = true; releases = 0
        pending = null; requestCloses = 0; cancels = 0; queues = 0; event = null
        waitHook = {}; cancelSucceeds = true; drainSucceeds = true; disconnected = false; timeouts.clear()
    }
    fun waitForRequest(timeout: Long): UsbRequest? {
        timeouts += timeout
        waitHook()
        if (disconnected) return null
        val request = checkNotNull(pending)
        if (request.cancelled) {
            if (!drainSucceeds) throw TimeoutException()
            pending = null
            return request
        }
        val bytes = event ?: throw TimeoutException()
        checkNotNull(request.buffer).put(bytes)
        pending = null
        return request
    }
}

class UsbRequest {
    var buffer: ByteBuffer? = null
    var cancelled = false
    fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean {
        check(endpoint.type == 3 && endpoint.direction == 128)
        return true
    }
    fun queue(bytes: ByteBuffer): Boolean {
        check(RemoteUsbFake.pending == null)
        buffer = bytes
        RemoteUsbFake.pending = this
        RemoteUsbFake.queues++
        return true
    }
    fun cancel(): Boolean {
        RemoteUsbFake.cancels++
        if (RemoteUsbFake.cancelSucceeds) cancelled = true
        return RemoteUsbFake.cancelSucceeds
    }
    fun close() { RemoteUsbFake.requestCloses++ }
}
