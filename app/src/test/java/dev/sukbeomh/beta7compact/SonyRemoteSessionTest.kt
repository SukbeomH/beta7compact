package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.remote.*
import dev.sukbeomh.beta7compact.usb.buffer
import kotlinx.coroutines.*
import org.junit.Test
import java.io.IOException
import kotlin.test.*

/** Synthetic payload transport only; no model support or real connection is established by these tests. */
class SonyRemoteSessionTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    private fun text(s: String) = byteArrayOf((s.length + 1).toByte()) + (s + '\u0000').toByteArray(Charsets.UTF_16LE)
    private fun codes(vararg codes: Int) = buffer(4 + codes.size * 2).putInt(codes.size).apply {
        codes.forEach { putShort(it.toShort()) }
    }.array()
    private fun device(model: String = "ILCE-7C", maker: String = "Sony Corporation", ops: IntArray = intArrayOf(0x9201, 0x9202, 0x9209)) =
        buffer(8).putShort(100).putInt(6).putShort(100).array() + byteArrayOf(0) + buffer(2).putShort(0).array() +
            codes(*ops) + codes() + codes() + codes() + codes() + text(maker) + text(model) + text("SYNTHETIC") + text("NOT-A-CAPTURE")
    private val ext2 = hex("c8 00 01 00 00 00 07 50 00 00 00 00")
    private val props = hex("01 00 00 00 00 00 00 00 07 50 04 00 01 01 64 00 c8 00 02 02 00 64 00 c8 00")
    private val propertyEvent = hex("10 00 00 00 04 00 03 c2 ff ff ff ff 07 50 00 00")
    private val connectOrder = listOf(SonySessionOperation.DEVICE_INFO, SonySessionOperation.OPEN_SESSION,
        SonySessionOperation.CONNECT_1, SonySessionOperation.CONNECT_2, SonySessionOperation.EXTENDED_INFO,
        SonySessionOperation.CONNECT_3, SonySessionOperation.PROPERTY_SNAPSHOT)

    private inner class Fake : SonySessionTransport {
        val calls = mutableListOf<SonySessionOperation>()
        var info = device()
        var extended = ext2
        var properties = props
        var event: ByteArray? = null
        var closes = 0
        var closeFailure: Throwable? = null
        var exchangeHook: suspend (SonySessionOperation) -> Unit = {}
        var eventHook: suspend () -> Unit = {}
        override suspend fun exchange(operation: SonySessionOperation): ByteArray {
            calls += operation
            exchangeHook(operation)
            return when (operation) {
                SonySessionOperation.DEVICE_INFO -> info
                SonySessionOperation.EXTENDED_INFO -> extended
                SonySessionOperation.PROPERTY_SNAPSHOT -> properties
                else -> byteArrayOf()
            }
        }
        override suspend fun nextEvent(timeoutMillis: Int): ByteArray? { eventHook(); return event }
        override fun close() { closes++; closeFailure?.let { throw it } }
    }

    @Test fun opensBothFormatsInOrderAndClosesOnlyOnce() = runBlocking {
        for (version in listOf(SonyProtocolVersion.MODE_2, SonyProtocolVersion.MODE_3)) {
            val wire = Fake().apply { extended = ext2.copyOf().apply {
                this[0] = version.advertisedValue.toByte(); this[1] = (version.advertisedValue shr 8).toByte()
            } }
            val session = SonyRemoteSession(wire)
            val snapshot = session.connect()
            assertEquals(connectOrder, wire.calls)
            assertEquals(version, snapshot.extendedInfo.protocolVersion)
            assertEquals(SonyRemoteSessionState.OBSERVING, session.state)
            assertFalse(snapshot.propertiesStale)
            session.close(); session.close()
            assertEquals(connectOrder + SonySessionOperation.CLOSE_SESSION, wire.calls)
            assertEquals(1, wire.closes)
            assertEquals(SonyPtpCloseStatus.CONFIRMED, session.ptpCloseStatus)
            assertEquals(SonyRemoteSessionState.CLOSED, session.state)
            assertTrue(checkNotNull(session.snapshot).propertiesStale)
        }
    }

    @Test fun otherSonyModelCanRunExperimentalDiagnostic() = runBlocking {
        val wire = Fake().apply { info = device(model = "ILCE-7CM2") }
        val session = SonyRemoteSession(wire)
        session.connect()
        assertEquals(SonyRemoteSessionState.OBSERVING, session.state)
        assertEquals(connectOrder, wire.calls)
        session.close()
        assertEquals(SonySessionOperation.CLOSE_SESSION, wire.calls.last())
    }

    @Test fun wrongMakerOrMissingHandshakeCodeNeverOpensSession() = runBlocking {
        for (info in listOf(device(maker = "Other"), device(ops = intArrayOf(0x9202, 0x9209)), device(ops = intArrayOf(0x9201, 0x9209)))) {
            val wire = Fake().apply { this.info = info }
            val session = SonyRemoteSession(wire)
            assertFailsWith<IllegalArgumentException> { session.connect() }
            assertEquals(listOf(SonySessionOperation.DEVICE_INFO), wire.calls)
            assertEquals(SonyRemoteSessionState.FAILED, session.state)
            assertEquals(SonyPtpCloseStatus.NOT_OPENED, session.ptpCloseStatus)
            assertEquals(1, wire.closes)
        }
    }

    @Test fun acceptsSnapshotOpcodeFromEitherExtendedArray() = runBlocking {
        for (extended in listOf(hex("c8 00 01 00 00 00 09 92 00 00 00 00"), hex("c8 00 00 00 00 00 01 00 00 00 09 92"))) {
            val wire = Fake().apply { info = device(ops = intArrayOf(0x9201, 0x9202)); this.extended = extended }
            val session = SonyRemoteSession(wire)
            session.connect()
            assertEquals(SonyRemoteSessionState.OBSERVING, session.state)
            session.close()
        }
    }

    @Test fun missingSnapshotOpcodeStopsBeforeThirdConnectPhase() = runBlocking {
        val wire = Fake().apply { info = device(ops = intArrayOf(0x9201, 0x9202)) }
        val session = SonyRemoteSession(wire)
        assertFailsWith<IllegalArgumentException> { session.connect() }
        assertFalse(SonySessionOperation.CONNECT_3 in wire.calls)
        assertEquals(SonySessionOperation.CLOSE_SESSION, wire.calls.last())
        assertEquals(1, wire.closes)
    }

    @Test fun malformedNegotiationOrInitialSnapshotFailsAtomically() = runBlocking {
        for (invalidExt in listOf(true, false)) {
            val wire = Fake().apply { if (invalidExt) extended = hex("ff ff") else properties = props.copyOf(10) }
            val session = SonyRemoteSession(wire)
            assertFailsWith<IllegalArgumentException> { session.connect() }
            assertNull(session.snapshot)
            assertEquals(SonyRemoteSessionState.FAILED, session.state)
            assertEquals(SonySessionOperation.CLOSE_SESSION, wire.calls.last())
            assertEquals(1, wire.closes)
        }
    }

    @Test fun failedOpenDoesNotCloseAnUnconfirmedOrForeignPtpSession() = runBlocking {
        val wire = Fake().apply { exchangeHook = { if (it == SonySessionOperation.OPEN_SESSION) throw IOException("rejected") } }
        val session = SonyRemoteSession(wire)
        assertFailsWith<IOException> { session.connect() }
        assertFalse(SonySessionOperation.CLOSE_SESSION in wire.calls)
        assertEquals(SonyPtpCloseStatus.UNCONFIRMED, session.ptpCloseStatus)
        assertEquals(1, wire.closes)
    }

    @Test fun preservesPrimaryFailureAndBothCleanupFailures() = runBlocking {
        val original = IOException("original")
        val ptpClose = IOException("close session")
        val wireClose = IOException("close transport")
        val wire = Fake().apply {
            exchangeHook = { when (it) {
                SonySessionOperation.EXTENDED_INFO -> throw original
                SonySessionOperation.CLOSE_SESSION -> throw ptpClose
                else -> Unit
            } }
            closeFailure = wireClose
        }
        val session = SonyRemoteSession(wire)
        assertSame(original, assertFailsWith<IOException> { session.connect() })
        assertContentEquals(arrayOf(ptpClose, wireClose), original.suppressed)
        session.close()
        assertEquals(1, wire.closes)
        assertEquals(SonyRemoteSessionState.FAILED, session.state)
        assertEquals(SonyPtpCloseStatus.UNCONFIRMED, session.ptpCloseStatus)
    }

    @Test fun explicitCloseFailureIsVisibleAndStillReleasesTransport() = runBlocking {
        val wire = Fake()
        val session = SonyRemoteSession(wire)
        session.connect()
        wire.exchangeHook = { if (it == SonySessionOperation.CLOSE_SESSION) throw IOException("close failed") }
        assertFailsWith<IOException> { session.close() }
        session.close()
        assertEquals(1, wire.closes)
        assertEquals(SonyRemoteSessionState.FAILED, session.state)
    }

    @Test fun propertyEventsInvalidateUntilExplicitRefreshAndNeverAutoSend() = runBlocking {
        val wire = Fake()
        val session = SonyRemoteSession(wire)
        session.connect()
        assertNull(session.pollEvent(1))
        wire.event = propertyEvent
        repeat(2) { assertEquals(SonyEventParser.PROPERTY_CHANGED, session.pollEvent(1)?.code) }
        assertTrue(checkNotNull(session.snapshot).propertiesStale)
        assertEquals(connectOrder, wire.calls)
        assertFalse(session.refreshProperties().propertiesStale)
        assertEquals(connectOrder + SonySessionOperation.PROPERTY_SNAPSHOT, wire.calls)
        session.close()
    }

    @Test fun objectAndCapturedAndUnknownEventsDoNotClaimCaptureSuccess() = runBlocking {
        val wire = Fake()
        val session = SonyRemoteSession(wire)
        session.connect()
        for (code in listOf(0xc206, 0xcffe)) {
            wire.event = hex("0c 00 00 00 04 00 00 00 00 00 00 00").apply { this[6] = code.toByte(); this[7] = (code shr 8).toByte() }
            assertEquals(code, session.pollEvent(1)?.code)
            assertFalse(checkNotNull(session.snapshot).objectsNeedRefresh)
        }
        wire.event = hex("10 00 00 00 04 00 01 c2 00 00 00 00 01 c0 ff ff")
        session.pollEvent(1)
        assertTrue(checkNotNull(session.snapshot).objectsNeedRefresh)
        session.close()
    }

    @Test fun malformedRefreshInvalidatesPriorSnapshotAndCannotReconnect() = runBlocking {
        val wire = Fake()
        val session = SonyRemoteSession(wire)
        val first = session.connect()
        wire.properties = props.copyOf().apply { this[10] = 0xff.toByte(); this[11] = 0xff.toByte() }
        assertFailsWith<IllegalArgumentException> { session.refreshProperties() }
        assertEquals(first.properties, session.snapshot?.properties)
        assertTrue(checkNotNull(session.snapshot).propertiesStale)
        assertEquals(SonyRemoteSessionState.FAILED, session.state)
        val count = wire.calls.size
        assertFailsWith<IllegalStateException> { session.connect() }
        assertFailsWith<IllegalStateException> { session.refreshProperties() }
        assertFailsWith<IllegalStateException> { session.pollEvent(1) }
        assertEquals(count, wire.calls.size)
    }

    @Test fun eventFramingAndTransportTimeoutFailuresTerminateSession() = runBlocking {
        for (timeout in listOf(false, true)) {
            val wire = Fake()
            val session = SonyRemoteSession(wire)
            session.connect()
            if (timeout) wire.eventHook = { throw IOException("timeout") } else wire.event = byteArrayOf(0)
            assertFails { session.pollEvent(1) }
            assertEquals(SonyRemoteSessionState.FAILED, session.state)
            assertEquals(1, wire.closes)
        }
    }

    @Test fun cancellationDuringConnectionCleansUpNonCancellably() = runBlocking {
        for (cancelAt in listOf(SonySessionOperation.CONNECT_2, SonySessionOperation.PROPERTY_SNAPSHOT)) {
            val reached = CompletableDeferred<Unit>()
            val wire = Fake().apply { exchangeHook = {
                if (it == cancelAt) { reached.complete(Unit); awaitCancellation() }
                if (it == SonySessionOperation.CLOSE_SESSION) currentCoroutineContext().ensureActive()
            } }
            val session = SonyRemoteSession(wire)
            val job = launch { session.connect() }
            reached.await()
            job.cancelAndJoin()
            assertEquals(SonyRemoteSessionState.FAILED, session.state)
            assertNull(session.snapshot)
            assertEquals(SonyPtpCloseStatus.CONFIRMED, session.ptpCloseStatus)
            assertEquals(1, wire.closes)
        }
    }

    @Test fun eventPollAndRefreshAreSerialized() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val wire = Fake().apply { eventHook = { reached.complete(Unit); resume.await() }; event = propertyEvent }
        val session = SonyRemoteSession(wire)
        session.connect()
        val poll = async { session.pollEvent(100) }
        reached.await()
        val refresh = async { session.refreshProperties() }
        yield()
        assertEquals(1, wire.calls.count { it == SonySessionOperation.PROPERTY_SNAPSHOT })
        resume.complete(Unit)
        poll.await(); refresh.await()
        assertFalse(checkNotNull(session.snapshot).propertiesStale)
        session.close()
    }

    @Test fun snapshotsCannotBeMutatedThroughListCasts() = runBlocking {
        val session = SonyRemoteSession(Fake())
        val snapshot = session.connect()
        assertFailsWith<UnsupportedOperationException> { (snapshot.properties as MutableList<*>).clear() }
        assertFailsWith<UnsupportedOperationException> { (snapshot.extendedInfo.propertyCodes as MutableList<*>).clear() }
        val values = (snapshot.properties.single().form as SonyPropertyForm.Enumeration).values
        assertFailsWith<UnsupportedOperationException> { (values as MutableList<*>).clear() }
        session.close()
    }

    @Test fun newSessionCanBeClosedWithoutOpeningAndRejectsInvalidPollDeadline() = runBlocking {
        val unused = Fake()
        val closed = SonyRemoteSession(unused)
        closed.close(); closed.close()
        assertTrue(unused.calls.isEmpty())
        assertEquals(1, unused.closes)
        assertEquals(SonyRemoteSessionState.CLOSED, closed.state)
        val wire = Fake()
        val session = SonyRemoteSession(wire)
        session.connect()
        for (timeout in listOf(0, -1, 8001)) assertFailsWith<IllegalArgumentException> { session.pollEvent(timeout) }
        assertEquals(SonyRemoteSessionState.OBSERVING, session.state)
        session.close()
    }

    @Test fun emptyExtendedInfoIsRequestedAgainUntilTheCameraAnswers() = runBlocking {
        val fake = Fake(); var asked = 0; val waits = mutableListOf<Long>()
        fake.extended = byteArrayOf()
        fake.exchangeHook = { if (it == SonySessionOperation.EXTENDED_INFO && ++asked == 3) fake.extended = ext2 }
        val session = SonyRemoteSession(fake) { waits += it }
        assertEquals(SonyProtocolVersion.MODE_2, session.connect().extendedInfo.protocolVersion)
        assertEquals(3, fake.calls.count { it == SonySessionOperation.EXTENDED_INFO }); assertEquals(listOf(50L, 50L), waits)
        session.close()
    }

    @Test fun extendedInfoThatStaysEmptyFailsAfterBoundedRetries() = runBlocking {
        val fake = Fake().apply { extended = byteArrayOf() }
        val session = SonyRemoteSession(fake) {}
        assertFailsWith<IllegalArgumentException> { session.connect() }
        assertEquals(11, fake.calls.count { it == SonySessionOperation.EXTENDED_INFO })
        assertEquals(SonyRemoteSessionState.FAILED, session.state); assertEquals(1, fake.closes)
    }

    @Test fun diagnosticKeepsOnlyTheListedConditionPropertiesAndSaysWhenOneIsMissing() {
        fun property(code: Int, type: Int, value: Long) = SonyPropertyDescriptor(code, type, 0x01, 1,
            java.math.BigInteger.ZERO, java.math.BigInteger.valueOf(value), SonyPropertyForm.None)
        val lines = RemoteDiagnosticReport.conditions(listOf(property(0xd25a, 2, 0), property(0xd222, 4, 0x11),
            property(0x5007, 4, 0x190), property(0xd215, 4, 0x8001), property(0xd215, 4, 0)))
        assertEquals(RemoteDiagnosticReport.CONDITION_CODES.size, lines.size)
        assertContains(lines, "0xD25A type=0x0002 getset=0x01 enabled=1 value=0x0")
        assertContains(lines, "0xD222 type=0x0004 getset=0x01 enabled=1 value=0x11")
        assertContains(lines, "0xD215 중복 2"); assertContains(lines, "0xD221 없음")
        assertTrue(lines.none { it.startsWith("0x5007") })
    }
}
