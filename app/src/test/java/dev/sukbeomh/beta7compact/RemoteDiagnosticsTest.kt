package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.remote.*
import dev.sukbeomh.beta7compact.usb.PtpContainer
import dev.sukbeomh.beta7compact.usb.PtpResponseException
import dev.sukbeomh.beta7compact.usb.buffer
import kotlinx.coroutines.*
import org.junit.Test
import java.io.IOException
import kotlin.test.*

/** Hand-authored payloads, not actual camera responses. No physical USB adapter is opened. */
class RemoteDiagnosticsTest {
    private fun text(value: String) = byteArrayOf((value.length + 1).toByte()) + (value + '\u0000').toByteArray(Charsets.UTF_16LE)
    private fun codes(vararg codes: Int) = buffer(4 + codes.size * 2).putInt(codes.size).apply { codes.forEach { putShort(it.toShort()) } }.array()
    private fun info() = buffer(8).putShort(100).putInt(17).putShort(100).array() + byteArrayOf(0, 0, 0) +
        codes(0x9201, 0x9202, 0x9209) + codes() + codes() + codes() + codes() +
        text("Sony Corporation") + text("ILCE-7C") + text("PRIVATE-VERSION") + text("PRIVATE-SERIAL")

    private inner class Peer : SonySessionTransport {
        val calls = mutableListOf<SonySessionOperation>()
        var closes = 0
        var polls = 0
        var fail: SonySessionOperation? = null
        var failure: Exception = IOException("PRIVATE-EXCEPTION-PAYLOAD")
        var closeFailure = false
        var snapshot = ByteArray(8)
        var event: ByteArray? = null
        var eventWait: suspend () -> Unit = {}
        override suspend fun exchange(operation: SonySessionOperation): ByteArray {
            calls += operation
            if (operation == fail) throw failure
            return when (operation) {
                SonySessionOperation.DEVICE_INFO -> info()
                SonySessionOperation.EXTENDED_INFO -> byteArrayOf(0xc8.toByte(), 0) + codes() + codes()
                SonySessionOperation.PROPERTY_SNAPSHOT -> snapshot
                else -> byteArrayOf()
            }
        }
        override suspend fun nextEvent(timeoutMillis: Int): ByteArray? {
            assertEquals(500, timeoutMillis)
            polls++
            eventWait()
            return event
        }
        override fun close() { closes++; if (closeFailure) throw IOException("PRIVATE-CLOSE") }
    }

    private suspend fun run(peer: Peer, events: Boolean = true) = collectRemoteDiagnostics {
        it("DISCOVERY"); it("CLAIM")
        RemoteDiagnosticConnection(peer, RemoteUsbSelection(0x054c, 0x0d2b, 1, 0, 0, 0x81, 0x02, if (events) 0x83 else null), events)
    }

    @Test fun completeReportContainsBoundedObservationAndConfirmedCleanup() = runBlocking {
        val peer = Peer().apply { event = PtpContainer(4, 0xc203, -1, buffer(4).putInt(0x12345678).array()).encode() }
        val result = run(peer)
        assertEquals(RemoteDiagnosticOutcome.COMPLETE, result.outcome)
        assertEquals(3, peer.polls)
        assertEquals(listOf(0xc203, 0xc203, 0xc203), result.eventCodes)
        assertEquals(0xc8, result.advertisedProtocolVersion)
        assertEquals(0, result.propertyCount)
        assertEquals(SonyPtpCloseStatus.CONFIRMED, result.ptpCloseStatus)
        assertEquals(RemoteResourceClose.CONFIRMED, result.resourceClose)
        assertEquals(1, peer.closes)
        val text = result.safeText()
        for (privateText in listOf("PRIVATE", "Sony Corporation", "ILCE-7C", "12345678", "305419896")) assertFalse(privateText in text)
        assertTrue("0xC203" in text)
        assertTrue(result.trace.any { it.stage == "CLOSE_SESSION" && it.status == RemoteTraceStatus.OK })
    }

