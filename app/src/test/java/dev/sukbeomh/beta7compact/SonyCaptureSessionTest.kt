package dev.sukbeomh.beta7compact

import dev.sukbeomh.beta7compact.remote.*
import dev.sukbeomh.beta7compact.usb.PtpContainer
import dev.sukbeomh.beta7compact.usb.buffer
import kotlinx.coroutines.*
import org.junit.Test
import java.io.IOException
import kotlin.test.*

/** Synthetic conservative profile only, not an alpha7C capture or a confirmed saved photo. */
class SonyCaptureSessionTest {
    private fun text(s: String) = byteArrayOf((s.length + 1).toByte()) + (s + '\u0000').toByteArray(Charsets.UTF_16LE)
    private fun codes(vararg values: Int) = buffer(4 + values.size * 2).putInt(values.size).apply {
        values.forEach { putShort(it.toShort()) }
    }.array()
    private fun descriptor(code: Int, type: Int, value: Long, access: Int = 0, enabled: Int = 1): ByteArray {
        val width = 1 shl ((type - 1) / 2)
        val n = ByteArray(width) { (value ushr (it * 8)).toByte() }
        return buffer(6).putShort(code.toShort()).putShort(type.toShort()).put(access.toByte()).put(enabled.toByte()).array() + n + n + byteArrayOf(0)
    }
    private fun event(code: Int, handle: Int? = null) = PtpContainer(4, code, -1,
        handle?.let { buffer(4).putInt(it).array() } ?: byteArrayOf()).encode()

    private inner class Peer(val mode3: Boolean = false) : SonyCaptureTransport {
        var model = "ILCE-7C"
        var opcode = true
        var controlsAdvertised = true
        val controlCalls = mutableListOf<SonyCaptureCommand>()
        var controlHook: suspend (SonyCaptureCommand) -> Unit = {}
        var snapshotHook: suspend () -> Unit = {}
        var closed = 0
        var half = false
        var full = false
        var focus = ArrayDeque(listOf(1L, 2L))
        var focusFallback = 2L
        var idleFocus = 1L
        var priorityAccess = 1
        var priorityAccepted = true
        var restoreFails = false
        var restoreIgnored = false
        val sets = mutableListOf<SonySettingCommand>()
        fun session(claim: Boolean) = SonyCaptureSession(this, { clock }, { clock += it; waitCalls++ }, claim)
        override suspend fun set(command: SonySettingCommand) {
            sets += command
            if (command == SonySettingCommand.PRIORITY_CAMERA) { if (restoreFails) throw java.io.IOException("restore"); if (restoreIgnored) return }
            if (priorityAccepted) overrides[0xd25a] = (overrides[0xd25a]?.first ?: 2) to command.value.toLong()
        }
        var access = 0x81
        var enabled = 1
        val overrides = mutableMapOf<Int, Pair<Int, Long>>()
        val omitted = mutableSetOf<Int>()
        val eventTimeouts = mutableListOf<Int>()
        val beforeEvents = ArrayDeque<ByteArray>()
        val afterEvents = ArrayDeque<ByteArray>()
        var clock = 0L
        var waitCalls = 0
        fun session() = SonyCaptureSession(this, { clock }, { clock += it; waitCalls++ })
        fun properties(): ByteArray {
            val varyingType = if (mode3) 6 else 4
            val props = linkedMapOf(
                0x5013 to (varyingType to 1L), 0x500a to (4 to 2L), 0x500e to (varyingType to 1L),
                0xd25a to (2 to 1L), 0xd222 to (4 to 0x10L), 0xd215 to (4 to 0L),
                0xd213 to (2 to if (half) (focus.removeFirstOrNull() ?: focusFallback) else idleFocus),
                0xd20d to (6 to 0x0001007dL), 0xd2c1 to (4 to if (half) 2L else 1L),
                0xd2c2 to (4 to 1L),
            )
            props.putAll(overrides)
            omitted.forEach { props.remove(it) }
            return buffer(8).putInt(props.size).putInt(0).array() + props.entries.fold(byteArrayOf()) { a, (code, pair) ->
                a + descriptor(code, pair.first, pair.second, if (code in listOf(0xd2c1, 0xd2c2)) access else if (code == 0xd25a) priorityAccess else 0, enabled)
            }
        }
        override suspend fun exchange(operation: SonySessionOperation): ByteArray = when (operation) {
            SonySessionOperation.DEVICE_INFO -> buffer(8).putShort(100).putInt(17).putShort(100).array() +
                byteArrayOf(0, 0, 0) + codes(0x9201, 0x9202, 0x9209, if (opcode) 0x9207 else 0x1001) +
                codes() + codes() + codes() + codes() + text("Sony") + text(model) + text("synthetic") + text("")
            SonySessionOperation.EXTENDED_INFO -> buffer(2).putShort(if (mode3) 0x12c else 0xc8).array() +
                codes() + if (controlsAdvertised) codes(0xd2c1, 0xd2c2) else codes()
            SonySessionOperation.PROPERTY_SNAPSHOT -> { snapshotHook(); properties() }
            else -> byteArrayOf()
        }
        override suspend fun control(command: SonyCaptureCommand) {
            controlCalls += command
            controlHook(command)
            when (command) {
                SonyCaptureCommand.HALF_PRESS -> half = true
                SonyCaptureCommand.HALF_RELEASE -> half = false
                SonyCaptureCommand.FULL_PRESS -> full = true
                else -> Unit
            }
        }
        override suspend fun nextEvent(timeoutMillis: Int): ByteArray? {
            eventTimeouts += timeoutMillis
            return if (full) afterEvents.removeFirstOrNull() else beforeEvents.removeFirstOrNull()
        }
        override fun close() { closed++ }
    }

