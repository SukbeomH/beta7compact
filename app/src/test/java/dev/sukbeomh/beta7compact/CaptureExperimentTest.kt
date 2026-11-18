package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.remote.*
import dev.sukbeomh.beta7compact.usb.PtpContainer
import dev.sukbeomh.beta7compact.usb.PtpResponseException
import dev.sukbeomh.beta7compact.usb.buffer
import kotlinx.coroutines.*
import org.junit.Test
import java.io.IOException
import kotlin.test.*

/** Synthetic permission, payload and capture mocks. No native wire, camera or photo is used. */
class CaptureExperimentTest {
    private fun text(s: String) = byteArrayOf((s.length + 1).toByte()) + (s + '\u0000').toByteArray(Charsets.UTF_16LE)
    private fun codes(vararg values: Int) = buffer(4 + values.size * 2).putInt(values.size).apply { values.forEach { putShort(it.toShort()) } }.array()
    private fun descriptor(code: Int, type: Int, value: Long): ByteArray {
        val width = 1 shl ((type - 1) / 2)
        val raw = ByteArray(width) { (value ushr (it * 8)).toByte() }
        val access = if (code in listOf(0xd2c1, 0xd2c2)) 0x81 else 0
        return buffer(6).putShort(code.toShort()).putShort(type.toShort()).put(access.toByte()).put(1).array() + raw + raw + byteArrayOf(0)
    }
    private inner class Peer : SonyCaptureTransport {
        var closed = 0
        var half = false
        var submitted = false
        var clock = 0L
        var focus = ArrayDeque(listOf(1L, 2L))
        var focusFallback = 2L
        var priority = 1L
        val controls = mutableListOf<SonyCaptureCommand>()
        var controlHook: suspend (SonyCaptureCommand) -> Unit = {}
        var exchangeFailure: SonySessionOperation? = null
        var failure: Exception = IOException("PRIVATE-PATH-EXCEPTION")
        var eventSent = false
        var resourceFail = false
        fun properties(): ByteArray {
            val props = linkedMapOf(0x5013 to (4 to 1L), 0x500a to (4 to 2L), 0x500e to (4 to 1L),
                0xd25a to (2 to priority), 0xd222 to (4 to 0x10L), 0xd215 to (4 to 0L),
                0xd213 to (2 to if (half) (focus.removeFirstOrNull() ?: focusFallback) else 1L),
                0xd20d to (6 to 0x0001007dL), 0xd2c1 to (4 to if (half) 2L else 1L), 0xd2c2 to (4 to 1L))
            return buffer(8).putInt(props.size).putInt(0).array() + props.entries.fold(byteArrayOf()) { a, (code, pair) ->
                a + descriptor(code, pair.first, pair.second)
            }
        }
        override suspend fun exchange(operation: SonySessionOperation): ByteArray {
            if (operation == exchangeFailure) throw failure
            return when (operation) {
                SonySessionOperation.DEVICE_INFO -> buffer(8).putShort(100).putInt(17).putShort(100).array() + byteArrayOf(0, 0, 0) +
                    codes(0x9201, 0x9202, 0x9209, 0x9207) + codes() + codes() + codes() + codes() +
                    text("Sony") + text("ILCE-7C") + text("PRIVATE-FIRMWARE") + text("PRIVATE-SERIAL")
                SonySessionOperation.EXTENDED_INFO -> byteArrayOf(0xc8.toByte(), 0) + codes() + codes(0xd2c1, 0xd2c2)
                SonySessionOperation.PROPERTY_SNAPSHOT -> properties()
                else -> byteArrayOf()
            }
        }
        override suspend fun control(command: SonyCaptureCommand) {
            controls += command
            controlHook(command)
            when (command) {
                SonyCaptureCommand.HALF_PRESS -> half = true
                SonyCaptureCommand.HALF_RELEASE -> half = false
                SonyCaptureCommand.FULL_PRESS -> submitted = true
                else -> Unit
            }
        }
        override suspend fun nextEvent(timeoutMillis: Int): ByteArray? {
            if (!submitted || eventSent) return null
            eventSent = true
            return PtpContainer(4, 0xc201, -1, buffer(4).putInt(0x12345678).array()).encode()
        }
        override fun close() { closed++; if (resourceFail) throw IOException("PRIVATE-CLOSE") }
    }
    private suspend fun run(peer: Peer, action: CaptureExperimentAction = CaptureExperimentAction.SINGLE_CAPTURE, events: Boolean = true) =
        runCaptureExperiment(action, open = {
            it("DISCOVERY"); it("CLAIM")
            CaptureExperimentConnection(peer, RemoteUsbSelection(0x054c, 0x0d2b, 1, 0, 0, 0x81, 2, if (events) 0x83 else null), events)
        }, sessionFactory = { SonyCaptureSession(it, { peer.clock }, { peer.clock += it }) })

