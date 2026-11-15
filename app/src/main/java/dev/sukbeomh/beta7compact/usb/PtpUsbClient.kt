package dev.sukbeomh.beta7compact.usb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

/** One bulk transfer per call; Android and the fake camera share this boundary. */
interface UsbBulkTransport {
    fun write(bytes: ByteArray, timeoutMs: Int): Int
    fun read(bytes: ByteArray, timeoutMs: Int): Int
}

/** Native MTP and raw metadata must never own the interface at the same time. */
internal fun <C : Closeable, T> readExclusiveMetadata(closeNative: () -> Unit, openRaw: () -> C,
                                                    reopenNative: () -> Unit, read: (C) -> T): T {
    closeNative()
    try {
        return openRaw().use(read)
    } finally {
        reopenNative()
    }
}

/** Only the already active configuration/alternate can be claimed. No USB SET requests. */
internal fun <T> activeStillImageInterface(current: Int, configurations: List<Pair<Int, T>>,
                                         alternate: (T) -> Int, activeAlternate: (T) -> Int,
                                         stillImageBulk: (T) -> Boolean): T {
    return configurations.firstOrNull { (config, face) ->
        config == current && current > 0 && stillImageBulk(face) && alternate(face) == activeAlternate(face)
    }?.second ?: error("카메라 설정 → USB 연결 → MTP")
}

/** Read-only PTP operations plus Open/CloseSession. No camera property or shutter writes. */
class PtpUsbClient(private val transport: UsbBulkTransport, private val log: (String) -> Unit = {}) : Closeable {
    private val packet = ByteArray(16384) // A multiple of USB bulk maxPacketSize, never a 12-byte IN request.
    private var offset = 0
    private var available = 0
    private var lastRead = "none"
    private var lastWrite = "none"
    private val receivedPrefix = ByteArrayOutputStream(64)
    private var transaction = 1
    private var session = false
    private var broken = false
    private var closed = false
    private var started = 0L

    fun openSession() {
        check(!session)
        try { execute(0x1002, intArrayOf(1)) }
        catch (e: PtpResponseException) {
            // Match AOSP MtpDevice: an existing session is usable; first transaction is 1.
            if (e.code != 0x201E) throw e
        }
        session = true
        transaction = 1
    }

    fun read(code: Int, vararg params: Int): ByteArray {
        val count = when (code) { 0x1001, 0x1004 -> 0; 0x1007 -> 3; 0x1008, 0x100A -> 1; else -> error("USB read operation not allowed ${hex(code)}") }
        require(params.size == count)
        check(code == 0x1001 || session) { "USB PTP session required" }
        val output = ByteArrayOutputStream()
        execute(code, params, output, 4L * 1024 * 1024)
        return output.toByteArray()
    }

    fun getObject(handle: Int, output: OutputStream, expected: Long): Long {
        check(session)
        require(expected in 1..0xfffffff0L) { "Invalid object size=$expected" }
        return execute(0x1009, intArrayOf(handle), output, expected).also {
            require(it == expected) { "Object size mismatch expected=$expected received=$it" }
        }
    }

    private fun elapsed() = (System.nanoTime() - started) / 1000000
    private fun checkDeadline() {
        check(!Thread.currentThread().isInterrupted) { "USB PTP interrupted" }
        check(elapsed() < 120000) { "USB PTP transaction deadline exceeded" }
    }

    private fun fill() {
        var emptyReads = 0
        while (offset == available) {
            checkDeadline()
            offset = 0
            available = 0
            val result = transport.read(packet, 8000)
            lastRead = result.toString()
            if (result > 0 && result <= packet.size) {
                available = result
                val prefixSize = minOf(64 - receivedPrefix.size(), result)
                receivedPrefix.write(packet, 0, prefixSize)
            }
            if (result < 0 || result > packet.size) throw IOException("USB bulkTransfer IN returned=$result")
            // Zero-length packet can terminate a maxPacket-aligned data phase. Read the response next.
            if (result == 0) {
                log("USB bulkTransfer IN returned=0 (ZLP); waiting for container")
                check(++emptyReads <= 8) { "USB bulkTransfer IN repeated zero-length packets without progress" }
            }
        }
    }