    @Test fun autofocusObservesNewLockAndAlwaysReleasesHalf() = runBlocking {
        val peer = Peer(); val session = peer.session(); session.connect()
        assertEquals(SonyAutofocusResult.LOCK_OBSERVED, session.autofocus())
        assertEquals(listOf(SonyCaptureCommand.HALF_PRESS, SonyCaptureCommand.HALF_RELEASE), peer.controlCalls)
        assertEquals(SonyButtonReleaseStatus.CONFIRMED, session.halfReleaseStatus)
        assertTrue(peer.clock >= 3000)
        assertFailsWith<IllegalStateException> { session.autofocus() }
        session.close(); assertEquals(1, peer.closed)
    }

    @Test fun otherSonyModelStillRequiresSafeCaptureProfile() = runBlocking {
        val peer = Peer().apply { model = "ILCE-7CM2"; overrides[0x5013] = 4 to 2L }
        val session = peer.session(); session.connect()
        assertFailsWith<SonyCaptureEligibilityException> { session.autofocus() }
        assertTrue(peer.controlCalls.isEmpty())
        session.close()
    }

    @Test fun singleCaptureReleasesBeforeObservationAndNeverClaimsSavedImage() = runBlocking {
        for (mode3 in listOf(false, true)) {
            val peer = Peer(mode3)
            peer.afterEvents.addAll(listOf(event(0xc201, 42), event(0xc201, 42), event(0xc201, 43)))
            val session = peer.session(); session.connect()
            val result = session.captureSingle()
            assertEquals(SonySingleCaptureOutcome.SUBMITTED_UNCONFIRMED, result.outcome)
            assertEquals(listOf(42L, 43L), result.observedObjectHandles)
            assertEquals(listOf(SonyCaptureCommand.HALF_PRESS, SonyCaptureCommand.FULL_PRESS,
                SonyCaptureCommand.FULL_RELEASE, SonyCaptureCommand.HALF_RELEASE), peer.controlCalls)
            assertTrue(session.capturePressAttempted)
            assertFailsWith<IllegalStateException> { session.captureSingle() }
            session.close()
        }
    }

    @Test fun staleFocusAndNoFocusDoNotFireShutter() = runBlocking {
        for (state in listOf(2L, 3L, 1L)) {
            // A lock is stale when focus already read "locked" before the press and never left it.
            val peer = Peer().apply { focus.clear(); focusFallback = state; if (state == 2L) idleFocus = 2L }
            val session = peer.session(); session.connect()
            assertFailsWith<SonyFocusNotAcquiredException> { session.captureSingle() }
            assertEquals(listOf(SonyCaptureCommand.HALF_PRESS, SonyCaptureCommand.HALF_RELEASE), peer.controlCalls)
            assertEquals(SonyCaptureState.FAILED, session.state)
            assertEquals(1, peer.closed)
        }
    }

    @Test fun lockReportedOnTheFirstReadAfterAnUnlockedStartIsFresh() = runBlocking {
        val peer = Peer().apply { focus.clear(); focusFallback = 2L }
        val session = peer.session(); session.connect()
        assertEquals(SonyAutofocusResult.LOCK_OBSERVED, session.autofocus())
        assertEquals(listOf(SonyCaptureCommand.HALF_PRESS, SonyCaptureCommand.HALF_RELEASE), peer.controlCalls)
        session.close()
    }

