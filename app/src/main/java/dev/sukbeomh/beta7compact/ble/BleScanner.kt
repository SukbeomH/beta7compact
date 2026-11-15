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
    val scanning: Boolean = false, val unfiltered: Boolean = false, val failed: Boolean = false
) {
    val diagnostic: String get() = "필터 스캔 Sony 광고 $filteredSony · 무필터 스캔 Sony 광고 $unfilteredSony · 카메라 유형 불일치 $typeMismatch"
}

@SuppressLint("MissingPermission") // Callers request BLE and (API 29/30) location permissions first.
object BleScanner {
    const val EMPTY_GUIDANCE = "카메라 광고가 없습니다. 카메라 MENU→네트워크→Bluetooth 설정→페어링 화면을 열고 다시 검색하세요. 이미 다른 폰/IEM과 페어링되어 있다면 카메라 쪽 페어링을 해제하세요."
    fun bonded(context: Context, saved: String?): List<BluetoothDevice> =
        context.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty()
            .filter { it.address == saved || cameraName(it.name) }

    suspend fun scan(context: Context, saved: String?, update: (ScanReport) -> Unit): ScanReport =
        scanWithWait(context, saved, update) { delay(it) }

    // Test seam replaces only the clock; the real filter, callbacks and fallback execute in contracts.
    internal suspend fun scanWithWait(context: Context, saved: String?, update: (ScanReport) -> Unit,
                                      wait: suspend (Long) -> Unit): ScanReport {
        val devices = ConcurrentHashMap<String, BluetoothDevice>()
        bonded(context, saved).forEach { devices[it.address] = it }
        var report = ScanReport(devices.values.toList(), scanning = true)
        update(report) // Bonded cameras are selectable before the first scan completes.
        if (Build.VERSION.SDK_INT <= 30) check(context.getSystemService(LocationManager::class.java).isLocationEnabled) {
            "휴대폰 위치 기능을 켜세요"
        }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        check(adapter?.isEnabled == true) { "휴대폰 Bluetooth를 켜세요" }
        val scanner = checkNotNull(adapter.bluetoothLeScanner) { "BLE 검색을 사용할 수 없습니다" }
        val filteredSony = AtomicInteger()
        val unfilteredSony = AtomicInteger()
        val mismatch = AtomicInteger()
        val cameraAds = AtomicInteger()
        suspend fun pass(unfiltered: Boolean): Boolean {
            val failed = AtomicBoolean()
            val accepting = AtomicBoolean(true)
            val callbackLock = Any()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) = record(result)
                override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::record) }
                private fun record(result: ScanResult) = synchronized(callbackLock) {
                    if (!accepting.get()) return
                    val data = result.scanRecord?.getManufacturerSpecificData(SONY_COMPANY) ?: return
                    (if (unfiltered) unfilteredSony else filteredSony).incrementAndGet()
                    if (!cameraType(data)) { mismatch.incrementAndGet(); return }
                    cameraAds.incrementAndGet()
                    devices[result.device.address] = result.device
                }
                override fun onScanFailed(errorCode: Int) = synchronized(callbackLock) {
                    if (accepting.get()) failed.set(true)
                }
            }
            report = report.copy(scanning = true, unfiltered = unfiltered)
            update(report)
            DiagnosticLog.record(if (unfiltered) DiagnosticEvent.BLE_UNFILTERED_START else DiagnosticEvent.BLE_FILTER_START)
            try {
                val filters = if (unfiltered) emptyList() else listOf(
                    ScanFilter.Builder().setManufacturerData(SONY_COMPANY, byteArrayOf()).build())
                scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                wait(SCAN_MS)
            } finally {
                synchronized(callbackLock) { accepting.set(false) }
                try { scanner.stopScan(callback) }
                finally {
                    report = ScanReport(devices.values.toList(), filteredSony.get(), unfilteredSony.get(),
                        mismatch.get(), unfiltered = unfiltered, failed = failed.get())
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