    private fun take(size: Int): ByteArray {
        val result = ByteArray(size)
        var copied = 0
        while (copied < size) {
            fill()
            val n = minOf(size - copied, available - offset)
            packet.copyInto(result, copied, offset, offset + n)
            offset += n
            copied += n
        }
        return result
    }

    private fun copy(output: OutputStream, size: Long) {
        var remaining = size
        while (remaining > 0) {
            fill()
            val n = minOf(remaining, (available - offset).toLong()).toInt()
            output.write(packet, offset, n)
            offset += n
            remaining -= n
        }
    }

    private fun execute(code: Int, params: IntArray, output: OutputStream? = null, limit: Long = 0): Long {
        check(!closed && !broken) { "USB PTP connection unusable; reconnect" }
        started = System.nanoTime()
        val tx = if (session) transaction++ else 0
        var received = 0L
        var dataSeen = false
        lastRead = "none"; lastWrite = "none"; receivedPrefix.reset()
        try {
            checkDeadline()
            log("USB raw OP ${hex(code)} tx=$tx START params=${params.joinToString { hex(it) }}")
            val payload = buffer(params.size * 4).apply { params.forEach { putInt(it) } }.array()
            val command = PtpContainer(1, code, tx, payload).encode()
            val written = transport.write(command, 8000)
            lastWrite = written.toString()
            log("USB bulkTransfer OUT returned=$written requested=${command.size} hex=${command.hexPrefix()}")
            if (written != command.size) throw IOException("USB bulkTransfer OUT incomplete returned=$written requested=${command.size}")
            while (true) {
                checkDeadline()
                val raw = take(12)
                val header = le(raw)
                val length = header.int.toLong() and 0xffffffffL
                val type = header.short.toInt() and 65535
                val result = header.short.toInt() and 65535
                require(header.int == tx) { "USB PTP transaction mismatch" }
                require(length >= 12) { "USB PTP invalid container length=$length" }
                log("USB raw RX type=$type code=${hex(result)} tx=$tx len=$length bulkTransferIN=$lastRead hex=${raw.hexPrefix()}")
                when (type) {
                    2 -> {
                        require(output != null && !dataSeen && result == code) { "Unexpected USB PTP data phase/code" }
                        received = length - 12
                        require(received <= limit) { "USB PTP data size=$received limit=$limit" }
                        dataSeen = true
                        copy(output, received)
                    }
                    3 -> {
                        require(length in 12..32 && (length - 12) % 4 == 0L) { "USB PTP invalid response length=$length" }
                        val response = PtpContainer.decode(raw + take((length - 12).toInt()))
                        log("USB raw OP ${hex(code)} response=${hex(response.code)} tx=$tx bytes=$received ms=${elapsed()}")
                        if (response.code != Ptp.OK) throw PtpResponseException(response.code)
                        require(output == null || dataSeen) { "USB PTP missing required data phase" }
                        return received
                    }
                    else -> error("Unexpected USB PTP container type=$type")
                }
            }
        } catch (e: Exception) {
            if (e !is PtpResponseException) broken = true
            log("USB raw OP ${hex(code)} FAIL ${e.javaClass.simpleName}: ${e.message} bulkTransferOUT=$lastWrite bulkTransferIN=$lastRead hex=${receivedPrefix.toByteArray().hexPrefix()} ms=${elapsed()}")
            throw e
        }
    }

    fun closeSession() {
        if (session && !closed) {
            try {
                if (broken) log("USB raw CloseSession SKIP: framing/transport unusable; camera session state unconfirmed")
                else execute(0x1003, intArrayOf())
            } finally { session = false }
        }
    }

    override fun close() {
        try { closeSession() } finally { closed = true }
    }
}
