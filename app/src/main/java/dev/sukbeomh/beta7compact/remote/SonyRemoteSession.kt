package dev.sukbeomh.beta7compact.remote

import dev.sukbeomh.beta7compact.usb.Ptp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections

enum class SonyRemoteSessionState { NEW, CONNECTING, OBSERVING, CLOSING, CLOSED, FAILED }
enum class SonyPtpCloseStatus { NOT_OPENED, UNCONFIRMED, CONFIRMED }

data class SonyRemoteSnapshot(
    val extendedInfo: SonyExtendedInfo,
    val properties: List<SonyPropertyDescriptor>,
    val propertiesStale: Boolean = false,
    val objectsNeedRefresh: Boolean = false,
)

/** A separate, single-use observation session. OBSERVING does not grant camera write permission. */
class SonyRemoteSession(private val transport: SonySessionTransport) {
    private val mutex = Mutex()
    private var opened = false
    private var transportClosed = false

    @Volatile var state = SonyRemoteSessionState.NEW
        private set
    @Volatile var snapshot: SonyRemoteSnapshot? = null
        private set
    @Volatile var ptpCloseStatus = SonyPtpCloseStatus.NOT_OPENED
        private set

    suspend fun connect(): SonyRemoteSnapshot = mutex.withLock {
        check(state == SonyRemoteSessionState.NEW) { "Sony remote session is single-use" }
        state = SonyRemoteSessionState.CONNECTING
        guarded {
            val device = Ptp.deviceInfo(transport.exchange(SonySessionOperation.DEVICE_INFO))
            require(device.model == "ILCE-7C" && device.manufacturer in setOf("Sony", "Sony Corporation")) {
                "Unsupported Sony remote model"
            }
            require(device.operations.toSet().containsAll(setOf(0x9201, 0x9202))) {
                "Required Sony session operations not advertised"
            }
            ptpCloseStatus = SonyPtpCloseStatus.UNCONFIRMED
            transport.exchange(SonySessionOperation.OPEN_SESSION)
            opened = true
            // Sequence reference: libgphoto2 31a2f408 library.c:740-775, without PriorityMode write.
            transport.exchange(SonySessionOperation.CONNECT_1)
            transport.exchange(SonySessionOperation.CONNECT_2)
            val info = SonyExtendedInfoParser.parse(transport.exchange(SonySessionOperation.EXTENDED_INFO))
            require(0x9209 in device.operations || 0x9209 in info.propertyCodes || 0x9209 in info.controlCodes) {
                "Sony property snapshot operation not advertised"
            }
            transport.exchange(SonySessionOperation.CONNECT_3)
            val properties = readProperties()
            SonyRemoteSnapshot(info.copy(propertyCodes = frozen(info.propertyCodes), controlCodes = frozen(info.controlCodes)), properties)
                .also { snapshot = it; state = SonyRemoteSessionState.OBSERVING }
        }
    }

    suspend fun refreshProperties(): SonyRemoteSnapshot = mutex.withLock {
        checkObserving()
        guarded {
            checkNotNull(snapshot).copy(properties = readProperties(), propertiesStale = false)
                .also { snapshot = it }
        }
    }

    suspend fun pollEvent(timeoutMillis: Int): SonyEvent? = mutex.withLock {
        checkObserving()
        require(timeoutMillis in 1..8000) { "Event poll timeout must be 1..8000 ms" }
        guarded {
            transport.nextEvent(timeoutMillis)?.let { bytes ->
                val event = SonyEventParser.parse(bytes)
                val current = checkNotNull(snapshot)
                snapshot = when (event.code) {
                    SonyEventParser.PROPERTY_CHANGED -> current.copy(propertiesStale = true)
                    SonyEventParser.OBJECT_ADDED, SonyEventParser.OBJECT_REMOVED -> current.copy(objectsNeedRefresh = true)
                    else -> current
                }
                event.copy(parameters = frozen(event.parameters))
            }
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            if (!transportClosed) {
                state = SonyRemoteSessionState.CLOSING
                snapshot = snapshot?.copy(propertiesStale = true, objectsNeedRefresh = true)
                try {
                    release(null)
                    state = SonyRemoteSessionState.CLOSED
                } catch (failure: Throwable) {
                    state = SonyRemoteSessionState.FAILED
                    throw failure
                }
            }
        }
    }

    private fun checkObserving() = check(state == SonyRemoteSessionState.OBSERVING) { "Sony session is not observing" }

    private suspend fun readProperties(): List<SonyPropertyDescriptor> =
        frozen(SonyPropertyParser.snapshot(transport.exchange(SonySessionOperation.PROPERTY_SNAPSHOT)).map { property ->
            when (val form = property.form) {
                is SonyPropertyForm.Enumeration -> property.copy(form = form.copy(values = frozen(form.values)))
                else -> property
            }
        })

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (failure: Throwable) {
        state = SonyRemoteSessionState.FAILED
        snapshot = snapshot?.copy(propertiesStale = true, objectsNeedRefresh = true)
        withContext(NonCancellable) { release(failure) }
        throw failure
    }

    private suspend fun release(primary: Throwable?) {
        if (transportClosed) return
        var failure = primary
        try {
            if (opened) {
                try {
                    transport.exchange(SonySessionOperation.CLOSE_SESSION)
                    ptpCloseStatus = SonyPtpCloseStatus.CONFIRMED
                } catch (closeFailure: Throwable) {
                    if (failure == null) failure = closeFailure
                    else if (failure !== closeFailure) failure.addSuppressed(closeFailure)
                } finally { opened = false }
            }
        } finally {
            transportClosed = true
            try { transport.close() } catch (closeFailure: Throwable) {
                if (failure == null) failure = closeFailure
                else if (failure !== closeFailure) failure.addSuppressed(closeFailure)
            }
        }
        if (primary == null) failure?.let { throw it }
    }

    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
