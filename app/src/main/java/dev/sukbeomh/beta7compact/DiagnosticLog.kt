package dev.sukbeomh.beta7compact

/** Only allowlisted events enter the bounded, process-local share buffer. No payload API. */
enum class DiagnosticEvent(val text: String) {
    USB_MTP_OPEN_FAILED("USB MTP 세션 실패"),
    USB_MTP_LIST_FAILED("USB MTP 목록 실패"),
    USB_MTP_THUMB_FAILED("USB MTP 미리보기 실패"),
    USB_MTP_DOWNLOAD_FAILED("USB MTP 수신 실패"),
    USB_RAW_FALLBACK("USB raw PTP 자동 대체"),
    USB_MTP_OK("USB MTP 읽기 성공"),
    USB_RAW_OK("USB raw PTP 읽기 성공"),
    IMPORT_STARTED("가져오기 시작"), IMPORT_DONE("가져오기 완료"), IMPORT_FAILED("가져오기 실패"),
    BLE_FILTER_START("BLE 필터 검색 시작"), BLE_UNFILTERED_START("BLE 무필터 검색 시작"),
    BLE_FOUND("BLE 카메라 발견"), BLE_EMPTY("BLE 카메라 광고 없음"),
    BLE_FAILED("BLE 검색 실패"), BLE_SELECTED("BLE 카메라 선택"),
    BLE_BOND_STARTED("BLE 페어링 시작"), BLE_BOND_FAILED("BLE 페어링 실패")
}

object DiagnosticLog {
    private val events = ArrayDeque<DiagnosticEvent>()
    @Synchronized fun record(event: DiagnosticEvent) {
        // Repeated thumbnail reads should not crowd out earlier failure events.
        if (events.lastOrNull() == event) return
        if (events.size == 200) events.removeFirst()
        events.addLast(event)
    }
    @Synchronized fun shareText(): String =
        (listOf("beta7compact 0.1.2 진단 로그") + events.map { it.text }).joinToString("\n")
}
