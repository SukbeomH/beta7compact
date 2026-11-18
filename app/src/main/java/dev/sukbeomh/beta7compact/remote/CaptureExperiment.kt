package dev.sukbeomh.beta7compact.remote

import android.content.Context
import dev.sukbeomh.beta7compact.usb.PtpResponseException
import dev.sukbeomh.beta7compact.usb.UsbAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.net.SocketFactory

enum class CaptureExperimentAction { AUTOFOCUS, SINGLE_CAPTURE }
enum class CaptureExperimentOutcome { AF_LOCK_OBSERVED, CAPTURE_SUBMITTED_UNCONFIRMED, FAILED }
enum class CaptureExperimentFailure {
    ELIGIBILITY, FOCUS_NOT_ACQUIRED, EVENT_ENDPOINT_UNAVAILABLE, PTP_RESPONSE,
    UNSUPPORTED_PROTOCOL, UNSUPPORTED_PROPERTY_TYPE, TIMEOUT, IO, INVALID_DATA_OR_CAPABILITY, OTHER,
}

/** Contains only fixed labels and numeric diagnostic metadata, never photographs or raw device values. */
data class CaptureExperimentReport(
    val action: CaptureExperimentAction,
    val outcome: CaptureExperimentOutcome,
    val failure: CaptureExperimentFailure?,
    val failureStage: String?,
    val eligibilityReason: String?,
    val eligibilityPropertyCode: Int?,
    val unsupportedPropertyCode: Int?,
    val unsupportedPropertyType: Int?,
    val selection: RemoteUsbSelection?,
    val advertisedProtocolVersion: Int?,
    val capturePressAttempted: Boolean,
    val halfReleaseStatus: SonyButtonReleaseStatus?,
    val fullReleaseStatus: SonyButtonReleaseStatus?,
    val ptpCloseStatus: SonyPtpCloseStatus?,
    val resourceClose: RemoteResourceClose,
    val cleanupErrorCount: Int,
    val elapsedMillis: Long,
    val eventCodes: List<Int>,
    val trace: List<RemoteDiagnosticTrace>,
    val priorityStatus: SonyPriorityStatus? = null,
    val priorityRestore: SonyPriorityRestore? = null,
) {
    fun safeText(): String = buildString {
        appendLine("AF·단발 촬영 실험 · 형식 2")
        appendLine("선택=$action 결과=$outcome elapsedMs=$elapsedMillis")
        appendLine("전체셔터시도=$capturePressAttempted 사진저장=미확인")
        failure?.let { appendLine("실패단계=$failureStage 분류=$it") }
        eligibilityReason?.let { appendLine("실행조건=$it 속성=${eligibilityPropertyCode?.let(::hex) ?: "해당없음"}") }
        unsupportedPropertyType?.let { appendLine("미지원속성 code=${unsupportedPropertyCode?.let(::hex)} type=${hex(it)}") }
        selection?.let {
            appendLine("USB vid=${hex(it.vid)} pid=${hex(it.pid)} config=${it.config} interface=${it.interfaceId} alternate=${it.alternate}")
            appendLine("endpoint bulkIn=${hex(it.bulkIn)} bulkOut=${hex(it.bulkOut)} event=${it.eventEndpoint?.let(::hex) ?: "없음"}")
        }
        appendLine("광고프로토콜=${advertisedProtocolVersion?.let(::hex) ?: "미확인"}")
        appendLine("우선순위=${priorityStatus ?: "미확인"} 되돌림=${priorityRestore ?: "미확인"}")
        appendLine("반셔터해제=${halfReleaseStatus ?: "미확인"} 전체셔터해제=${fullReleaseStatus ?: "미확인"}")
        appendLine("PTP종료=${ptpCloseStatus ?: "미확인"} 자원종료=$resourceClose 정리오류수=$cleanupErrorCount")
        appendLine("관찰이벤트=${eventCodes.size} codes=${eventCodes.joinToString { hex(it) }}")
        trace.forEach { row ->
            append("${row.elapsedMillis}ms ${row.stage} ${row.status}")
            row.payloadBytes?.let { append(" bytes=$it") }
            row.responseCode?.let { append(" response=${hex(it)}") }
            appendLine()
        }
        appendLine("누름·해제 응답은 실제 사진 저장이나 물리 상태의 보증이 아님. 모호한 촬영은 자동 재시도하지 않음.")
        append("시리얼·기기 문자열·경로·사진·속성값·객체핸들·원시 응답·예외 메시지는 포함하지 않음.")
    }
    private fun hex(value: Int) = "0x%04X".format(value)
}

internal data class CaptureExperimentConnection(
    val transport: SonyCaptureTransport,
    val selection: RemoteUsbSelection? = null,
    val eventPollingAvailable: Boolean = false,
)
private class MissingCaptureEventEndpoint : IOException()

