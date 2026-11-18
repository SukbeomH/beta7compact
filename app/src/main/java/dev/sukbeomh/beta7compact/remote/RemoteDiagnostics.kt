package dev.sukbeomh.beta7compact.remote

import android.content.Context
import dev.sukbeomh.beta7compact.usb.PtpResponseException
import dev.sukbeomh.beta7compact.usb.UsbAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.IOException

internal data class RemoteDiagnosticConnection(
    val transport: SonySessionTransport,
    val selection: RemoteUsbSelection? = null,
    val eventPollingAvailable: Boolean = false,
)

suspend fun collectRemoteDiagnostics(context: Context): RemoteDiagnosticReport = collectRemoteDiagnostics { stage ->
    val wire = AndroidSonyRemoteWire.open(context, stage)
    RemoteDiagnosticConnection(SonySessionWireTransport(wire), wire.selection, wire.selection.eventEndpoint != null)
}

/** The factory is invoked inside the same USB ownership scope as imports and regular diagnostics. */
internal suspend fun collectRemoteDiagnostics(
    open: suspend ((String) -> Unit) -> RemoteDiagnosticConnection,
): RemoteDiagnosticReport {
    val started = System.nanoTime()
    fun elapsed() = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
    val trace = mutableListOf<RemoteDiagnosticTrace>()
    val events = mutableListOf<Int>()
    var stage = "WAITING_FOR_USB"
    var failureStage: String? = null
    var failure: RemoteDiagnosticFailure? = null
    var selection: RemoteUsbSelection? = null
    var eventAvailable: Boolean? = null
    var version: Int? = null
    var propertyCount: Int? = null
    var conditions = emptyList<String>()
    var unsupportedCode: Int? = null
    var unsupportedType: Int? = null
    var session: SonyRemoteSession? = null
    var resourceClose = RemoteResourceClose.NOT_CREATED
    var cleanupErrorCount = 0
    var outcome = RemoteDiagnosticOutcome.FAILED
    fun record(name: String, status: RemoteTraceStatus, bytes: Int? = null, response: Int? = null) {
        trace += RemoteDiagnosticTrace(name, status, elapsed(), bytes, response)
    }
    try {
        UsbAccess.useRemote(openTransport = {
            stage = "DISCOVERY"
            resourceClose = RemoteResourceClose.UNCONFIRMED
            val connection = open { incoming ->
                stage = incoming.takeIf { it in setOf("DISCOVERY", "PERMISSION", "CONFIGURATION", "INTERFACE", "CLAIM") } ?: "USB_OPEN"
                record(stage, RemoteTraceStatus.START)
            }
            selection = connection.selection
            eventAvailable = connection.eventPollingAvailable
            object : SonySessionTransport {
                override suspend fun exchange(operation: SonySessionOperation): ByteArray {
                    if (operation != SonySessionOperation.CLOSE_SESSION) stage = operation.name
                    record(operation.name, RemoteTraceStatus.START)
                    try {
                        val bytes = connection.transport.exchange(operation)
                        if (operation == SonySessionOperation.EXTENDED_INFO && bytes.size >= 2) {
                            version = (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
                        }
                        record(operation.name, RemoteTraceStatus.OK, bytes.size, 0x2001)
                        if (operation != SonySessionOperation.CLOSE_SESSION) stage = "${operation.name}_VALIDATION"
                        return bytes
                    } catch (error: Throwable) {
                        record(operation.name, RemoteTraceStatus.FAIL, response = (error as? PtpResponseException)?.code)
                        if (operation == SonySessionOperation.CLOSE_SESSION) cleanupErrorCount++
                        throw error
                    }
                }
                override suspend fun nextEvent(timeoutMillis: Int): ByteArray? {
                    stage = "EVENT_POLL"
                    record(stage, RemoteTraceStatus.START)
                    return try {
                        connection.transport.nextEvent(timeoutMillis).also {
                            record(stage, if (it == null) RemoteTraceStatus.NO_EVENT else RemoteTraceStatus.OK, it?.size)
                        }
                    } catch (error: Throwable) {
                        record(stage, RemoteTraceStatus.FAIL)
                        throw error
                    }
                }
                override fun close() {
                    record("RESOURCE_CLOSE", RemoteTraceStatus.START)
                    try {
                        connection.transport.close()
                        resourceClose = RemoteResourceClose.CONFIRMED
                        record("RESOURCE_CLOSE", RemoteTraceStatus.OK)
                    } catch (error: Throwable) {
                        resourceClose = RemoteResourceClose.FAILED
                        cleanupErrorCount++
                        record("RESOURCE_CLOSE", RemoteTraceStatus.FAIL)
                        throw error
                    }
                }
            }
        }, onSession = { session = it }) { connected ->
            propertyCount = connected.snapshot?.properties?.size
            conditions = connected.snapshot?.properties?.let(RemoteDiagnosticReport::conditions).orEmpty()
            stage = "OBSERVING"
            record(stage, RemoteTraceStatus.OK)
            if (eventAvailable == true) {
                repeat(3) { connected.pollEvent(500)?.let { events += it.code } }
                outcome = RemoteDiagnosticOutcome.COMPLETE
            } else {
                record("EVENT_POLL", RemoteTraceStatus.SKIPPED)
                outcome = RemoteDiagnosticOutcome.COMPLETE_WITHOUT_EVENTS
            }
            stage = "CLOSE_SESSION"
        }
    } catch (error: Throwable) {
        if (error is CancellationException && (error !is TimeoutCancellationException || !currentCoroutineContext().isActive)) throw error
        failureStage = stage
        failure = when (error) {
            is PtpResponseException -> RemoteDiagnosticFailure.PTP_RESPONSE
            is UnsupportedSonyProtocolVersionException -> RemoteDiagnosticFailure.UNSUPPORTED_PROTOCOL
            is UnsupportedSonyPropertyTypeException -> {
                unsupportedCode = error.propertyCode
                unsupportedType = error.dataType
                RemoteDiagnosticFailure.UNSUPPORTED_PROPERTY_TYPE
            }
            is TimeoutCancellationException -> RemoteDiagnosticFailure.TIMEOUT
            is RemoteTransportTimeoutException -> RemoteDiagnosticFailure.TIMEOUT
            is IOException -> RemoteDiagnosticFailure.IO
            is IllegalArgumentException, is IllegalStateException -> RemoteDiagnosticFailure.INVALID_DATA_OR_CAPABILITY
            else -> RemoteDiagnosticFailure.OTHER
        }
        record(stage, RemoteTraceStatus.FAIL, response = (error as? PtpResponseException)?.code)
        outcome = RemoteDiagnosticOutcome.FAILED
    }
    return RemoteDiagnosticReport(outcome, failureStage, failure, elapsed(), selection, version, propertyCount,
        unsupportedCode, unsupportedType, events.toList(), eventAvailable, session?.state, session?.ptpCloseStatus,
        resourceClose, cleanupErrorCount, trace.toList(), conditions)
}
