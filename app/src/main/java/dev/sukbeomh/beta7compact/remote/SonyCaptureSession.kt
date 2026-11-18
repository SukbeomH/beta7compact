package dev.sukbeomh.beta7compact.remote

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.math.BigInteger
import java.util.Collections

enum class SonyCaptureState { NEW, READY, FOCUSING, CAPTURING, FINISHED, FAILED, CLOSED }
enum class SonyButtonReleaseStatus { NOT_ATTEMPTED, CONFIRMED, UNCONFIRMED }
enum class SonyAutofocusResult { LOCK_OBSERVED }
/** What happened to control priority 0xD25A in this session. */
enum class SonyPriorityStatus { NOT_REQUESTED, ALREADY_APPLICATION, NOT_WRITABLE, WRITTEN_CONFIRMED, WRITTEN_UNCONFIRMED }
/** CONFIRMED means the value read 0 again after the write; a bare OK response is only UNCONFIRMED. */
enum class SonyPriorityRestore { NOT_NEEDED, CONFIRMED, UNCONFIRMED, FAILED, SKIPPED_UNKNOWN_PREVIOUS }
enum class SonySingleCaptureOutcome { SUBMITTED_UNCONFIRMED }
data class SonySingleCaptureResult(
    val outcome: SonySingleCaptureOutcome,
    val eventCodes: List<Int>,
    val observedObjectHandles: List<Long>,
)
enum class SonyCaptureEligibilityReason {
    PROFILE_REQUIREMENTS, MISSING_CAPABILITY, EVENT_QUEUE_BUSY, DRIVE_MODE, FOCUS_MODE,
    STILL_MODE, PRIORITY_MODE, STORAGE_TARGET, PENDING_OBJECTS, SHUTTER_MODE, BUTTON_STATE,
    PROPERTY_MISSING, PROPERTY_TYPE, PROPERTY_VALUE, CLOCK,
}
class SonyCaptureEligibilityException(
    val reason: SonyCaptureEligibilityReason = SonyCaptureEligibilityReason.PROFILE_REQUIREMENTS,
    val propertyCode: Int? = null,
) : IllegalArgumentException("Capture profile requirements not met")
class SonyFocusNotAcquiredException : IOException("Fresh focus lock not observed")

