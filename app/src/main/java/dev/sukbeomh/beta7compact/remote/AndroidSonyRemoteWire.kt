package dev.sukbeomh.beta7compact.remote

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

data class RemoteUsbSelection(
    val vid: Int, val pid: Int, val config: Int, val interfaceId: Int, val alternate: Int,
    val bulkIn: Int, val bulkOut: Int, val eventEndpoint: Int?,
)

/** Must be opened and used inside UsbAccess.useRemote, on an IO dispatcher. */
class AndroidSonyRemoteWire private constructor(
    private val connection: UsbDeviceConnection,
    private val face: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint,
    private val event: UsbEndpoint?,
    val selection: RemoteUsbSelection,
) : SonyRemoteWire {
    private var closed = false

    override suspend fun write(bytes: ByteArray, timeoutMillis: Int): Int = bulk(output, bytes, timeoutMillis)
    override suspend fun read(bytes: ByteArray, timeoutMillis: Int): Int = bulk(input, bytes, timeoutMillis)

    private suspend fun bulk(endpoint: UsbEndpoint, bytes: ByteArray, timeoutMillis: Int): Int {
        check(!closed) { "Remote USB connection closed" }
        require(timeoutMillis in 1..30_000)
        currentCoroutineContext().ensureActive()
        val result = connection.bulkTransfer(endpoint, bytes, bytes.size, timeoutMillis)
        currentCoroutineContext().ensureActive()
        return result
    }

    override suspend fun readEvent(timeoutMillis: Int): ByteArray? {
        check(!closed) { "Remote USB connection closed" }
        require(timeoutMillis in 4..30_000)
        val endpoint = checkNotNull(event) { "Remote interrupt endpoint unavailable" }
        currentCoroutineContext().ensureActive()
        val request = UsbRequest()
        var queued = false
        var retired = false
        try {
            check(request.initialize(connection, endpoint)) { "Remote event request initialization failed" }
            // The buffer covers an entire max packet; the event parser later enforces the PTP limit.
            val data = ByteBuffer.allocateDirect(maxOf(24, endpoint.maxPacketSize))
            check(request.queue(data)) { "Remote event request queue failed" }
            queued = true
            val drainBudget = minOf(200L, timeoutMillis.toLong() / 3)
            val waitBudget = timeoutMillis.toLong() - drainBudget - maxOf(1L, timeoutMillis / 10L)
            val completed = try {
                connection.requestWait(waitBudget)
            } catch (_: TimeoutException) {
                if (!request.cancel()) throw IOException("Remote event cancellation unconfirmed")
                val drained = try { connection.requestWait(drainBudget) }
                    catch (_: TimeoutException) { throw IOException("Remote event cancellation drain timed out") }
                if (drained !== request) throw IOException("Remote event cancellation drain failed")
                retired = true
                currentCoroutineContext().ensureActive()
                // A packet may have won the cancellation race: preserve it instead of discarding it.
                if (data.position() == 0) return null
                request
            }
            if (completed !== request) throw IOException("Remote event request failed")
            retired = true
            currentCoroutineContext().ensureActive()
            if (data.position() == 0) throw IOException("Remote event packet empty")
            data.flip()
            return ByteArray(data.remaining()).also { data.get(it) }
        } finally {
            try {
                if (queued && !retired) {
                    // No uncertain request is left alive when the transport is reused.
                    try { request.cancel() } finally { close() }
                }
            } finally { request.close() }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { connection.releaseInterface(face) } finally { connection.close() }
    }

    companion object {
        fun open(context: Context, onStage: (String) -> Unit = {}): AndroidSonyRemoteWire {
            onStage("DISCOVERY")
            val manager = context.getSystemService(UsbManager::class.java)
            val devices = manager.deviceList.values.filter { it.vendorId == 0x054c }
            check(devices.size == 1) { "Remote diagnosis requires one Sony device" }
            val device = devices.single()
            onStage("PERMISSION")
            check(manager.hasPermission(device)) { "Remote USB permission required" }
            val connection = checkNotNull(manager.openDevice(device)) { "Remote USB open failed" }
            var claimed: UsbInterface? = null
            try {
                onStage("CONFIGURATION")
                val value = ByteArray(1)
                check(connection.controlTransfer(0x80, 8, 0, 0, value, 1, 8_000) == 1) {
                    "Remote active configuration unavailable"
                }
                val config = value[0].toInt() and 255
                check(config != 0) { "Remote device unconfigured" }
                onStage("INTERFACE")
                val candidates = (0 until device.configurationCount).map { device.getConfiguration(it) }
                    .filter { it.id == config }.flatMap { c -> (0 until c.interfaceCount).map { c.getInterface(it) } }
                val alternates = mutableMapOf<Int, Int>()
                val faces = candidates.filter { f ->
                    if (f.interfaceClass != 6) false else {
                        val active = alternates.getOrPut(f.id) {
                            check(connection.controlTransfer(0x81, 10, 0, f.id, value, 1, 8_000) == 1) {
                                "Remote active alternate unavailable"
                            }
                            value[0].toInt() and 255
                        }
                        val endpoints = (0 until f.endpointCount).map { f.getEndpoint(it) }
                        f.alternateSetting == active && endpoints.count { it.type == 2 && it.direction == 128 } == 1 &&
                            endpoints.count { it.type == 2 && it.direction == 0 } == 1
                    }
                }
                check(faces.size == 1) { "Remote active still-image interface ambiguous or unavailable" }
                val face = faces.single()
                val endpoints = (0 until face.endpointCount).map { face.getEndpoint(it) }
                val input = endpoints.single { it.type == 2 && it.direction == 128 }
                val output = endpoints.single { it.type == 2 && it.direction == 0 }
                val events = endpoints.filter { it.type == 3 && it.direction == 128 }
                check(events.size <= 1) { "Remote interrupt endpoint ambiguous" }
                val event = events.singleOrNull()
                check(event == null || event.maxPacketSize in 1..1024) { "Remote event packet size unsupported" }
                onStage("CLAIM")
                check(connection.claimInterface(face, true)) { "Remote interface claim failed" }
                claimed = face
                return AndroidSonyRemoteWire(connection, face, input, output, event,
                    RemoteUsbSelection(device.vendorId, device.productId, config, face.id, face.alternateSetting,
                        input.address, output.address, event?.address))
            } catch (failure: Throwable) {
                try { claimed?.let { connection.releaseInterface(it) } }
                catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { connection.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
