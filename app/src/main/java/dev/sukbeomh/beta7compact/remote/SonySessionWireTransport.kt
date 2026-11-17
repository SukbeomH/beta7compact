package dev.sukbeomh.beta7compact.remote

import dev.sukbeomh.beta7compact.usb.PtpContainer
import dev.sukbeomh.beta7compact.usb.PtpResponseException
import dev.sukbeomh.beta7compact.usb.buffer
import dev.sukbeomh.beta7compact.usb.le
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class RemoteTransportTimeoutException : IOException("Remote transport deadline exceeded")

/** Bounded USB container framing for the fixed stage-2 command set. */
class SonySessionWireTransport(
    private val wire: SonyRemoteWire,
    private val transactionTimeoutMillis: Int = 30_000,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : SonySessionTransport {
    private val lock = Mutex()
    private val packet = ByteArray(16_384)
    private var offset = 0
    private var available = 0
    private var nextTransaction = 1L
    private var opened = false
    private var broken = false
    private var closed = false

    init { require(transactionTimeoutMillis > 0) }

    override suspend fun exchange(operation: SonySessionOperation): ByteArray = lock.withLock {
        check(!closed && !broken) { "Remote transport requires a new connection" }
        check(if (opened) operation != SonySessionOperation.OPEN_SESSION
            else operation == SonySessionOperation.DEVICE_INFO || operation == SonySessionOperation.OPEN_SESSION) {
            "Remote operation outside owned PTP session"
        }
        check(nextTransaction <= 0xffffffffL) { "PTP transaction IDs exhausted" }
        val tx = if (opened) nextTransaction++.toInt() else 0
        val started = clockMillis()
        suspend fun remaining(): Int {
            currentCoroutineContext().ensureActive()
            val elapsed = clockMillis() - started
            if (elapsed < 0 || elapsed >= transactionTimeoutMillis) throw RemoteTransportTimeoutException()
            return minOf(8_000, transactionTimeoutMillis - elapsed.toInt())
        }
        suspend fun take(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var copied = 0
            var emptyReads = 0
            while (copied < size) {
                if (offset == available) {
                    offset = 0
                    available = 0
                    val count = wire.read(packet, remaining())
                    remaining()
                    if (count < 0 || count > packet.size) throw IOException("Remote bulk read failed")
                    if (count == 0) {
                        if (++emptyReads > 8) throw IOException("Remote bulk read made no progress")
                        continue
                    }
                    available = count
                    emptyReads = 0
                }
                val count = minOf(size - copied, available - offset)
                packet.copyInto(bytes, copied, offset, offset + count)
                offset += count
                copied += count
            }
            return bytes
        }
        try {
            withTimeoutOrNull(transactionTimeoutMillis.toLong()) {
                val params = buffer(operation.parameters.size * 4).apply {
                    operation.parameters.forEach { putInt(it) }
                }.array()
                val command = PtpContainer(1, operation.code, tx, params).encode()
                if (wire.write(command, remaining()) != command.size) throw IOException("Remote bulk write incomplete")
                remaining()
                var data: ByteArray? = null
                var complete = false
                while (!complete) {
                    remaining()
                    val header = le(take(12))
                    val length = header.int.toLong() and 0xffffffffL
                    val type = header.short.toInt() and 0xffff
                    val code = header.short.toInt() and 0xffff
                    require(header.int == tx) { "Remote transaction mismatch" }
                    require(length >= 12) { "Remote container length invalid" }
                    when (type) {
                        2 -> {
                            require(operation.receivesData && data == null && code == operation.code) { "Unexpected remote data phase" }
                            require(length - 12 <= 1_048_576) { "Remote data exceeds limit" }
                            data = take((length - 12).toInt())
                        }
                        3 -> {
                            require(length in 12..32 && (length - 12) % 4 == 0L) { "Remote response length invalid" }
                            take((length - 12).toInt())
                            require(offset == available) { "Unexpected bytes after remote response" }
                            if (code != 0x2001) throw PtpResponseException(code)
                            require(!operation.receivesData || data != null) { "Remote response missing data phase" }
                            complete = true
                        }
                        else -> throw IllegalArgumentException("Unexpected remote container type")
                    }
                }
                if (operation == SonySessionOperation.OPEN_SESSION) {
                    opened = true
                    nextTransaction = 1
                } else if (operation == SonySessionOperation.CLOSE_SESSION) opened = false
                data ?: byteArrayOf()
            } ?: throw RemoteTransportTimeoutException()
        } catch (failure: Exception) {
            if (failure !is PtpResponseException) broken = true
            throw failure
        }
    }

    override suspend fun nextEvent(timeoutMillis: Int): ByteArray? = lock.withLock {
        require(timeoutMillis in 1..30_000) { "Invalid event poll timeout" }
        check(opened && !closed && !broken) { "Remote event poll requires an owned session" }
        try {
            currentCoroutineContext().ensureActive()
            // Box a legitimate no-event result so only our own expired deadline becomes an I/O error.
            val result = withTimeoutOrNull(timeoutMillis.toLong()) { Pair(Unit, wire.readEvent(timeoutMillis)) }
                ?: throw RemoteTransportTimeoutException()
            val bytes = result.second
            currentCoroutineContext().ensureActive()
            bytes?.also { SonyEventParser.parse(it) }
        } catch (failure: Exception) {
            broken = true
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        opened = false
        wire.close()
    }
}