/** One explicitly requested AF-only or single-capture attempt; never activated by diagnostics. */
class SonyCaptureSession(
    private val transport: SonyCaptureTransport,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** The user allowed, for this run only, writing control priority before the action and putting it back after. */
    private val claimPriority: Boolean = false,
    /** The ILCE-7C can advertise its Wi-Fi shutter buttons as controls without property descriptors. */
    private val wifi: Boolean = false,
) {
    private val mutex = Mutex()
    private val remote = SonyRemoteSession(transport, pause)
    private val focusTimeoutMillis = if (wifi) 3000L else 1000L
    private var connectedAt = 0L
    private var halfAttempted = false
    private var fullAttempted = false
    private var fullReleaseAttempted = false
    private var halfReleaseAttempted = false
    @Volatile var state = SonyCaptureState.NEW
        private set
    @Volatile var halfReleaseStatus = SonyButtonReleaseStatus.NOT_ATTEMPTED
        private set
    @Volatile var fullReleaseStatus = SonyButtonReleaseStatus.NOT_ATTEMPTED
        private set
    @Volatile var priorityStatus = SonyPriorityStatus.NOT_REQUESTED
        private set
    @Volatile var priorityRestore = SonyPriorityRestore.NOT_NEEDED
        private set
    private var previousPriority: BigInteger? = null
    private var priorityRestoreAttempted = false
    val capturePressAttempted: Boolean get() = fullAttempted
    val ptpCloseStatus: SonyPtpCloseStatus get() = remote.ptpCloseStatus

    suspend fun connect(): SonyRemoteSnapshot = mutex.withLock {
        check(state == SonyCaptureState.NEW) { "Capture session is single-use" }
        try {
            remote.connect().also { connectedAt = clockMillis(); state = SonyCaptureState.READY }
        } catch (failure: Throwable) { state = SonyCaptureState.FAILED; throw failure }
    }

    suspend fun autofocus(): SonyAutofocusResult = mutex.withLock {
        action {
            focus(prepare())
            SonyAutofocusResult.LOCK_OBSERVED
        }
    }

    suspend fun captureSingle(): SonySingleCaptureResult = mutex.withLock {
        action {
            val focused = focus(prepare())
            validateProfile(focused, buttonsReleased = false)
            state = SonyCaptureState.CAPTURING
            fullAttempted = true
            fullReleaseStatus = SonyButtonReleaseStatus.UNCONFIRMED
            transport.control(SonyCaptureCommand.FULL_PRESS)
            // Focus is already acquired; do not hold the full shutter while waiting for events.
            releaseButtons()?.let { throw it }
            val codes = mutableListOf<Int>()
            val handles = linkedSetOf<Long>()
            repeat(3) {
                transport.nextEvent(100)?.let { packet ->
                    val event = SonyEventParser.parse(packet)
                    codes += event.code
                    if (event.code == SonyEventParser.OBJECT_ADDED && event.parameters.size == 1) {
                        handles += event.parameters.single()
                    }
                }
            }
            // Card-only capture lacks a proven saved-image completion/readback in this stage.
            SonySingleCaptureResult(SonySingleCaptureOutcome.SUBMITTED_UNCONFIRMED,
                frozen(codes), frozen(handles.toList()))
        }
    }

    private suspend fun prepare(): List<SonyPropertyDescriptor> {
        val connected = checkNotNull(remote.snapshot)
        val advertised = connected.advertisedOperations + connected.extendedInfo.propertyCodes + connected.extendedInfo.controlCodes
        eligible(0x9207 in advertised && 0xd2c1 in advertised && 0xd2c2 in advertised, SonyCaptureEligibilityReason.MISSING_CAPABILITY)
        val sinceConnection = clockMillis() - connectedAt
        eligible(sinceConnection >= 0, SonyCaptureEligibilityReason.CLOCK)
        if (sinceConnection < 3000) pause(3000 - sinceConnection)
        // Refuse a non-quiescent event queue instead of consuming old image objects from RAM.
        var quiet = false
        for (attempt in 0 until 8) {
            val packet = transport.nextEvent(500)
            if (packet == null) { quiet = true; break }
            SonyEventParser.parse(packet)
        }
        eligible(quiet, SonyCaptureEligibilityReason.EVENT_QUEUE_BUSY)
        var properties = readProperties()
        if (claimPriority) properties = claimPriority(properties)
        validateProfile(properties, buttonsReleased = true)
        return properties
    }

    /** Write only when the property is the expected one-byte, enabled, writable value; then read it back. */
    private suspend fun claimPriority(properties: List<SonyPropertyDescriptor>): List<SonyPropertyDescriptor> {
        val priority = properties.singleOrNull { it.code == 0xd25a }
        val current = priority?.currentValue
        if (priority == null || current == null || priority.dataType !in PRIORITY_TYPES || priority.rawEnabled != 1 || priority.rawGetSet and 1 == 0) {
            priorityStatus = SonyPriorityStatus.NOT_WRITABLE
            return properties
        }
        if (current == BigInteger.ONE) { priorityStatus = SonyPriorityStatus.ALREADY_APPLICATION; return properties }
        previousPriority = current
        priorityStatus = SonyPriorityStatus.WRITTEN_UNCONFIRMED
        transport.set(SonySettingCommand.PRIORITY_APPLICATION)
        var latest = properties
        repeat(10) {
            pause(100)
            latest = readProperties()
            if (latest.singleOrNull { it.code == 0xd25a }?.currentValue == BigInteger.ONE) {
                priorityStatus = SonyPriorityStatus.WRITTEN_CONFIRMED
                // The write raises property-changed events; clear them so they cannot fill the short window after the shutter.
                for (attempt in 0 until 8) { transport.nextEvent(200) ?: break }
                return latest
            }
        }
        return latest
    }

    /** Puts priority back to the camera when this session took it from 0. Never throws: the action's result stands. */
    private suspend fun restorePriority() = withContext(NonCancellable) {
        if (previousPriority == null || priorityRestoreAttempted) return@withContext
        priorityRestoreAttempted = true
        if (previousPriority != BigInteger.ZERO) { priorityRestore = SonyPriorityRestore.SKIPPED_UNKNOWN_PREVIOUS; return@withContext }
        priorityRestore = try {
            transport.set(SonySettingCommand.PRIORITY_CAMERA)
            confirmPriorityRestored()
        } catch (_: Throwable) { SonyPriorityRestore.FAILED }
    }

    private suspend fun confirmPriorityRestored(): SonyPriorityRestore {
        repeat(10) { attempt ->
            if (attempt > 0) pause(100)
            val value = try { readProperties().singleOrNull { it.code == 0xd25a }?.currentValue }
            catch (_: Exception) { return SonyPriorityRestore.UNCONFIRMED }
            if (value == BigInteger.ZERO) return SonyPriorityRestore.CONFIRMED
        }
        return SonyPriorityRestore.UNCONFIRMED
    }

    private suspend fun focus(before: List<SonyPropertyDescriptor>): List<SonyPropertyDescriptor> =
        withTimeoutOrNull(focusTimeoutMillis) { focusWithinDeadline(before) } ?: throw SonyFocusNotAcquiredException()

    private suspend fun focusWithinDeadline(before: List<SonyPropertyDescriptor>): List<SonyPropertyDescriptor> {
        state = SonyCaptureState.FOCUSING
        halfAttempted = true
        halfReleaseStatus = SonyButtonReleaseStatus.UNCONFIRMED
        val started = clockMillis()
        transport.control(SonyCaptureCommand.HALF_PRESS)
        // A lock is fresh when focus was not already locked before the press, or was seen unlocked after it.
        // Fast AF can report "locked" on the very first read, so the pre-press value counts as the unlocked sighting.
        var sawUnlock = number(before, 0xd213, 2) != 2L
        repeat((focusTimeoutMillis / 50).toInt()) {
            val elapsed = clockMillis() - started
            if (elapsed < 0 || elapsed >= focusTimeoutMillis) throw SonyFocusNotAcquiredException()
            val properties = readProperties()
            val afterRead = clockMillis() - started
            if (afterRead < 0 || afterRead >= focusTimeoutMillis) throw SonyFocusNotAcquiredException()
            when (number(properties, 0xd213, 2)) {
                1L -> sawUnlock = true
                2L -> if (sawUnlock) return properties
                3L -> throw SonyFocusNotAcquiredException()
            }
            pause(50)
        }
        throw SonyFocusNotAcquiredException()
    }

    private suspend fun readProperties() = SonyPropertyParser.snapshot(transport.exchange(SonySessionOperation.PROPERTY_SNAPSHOT))

    private fun validateProfile(properties: List<SonyPropertyDescriptor>, buttonsReleased: Boolean) {
        val mode3 = remote.snapshot?.extendedInfo?.protocolVersion == SonyProtocolVersion.MODE_3
        val driveType = if (mode3) 6 else 4
        eligible(number(properties, 0x5013, driveType) == 1L, SonyCaptureEligibilityReason.DRIVE_MODE, 0x5013)
        eligible(number(properties, 0x500a, 4) == 2L, SonyCaptureEligibilityReason.FOCUS_MODE, 0x500a)
        val programs = if (mode3) setOf(1L, 0x10002L, 0x20003L, 0x30004L) else setOf(1L, 2L, 3L, 4L)
        eligible(number(properties, 0x500e, driveType) in programs, SonyCaptureEligibilityReason.STILL_MODE, 0x500e)
        val priority = properties.singleOrNull { it.code == 0xd25a }
            ?: throw SonyCaptureEligibilityException(SonyCaptureEligibilityReason.PROPERTY_MISSING, 0xd25a)
        eligible(priority.dataType in PRIORITY_TYPES, SonyCaptureEligibilityReason.PROPERTY_TYPE, 0xd25a)
        eligible(priority.currentValue == BigInteger.ONE, SonyCaptureEligibilityReason.PRIORITY_MODE, 0xd25a)
        eligible(number(properties, 0xd222, 4) == 0x10L, SonyCaptureEligibilityReason.STORAGE_TARGET, 0xd222)
        eligible(number(properties, 0xd215, 4) == 0L, SonyCaptureEligibilityReason.PENDING_OBJECTS, 0xd215)
        val focusState = number(properties, 0xd213, 2)
        fun normalShutter(code: Int) {
            val value = number(properties, code, 6)
            eligible((value ushr 16) > 0 && (value and 0xffff) > 0, SonyCaptureEligibilityReason.SHUTTER_MODE, code)
        }
        normalShutter(0xd20d)
        if (!mode3 && properties.any { it.code == 0xd229 }) normalShutter(0xd229)
        val buttons = listOf(0xd2c1, 0xd2c2)
        val snapshot = checkNotNull(remote.snapshot)
        val controlsWithoutDescriptors = wifi && mode3 && remote.deviceModel == "ILCE-7C" &&
            0x9207 in snapshot.advertisedOperations && snapshot.extendedInfo.controlCodes.containsAll(buttons)
        for (code in buttons) {
            val descriptor = properties.singleOrNull { it.code == code }
            if (descriptor == null) {
                eligible(controlsWithoutDescriptors, SonyCaptureEligibilityReason.PROPERTY_MISSING, code)
                val expectedFocus = if (buttonsReleased) 1L else 2L
                eligible(focusState == expectedFocus, SonyCaptureEligibilityReason.BUTTON_STATE, code)
                continue
            }
            eligible(descriptor.dataType == 4, SonyCaptureEligibilityReason.PROPERTY_TYPE, code)
            eligible(descriptor.rawGetSet == 0x81 && descriptor.rawEnabled == 1, SonyCaptureEligibilityReason.BUTTON_STATE, code)
            if (buttonsReleased || code == 0xd2c2) eligible(descriptor.currentValue == BigInteger.ONE, SonyCaptureEligibilityReason.BUTTON_STATE, code)
            else eligible(descriptor.currentValue == BigInteger.valueOf(2), SonyCaptureEligibilityReason.BUTTON_STATE, code)
        }
    }

    private fun number(properties: List<SonyPropertyDescriptor>, code: Int, type: Int): Long {
        val property = properties.singleOrNull { it.code == code } ?: throw SonyCaptureEligibilityException(SonyCaptureEligibilityReason.PROPERTY_MISSING, code)
        eligible(property.dataType == type, SonyCaptureEligibilityReason.PROPERTY_TYPE, code)
        val value = property.currentValue ?: throw SonyCaptureEligibilityException(SonyCaptureEligibilityReason.PROPERTY_VALUE, code)
        eligible(value.signum() >= 0 && value.bitLength() <= 63, SonyCaptureEligibilityReason.PROPERTY_VALUE, code)
        return value.toLong()
    }

    private suspend fun <T> action(block: suspend () -> T): T {
        check(state == SonyCaptureState.READY) { "Capture action requires a fresh session" }
        var primary: Throwable? = null
        try {
            return block().also { state = SonyCaptureState.FINISHED }
        } catch (failure: Throwable) {
            primary = failure
            state = SonyCaptureState.FAILED
            throw failure
        } finally {
            withContext(NonCancellable) {
                var cleanup = releaseButtons()
                restorePriority()
                if (primary != null || cleanup != null) {
                    state = SonyCaptureState.FAILED
                    try { remote.close() } catch (closeFailure: Throwable) {
                        if (cleanup == null) cleanup = closeFailure else cleanup!!.addSuppressed(closeFailure)
                    }
                }
                cleanup?.let {
                    if (primary == null) throw it else if (primary !== it) primary.addSuppressed(it)
                }
            }
        }
    }

    private suspend fun releaseButtons(): Throwable? = withContext(NonCancellable) {
        var failure: Throwable? = null
        if (fullAttempted && !fullReleaseAttempted) {
            fullReleaseAttempted = true
            try {
                transport.control(SonyCaptureCommand.FULL_RELEASE)
                fullReleaseStatus = SonyButtonReleaseStatus.CONFIRMED
            } catch (error: Throwable) { failure = error }
        }
        if (halfAttempted && !halfReleaseAttempted) {
            halfReleaseAttempted = true
            try {
                transport.control(SonyCaptureCommand.HALF_RELEASE)
                halfReleaseStatus = SonyButtonReleaseStatus.CONFIRMED
            } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        failure
    }

    suspend fun close(): Unit = withContext(NonCancellable) {
        mutex.withLock {
            if (state == SonyCaptureState.CLOSED) return@withLock
            var failure = releaseButtons()
            restorePriority()
            try { remote.close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
            state = if (failure == null && state != SonyCaptureState.FAILED) SonyCaptureState.CLOSED else SonyCaptureState.FAILED
            failure?.let { throw it }
        }
    }

    private fun eligible(value: Boolean, reason: SonyCaptureEligibilityReason, propertyCode: Int? = null) {
        if (!value) throw SonyCaptureEligibilityException(reason, propertyCode)
    }
    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    private companion object {
        /** libgphoto2 writes INT8; AlphaWire reads UINT8. Either is one byte on the wire. */
        val PRIORITY_TYPES = setOf(1, 2)
    }
}
