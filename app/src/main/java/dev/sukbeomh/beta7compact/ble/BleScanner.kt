package dev.sukbeomh.beta7compact.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.location.LocationManager
import android.os.Build
import dev.sukbeomh.beta7compact.DiagnosticEvent
import dev.sukbeomh.beta7compact.DiagnosticLog
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal const val SONY_COMPANY = 0x012D
internal const val SCAN_MS = 15_000L
internal fun cameraType(data: ByteArray) = data.size >= 2 && data[0] == 3.toByte() && data[1] == 0.toByte()
internal fun cameraName(name: String?) = listOf("ILCE-", "DSC-", "ZV-", "ILME-").any { name?.startsWith(it) == true }

data class ScanReport(
    val devices: List<BluetoothDevice> = emptyList(), val filteredSony: Int = 0,
    val unfilteredSony: Int = 0, val typeMismatch: Int = 0,
    val scanning: Boolean = false, val unfiltered: Boolean = false, val failed: Boolean = false,
    /** Every advertisement of the unfiltered pass, Sony or not: tells "the phone hears nothing" from "no camera". */
    val total: Int = 0, val unfilteredRan: Boolean = false,
    /** Distinct decoded Sony camera advertisements. No address or name. */
    val cameras: List<String> = emptyList(),
    val locationOff: Boolean = false, val waitMs: Long = 0
) {
    val diagnostic: String get() = "필터 스캔 Sony 광고 $filteredSony · 무필터 스캔 Sony 광고 $unfilteredSony · 카메라 유형 불일치 $typeMismatch" +
        " · 무필터 전체 광고 ${if (unfilteredRan) "$total" else "실행 안 함"}"
    /** What the counts mean, for a user standing next to the camera. */
    val reading: String get() = when {
        waitMs > 0 -> "검색을 너무 자주 시작했습니다. ${(waitMs + 999) / 1000}초 뒤에 다시 검색하세요. Android는 30초에 5번을 넘는 검색을 결과 없이 무시합니다."
        failed -> "BLE 검색 실패. Bluetooth를 껐다 켠 뒤 다시 검색하세요."
        cameras.isNotEmpty() -> "카메라 광고를 받았습니다."
        typeMismatch > 0 -> "Sony 기기 광고는 있으나 카메라 유형(03 00)이 아닙니다."
        unfilteredRan && total == 0 -> "주변 광고를 하나도 받지 못했습니다. 카메라가 아니라 휴대폰 쪽 검색 문제입니다." +
            (if (locationOff) " 휴대폰 위치 기능이 꺼져 있습니다. 켜고 다시 검색하세요." else " Bluetooth를 껐다 켜고 다시 검색하세요.")
        unfilteredRan -> "휴대폰 검색은 동작합니다(다른 기기 광고 ${total}건). 카메라가 광고를 내보내지 않고 있습니다."
        else -> ""
    }
}

@SuppressLint("MissingPermission") // Callers request BLE and (API 29/30) location permissions first.
object BleScanner {
    const val EMPTY_GUIDANCE = "카메라 광고가 없습니다. 카메라 MENU→네트워크→Bluetooth 설정→페어링 화면을 열고 다시 검색하세요. 이미 다른 폰/IEM과 페어링되어 있다면 카메라 쪽 페어링을 해제하세요."
    fun bonded(context: Context, saved: String?): List<BluetoothDevice> =
        context.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
            .filter { it.address == saved || cameraName(it.name) }

    private val limiter = dev.sukbeomh.beta7compact.core.ScanLimiter()
    private fun now() = System.nanoTime() / 1_000_000

    suspend fun scan(context: Context, saved: String?, update: (ScanReport) -> Unit): ScanReport {
        // One search may start two scans (filtered, then unfiltered).
        val wait = limiter.waitFor(now(), 2)
        if (wait > 0) return ScanReport(bonded(context, saved), waitMs = wait).also(update)
        return scanWithWait(context, saved, update, { limiter.started(now()) }) { delay(it) }
    }