    @Test fun allowedRunTakesPriorityReadsItBackAndReturnsItAfterTheAction() = runBlocking {
        for (type in listOf(1, 2)) {
            val peer = Peer().apply { overrides[0xd25a] = type to 0L }
            val session = peer.session(claim = true); session.connect()
            assertEquals(SonyAutofocusResult.LOCK_OBSERVED, session.autofocus())
            assertEquals(listOf(SonySettingCommand.PRIORITY_APPLICATION, SonySettingCommand.PRIORITY_CAMERA), peer.sets)
            assertEquals(SonyPriorityStatus.WRITTEN_CONFIRMED, session.priorityStatus)
            assertEquals(SonyPriorityRestore.CONFIRMED, session.priorityRestore)
            assertEquals(0L, peer.overrides[0xd25a]!!.second)
            session.close(); assertEquals(2, peer.sets.size)
        }
    }

    @Test fun priorityIsNeverWrittenWithoutTheRunsPermissionOrWhenAlreadyApplication() = runBlocking {
        val refused = Peer().apply { overrides[0xd25a] = 2 to 0L }
        val session = refused.session(); session.connect()
        val error = assertFailsWith<SonyCaptureEligibilityException> { session.autofocus() }
        assertEquals(SonyCaptureEligibilityReason.PRIORITY_MODE, error.reason)
        assertTrue(refused.sets.isEmpty()); assertTrue(refused.controlCalls.isEmpty())
        assertEquals(SonyPriorityStatus.NOT_REQUESTED, session.priorityStatus)
        val already = Peer(); val second = already.session(claim = true); second.connect()
        second.autofocus()
        assertTrue(already.sets.isEmpty()); assertEquals(SonyPriorityStatus.ALREADY_APPLICATION, second.priorityStatus)
        assertEquals(SonyPriorityRestore.NOT_NEEDED, second.priorityRestore)
    }

    @Test fun readOnlyOrUnexpectedPriorityIsLeftAloneAndRefusesTheAction() = runBlocking {
        for (change in listOf<Peer.() -> Unit>({ priorityAccess = 0 }, { enabled = 0 }, { overrides[0xd25a] = 4 to 0L })) {
            val peer = Peer().apply { overrides[0xd25a] = 2 to 0L }.apply(change)
            val session = peer.session(claim = true); session.connect()
            assertFailsWith<SonyCaptureEligibilityException> { session.autofocus() }
            assertTrue(peer.sets.isEmpty()); assertTrue(peer.controlCalls.isEmpty())
            assertEquals(SonyPriorityStatus.NOT_WRITABLE, session.priorityStatus)
        }
    }

    @Test fun writeThatDoesNotTakeEffectRefusesTheActionAndStillReturnsPriority() = runBlocking {
        val peer = Peer().apply { overrides[0xd25a] = 2 to 0L; priorityAccepted = false }
        val session = peer.session(claim = true); session.connect()
        val error = assertFailsWith<SonyCaptureEligibilityException> { session.captureSingle() }
        assertEquals(SonyCaptureEligibilityReason.PRIORITY_MODE, error.reason)
        assertTrue(peer.controlCalls.isEmpty())
        assertEquals(SonyPriorityStatus.WRITTEN_UNCONFIRMED, session.priorityStatus)
        assertEquals(listOf(SonySettingCommand.PRIORITY_APPLICATION, SonySettingCommand.PRIORITY_CAMERA), peer.sets)
    }

    @Test fun restoreThatFailsOrDoesNotTakeEffectIsReportedWithoutHidingTheResult() = runBlocking {
        for ((fails, expected) in listOf(true to SonyPriorityRestore.FAILED, false to SonyPriorityRestore.UNCONFIRMED)) {
            val peer = Peer().apply { overrides[0xd25a] = 2 to 0L; restoreFails = fails; restoreIgnored = !fails }
            val session = peer.session(claim = true); session.connect()
            assertEquals(SonyAutofocusResult.LOCK_OBSERVED, session.autofocus())
            assertEquals(expected, session.priorityRestore)
            assertEquals(SonyCaptureState.FINISHED, session.state)
            session.close(); assertEquals(2, peer.sets.size)
        }
    }

    @Test fun priorityThatWasNeitherCameraNorApplicationIsNotGuessedBack() = runBlocking {
        val peer = Peer().apply { overrides[0xd25a] = 2 to 2L }
        val session = peer.session(claim = true); session.connect()
        session.autofocus()
        assertEquals(listOf(SonySettingCommand.PRIORITY_APPLICATION), peer.sets)
        assertEquals(SonyPriorityRestore.SKIPPED_UNKNOWN_PREVIOUS, session.priorityRestore)
    }