suspend fun runCaptureExperiment(context: Context, action: CaptureExperimentAction,
                                 claimPriority: Boolean = false): CaptureExperimentReport =
    runCaptureExperiment(action, sessionFactory = { SonyCaptureSession(it, claimPriority = claimPriority) }, open = { onStage ->
        val wire = AndroidSonyRemoteWire.open(context, onStage)
        try {
            CaptureExperimentConnection(SonySessionWireTransport(wire, allowCaptureControls = true, allowPriorityWrite = claimPriority),
                wire.selection, wire.selection.eventEndpoint != null)
        } catch (failure: Throwable) {
            try { wire.close() } catch (closing: Throwable) { if (failure !== closing) failure.addSuppressed(closing) }
            throw failure
        }
    })

internal suspend fun runWifiCaptureExperiment(target: WifiDiagnosticTarget.Address, guid: ByteArray,
                                              action: CaptureExperimentAction, claimPriority: Boolean): CaptureExperimentReport =
    withContext(Dispatchers.IO) {
        val connector = ptpIpConnector(SocketFactory.getDefault(), InetAddress.getByAddress(target.address))
        runCaptureExperiment(action, wifi = true, sessionFactory = { SonyCaptureSession(it, claimPriority = claimPriority, wifi = true) }, open = { onStage ->
            val transport = PtpIpSessionTransport.open(connector, guid, "beta7compact", allowCaptureControls = true,
                allowPriorityWrite = claimPriority, stage = onStage)
            CaptureExperimentConnection(transport, eventPollingAvailable = true)
        })
    }

