package dev.sukbeomh.beta7compact.remote

import dev.sukbeomh.beta7compact.usb.PtpResponseException
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WifiObservationReport(
    val failureStage: String?,
    val failure: WifiDiagnosticFailure?,
    val ptpResponse: Int?,
    val protocol: Int?,
    val propertyCount: Int?,
    val textPropertyCount: Int?,
    val secondaryEnumerationCount: Int?,
    val eventCodes: List<Int>,
    val ptpCloseStatus: SonyPtpCloseStatus,
) {
    fun safeText(): String = buildString {
        appendLine("Wi-Fi 관찰 세션 · 형식 1")
        appendLine("결과=${if (failure == null) "COMPLETE" else "FAILED"}")
        failureStage?.let { appendLine("실패단계=$it 분류=$failure 응답=${ptpResponse?.let(::hex) ?: "미확인"}") }
        appendLine("광고프로토콜=${protocol?.let(::hex) ?: "미확인"} 속성수=${propertyCount ?: "미확인"}")
        appendLine("문자열속성=${textPropertyCount ?: "미확인"} 두번째열거목록=${secondaryEnumerationCount ?: "미확인"}")
        appendLine("이벤트수=${eventCodes.size} 코드=${eventCodes.joinToString(transform = ::hex)}")
        append("PTP종료=$ptpCloseStatus")
    }

    private fun hex(value: Int) = "0x%04X".format(value)
}

internal suspend fun runWifiObservation(address: WifiDiagnosticTarget.Address, guid: ByteArray): WifiObservationReport =
    withContext(Dispatchers.IO) {
        val connector = ptpIpConnector(SocketFactory.getDefault(), InetAddress.getByAddress(address.address))
        collectWifiObservation(connector, guid)
    }

internal suspend fun collectWifiObservation(connector: PtpIpConnector, guid: ByteArray): WifiObservationReport {
    var stage = "COMMAND_CONNECT"
    var session: SonyRemoteSession? = null
    var snapshot: SonyRemoteSnapshot? = null
    var failure: WifiDiagnosticFailure? = null
    var response: Int? = null
    val events = mutableListOf<Int>()
    try {
        val transport = PtpIpSessionTransport.open(connector, guid, "beta7compact") { stage = it }
        val observing = SonyRemoteSession(transport)
        session = observing
        var primary: Throwable? = null
        try {
            snapshot = observing.connect()
            stage = "EVENT_POLL"
            val deadline = System.nanoTime() + 2_000_000_000L
            while (events.size < 20) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining < 1) break
                val event = observing.pollEvent(remaining.toInt()) ?: break
                events += event.code
            }
            stage = "CLOSE_SESSION"
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            try {
                observing.close()
            } catch (closeFailure: Throwable) {
                if (primary == null) throw closeFailure
                if (primary !== closeFailure) primary.addSuppressed(closeFailure)
            }
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        response = (error as? PtpResponseException)?.code
        failure = when (error) {
            is PtpResponseException -> WifiDiagnosticFailure.PTP_RESPONSE
            is SocketTimeoutException -> WifiDiagnosticFailure.TIMEOUT
            is ConnectException -> WifiDiagnosticFailure.CONNECTION_REFUSED
            is PtpIpSessionRejectedException -> WifiDiagnosticFailure.INIT_REJECTED
            is PtpIpFormatException, is IllegalArgumentException, is IllegalStateException -> WifiDiagnosticFailure.INVALID_DATA
            is IOException -> WifiDiagnosticFailure.IO
            else -> WifiDiagnosticFailure.OTHER
        }
    }
    val properties = snapshot?.properties
    return WifiObservationReport(stage.takeIf { failure != null }, failure, response, snapshot?.extendedInfo?.advertisedVersion,
        properties?.size, properties?.count { it.textValues != null }, properties?.count {
            when (val form = it.form) {
                is SonyPropertyForm.Enumeration -> form.secondaryValues != null
                is SonyPropertyForm.TextEnumeration -> form.secondaryValues != null
                else -> false
            }
        }, events.toList(), session?.ptpCloseStatus ?: SonyPtpCloseStatus.NOT_OPENED)
}
