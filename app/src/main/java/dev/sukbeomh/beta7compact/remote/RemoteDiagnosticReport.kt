package dev.sukbeomh.beta7compact.remote

enum class RemoteDiagnosticOutcome { COMPLETE, COMPLETE_WITHOUT_EVENTS, FAILED }
enum class RemoteDiagnosticFailure { PTP_RESPONSE, UNSUPPORTED_PROTOCOL, UNSUPPORTED_PROPERTY_TYPE, INVALID_DATA_OR_CAPABILITY, IO, TIMEOUT, OTHER }
enum class RemoteResourceClose { NOT_CREATED, UNCONFIRMED, CONFIRMED, FAILED }
enum class RemoteTraceStatus { START, OK, FAIL, NO_EVENT, SKIPPED }

data class RemoteDiagnosticTrace(
    val stage: String,
    val status: RemoteTraceStatus,
    val elapsedMillis: Long,
    val payloadBytes: Int? = null,
    val responseCode: Int? = null,
)

/**
 * No device strings, raw payload, paths or exception messages are retained. Property values are kept only for
 * [CONDITION_CODES]: the capture conditions and state the next stages depend on, none of which identifies a device.
 */
data class RemoteDiagnosticReport(
    val outcome: RemoteDiagnosticOutcome,
    val failureStage: String?,
    val failure: RemoteDiagnosticFailure?,
    val elapsedMillis: Long,
    val selection: RemoteUsbSelection?,
    val advertisedProtocolVersion: Int?,
    val propertyCount: Int?,
    val unsupportedPropertyCode: Int?,
    val unsupportedPropertyType: Int?,
    val eventCodes: List<Int>,
    val eventPollingAvailable: Boolean?,
    val sessionState: SonyRemoteSessionState?,
    val ptpCloseStatus: SonyPtpCloseStatus?,
    val resourceClose: RemoteResourceClose,
    val cleanupErrorCount: Int,
    val trace: List<RemoteDiagnosticTrace>,
    val conditions: List<String> = emptyList(),
) {
    fun safeText(): String = buildString {
        appendLine("PC Remote 실험 진단 · 형식 2")
        appendLine("SDIO 연결은 카메라 모드를 바꿀 수 있음. 촬영·설정 쓰기 미실행.")
        appendLine("결과=$outcome elapsedMs=$elapsedMillis")
        failureStage?.let { appendLine("실패단계=$it 분류=$failure") }
        selection?.let {
            appendLine("USB vid=${hex(it.vid)} pid=${hex(it.pid)} config=${it.config} interface=${it.interfaceId} alternate=${it.alternate}")
            appendLine("endpoint bulkIn=${hex(it.bulkIn)} bulkOut=${hex(it.bulkOut)} event=${it.eventEndpoint?.let(::hex) ?: "없음"}")
        }
        appendLine("광고프로토콜=${advertisedProtocolVersion?.let(::hex) ?: "미확인"} 속성수=${propertyCount ?: "미확인"}")
        unsupportedPropertyType?.let { appendLine("미지원속성 code=${unsupportedPropertyCode?.let(::hex)} type=${hex(it)}") }
        appendLine("이벤트관찰=${eventPollingAvailable ?: "미확인"} count=${eventCodes.size} codes=${eventCodes.joinToString { hex(it) }}")
        conditions.forEach { appendLine("조건속성 $it") }
        appendLine("세션=$sessionState PTP종료=${ptpCloseStatus ?: "미확인"} 자원종료=$resourceClose 정리오류수=$cleanupErrorCount")
        trace.forEach { row ->
            append("${row.elapsedMillis}ms ${row.stage} ${row.status}")
            row.payloadBytes?.let { append(" bytes=$it") }
            row.responseCode?.let { append(" response=${hex(it)}") }
            appendLine()
        }
        appendLine("종료 응답은 카메라 모드 원복의 보증이 아님. 실제 기능 호환성은 별도 확인 필요.")
        append("시리얼·기기 문자열·경로·사진·원시 응답·예외 메시지는 포함하지 않음. 속성값은 위 조건속성 줄의 촬영 조건·상태만 포함.")
    }

    companion object {
        /** Drive, focus mode, exposure program, shutter speed, focus state, pending objects, live view, save target, priority, slot count, buttons. */
        val CONDITION_CODES = listOf(0x5013, 0x500a, 0x500e, 0xd20d, 0xd229, 0xd213, 0xd215, 0xd221, 0xd222, 0xd25a, 0xd249, 0xd2c1, 0xd2c2)
        fun conditions(properties: List<SonyPropertyDescriptor>): List<String> = CONDITION_CODES.map { code ->
            val found = properties.filter { it.code == code }
            val property = found.singleOrNull()
            when {
                found.isEmpty() -> "0x%04X 없음".format(code)
                property == null -> "0x%04X 중복 ${found.size}".format(code)
                property.currentValue == null -> "0x%04X type=0x%04X 값 제외".format(code, property.dataType)
                else -> "0x%04X type=0x%04X getset=0x%02X enabled=%d value=0x%s".format(code, property.dataType,
                    property.rawGetSet, property.rawEnabled, property.currentValue.toString(16).uppercase().take(16))
            }
        }
    }

    private fun hex(value: Int) = "0x%04X".format(value)
}