    @Test fun unsupportedProfileRefusesBeforeAnyButtonCommand() = runBlocking {
        val changes = listOf(0x5013 to (4 to 2L), 0x500a to (4 to 0x8004L), 0x500e to (4 to 0x8000L),
            0xd25a to (2 to 0L), 0xd222 to (4 to 0x11L), 0xd215 to (4 to 0x8001L),
            0xd20d to (6 to 0L), 0xd20d to (6 to 0x00010000L), 0xd229 to (6 to 0L))
        for ((code, value) in changes) {
            val peer = Peer().apply { overrides[code] = value }
            val session = peer.session(); session.connect()
            assertFailsWith<SonyCaptureEligibilityException> { session.captureSingle() }
            assertTrue(peer.controlCalls.isEmpty())
        }
    }

    @Test fun missingAdvertisementOrButtonDescriptorNeverSendsControl() = runBlocking {
        for (case in 0..4) {
            val peer = Peer().apply { when (case) {
                0 -> opcode = false
                1 -> controlsAdvertised = false
                2 -> access = 0
                3 -> enabled = 0
                else -> overrides[0xd2c1] = 2 to 1L
            } }
            val session = peer.session(); session.connect()
            assertFailsWith<SonyCaptureEligibilityException> { session.autofocus() }
            assertTrue(peer.controlCalls.isEmpty())
        }
    }

    @Test fun preexistingEventsAreDrainedAndCapturedEventDoesNotConfirmPhoto() = runBlocking {
        val peer = Peer()
        peer.beforeEvents.add(event(0xc201, 123))
        peer.afterEvents.add(event(0xc206))
        peer.afterEvents.add(event(0xcfff))
        val session = peer.session(); session.connect()
        val result = session.captureSingle()
        assertEquals(SonySingleCaptureOutcome.SUBMITTED_UNCONFIRMED, result.outcome)
        assertTrue(result.observedObjectHandles.isEmpty())
        assertEquals(listOf(0xc206, 0xcfff), result.eventCodes)
        session.close()
    }

    @Test fun busyPreexistingEventQueueRefusesCapture() = runBlocking {
        val peer = Peer().apply { repeat(8) { beforeEvents.add(event(0xc201, 123)) } }
        val session = peer.session(); session.connect()
        assertFailsWith<SonyCaptureEligibilityException> { session.captureSingle() }
        assertTrue(peer.controlCalls.isEmpty())
    }

    @Test fun ambiguousPressAttemptsBothReleasesAndNeverRetries() = runBlocking {
        val primary = IOException("synthetic disconnect")
        val peer = Peer().apply { controlHook = { if (it == SonyCaptureCommand.FULL_PRESS) throw primary } }
        val session = peer.session(); session.connect()
        assertSame(primary, assertFailsWith<IOException> { session.captureSingle() })
        assertEquals(listOf(SonyCaptureCommand.HALF_PRESS, SonyCaptureCommand.FULL_PRESS,
            SonyCaptureCommand.FULL_RELEASE, SonyCaptureCommand.HALF_RELEASE), peer.controlCalls)
        assertTrue(session.capturePressAttempted)
        assertFailsWith<IllegalStateException> { session.captureSingle() }
        session.close(); assertEquals(1, peer.closed)
    }

    @Test fun releaseFailureStillAttemptsOtherReleaseAndPreservesUnconfirmedState() = runBlocking {
        val peer = Peer().apply { controlHook = { if (it == SonyCaptureCommand.FULL_RELEASE) throw IOException("release") } }
        val session = peer.session(); session.connect()
        assertFailsWith<IOException> { session.captureSingle() }
        assertEquals(SonyButtonReleaseStatus.UNCONFIRMED, session.fullReleaseStatus)
        assertEquals(SonyButtonReleaseStatus.CONFIRMED, session.halfReleaseStatus)
        session.close()
        assertEquals(1, peer.controlCalls.count { it == SonyCaptureCommand.FULL_RELEASE })
        assertEquals(SonyCaptureState.FAILED, session.state)
    }

