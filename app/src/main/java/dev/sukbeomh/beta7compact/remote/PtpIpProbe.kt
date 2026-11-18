package dev.sukbeomh.beta7compact.remote

import dev.sukbeomh.beta7compact.usb.Ptp
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP/IP packets as libgphoto2 31a2f408 `camlibs/ptp2/ptpip.c` reads and writes them: little-endian
 * `u32 length, u32 type`, then the body. Only what a connection check needs; no session, no vendor operation.
 */
object PtpIp {
    const val PORT = 15740
    const val INIT_COMMAND_REQUEST = 1
    const val INIT_COMMAND_ACK = 2
    const val INIT_EVENT_REQUEST = 3
    const val INIT_EVENT_ACK = 4
    const val INIT_FAIL = 5
    const val COMMAND_REQUEST = 6
    const val COMMAND_RESPONSE = 7
    const val START_DATA = 9
    const val DATA = 10
    const val END_DATA = 12
    const val PING = 13
    const val PONG = 14
    const val MAX_DATA = 1024 * 1024
    private const val MAX_PACKET = MAX_DATA + 64

    class Packet(val type: Int, val body: ByteArray)

    private fun packet(type: Int, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(8 + body.size).putInt(type).put(body).array()

    /** GUID, UTF-16LE name with its terminator, then version 1.0 (minor first). */
    fun initCommand(guid: ByteArray, name: String): ByteArray {
        require(guid.size == 16) { "PTP/IP GUID must be 16 bytes" }
        val text = (name + '\u0000').toByteArray(Charsets.UTF_16LE)
        return packet(INIT_COMMAND_REQUEST, guid + text + byteArrayOf(0, 0, 1, 0))
    }
    fun initEvent(connection: Int): ByteArray =
        packet(INIT_EVENT_REQUEST, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(connection).array())
    /** Data phase 1: no data sent by this side. */
    fun command(code: Int, transaction: Int): ByteArray =
        packet(COMMAND_REQUEST, ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putShort(code.toShort()).putInt(transaction).array())
    fun pong(): ByteArray = packet(PONG, byteArrayOf())

    fun read(channel: PtpIpChannel, timeoutMillis: Int): Packet {
        val header = ByteArray(8)
        channel.readFully(header, timeoutMillis)
        val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val length = fields.int
        val type = fields.int
        if (length < 8 || length > MAX_PACKET) throw PtpIpFormatException()
        return Packet(type, ByteArray(length - 8).also { if (it.isNotEmpty()) channel.readFully(it, timeoutMillis) })
    }
    /** Dotted IPv4 in a private range only: the diagnostic talks to a camera on the local link, never a name or a public host. */
    fun localAddress(text: String): ByteArray? {
        val parts = text.trim().split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return null
        val octets = parts.map { it.toInt() }
        if (octets.any { it > 255 }) return null
        val (a, b) = octets
        val private = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        return if (private) ByteArray(4) { octets[it].toByte() } else null
    }
    internal fun u32(body: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 4 > body.size) throw PtpIpFormatException()
        return ByteBuffer.wrap(body, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
    internal fun u16(body: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > body.size) throw PtpIpFormatException()
        return ByteBuffer.wrap(body, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
    }
}

/** Network name and passphrase read from a QR code. Used for one run; never stored or logged. */
data class CameraWifiCredentials(val ssid: String, val passphrase: String)

/**
 * Text of the QR code a Sony camera shows for its own Wi-Fi: `W01:S:<suffix>;P:<password>;C:<model>;M:<id>;`, where the
 * network is named `DIRECT-<suffix>:<model>` (layout as documented by the open OpenAlpha and Sony-Image-Import projects).
 * The standard `WIFI:S:<ssid>;T:WPA;P:<password>;;` form is accepted too.
 */
object CameraWifiQr {
    fun parse(text: String): CameraWifiCredentials? {
        val value = text.trim()
        val sony = value.startsWith("W01:")
        if (!sony && !value.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = fields(value.substring(value.indexOf(':') + 1))
        val name = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
        val passphrase = fields["P"]?.takeIf { it.length in 8..63 } ?: return null
        val ssid = if (sony) "DIRECT-$name:${fields["C"]?.takeIf { it.isNotEmpty() } ?: return null}" else name
        return if (ssid.toByteArray(Charsets.UTF_8).size <= 32) CameraWifiCredentials(ssid, passphrase) else null
    }
    /** `K:value;` pairs; a backslash keeps the next character, as the Wi-Fi QR convention escapes `;` `:` `,` `\`. */
    private fun fields(body: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var key: String? = null
        val current = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> { current.append(body[i + 1]); i++ }
                key == null && c == ':' -> { key = current.toString(); current.clear() }
                c == ';' -> { key?.let { if (it !in result) result[it] = current.toString() }; key = null; current.clear() }
                else -> current.append(c)
            }
            i++
        }
        return result
    }
}

class PtpIpFormatException : IOException("Unexpected PTP/IP packet")

/** One TCP connection. Reads either fill the buffer or throw; a timeout is a [SocketTimeoutException]. */
interface PtpIpChannel : Closeable {
    fun write(bytes: ByteArray)
    fun readFully(buffer: ByteArray, timeoutMillis: Int)
}
fun interface PtpIpConnector { fun open(timeoutMillis: Int): PtpIpChannel }

enum class WifiDiagnosticOutcome { COMPLETE, FAILED }
enum class WifiDiagnosticFailure {
    WIFI_UNAVAILABLE, NO_CAMERA_ADDRESS, CONNECTION_REFUSED, TIMEOUT, INIT_REJECTED, PTP_RESPONSE, INVALID_DATA, IO, OTHER,
}

/**
 * Fixed labels and numbers only. The camera's GUID, name, model, serial and the network name are never kept;
 * the camera's local address is, because it decides how a client finds the camera.
 */
data class WifiDiagnosticReport(
    val outcome: WifiDiagnosticOutcome,
    val failureStage: String?,
    val failure: WifiDiagnosticFailure?,
    val elapsedMillis: Long,
    val cameraAddress: String?,
    val initWaitMillis: Long?,
    val initFailReason: Int?,
    val eventChannel: Boolean,
    val responseCode: Int?,
    val sonyVendor: Boolean?,
    val operationCount: Int?,
    val operations: Map<Int, Boolean>,
    val pings: Int,
    val trace: List<RemoteDiagnosticTrace>,
) {
    fun safeText(): String = buildString {
        appendLine("Wi-Fi 연결 진단 · 형식 1")
        appendLine("PTP/IP 연결 인사와 GetDeviceInfo만 실행. 세션 열기·Sony 연결·촬영·설정 쓰기 미실행.")
        appendLine("결과=$outcome elapsedMs=$elapsedMillis")
        failureStage?.let { appendLine("실패단계=$it 분류=$failure") }
        appendLine("카메라주소=${cameraAddress ?: "미확인"} 포트=${PtpIp.PORT}")
        appendLine("연결인사응답대기Ms=${initWaitMillis ?: "미확인"} 거부사유=${initFailReason?.let(::hex) ?: "없음"}")
        appendLine("이벤트채널=${if (eventChannel) "연결됨" else "미연결"} 핑=$pings")
        appendLine("GetDeviceInfo응답=${responseCode?.let(::hex) ?: "미확인"} Sony제조사=${sonyVendor ?: "미확인"} 명령수=${operationCount ?: "미확인"}")
        if (operations.isNotEmpty()) appendLine("광고명령 " + operations.entries.joinToString(" ") { "${hex(it.key)}=${if (it.value) "있음" else "없음"}" })
        trace.forEach { row ->
            append("${row.elapsedMillis}ms ${row.stage} ${row.status}")
            row.payloadBytes?.let { append(" bytes=$it") }
            row.responseCode?.let { append(" response=${hex(it)}") }
            appendLine()
        }
        appendLine("연결 성공은 원격 촬영 호환성의 보증이 아님. 카메라 화면에 확인창이 떴는지는 별도로 기록.")
        append("네트워크 이름·암호·카메라 GUID·기기 문자열·시리얼·원시 응답은 포함하지 않음.")
    }
    private fun hex(value: Int) = "0x%04X".format(value)

    companion object {
        /** Sony session, control, property snapshot and the two PTP/IP-only operations libgphoto2 sends. */
        val WATCHED_OPERATIONS = listOf(0x9201, 0x9202, 0x9205, 0x9207, 0x9209, 0x9280, 0x9281)
    }
}

/**
 * Read-only connection check: greet on the command connection, greet on the event connection, ask GetDeviceInfo
 * outside any session, close. [guid] and [name] are what the camera is shown; nothing else is sent.
 */
fun runPtpIpProbe(
    connector: PtpIpConnector, guid: ByteArray, name: String, cameraAddress: String?,
    clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }, pause: (Long) -> Unit = { Thread.sleep(it) },
    earlier: List<RemoteDiagnosticTrace> = emptyList(), startedAt: Long = clockMillis(),
): WifiDiagnosticReport {
    fun elapsed() = (clockMillis() - startedAt).coerceAtLeast(0)
    val trace = earlier.toMutableList()
    fun record(stage: String, status: RemoteTraceStatus, bytes: Int? = null, response: Int? = null) {
        trace += RemoteDiagnosticTrace(stage, status, elapsed(), bytes, response)
    }
    var stage = "COMMAND_CONNECT"
    var failure: WifiDiagnosticFailure? = null
    var initWait: Long? = null
    var failReason: Int? = null
    var eventChannel = false
    var responseCode: Int? = null
    var sony: Boolean? = null
    var operationCount: Int? = null
    var operations = emptyMap<Int, Boolean>()
    var pings = 0
    var command: PtpIpChannel? = null
    var event: PtpIpChannel? = null
    /** The camera may probe the client at any time; answer and keep waiting for the expected packet. */
    fun next(channel: PtpIpChannel, timeout: Int): PtpIp.Packet {
        while (true) {
            val packet = PtpIp.read(channel, timeout)
            if (packet.type != PtpIp.PING) return packet
            if (++pings > 16) throw PtpIpFormatException()
            channel.write(PtpIp.pong())
        }
    }
    try {
        record(stage, RemoteTraceStatus.START)
        command = connector.open(5_000)
        record(stage, RemoteTraceStatus.OK)

        stage = "INIT_COMMAND"
        record(stage, RemoteTraceStatus.START)
        val asked = clockMillis()
        command.write(PtpIp.initCommand(guid, name))
        // A camera that asks its user to confirm the client answers only after OK is pressed.
        val greeting = next(command, 30_000)
        initWait = (clockMillis() - asked).coerceAtLeast(0)
        if (greeting.type == PtpIp.INIT_FAIL) {
            failReason = if (greeting.body.size >= 4) PtpIp.u32(greeting.body, 0) else null
            throw InitRejected()
        }
        if (greeting.type != PtpIp.INIT_COMMAND_ACK) throw PtpIpFormatException()
        val connection = PtpIp.u32(greeting.body, 0)
        record(stage, RemoteTraceStatus.OK, greeting.body.size)

        stage = "EVENT_CONNECT"
        record(stage, RemoteTraceStatus.START)
        var attempt = 0
        while (true) {
            try { event = connector.open(5_000); break } catch (refused: ConnectException) {
                if (++attempt > 2) throw refused
                pause(100)
            }
        }
        record(stage, RemoteTraceStatus.OK)
        stage = "INIT_EVENT"
        record(stage, RemoteTraceStatus.START)
        event!!.write(PtpIp.initEvent(connection))
        val eventGreeting = next(event!!, 5_000)
        if (eventGreeting.type == PtpIp.INIT_FAIL) throw InitRejected()
        if (eventGreeting.type != PtpIp.INIT_EVENT_ACK) throw PtpIpFormatException()
        eventChannel = true
        record(stage, RemoteTraceStatus.OK)

        stage = "DEVICE_INFO"
        record(stage, RemoteTraceStatus.START)
        command.write(PtpIp.command(0x1001, 0))
        var expected = -1L
        val data = java.io.ByteArrayOutputStream()
        while (true) {
            val packet = next(command, 10_000)
            when (packet.type) {
                PtpIp.START_DATA -> {
                    if (expected >= 0 || packet.body.size < 12) throw PtpIpFormatException()
                    expected = PtpIp.u32(packet.body, 4).toLong() and 0xffffffffL
                    if (expected > PtpIp.MAX_DATA) throw PtpIpFormatException()
                }
                PtpIp.DATA, PtpIp.END_DATA -> {
                    if (expected < 0 || packet.body.size < 4) throw PtpIpFormatException()
                    data.write(packet.body, 4, packet.body.size - 4)
                    if (data.size() > expected) throw PtpIpFormatException()
                }
                PtpIp.COMMAND_RESPONSE -> {
                    responseCode = PtpIp.u16(packet.body, 0)
                    break
                }
                else -> throw PtpIpFormatException()
            }
        }
        if (responseCode != Ptp.OK) throw ResponseRejected()
        if (data.size().toLong() != expected) throw PtpIpFormatException()
        val info = try { Ptp.deviceInfo(data.toByteArray()) } catch (_: RuntimeException) { throw PtpIpFormatException() }
        sony = info.manufacturer.startsWith("Sony")
        operationCount = info.operations.size
        operations = WifiDiagnosticReport.WATCHED_OPERATIONS.associateWith { it in info.operations }
        record(stage, RemoteTraceStatus.OK, data.size(), responseCode)
        stage = "CLOSE"
    } catch (error: Exception) {
        failure = when (error) {
            is InitRejected -> WifiDiagnosticFailure.INIT_REJECTED
            is ResponseRejected -> WifiDiagnosticFailure.PTP_RESPONSE
            is PtpIpFormatException -> WifiDiagnosticFailure.INVALID_DATA
            is SocketTimeoutException -> WifiDiagnosticFailure.TIMEOUT
            is ConnectException -> WifiDiagnosticFailure.CONNECTION_REFUSED
            is IOException -> WifiDiagnosticFailure.IO
            else -> WifiDiagnosticFailure.OTHER
        }
        record(stage, RemoteTraceStatus.FAIL, response = responseCode)
    } finally {
        for (channel in listOf(event, command)) try { channel?.close() } catch (_: Exception) {}
        record("CLOSE", RemoteTraceStatus.OK)
    }
    return WifiDiagnosticReport(if (failure == null) WifiDiagnosticOutcome.COMPLETE else WifiDiagnosticOutcome.FAILED,
        stage.takeIf { failure != null }, failure, elapsed(), cameraAddress, initWait, failReason, eventChannel,
        responseCode, sony, operationCount, operations, pings, trace.toList())
}

private class InitRejected : IOException()
private class ResponseRejected : IOException()