    // Test seam replaces only the clock; the real filter, callbacks and fallback execute in contracts.
    internal suspend fun scanWithWait(context: Context, saved: String?, update: (ScanReport) -> Unit,
                                      started: () -> Unit = {}, wait: suspend (Long) -> Unit): ScanReport {
        val devices = ConcurrentHashMap<String, BluetoothDevice>()
        bonded(context, saved).forEach { devices[it.address] = it }
        var report = ScanReport(devices.values.toList(), scanning = true)
        update(report) // Bonded cameras are selectable before the first scan completes.
        // Required up to Android 11; later versions only report it, since some phones still return nothing with it off.
        val locationOff = !context.getSystemService(LocationManager::class.java).isLocationEnabled
        if (Build.VERSION.SDK_INT <= 30) check(!locationOff) { "휴대폰 위치 기능을 켜세요" }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        check(adapter?.isEnabled == true) { "휴대폰 Bluetooth를 켜세요" }
        val scanner = checkNotNull(adapter.bluetoothLeScanner) { "BLE 검색을 사용할 수 없습니다" }
        val filteredSony = AtomicInteger()
        val unfilteredSony = AtomicInteger()
        val mismatch = AtomicInteger()
        val cameraAds = AtomicInteger()
        val total = AtomicInteger()
        val cameras = ConcurrentHashMap.newKeySet<String>()
        var unfilteredRan = false
        fun snapshot(unfiltered: Boolean, failed: Boolean, scanning: Boolean = false) = ScanReport(devices.values.toList(),
            filteredSony.get(), unfilteredSony.get(), mismatch.get(), scanning, unfiltered, failed,
            total.get(), unfilteredRan, cameras.sorted(), locationOff)
        suspend fun pass(unfiltered: Boolean): Boolean {
            val failed = AtomicBoolean()
            val accepting = AtomicBoolean(true)
            val callbackLock = Any()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
                override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::record) }
                private fun record(result: ScanResult) = synchronized(callbackLock) {
                    if (!accepting.get()) return
                    if (unfiltered) total.incrementAndGet()
                    val data = result.scanRecord?.getManufacturerSpecificData(SONY_COMPANY) ?: return
                    (if (unfiltered) unfilteredSony else filteredSony).incrementAndGet()
                    if (!cameraType(data)) { mismatch.incrementAndGet(); return }
                    cameraAds.incrementAndGet()
                    dev.sukbeomh.beta7compact.core.SonyAdvertisement.parse(data)?.let { if (cameras.size < 8) cameras.add(it.summary) }
                    devices[result.device.address] = result.device
                }
                override fun onScanFailed(errorCode: Int) = synchronized(callbackLock) {
                    if (accepting.get()) failed.set(true)
                }
            }
            if (unfiltered) unfilteredRan = true
            report = snapshot(unfiltered, failed = false, scanning = true)
            update(report)
            DiagnosticLog.record(if (unfiltered) DiagnosticEvent.BLE_UNFILTERED_START else DiagnosticEvent.BLE_FILTER_START)
            try {
                val filters = if (unfiltered) emptyList() else listOf(
                    ScanFilter.Builder().setManufacturerData(SONY_COMPANY, byteArrayOf()).build())
                started()
                scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                wait(SCAN_MS)
            } finally {
                synchronized(callbackLock) { accepting.set(false) }
                try { scanner.stopScan(callback) }
                finally {
                    report = snapshot(unfiltered, failed.get())
                    update(report)
                }
            }
            return failed.get()
        }
        val filterFailed = pass(false)
        // Bonded entries must not prevent the fallback when no camera advertisement was seen.
        val finalFailed = if (cameraAds.get() == 0) pass(true) else filterFailed
        report = report.copy(failed = finalFailed)
        DiagnosticLog.record(when {
            finalFailed -> DiagnosticEvent.BLE_FAILED
            cameraAds.get() == 0 -> DiagnosticEvent.BLE_EMPTY
            else -> DiagnosticEvent.BLE_FOUND
        })
        update(report)
        return report
    }
}