    @Test fun reportStatesWhatHappenedToControlPriority() = runBlocking {
        val result = run(Peer(), CaptureExperimentAction.AUTOFOCUS)
        assertEquals(SonyPriorityStatus.NOT_REQUESTED, result.priorityStatus)
        assertContains(result.safeText(), "우선순위=NOT_REQUESTED 되돌림=NOT_NEEDED")
        assertContains(result.safeText(), "AF·단발 촬영 실험 · 형식 2")
    }

    @Test fun autofocusNeverPressesFullAndSharesOnlySafeMetadata() = runBlocking {
        val peer = Peer()
        val result = run(peer, CaptureExperimentAction.AUTOFOCUS)
        assertEquals(CaptureExperimentOutcome.AF_LOCK_OBSERVED, result.outcome)
        assertFalse(result.capturePressAttempted)
        assertEquals(listOf(SonyCaptureCommand.HALF_PRESS, SonyCaptureCommand.HALF_RELEASE), peer.controls)
        assertEquals(SonyButtonReleaseStatus.CONFIRMED, result.halfReleaseStatus)
        assertEquals(SonyButtonReleaseStatus.NOT_ATTEMPTED, result.fullReleaseStatus)
        assertEquals(SonyPtpCloseStatus.CONFIRMED, result.ptpCloseStatus)
        assertEquals(RemoteResourceClose.CONFIRMED, result.resourceClose)
        assertEquals(1, peer.closed)
        for (privateValue in listOf("PRIVATE", "Sony", "ILCE-7C", "655486")) assertFalse(privateValue in result.safeText())
    }

    @Test fun submittedCaptureIsUnconfirmedAndNeverSharesObjectHandles() = runBlocking {
        val peer = Peer()
        val result = run(peer)
        assertEquals(CaptureExperimentOutcome.CAPTURE_SUBMITTED_UNCONFIRMED, result.outcome)
        assertTrue(result.capturePressAttempted)
        assertEquals(SonyButtonReleaseStatus.CONFIRMED, result.fullReleaseStatus)
        assertEquals(listOf(0xc201), result.eventCodes)
        assertFalse("12345678" in result.safeText())
        assertFalse("305419896" in result.safeText())
        assertEquals(1, peer.controls.count { it == SonyCaptureCommand.FULL_PRESS })
        assertEquals(1, peer.closed)
    }

    @Test fun eligibilityFailureReportsReasonWithoutValuesAndDoesNotPress() = runBlocking {
        val peer = Peer().apply { priority = 0 }
        val result = run(peer)
        assertEquals(CaptureExperimentOutcome.FAILED, result.outcome)
        assertEquals(CaptureExperimentFailure.ELIGIBILITY, result.failure)
        assertEquals("PRIORITY_MODE", result.eligibilityReason)
        assertEquals(0xd25a, result.eligibilityPropertyCode)
        assertFalse(result.capturePressAttempted)
        assertTrue(peer.controls.isEmpty())
        assertEquals(RemoteResourceClose.CONFIRMED, result.resourceClose)
    }