/** Shares camera ownership and cleanup across capture transports. */
internal suspend fun runCaptureExperiment(
    action: CaptureExperimentAction,
    open: suspend ((String) -> Unit) -> CaptureExperimentConnection,
    sessionFactory: (SonyCaptureTransport) -> SonyCaptureSession = { SonyCaptureSession(it) },
    wifi: Boolean = false,
): CaptureExperimentReport {
    val started = System.nanoTime()
    fun elapsed() = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
    var stage = if (wifi) "WAITING_FOR_WIFI" else "WAITING_FOR_USB"
    var firstFailureStage: String? = null
    var resourceClose = RemoteResourceClose.NOT_CREATED
    var cleanupErrorCount = 0
    var session: SonyCaptureSession? = null
    var selection: RemoteUsbSelection? = null
    var version: Int? = null
    var outcome = CaptureExperimentOutcome.FAILED
    var failure: CaptureExperimentFailure? = null
    var eligibilityReason: String? = null
    var eligibilityCode: Int? = null
    var unsupportedCode: Int? = null
    var unsupportedType: Int? = null
    val trace = mutableListOf<RemoteDiagnosticTrace>()
    val events = mutableListOf<Int>()
    val cleanupFailures = mutableListOf<Pair<Throwable, String>>()
    fun record(name: String, status: RemoteTraceStatus, bytes: Int? = null, response: Int? = null) {
        trace += RemoteDiagnosticTrace(name, status, elapsed(), bytes, response)
    }
    fun failed(name: String) { if (firstFailureStage == null) firstFailureStage = name }
    try {
        UsbAccess.useCapture(openTransport = {
            stage = if (wifi) "COMMAND_CONNECT" else "DISCOVERY"
            resourceClose = RemoteResourceClose.UNCONFIRMED
            val connection = open { incoming ->
                val openStages = if (wifi) setOf("COMMAND_CONNECT", "INIT_COMMAND", "EVENT_CONNECT", "INIT_EVENT")
                    else setOf("DISCOVERY", "PERMISSION", "CONFIGURATION", "INTERFACE", "CLAIM")
                if (incoming in openStages) stage = incoming
                else if (wifi) return@open
                else stage = "USB_OPEN"
                record(stage, RemoteTraceStatus.START)
            }
            selection = connection.selection
            val observing = object : SonyCaptureTransport {
                override suspend fun exchange(operation: SonySessionOperation): ByteArray {
                    val cleanup = operation == SonySessionOperation.CLOSE_SESSION
                    if (!cleanup) stage = operation.name
                    record(operation.name, RemoteTraceStatus.START)
                    return try {
                        connection.transport.exchange(operation).also { bytes ->
                            if (operation == SonySessionOperation.EXTENDED_INFO && bytes.size >= 2) {
                                version = (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
                            }
                            record(operation.name, RemoteTraceStatus.OK, bytes.size, 0x2001)
                            if (!cleanup) stage = "${operation.name}_VALIDATION"
                        }
                    } catch (error: Throwable) {
                        if (cleanup) { cleanupErrorCount++; cleanupFailures += error to operation.name } else failed(operation.name)
                        record(operation.name, RemoteTraceStatus.FAIL, response = (error as? PtpResponseException)?.code)
                        throw error
                    }
                }
                override suspend fun control(command: SonyCaptureCommand) {
                    val cleanup = command == SonyCaptureCommand.FULL_RELEASE || command == SonyCaptureCommand.HALF_RELEASE
                    if (!cleanup) stage = command.name
                    record(command.name, RemoteTraceStatus.START)
                    try {
                        connection.transport.control(command)
                        record(command.name, RemoteTraceStatus.OK, response = 0x2001)
                    } catch (error: Throwable) {
                        if (cleanup) { cleanupErrorCount++; cleanupFailures += error to command.name } else failed(command.name)
                        record(command.name, RemoteTraceStatus.FAIL, response = (error as? PtpResponseException)?.code)
                        throw error
                    }
                }
                override suspend fun set(command: SonySettingCommand) {
                    val cleanup = command == SonySettingCommand.PRIORITY_CAMERA
                    if (!cleanup) stage = command.name
                    record(command.name, RemoteTraceStatus.START)
                    try {
                        connection.transport.set(command)
                        record(command.name, RemoteTraceStatus.OK, response = 0x2001)
                    } catch (error: Throwable) {
                        if (cleanup) cleanupErrorCount++ else failed(command.name)
                        record(command.name, RemoteTraceStatus.FAIL, response = (error as? PtpResponseException)?.code)
                        throw error
                    }
                }
                override suspend fun nextEvent(timeoutMillis: Int): ByteArray? {
                    stage = "EVENT_POLL"
                    record(stage, RemoteTraceStatus.START)
                    return try {
                        connection.transport.nextEvent(timeoutMillis).also { bytes ->
                            record(stage, if (bytes == null) RemoteTraceStatus.NO_EVENT else RemoteTraceStatus.OK, bytes?.size)
                            bytes?.let { events += SonyEventParser.parse(it).code }
                        }
                    } catch (error: Throwable) {
                        failed(stage); record(stage, RemoteTraceStatus.FAIL); throw error
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
                        cleanupFailures += error to "RESOURCE_CLOSE"
                        record("RESOURCE_CLOSE", RemoteTraceStatus.FAIL)
                        throw error
                    }
                }
            }
            if (!connection.eventPollingAvailable) {
                stage = "EVENT_ENDPOINT"
                val missing = MissingCaptureEventEndpoint()
                try { observing.close() } catch (closing: Throwable) { missing.addSuppressed(closing) }
                throw missing
            }
            observing
        }, onSession = { session = it }, sessionFactory = sessionFactory) { connected ->
            stage = action.name
            record(stage, RemoteTraceStatus.START)
            outcome = when (action) {
                CaptureExperimentAction.AUTOFOCUS -> {
                    connected.autofocus()
                    CaptureExperimentOutcome.AF_LOCK_OBSERVED
                }
                CaptureExperimentAction.SINGLE_CAPTURE -> {
                    connected.captureSingle()
                    CaptureExperimentOutcome.CAPTURE_SUBMITTED_UNCONFIRMED
                }
            }
            record(action.name, RemoteTraceStatus.OK)
            stage = "CLOSE_SESSION"
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        failure = when (error) {
            is SonyCaptureEligibilityException -> {
                eligibilityReason = error.reason.name
                eligibilityCode = error.propertyCode
                CaptureExperimentFailure.ELIGIBILITY
            }
            is SonyFocusNotAcquiredException -> CaptureExperimentFailure.FOCUS_NOT_ACQUIRED
            is MissingCaptureEventEndpoint -> CaptureExperimentFailure.EVENT_ENDPOINT_UNAVAILABLE
            is PtpResponseException -> CaptureExperimentFailure.PTP_RESPONSE
            is UnsupportedSonyProtocolVersionException -> CaptureExperimentFailure.UNSUPPORTED_PROTOCOL
            is UnsupportedSonyPropertyTypeException -> {
                unsupportedCode = error.propertyCode; unsupportedType = error.dataType
                CaptureExperimentFailure.UNSUPPORTED_PROPERTY_TYPE
            }
            is RemoteTransportTimeoutException, is SocketTimeoutException -> CaptureExperimentFailure.TIMEOUT
            is IOException -> CaptureExperimentFailure.IO
            is IllegalArgumentException, is IllegalStateException -> CaptureExperimentFailure.INVALID_DATA_OR_CAPABILITY
            else -> CaptureExperimentFailure.OTHER
        }
        val causes = generateSequence(error) { it.cause }.take(8).toList()
        val cleanupStage = cleanupFailures.firstOrNull { (problem, _) -> causes.any { it === problem } }?.second
        failed(cleanupStage ?: stage)
        record(firstFailureStage ?: stage, RemoteTraceStatus.FAIL, response = (error as? PtpResponseException)?.code)
        outcome = CaptureExperimentOutcome.FAILED
    }
    return CaptureExperimentReport(action, outcome, failure, firstFailureStage, eligibilityReason, eligibilityCode,
        unsupportedCode, unsupportedType, selection, version, session?.capturePressAttempted ?: false,
        session?.halfReleaseStatus, session?.fullReleaseStatus, session?.ptpCloseStatus, resourceClose,
        cleanupErrorCount, elapsed(), events.toList(), trace.toList(), session?.priorityStatus, session?.priorityRestore)
}