    @Test fun cancellationDuringPressStillReleasesBeforeTransportClose() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val peer = Peer().apply { controlHook = { if (it == SonyCaptureCommand.FULL_PRESS) { entered.complete(Unit); awaitCancellation() } } }
        val session = peer.session(); session.connect()
        val task = launch { session.captureSingle() }
        entered.await(); task.cancelAndJoin()
        assertEquals(listOf(SonyCaptureCommand.FULL_RELEASE, SonyCaptureCommand.HALF_RELEASE), peer.controlCalls.takeLast(2))
        assertEquals(1, peer.closed)
        assertEquals(SonyCaptureState.FAILED, session.state)
    }

    @Test fun failedFocusSnapshotReleasesBeforeClosingAndPreservesPrimary() = runBlocking {
        val primary = IOException("snapshot")
        val peer = Peer().apply {
            snapshotHook = { if (half) throw primary }
            controlHook = { if (it == SonyCaptureCommand.HALF_RELEASE) throw IOException("release") }
        }
        val session = peer.session(); session.connect()
        val failure = assertFailsWith<IOException> { session.autofocus() }
        assertEquals("snapshot", failure.message)
        // Coroutine stack recovery may copy the exception while retaining the original as its cause.
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
        assertTrue(causes.any { it === primary })
        assertTrue(causes.flatMap { it.suppressed.toList() }.any { it.message == "release" })
        assertEquals(1, peer.closed)
        assertEquals(SonyButtonReleaseStatus.UNCONFIRMED, session.halfReleaseStatus)
    }
    @Test fun slowFocusResponseCannotTriggerFullPress() = runBlocking {
        val peer = Peer().apply { snapshotHook = { if (half) clock += 1000 } }
        val session = peer.session(); session.connect()
        assertFailsWith<SonyFocusNotAcquiredException> { session.captureSingle() }
        assertFalse(session.capturePressAttempted)
        assertEquals(listOf(SonyCaptureCommand.HALF_PRESS, SonyCaptureCommand.HALF_RELEASE), peer.controlCalls)
    }

    @Test fun mode3IgnoresD229BecauseItIsNotItsShutterProperty() = runBlocking {
        val peer = Peer(true).apply { overrides[0xd229] = 6 to 0L }
        val session = peer.session(); session.connect()
        assertEquals(SonySingleCaptureOutcome.SUBMITTED_UNCONFIRMED, session.captureSingle().outcome)
        session.close()
    }

    @Test fun eligibilityReasonsDistinguishUserSettingsWithoutExposingValues() = runBlocking {
        val cases = listOf(
            Triple(0x5013, 4 to 2L, SonyCaptureEligibilityReason.DRIVE_MODE),
            Triple(0xd25a, 2 to 0L, SonyCaptureEligibilityReason.PRIORITY_MODE),
            Triple(0xd222, 4 to 0x11L, SonyCaptureEligibilityReason.STORAGE_TARGET),
        )
        for ((code, value, reason) in cases) {
            val peer = Peer().apply { overrides[code] = value }
            val session = peer.session(); session.connect()
            val failure = assertFailsWith<SonyCaptureEligibilityException> { session.captureSingle() }
            assertEquals(reason, failure.reason)
            assertEquals(code, failure.propertyCode)
            assertEquals("Capture profile requirements not met", failure.message)
            assertTrue(peer.controlCalls.isEmpty())
        }
    }

    @Test fun missingPropertyAndIncorrectTypeHaveSeparateReasons() = runBlocking {
        for (missing in listOf(true, false)) {
            val peer = Peer().apply {
                if (missing) omitted += 0xd25a else overrides[0xd25a] = 4 to 1L
            }
            val session = peer.session(); session.connect()
            val failure = assertFailsWith<SonyCaptureEligibilityException> { session.autofocus() }
            assertEquals(if (missing) SonyCaptureEligibilityReason.PROPERTY_MISSING else SonyCaptureEligibilityReason.PROPERTY_TYPE,
                failure.reason)
            assertEquals(0xd25a, failure.propertyCode)
            assertTrue(peer.controlCalls.isEmpty())
        }
    }

    @Test fun eventQueuePreparationUsesRealAdapterSizedTimeout() = runBlocking {
        val peer = Peer().apply { repeat(8) { beforeEvents.add(event(0xc201, 42)) } }
        val session = peer.session(); session.connect()
        val failure = assertFailsWith<SonyCaptureEligibilityException> { session.autofocus() }
        assertEquals(SonyCaptureEligibilityReason.EVENT_QUEUE_BUSY, failure.reason)
        assertNull(failure.propertyCode)
        assertEquals(List(8) { 500 }, peer.eventTimeouts)
        assertTrue(peer.controlCalls.isEmpty())
    }

}
