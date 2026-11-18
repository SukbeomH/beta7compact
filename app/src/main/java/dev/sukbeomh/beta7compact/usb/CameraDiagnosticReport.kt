package dev.sukbeomh.beta7compact.usb

enum class DiagnosticStage(val label: String) {
    DISCOVERY("장치 검색"), PERMISSION("USB 권한"), OPEN("장치 열기"),
    CONFIGURATION("현재 USB 구성 조회"), INTERFACE("현재 인터페이스 조회"),
    CLAIM("인터페이스 사용"), DEVICE_INFO("PTP 장치 정보 수신"), PARSE("PTP 정보 해석"), COMPLETE("완료")
}
enum class DiagnosticOutcome(val label: String) {
    SUCCESS("조회 성공"), NO_DEVICE("Sony USB 장치 없음"), MULTIPLE_DEVICES("Sony 장치가 여러 대입니다. 한 대만 연결하세요"),
    NO_PERMISSION("카메라 탭에서 USB 권한을 허용하세요"),
    UNSUPPORTED_INTERFACE("현재 앱이 지원하는 still-image bulk 인터페이스를 찾지 못했습니다"),
    PTP_REJECTED("카메라가 PTP 요청을 거부했습니다"), INVALID_DATA("PTP 응답 형식이 올바르지 않습니다"),
    TRANSPORT_FAILURE("USB 통신에 실패했습니다. 케이블·연결 모드를 확인하세요")
}
data class DiagnosticEndpoint(val address: Int, val direction: Int, val type: Int, val maxPacketSize: Int)
data class DiagnosticInterface(val configuration: Int, val id: Int, val alternate: Int,
    val usbClass: Int, val subclass: Int, val protocol: Int, val endpoints: List<DiagnosticEndpoint>) {
    val supportsPtpBulk: Boolean get() = usbClass == 6 &&
        endpoints.any { it.type == 2 && it.direction == 128 } && endpoints.any { it.type == 2 && it.direction == 0 }
}
data class DiagnosticUsbDevice(val vendorId: Int, val productId: Int, val permission: Boolean,
    val interfaces: List<DiagnosticInterface>)
data class DiagnosticUsbSelection(val configuration: Int, val interfaceId: Int, val alternate: Int)
data class CameraDiagnosticReport(val devices: List<DiagnosticUsbDevice>, val stage: DiagnosticStage,
    val outcome: DiagnosticOutcome, val elapsedMs: Long, val ptpResponseCode: Int? = null, val info: DeviceInfo? = null,
    val activeSelection: DiagnosticUsbSelection? = null) {
    /** Only numeric USB fields and capability codes leave the app; never device-supplied strings. */
    fun safeText(): String = buildString {
        appendLine("beta7compact USB 읽기 전용 진단 v1")
        appendLine("단계: ${stage.label} · 결과: ${outcome.label} · ${elapsedMs}ms")
        appendLine("Sony 장치 수: ${devices.size}")
        appendLine("아래 구성·인터페이스 목록은 장치가 광고한 전체 목록입니다.")
        devices.forEachIndexed { index, device ->
            appendLine("장치 ${index + 1}: VID=${hex(device.vendorId)} PID=${hex(device.productId)} 권한=${device.permission}")
            device.interfaces.forEach { face ->
                appendLine("  config=${face.configuration} interface=${face.id} alt=${face.alternate} class=${face.usbClass} subclass=${face.subclass} protocol=${face.protocol}")
                face.endpoints.forEach { endpoint ->
                    appendLine("    endpoint=${hex(endpoint.address)} ${if (endpoint.direction == 128) "IN" else "OUT"} type=${endpoint.type} maxPacket=${endpoint.maxPacketSize}")
                }
            }
        }
        activeSelection?.let {
            appendLine("현재 선택: config=${it.configuration} interface=${it.interfaceId} alt=${it.alternate}")
        } ?: appendLine("현재 인터페이스 선택: 확인하지 못함")
        ptpResponseCode?.let { appendLine("PTP 응답: ${hex(it)}") }
        info?.let {
            appendLine("지원 명령: ${it.operations.distinct().joinToString { code -> hex(code) }}")
            appendLine("지원 속성: ${it.properties.distinct().joinToString { code -> hex(code) }}")
        }
        appendLine("연결 모드는 자동 판정하지 않습니다. 기기에서 MTP/PC Remote 설정을 확인하세요.")
        append("지원 목록은 실제 제어 성공의 증거가 아닙니다. 설정 변경·촬영·펌웨어 쓰기는 수행하지 않았습니다.")
    }
}
