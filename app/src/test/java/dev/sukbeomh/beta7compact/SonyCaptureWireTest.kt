package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.remote.*
import dev.sukbeomh.beta7compact.usb.PtpContainer
import dev.sukbeomh.beta7compact.usb.PtpResponseException
import kotlinx.coroutines.*
import org.junit.Test
import java.io.IOException
import kotlin.test.*

/** Synthetic wire exchanges, never a real shutter command or camera compatibility test. */
class SonyCaptureWireTest {
    private fun hex(text: String) = text.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    private class Wire : SonyRemoteWire {
        val writes = mutableListOf<ByteArray>()
        var pending = byteArrayOf()
        var shortData = false
        var closes = 0
        var dataHook: suspend () -> Unit = {}
        var controlReply: (PtpContainer) -> ByteArray = {
            PtpContainer(3, 0x2001, it.transaction, byteArrayOf()).encode()
        }
        override suspend fun write(bytes: ByteArray, timeoutMillis: Int): Int {
            writes += bytes.copyOf()
            val packet = PtpContainer.decode(bytes)
            if (packet.type == 2) {
                dataHook()
                if (shortData) return bytes.size - 1
                pending = controlReply(packet)
            } else if (packet.code != 0x9207) {
                pending = PtpContainer(3, 0x2001, packet.transaction, byteArrayOf()).encode()
            }
            return bytes.size
        }
        override suspend fun read(bytes: ByteArray, timeoutMillis: Int): Int {
            check(pending.isNotEmpty())
            val n = minOf(3, bytes.size, pending.size)
            pending.copyInto(bytes, 0, 0, n)
            pending = pending.copyOfRange(n, pending.size)
            return n
        }
        override suspend fun readEvent(timeoutMillis: Int): ByteArray? = null
        override fun close() { closes++ }
    }

    @Test fun diagnosticsDefaultRejectsAllControlCommandsWithoutWriting() = runBlocking {
        val wire = Wire()
        val client = SonySessionWireTransport(wire)
        for (command in SonyCaptureCommand.entries) assertFailsWith<IllegalStateException> { client.control(command) }
        assertTrue(wire.writes.isEmpty())
        client.exchange(SonySessionOperation.OPEN_SESSION)
        for (command in SonyCaptureCommand.entries) assertFailsWith<IllegalStateException> { client.control(command) }
        assertEquals(1, wire.writes.size)
        client.exchange(SonySessionOperation.CLOSE_SESSION)
        assertEquals(2, wire.writes.size)
    }

    @Test fun optInStillRequiresOwnedSession() = runBlocking {
        val wire = Wire()
        val client = SonySessionWireTransport(wire, allowCaptureControls = true)
        assertFailsWith<IllegalStateException> { client.control(SonyCaptureCommand.HALF_PRESS) }
        assertTrue(wire.writes.isEmpty())
        client.exchange(SonySessionOperation.OPEN_SESSION)
        client.exchange(SonySessionOperation.CLOSE_SESSION)
        assertFailsWith<IllegalStateException> { client.control(SonyCaptureCommand.HALF_PRESS) }
        client.close()
        assertFailsWith<IllegalStateException> { client.control(SonyCaptureCommand.FULL_PRESS) }
        assertEquals(2, wire.writes.size)
    }

    @Test fun exactCommandAndUint16DataFramesShareTransaction() = runBlocking {
        val wire = Wire()
        val client = SonySessionWireTransport(wire, allowCaptureControls = true)
        client.exchange(SonySessionOperation.OPEN_SESSION)
        SonyCaptureCommand.entries.forEach { client.control(it) }
        val expected = listOf(
            "10 00 00 00 01 00 07 92 01 00 00 00 c1 d2 00 00",
            "0e 00 00 00 02 00 07 92 01 00 00 00 02 00",
            "10 00 00 00 01 00 07 92 02 00 00 00 c2 d2 00 00",
            "0e 00 00 00 02 00 07 92 02 00 00 00 02 00",
            "10 00 00 00 01 00 07 92 03 00 00 00 c2 d2 00 00",
            "0e 00 00 00 02 00 07 92 03 00 00 00 01 00",
            "10 00 00 00 01 00 07 92 04 00 00 00 c1 d2 00 00",
            "0e 00 00 00 02 00 07 92 04 00 00 00 01 00",
        )
        assertEquals(9, wire.writes.size)
        expected.forEachIndexed { index, bytes -> assertContentEquals(hex(bytes), wire.writes[index + 1]) }
        client.exchange(SonySessionOperation.CLOSE_SESSION)
        assertEquals(5, PtpContainer.decode(wire.writes.last()).transaction)
    }

