package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.remote.*
import dev.sukbeomh.beta7compact.usb.PtpContainer
import dev.sukbeomh.beta7compact.usb.PtpResponseException
import dev.sukbeomh.beta7compact.usb.buffer
import dev.sukbeomh.beta7compact.usb.le
import kotlinx.coroutines.*
import org.junit.Test
import java.io.IOException
import kotlin.test.*

/** Scripted USB bytes only; no Android adapter or real-camera capture. */
class SonySessionWireTransportTest {
    private fun response(tx: Int, code: Int = 0x2001, payload: ByteArray = byteArrayOf()) =
        PtpContainer(3, code, tx, payload).encode()
    private fun data(op: Int, tx: Int, payload: ByteArray = byteArrayOf(9, 8)) =
        PtpContainer(2, op, tx, payload).encode()

    private inner class Wire : SonyRemoteWire {
        val commands = mutableListOf<PtpContainer>()
        val timeouts = mutableListOf<Int>()
        var fragment = Int.MAX_VALUE
        var pending = byteArrayOf()
        var closed = 0
        var zeroReads = 0
        var shortWrite = false
        var event: ByteArray? = null
        var readFailure: Exception? = null
        var afterWrite: () -> Unit = {}
        var onRead: suspend () -> Unit = {}
        var onEvent: suspend () -> Unit = {}
        var reply: (PtpContainer) -> ByteArray = { c ->
            (if (c.code == 0x1002 || c.code == 0x1003) byteArrayOf() else data(c.code, c.transaction)) + response(c.transaction)
        }
        override suspend fun write(bytes: ByteArray, timeoutMillis: Int): Int {
            timeouts += timeoutMillis
            val command = PtpContainer.decode(bytes)
            commands += command
            pending = reply(command)
            afterWrite()
            return if (shortWrite) bytes.size - 1 else bytes.size
        }
        override suspend fun read(bytes: ByteArray, timeoutMillis: Int): Int {
            timeouts += timeoutMillis
            onRead()
            readFailure?.let { throw it }
            if (zeroReads > 0) { zeroReads--; return 0 }
            if (pending.isEmpty()) return -1
            val n = minOf(bytes.size, pending.size, fragment)
            pending.copyInto(bytes, 0, 0, n)
            pending = pending.copyOfRange(n, pending.size)
            return n
        }
        override suspend fun readEvent(timeoutMillis: Int): ByteArray? {
            onEvent()
            timeouts += timeoutMillis
            return event
        }
        override fun close() { closed++ }
    }

    @Test fun fixedOperationsEncodeExactParametersAndTransactionSequence() = runBlocking {
        val wire = Wire()
        val client = SonySessionWireTransport(wire)
        val operations = listOf(SonySessionOperation.DEVICE_INFO, SonySessionOperation.OPEN_SESSION,
            SonySessionOperation.CONNECT_1, SonySessionOperation.CONNECT_2, SonySessionOperation.EXTENDED_INFO,
            SonySessionOperation.CONNECT_3, SonySessionOperation.PROPERTY_SNAPSHOT, SonySessionOperation.CLOSE_SESSION)
        operations.forEach { client.exchange(it) }
        assertEquals(listOf(0x1001, 0x1002, 0x9201, 0x9201, 0x9202, 0x9201, 0x9209, 0x1003), wire.commands.map { it.code })
        assertEquals(listOf(0, 0, 1, 2, 3, 4, 5, 6), wire.commands.map { it.transaction })
        val parameters = wire.commands.map { c -> le(c.payload).run { List(remaining() / 4) { int } } }
        assertEquals(listOf(emptyList(), listOf(1), listOf(1, 0, 0), listOf(2, 0, 0), listOf(0x12c, 1), listOf(3, 0, 0), emptyList(), emptyList()), parameters)
        assertTrue(wire.commands.all { it.type == 1 })
        assertTrue(wire.timeouts.all { it in 1..8_000 })
    }

    @Test fun fragmentedAndCoalescedDataResponseBothDecode() = runBlocking {
        for (fragment in listOf(1, 7, 12, 16_384)) {
            val wire = Wire().apply { this.fragment = fragment }
            val client = SonySessionWireTransport(wire)
            assertContentEquals(byteArrayOf(9, 8), client.exchange(SonySessionOperation.DEVICE_INFO))
        }
    }