    @Test fun noEventAndUnavailableEndpointAreDistinct() = runBlocking {
        val noEvent = run(Peer())
        assertEquals(RemoteDiagnosticOutcome.COMPLETE, noEvent.outcome)
        assertEquals(3, noEvent.trace.count { it.status == RemoteTraceStatus.NO_EVENT })
        val absent = Peer()
        val skipped = run(absent, false)
        assertEquals(RemoteDiagnosticOutcome.COMPLETE_WITHOUT_EVENTS, skipped.outcome)
        assertEquals(0, absent.polls)
        assertEquals(1, skipped.trace.count { it.status == RemoteTraceStatus.SKIPPED })
    }

    @Test fun parserFailureRetainsVersionAndTypedPropertyWithoutPayload() = runBlocking {
        val peer = Peer().apply {
            snapshot = byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0x1e, 0xd2.toByte(), -1, -1, 0, 0, 0, 0, 0)
        }
        val result = run(peer)
        assertEquals(RemoteDiagnosticOutcome.FAILED, result.outcome)
        assertEquals("PROPERTY_SNAPSHOT_VALIDATION", result.failureStage)
        assertEquals(RemoteDiagnosticFailure.UNSUPPORTED_PROPERTY_TYPE, result.failure)
        assertEquals(0xd21e, result.unsupportedPropertyCode)
        assertEquals(0xffff, result.unsupportedPropertyType)
        assertEquals(0xc8, result.advertisedProtocolVersion)
        assertNull(result.propertyCount)
        assertEquals(SonyPtpCloseStatus.CONFIRMED, result.ptpCloseStatus)
        assertEquals(RemoteResourceClose.CONFIRMED, result.resourceClose)
    }

    @Test fun responseFailurePreservesOriginalStageThroughCleanup() = runBlocking {
        val result = run(Peer().apply { fail = SonySessionOperation.CONNECT_2; failure = PtpResponseException(0x2019) })
        assertEquals("CONNECT_2", result.failureStage)
        assertEquals(RemoteDiagnosticFailure.PTP_RESPONSE, result.failure)
        assertTrue(result.trace.any { it.stage == "CONNECT_2" && it.responseCode == 0x2019 })
        assertEquals(SonyPtpCloseStatus.CONFIRMED, result.ptpCloseStatus)
    }

    @Test fun cleanupFailureIsNotReportedAsSuccess() = runBlocking {
        val result = run(Peer().apply { closeFailure = true })
        assertEquals(RemoteDiagnosticOutcome.FAILED, result.outcome)
        assertEquals("CLOSE_SESSION", result.failureStage)
        assertEquals(SonyPtpCloseStatus.CONFIRMED, result.ptpCloseStatus)
        assertEquals(RemoteResourceClose.FAILED, result.resourceClose)
        assertEquals(1, result.cleanupErrorCount)
        assertFalse("PRIVATE-CLOSE" in result.safeText())
    }

    @Test fun factoryFailureUsesOnlySafeFixedStageAndNeverExceptionText() = runBlocking {
        val result = collectRemoteDiagnostics {
            it("PRIVATE-PATH-OR-DEVICE")
            throw IOException("PRIVATE-EXCEPTION")
        }
        assertEquals("USB_OPEN", result.failureStage)
        assertEquals(RemoteResourceClose.UNCONFIRMED, result.resourceClose)
        assertNull(result.sessionState)
        assertFalse("PRIVATE" in result.safeText())
    }

    @Test fun cancelledObservationRethrowsAfterCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val peer = Peer().apply { eventWait = { entered.complete(Unit); awaitCancellation() } }
        val job = launch { run(peer) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, peer.closes)
        assertEquals(1, peer.calls.count { it == SonySessionOperation.CLOSE_SESSION })
    }

    @Test fun internalDeadlineProducesShareableFailureInsteadOfLosingReport() = runBlocking {
        val result = run(Peer().apply { eventWait = { withTimeout(1) { awaitCancellation() } } })
        assertEquals(RemoteDiagnosticOutcome.FAILED, result.outcome)
        assertEquals(RemoteDiagnosticFailure.TIMEOUT, result.failure)
        assertEquals("EVENT_POLL", result.failureStage)
        assertEquals(RemoteResourceClose.CONFIRMED, result.resourceClose)
    }
}
