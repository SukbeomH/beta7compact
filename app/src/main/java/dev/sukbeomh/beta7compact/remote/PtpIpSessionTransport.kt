package dev.sukbeomh.beta7compact.remote

import dev.sukbeomh.beta7compact.usb.Ptp
import dev.sukbeomh.beta7compact.usb.PtpContainer
import dev.sukbeomh.beta7compact.usb.PtpResponseException
import dev.sukbeomh.beta7compact.usb.buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class PtpIpSessionRejectedException : IOException("PTP/IP initialization refused")

/** PTP/IP implementation of fixed Sony session operations and explicitly enabled capture controls. */
internal class PtpIpSessionTransport private constructor(
    private val command: PtpIpChannel,
    private val event: PtpIpChannel,
    private val stage: (String) -> Unit,
    private val allowCaptureControls: Boolean,
    private val allowPriorityWrite: Boolean,
) : SonyCaptureTransport {
    private var opened = false
    private var usable = true
    private var nextTransaction = 1

    override suspend fun exchange(operation: SonySessionOperation): ByteArray {
        check(usable && if (opened) operation != SonySessionOperation.OPEN_SESSION
            else operation == SonySessionOperation.DEVICE_INFO || operation == SonySessionOperation.OPEN_SESSION)
        if (operation != SonySessionOperation.CLOSE_SESSION) stage(operation.name)
        return execute(operation.code, operation.parameters, operation.receivesData).also {
            when (operation) {
                SonySessionOperation.OPEN_SESSION -> { opened = true; nextTransaction = 1 }
                SonySessionOperation.CLOSE_SESSION -> opened = false
                else -> Unit
            }
            if (operation != SonySessionOperation.CLOSE_SESSION) stage("${operation.name}_VALIDATION")
        }
    }

    override suspend fun control(command: SonyCaptureCommand) {
        check(allowCaptureControls && opened && usable) { "Capture controls require an enabled, usable PTP/IP session" }
        execute(0x9207, listOf(command.propertyCode), receivesData = false,
            outgoingData = buffer(2).putShort(command.value.toShort()).array())
    }

    override suspend fun set(command: SonySettingCommand) {
        check(allowCaptureControls && allowPriorityWrite && opened && usable) {
            "Setting writes require explicit permission on a usable PTP/IP session"
        }
        execute(0x9205, listOf(command.propertyCode), receivesData = false, outgoingData = byteArrayOf(command.value.toByte()))
    }

    private suspend fun execute(code: Int, parameters: List<Int>, receivesData: Boolean, outgoingData: ByteArray? = null): ByteArray {
        currentCoroutineContext().ensureActive()
        val tx = if (opened) nextTransaction++ else 0
        return try {
            command.write(PtpIp.command(code, tx, parameters, sendsData = outgoingData != null))
            if (outgoingData != null) {
                command.write(PtpIp.startData(tx, outgoingData.size))
                command.write(PtpIp.endData(tx, outgoingData))
            }
            readResponse(receivesData, tx)
        } catch (failure: Exception) {
            if (failure !is PtpResponseException) usable = false
            throw failure
        }
    }

    private suspend fun readResponse(receivesData: Boolean, tx: Int): ByteArray {
        var expected: Long? = null
        var ended = false
        val data = ByteArrayOutputStream()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            val packet = readUntil(command, deadline) ?: throw SocketTimeoutException()
            val bytes = packet.body
            when (packet.type) {
                PtpIp.START_DATA -> {
                    if (!receivesData || expected != null || bytes.size != 12 ||
                        PtpIp.u32(bytes, 0) != tx || PtpIp.u32(bytes, 8) != 0) throw PtpIpFormatException()
                    expected = PtpIp.u32(bytes, 4).toLong() and 0xffffffffL
                    if (expected > PtpIp.MAX_DATA) throw PtpIpFormatException()
                }
                PtpIp.DATA, PtpIp.END_DATA -> {
                    if (expected == null || ended || PtpIp.u32(bytes, 0) != tx ||
                        data.size() + bytes.size - 4 > expected) throw PtpIpFormatException()
                    data.write(bytes, 4, bytes.size - 4)
                    ended = packet.type == PtpIp.END_DATA
                }
                PtpIp.COMMAND_RESPONSE -> {
                    if (bytes.size !in 6..26 || (bytes.size - 6) % 4 != 0 || PtpIp.u32(bytes, 2) != tx) throw PtpIpFormatException()
                    val code = PtpIp.u16(bytes, 0)
                    if (code != Ptp.OK) throw PtpResponseException(code)
                    if (receivesData && (!ended || data.size().toLong() != expected)) throw PtpIpFormatException()
                    return data.toByteArray()
                }
                else -> throw PtpIpFormatException()
            }
        }
    }

    override suspend fun nextEvent(timeoutMillis: Int): ByteArray? {
        currentCoroutineContext().ensureActive()
        check(opened)
        val packet = readUntil(event, System.nanoTime() + timeoutMillis * 1_000_000L, idleReturnsNull = true) ?: return null
        val bytes = packet.body
        if (packet.type != PtpIp.EVENT || bytes.size !in 6..18 || (bytes.size - 6) % 4 != 0) throw PtpIpFormatException()
        // SonyRemoteSession uses the same event parser for both transports.
        return PtpContainer(4, PtpIp.u16(bytes, 0), PtpIp.u32(bytes, 2), bytes.copyOfRange(6, bytes.size)).encode()
    }

    override fun close() {
        usable = false
        command.use { event.close() }
    }

    companion object {
        fun open(connector: PtpIpConnector, guid: ByteArray, name: String,
                 allowCaptureControls: Boolean = false, allowPriorityWrite: Boolean = false,
                 stage: (String) -> Unit): PtpIpSessionTransport {
            stage("COMMAND_CONNECT")
            val command = connector.open(5_000)
            var event: PtpIpChannel? = null
            try {
                stage("INIT_COMMAND")
                command.write(PtpIp.initCommand(guid, name))
                val acknowledgement = readUntil(command, System.nanoTime() + 30_000_000_000L) ?: throw SocketTimeoutException()
                requireAcknowledgement(acknowledgement, PtpIp.INIT_COMMAND_ACK)
                val connection = PtpIp.u32(acknowledgement.body, 0)

                stage("EVENT_CONNECT")
                val eventChannel = openEvent(connector)
                event = eventChannel
                stage("INIT_EVENT")
                eventChannel.write(PtpIp.initEvent(connection))
                val eventAck = readUntil(eventChannel, System.nanoTime() + 5_000_000_000L) ?: throw SocketTimeoutException()
                requireAcknowledgement(eventAck, PtpIp.INIT_EVENT_ACK)
                return PtpIpSessionTransport(command, eventChannel, stage, allowCaptureControls, allowPriorityWrite)
            } catch (failure: Throwable) {
                for (channel in listOf(event, command)) {
                    try {
                        channel?.close()
                    } catch (closeFailure: Throwable) {
                        if (failure !== closeFailure) failure.addSuppressed(closeFailure)
                    }
                }
                throw failure
            }
        }

        private fun requireAcknowledgement(packet: PtpIp.Packet, type: Int) {
            if (packet.type == PtpIp.INIT_FAIL) throw PtpIpSessionRejectedException()
            if (packet.type != type) throw PtpIpFormatException()
        }

        private fun openEvent(connector: PtpIpConnector): PtpIpChannel {
            repeat(2) {
                try {
                    return connector.open(5_000)
                } catch (_: ConnectException) {
                    Thread.sleep(100)
                }
            }
            return connector.open(5_000)
        }

        private fun readUntil(channel: PtpIpChannel, deadline: Long, idleReturnsNull: Boolean = false): PtpIp.Packet? {
            repeat(17) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    if (idleReturnsNull) return null
                    throw SocketTimeoutException()
                }
                val timeout = ((remaining + 999_999) / 1_000_000).toInt()
                val packet = if (idleReturnsNull) PtpIp.readEvent(channel, timeout) ?: return null else PtpIp.read(channel, timeout)
                if (packet.type != PtpIp.PING) return packet
                if (it < 16) channel.write(PtpIp.pong())
            }
            throw PtpIpFormatException()
        }
    }
}