    @Test fun missingEventEndpointClosesBeforeSessionOrControl() = runBlocking {
        val peer = Peer()
        val result = run(peer, events = false)
        assertEquals(CaptureExperimentFailure.EVENT_ENDPOINT_UNAVAILABLE, result.failure)
        assertEquals("EVENT_ENDPOINT", result.failureStage)
        assertEquals(RemoteResourceClose.CONFIRMED, result.resourceClose)
        assertNull(result.ptpCloseStatus)
        assertFalse(result.capturePressAttempted)
        assertTrue(peer.controls.isEmpty())
        assertEquals(1, peer.closed)
    }

    @Test fun permissionFailureSharesFixedStageAndNoExceptionMessage() = runBlocking {
        val result = runCaptureExperiment(CaptureExperimentAction.AUTOFOCUS, open = {
            it("PERMISSION"); throw SecurityException("PRIVATE-DEVICE-PATH")
        })
        assertEquals(CaptureExperimentOutcome.FAILED, result.outcome)
        assertEquals("PERMISSION", result.failureStage)
        assertFalse("PRIVATE" in result.safeText())
        assertFalse(result.capturePressAttempted)
    }

    @Test fun focusFailureNeverFiresAndStillReleases() = runBlocking {
        val peer = Peer().apply { focus.clear(); focusFallback = 3 }
        val result = run(peer)
        assertEquals(CaptureExperimentFailure.FOCUS_NOT_ACQUIRED, result.failure)
        assertFalse(result.capturePressAttempted)
        assertEquals(SonyButtonReleaseStatus.CONFIRMED, result.halfReleaseStatus)
        assertEquals(1, peer.closed)
    }

    @Test fun ambiguousFullPressRemainsVisibleAndCleanupCannotOverwriteFailureStage() = runBlocking {
        val peer = Peer().apply { controlHook = {
            if (it == SonyCaptureCommand.FULL_PRESS || it == SonyCaptureCommand.FULL_RELEASE) throw IOException("PRIVATE-DISCONNECTED")
        } }
        val result = run(peer)
        assertEquals(CaptureExperimentOutcome.FAILED, result.outcome)
        assertEquals("FULL_PRESS", result.failureStage)
        assertTrue(result.capturePressAttempted)
        assertEquals(SonyButtonReleaseStatus.UNCONFIRMED, result.fullReleaseStatus)
        assertEquals(SonyButtonReleaseStatus.CONFIRMED, result.halfReleaseStatus)
        assertEquals(1, result.cleanupErrorCount)
        assertFalse("PRIVATE" in result.safeText())
        assertEquals(1, peer.controls.count { it == SonyCaptureCommand.FULL_PRESS })
    }

    @Test fun primaryReleaseFailureIsIdentifiedRatherThanPreviousSuccessfulPress() = runBlocking {
        val peer = Peer().apply { controlHook = { if (it == SonyCaptureCommand.FULL_RELEASE) throw PtpResponseException(0x2019) } }
        val result = run(peer)
        assertEquals("FULL_RELEASE", result.failureStage)
        assertEquals(CaptureExperimentFailure.PTP_RESPONSE, result.failure)
        assertTrue(result.capturePressAttempted)
        assertEquals(SonyButtonReleaseStatus.UNCONFIRMED, result.fullReleaseStatus)
    }

    @Test fun resourceCloseFailureCannotReportSuccess() = runBlocking {
        val result = run(Peer().apply { resourceFail = true })
        assertEquals(CaptureExperimentOutcome.FAILED, result.outcome)
        assertEquals("RESOURCE_CLOSE", result.failureStage)
        assertEquals(RemoteResourceClose.FAILED, result.resourceClose)
        assertTrue(result.capturePressAttempted)
    }

    @Test fun cancellationIsRethrownAfterButtonAndTransportCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val peer = Peer().apply { controlHook = { if (it == SonyCaptureCommand.FULL_PRESS) { entered.complete(Unit); awaitCancellation() } } }
        val job = launch { run(peer) }
        entered.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf(SonyCaptureCommand.FULL_RELEASE, SonyCaptureCommand.HALF_RELEASE), peer.controls.takeLast(2))
        assertEquals(1, peer.closed)
    }
}