    @Test fun rejectedResponsePreservesCodeAndAllowsExplicitReleaseAttempt() = runBlocking {
        val wire = Wire()
        val client = SonySessionWireTransport(wire, allowCaptureControls = true)
        client.exchange(SonySessionOperation.OPEN_SESSION)
        wire.controlReply = { PtpContainer(3, 0x2019, it.transaction, byteArrayOf()).encode() }
        assertEquals(0x2019, assertFailsWith<PtpResponseException> { client.control(SonyCaptureCommand.HALF_PRESS) }.code)
        wire.controlReply = { PtpContainer(3, 0x2001, it.transaction, byteArrayOf()).encode() }
        client.control(SonyCaptureCommand.HALF_RELEASE)
        assertEquals(5, wire.writes.size)
        assertEquals(2, PtpContainer.decode(wire.writes.last()).transaction)
    }

    @Test fun partialDataWritePoisonsConnectionWithoutRetry() = runBlocking {
        val wire = Wire()
        val client = SonySessionWireTransport(wire, allowCaptureControls = true)
        client.exchange(SonySessionOperation.OPEN_SESSION)
        wire.shortData = true
        assertFailsWith<IOException> { client.control(SonyCaptureCommand.FULL_PRESS) }
        assertFailsWith<IllegalStateException> { client.control(SonyCaptureCommand.FULL_RELEASE) }
        assertFailsWith<IllegalStateException> { client.exchange(SonySessionOperation.CLOSE_SESSION) }
        assertEquals(3, wire.writes.size)
        client.close(); client.close()
        assertEquals(1, wire.closes)
    }

    @Test fun cancellationWhileSendingDataNeverReusesAmbiguousTransaction() = runBlocking {
        val wire = Wire()
        val client = SonySessionWireTransport(wire, allowCaptureControls = true)
        client.exchange(SonySessionOperation.OPEN_SESSION)
        val entered = CompletableDeferred<Unit>()
        wire.dataHook = { entered.complete(Unit); awaitCancellation() }
        val pending = launch { client.control(SonyCaptureCommand.HALF_PRESS) }
        entered.await()
        pending.cancelAndJoin()
        assertFailsWith<IllegalStateException> { client.control(SonyCaptureCommand.HALF_RELEASE) }
        assertEquals(3, wire.writes.size)
        client.close()
        assertEquals(1, wire.closes)
    }

    @Test fun controlRejectsIncomingDataAndWrongResponseTransaction() = runBlocking {
        for (unexpectedData in listOf(false, true)) {
            val wire = Wire()
            val client = SonySessionWireTransport(wire, allowCaptureControls = true)
            client.exchange(SonySessionOperation.OPEN_SESSION)
            wire.controlReply = {
                if (unexpectedData) PtpContainer(2, 0x9207, it.transaction, byteArrayOf(1, 0)).encode()
                else PtpContainer(3, 0x2001, it.transaction + 1, byteArrayOf()).encode()
            }
            assertFailsWith<IllegalArgumentException> { client.control(SonyCaptureCommand.FULL_RELEASE) }
            assertFailsWith<IllegalStateException> { client.control(SonyCaptureCommand.HALF_RELEASE) }
            assertEquals(3, wire.writes.size)
        }
    }

    @Test fun deadlineDuringDataIsBoundedAndReportable() = runBlocking {
        var now = 0L
        val wire = Wire()
        val client = SonySessionWireTransport(wire, 20, allowCaptureControls = true) { now }
        client.exchange(SonySessionOperation.OPEN_SESSION)
        wire.dataHook = { now = 20 }
        assertFailsWith<RemoteTransportTimeoutException> { client.control(SonyCaptureCommand.HALF_PRESS) }
        assertFailsWith<IllegalStateException> { client.control(SonyCaptureCommand.HALF_RELEASE) }
        assertEquals(3, wire.writes.size)
    }
}