    @Test fun responseOnlySdioIsRejectedAndConnectionCannotBeReused() = runBlocking {
        val wire = Wire()
        val client = SonySessionWireTransport(wire)
        client.exchange(SonySessionOperation.OPEN_SESSION)
        wire.reply = { response(it.transaction) }
        assertFailsWith<IllegalArgumentException> { client.exchange(SonySessionOperation.CONNECT_1) }
        assertFailsWith<IllegalStateException> { client.exchange(SonySessionOperation.CLOSE_SESSION) }
        assertEquals(2, wire.commands.size)
        client.close(); client.close()
        assertEquals(1, wire.closed)
    }

    @Test fun protocolErrorPreservesCodeAndDoesNotAssumeSessionOwnership() = runBlocking {
        val wire = Wire().apply { reply = { response(it.transaction, 0x201e) } }
        val client = SonySessionWireTransport(wire)
        assertEquals(0x201e, assertFailsWith<PtpResponseException> { client.exchange(SonySessionOperation.OPEN_SESSION) }.code)
        assertFailsWith<IllegalStateException> { client.exchange(SonySessionOperation.CLOSE_SESSION) }
        client.close()
        assertEquals(1, wire.commands.size)
    }

    @Test fun synchronizedNonOkResponseStillAllowsOwnedSessionClose() = runBlocking {
        val wire = Wire()
        val client = SonySessionWireTransport(wire)
        client.exchange(SonySessionOperation.OPEN_SESSION)
        wire.reply = { response(it.transaction, 0x2005) }
        assertFailsWith<PtpResponseException> { client.exchange(SonySessionOperation.CONNECT_1) }
        wire.reply = { response(it.transaction) }
        client.exchange(SonySessionOperation.CLOSE_SESSION)
        assertEquals(listOf(0, 1, 2), wire.commands.map { it.transaction })
    }

    @Test fun invalidHeadersDataAndTrailingBytesFailClosed() = runBlocking {
        val replies: List<(PtpContainer) -> ByteArray> = listOf(
            { c -> data(c.code, c.transaction + 1) + response(c.transaction) },
            { c -> data(c.code + 1, c.transaction) + response(c.transaction) },
            { c -> data(c.code, c.transaction) + data(c.code, c.transaction) + response(c.transaction) },
            { c -> PtpContainer(4, 0xc203, c.transaction, byteArrayOf()).encode() },
            { c -> response(c.transaction, payload = ByteArray(24)) },
            { c -> response(c.transaction, payload = byteArrayOf(0)) },
            { c -> data(c.code, c.transaction) + response(c.transaction) + byteArrayOf(0) },
            { c -> buffer(12).putInt(11).putShort(2).putShort(c.code.toShort()).putInt(c.transaction).array() },
            { c -> buffer(12).putInt(1_048_589).putShort(2).putShort(c.code.toShort()).putInt(c.transaction).array() },
            { c -> buffer(12).putInt(-1).putShort(2).putShort(c.code.toShort()).putInt(c.transaction).array() },
        )
        for (reply in replies) {
            val wire = Wire().apply { this.reply = reply }
            val client = SonySessionWireTransport(wire)
            assertFailsWith<IllegalArgumentException> { client.exchange(SonySessionOperation.DEVICE_INFO) }
            assertFailsWith<IllegalStateException> { client.exchange(SonySessionOperation.DEVICE_INFO) }
            assertEquals(1, wire.commands.size)
        }
    }

    @Test fun dataOnNoDataOperationFails(): Unit = runBlocking {
        val wire = Wire().apply { reply = { data(it.code, it.transaction) + response(it.transaction) } }
        assertFailsWith<IllegalArgumentException> { SonySessionWireTransport(wire).exchange(SonySessionOperation.OPEN_SESSION) }
    }

