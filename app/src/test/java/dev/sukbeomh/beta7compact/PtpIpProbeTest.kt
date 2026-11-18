package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.remote.*
import dev.sukbeomh.beta7compact.usb.buffer
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.*

class PtpIpProbeTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private fun packet(type: Int, body: ByteArray = byteArrayOf()) = buffer(8 + body.size).putInt(8 + body.size).putInt(type).put(body).array()
    private fun text(s: String) = byteArrayOf((s.length + 1).toByte()) + (s + '\u0000').toByteArray(Charsets.UTF_16LE)
    private fun codes(vararg values: Int) = buffer(4 + values.size * 2).putInt(values.size).apply { values.forEach { putShort(it.toShort()) } }.array()
    private fun deviceInfo(vararg ops: Int) = buffer(8).putShort(100).putInt(17).putShort(100).array() + byteArrayOf(0, 0, 0) +
        codes(*ops) + codes() + codes() + codes() + codes() + text("Sony Corporation") + text("ILCE-7C") + text("2.03") + text("SECRET-SERIAL")
    private val guid = ByteArray(16) { (it + 1).toByte() }

    /** Scripted peer: each write may queue replies; reads drain the queue or time out. */
    private class Channel(val onWrite: (ByteArray, Channel) -> Unit) : PtpIpChannel {
        val writes = mutableListOf<ByteArray>()
        var pending = byteArrayOf()
        var closed = false
        fun reply(bytes: ByteArray) { pending += bytes }
        override fun write(bytes: ByteArray) { writes += bytes; onWrite(bytes, this) }
        override fun readFully(buffer: ByteArray, timeoutMillis: Int) {
            if (pending.size < buffer.size) throw SocketTimeoutException()
            pending.copyInto(buffer, 0, 0, buffer.size); pending = pending.copyOfRange(buffer.size, pending.size)
        }
        override fun close() { closed = true }
    }
    private fun ack(connection: Int = 7) = packet(PtpIp.INIT_COMMAND_ACK, buffer(4).putInt(connection).array() + ByteArray(16) { 0x55 } +
        "CAMERA-NAME\u0000".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0, 1, 0))
    private fun info(data: ByteArray, code: Int = 0x2001) = packet(PtpIp.START_DATA, buffer(12).putInt(0).putLong(data.size.toLong()).array()) +
        packet(PtpIp.DATA, buffer(4).putInt(0).array() + data.copyOfRange(0, 10)) +
        packet(PtpIp.END_DATA, buffer(4).putInt(0).array() + data.copyOfRange(10, data.size)) +
        packet(PtpIp.COMMAND_RESPONSE, buffer(6).putShort(code.toShort()).putInt(0).array())
    private fun camera(command: (ByteArray, Channel) -> Unit, event: (ByteArray, Channel) -> Unit = { _, c -> c.reply(packet(PtpIp.INIT_EVENT_ACK)) }): Pair<List<Channel>, PtpIpConnector> {
        val channels = mutableListOf<Channel>()
        return channels to PtpIpConnector { Channel(if (channels.isEmpty()) command else event).also { channels += it } }
    }
    private fun clock(): () -> Long { var now = 0L; return { now += 10; now } }

    @Test fun framesMatchThePublicLayout() {
        assertContentEquals(hex("22 00 00 00 01 00 00 00") + guid + hex("62 00 37 00 00 00") + hex("00 00 01 00"), PtpIp.initCommand(guid, "b7"))
        assertContentEquals(hex("0c 00 00 00 03 00 00 00 07 00 00 00"), PtpIp.initEvent(7))
        assertContentEquals(hex("12 00 00 00 06 00 00 00 01 00 00 00 01 10 00 00 00 00"), PtpIp.command(0x1001, 0))
        assertContentEquals(hex("08 00 00 00 0e 00 00 00"), PtpIp.pong())
        assertFailsWith<IllegalArgumentException> { PtpIp.initCommand(ByteArray(15), "x") }
    }

    @Test fun onlyPrivateDottedAddressesAreAccepted() {
        assertContentEquals(byteArrayOf(192.toByte(), 168.toByte(), 122, 1), PtpIp.localAddress(" 192.168.122.1 "))
        assertNotNull(PtpIp.localAddress("10.0.0.5")); assertNotNull(PtpIp.localAddress("172.31.255.254"))
        for (bad in listOf("8.8.8.8", "172.32.0.1", "192.169.1.1", "camera.local", "192.168.1", "192.168.1.256", "192.168.1.1.1", "", "1e1.0.0.1", "192.168..1"))
            assertNull(PtpIp.localAddress(bad), bad)
    }

    @Test fun completeProbeAnswersPingsAndReportsOnlyNumbersAndFlags() {
        val (channels, connector) = camera({ bytes, c ->
            when (bytes[4].toInt()) {
                PtpIp.INIT_COMMAND_REQUEST -> c.reply(packet(PtpIp.PING) + ack())
                PtpIp.COMMAND_REQUEST -> c.reply(info(deviceInfo(0x1001, 0x9201, 0x9202, 0x9209, 0x9280)))
            }
        })
        val report = runPtpIpProbe(connector, guid, "beta7compact", "192.168.122.1", clock(), {})
        assertEquals(WifiDiagnosticOutcome.COMPLETE, report.outcome); assertNull(report.failure)
        assertTrue(report.eventChannel); assertEquals(1, report.pings); assertEquals(0x2001, report.responseCode)
        assertEquals(true, report.sonyVendor); assertEquals(5, report.operationCount)
        assertEquals(mapOf(0x9201 to true, 0x9202 to true, 0x9205 to false, 0x9207 to false, 0x9209 to true, 0x9280 to true, 0x9281 to false), report.operations)
        // Greeting, the pong, then exactly one operation; the event connection carries its greeting only.
        assertEquals(listOf(PtpIp.INIT_COMMAND_REQUEST, PtpIp.PONG, PtpIp.COMMAND_REQUEST), channels[0].writes.map { it[4].toInt() })
        assertContentEquals(PtpIp.initEvent(7), channels[1].writes.single())
        assertTrue(channels.all { it.closed })
        val shared = report.safeText()
        assertContains(shared, "카메라주소=192.168.122.1 포트=15740"); assertContains(shared, "0x9280=있음 0x9281=없음")
        for (secret in listOf("ILCE-7C", "SECRET-SERIAL", "CAMERA-NAME", "Sony Corporation", "2.03")) assertFalse(shared.contains(secret), secret)
    }

    @Test fun rejectedGreetingKeepsTheReasonAndSendsNothingElse() {
        val (channels, connector) = camera({ _, c -> c.reply(packet(PtpIp.INIT_FAIL, buffer(4).putInt(1).array())) })
        val report = runPtpIpProbe(connector, guid, "beta7compact", null, clock(), {})
        assertEquals(WifiDiagnosticFailure.INIT_REJECTED, report.failure); assertEquals("INIT_COMMAND", report.failureStage)
        assertEquals(1, report.initFailReason); assertFalse(report.eventChannel)
        assertEquals(1, channels.size); assertEquals(1, channels[0].writes.size); assertTrue(channels[0].closed)
        assertContains(report.safeText(), "거부사유=0x0001")
    }

    @Test fun silenceRefusalAndBrokenDataAreToldApart() {
        val silent = runPtpIpProbe(camera({ _, _ -> }).second, guid, "b", null, clock(), {})
        assertEquals(WifiDiagnosticFailure.TIMEOUT, silent.failure); assertEquals("INIT_COMMAND", silent.failureStage)

        val refused = runPtpIpProbe({ throw ConnectException() }, guid, "b", null, clock(), {})
        assertEquals(WifiDiagnosticFailure.CONNECTION_REFUSED, refused.failure); assertEquals("COMMAND_CONNECT", refused.failureStage)

        var opens = 0; val waits = mutableListOf<Long>()
        val first = Channel { _, c -> c.reply(ack()) }
        val noEvent = runPtpIpProbe({ if (opens++ == 0) first else throw ConnectException() }, guid, "b", null, clock(), { waits += it })
        assertEquals("EVENT_CONNECT", noEvent.failureStage); assertEquals(4, opens); assertEquals(listOf(100L, 100L), waits); assertTrue(first.closed)

        val busy = runPtpIpProbe(camera({ bytes, c ->
            if (bytes[4].toInt() == PtpIp.INIT_COMMAND_REQUEST) c.reply(ack()) else c.reply(packet(PtpIp.COMMAND_RESPONSE, buffer(6).putShort(0x2019).putInt(0).array()))
        }).second, guid, "b", null, clock(), {})
        assertEquals(WifiDiagnosticFailure.PTP_RESPONSE, busy.failure); assertEquals(0x2019, busy.responseCode)

        for (broken in listOf(
            packet(PtpIp.DATA, ByteArray(8)),                                                    // data before its start
            packet(PtpIp.START_DATA, buffer(12).putInt(0).putLong(PtpIp.MAX_DATA + 1L).array()), // larger than the limit
            hex("07 00 00 00 07 00 00 00"),                                                      // length shorter than a header
            // fewer bytes than the start packet announced
            packet(PtpIp.START_DATA, buffer(12).putInt(0).putLong(40).array()) + packet(PtpIp.END_DATA, buffer(4).putInt(0).array() + ByteArray(20)) +
                packet(PtpIp.COMMAND_RESPONSE, buffer(6).putShort(0x2001).putInt(0).array()),
        )) {
            val report = runPtpIpProbe(camera({ bytes, c ->
                if (bytes[4].toInt() == PtpIp.INIT_COMMAND_REQUEST) c.reply(ack()) else c.reply(broken)
            }).second, guid, "b", null, clock(), {})
            assertEquals("DEVICE_INFO", report.failureStage)
            assertEquals(WifiDiagnosticFailure.INVALID_DATA, report.failure)
        }
    }

    @Test fun endlessPingsAreCutOff() {
        val report = runPtpIpProbe(camera({ _, c -> c.reply(packet(PtpIp.PING)) }).second, guid, "b", null, clock(), {})
        assertEquals(WifiDiagnosticFailure.INVALID_DATA, report.failure); assertEquals(17, report.pings)
    }
}