    @Test fun boundedZeroReadsAndShortWritesPoisonConnection() = runBlocking {
        for (zero in listOf(false, true)) {
            val wire = Wire().apply { if (zero) zeroReads = 9 else shortWrite = true }
            val client = SonySessionWireTransport(wire)
            assertFailsWith<IOException> { client.exchange(SonySessionOperation.DEVICE_INFO) }
            assertFailsWith<IllegalStateException> { client.exchange(SonySessionOperation.DEVICE_INFO) }
        }
        val wire = Wire().apply { zeroReads = 8 }
        assertContentEquals(byteArrayOf(9, 8), SonySessionWireTransport(wire).exchange(SonySessionOperation.DEVICE_INFO))
    }

    @Test fun clockDeadlineAndTransportFailurePreventReuse(): Unit = runBlocking {
        var now = 0L
        val wire = Wire().apply { afterWrite = { now = 20 } }
        val client = SonySessionWireTransport(wire, 20) { now }
        assertFailsWith<IOException> { client.exchange(SonySessionOperation.DEVICE_INFO) }
        assertEquals(listOf(20), wire.timeouts)
        assertFailsWith<IllegalStateException> { client.exchange(SonySessionOperation.DEVICE_INFO) }
        val disconnected = Wire().apply { readFailure = IOException("disconnected") }
        val second = SonySessionWireTransport(disconnected)
        assertFailsWith<IOException> { second.exchange(SonySessionOperation.OPEN_SESSION) }
        assertFailsWith<IllegalStateException> { second.exchange(SonySessionOperation.CLOSE_SESSION) }
    }

    @Test fun cancellationDuringIoPoisonsSessionAndCleanupClosesOnce() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val wire = Wire().apply { onRead = { entered.complete(Unit); awaitCancellation() } }
        val client = SonySessionWireTransport(wire)
        val task = launch { client.exchange(SonySessionOperation.OPEN_SESSION) }
        entered.await()
        task.cancelAndJoin()
        assertFailsWith<IllegalStateException> { client.exchange(SonySessionOperation.OPEN_SESSION) }
        client.close(); client.close()
        assertEquals(1, wire.closed)
    }

    @Test fun eventPollingIsSeparateBoundedAndMalformedEventsBreakConnection() = runBlocking {
        val wire = Wire()
        val client = SonySessionWireTransport(wire)
        assertFailsWith<IllegalStateException> { client.nextEvent(10) }
        client.exchange(SonySessionOperation.OPEN_SESSION)
        assertNull(client.nextEvent(10))
        val packet = PtpContainer(4, 0xc203, -1, buffer(4).putInt(0xd21e).array()).encode()
        wire.event = packet
        assertContentEquals(packet, client.nextEvent(10))
        assertFailsWith<IllegalArgumentException> { client.nextEvent(0) }
        wire.event = packet.copyOf(11)
        assertFailsWith<IllegalArgumentException> { client.nextEvent(10) }
        assertFailsWith<IllegalStateException> { client.exchange(SonySessionOperation.CLOSE_SESSION) }
        assertEquals(1, wire.commands.size)
    }

    @Test fun closedTransportNeverWritesAndRejectsDuplicateOpen() = runBlocking {
        val wire = Wire()
        val client = SonySessionWireTransport(wire)
        client.exchange(SonySessionOperation.OPEN_SESSION)
        assertFailsWith<IllegalStateException> { client.exchange(SonySessionOperation.OPEN_SESSION) }
        client.close()
        assertFailsWith<IllegalStateException> { client.exchange(SonySessionOperation.DEVICE_INFO) }
        assertEquals(1, wire.commands.size)
    }

    @Test fun internalDeadlinesAreReportableIoFailuresNotUserCancellation(): Unit = runBlocking {
        val stalled = Wire().apply { onRead = { awaitCancellation() } }
        assertFailsWith<IOException> {
            SonySessionWireTransport(stalled, transactionTimeoutMillis = 20)
                .exchange(SonySessionOperation.OPEN_SESSION)
        }
        val events = Wire().apply { onEvent = { awaitCancellation() } }
        val client = SonySessionWireTransport(events)
        client.exchange(SonySessionOperation.OPEN_SESSION)
        assertFailsWith<IOException> { client.nextEvent(20) }
        assertTrue(currentCoroutineContext().isActive)
        client.close()
    }
}
