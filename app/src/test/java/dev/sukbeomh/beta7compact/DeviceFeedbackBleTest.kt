package dev.sukbeomh.beta7compact

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import dev.sukbeomh.beta7compact.ble.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class DeviceFeedbackBleTest {
    private fun result(id: String, data: ByteArray = byteArrayOf(3, 0), company: Int = SONY_COMPANY) =
        ScanResult(BluetoothDevice(id, "ILCE-7C"), ScanRecord(mapOf(company to data)))
    private fun runScan(block: (Context, BluetoothLeScanner) -> Unit) {
        val previous = Build.VERSION.SDK_INT
        try {
            Build.VERSION.SDK_INT = 35
            val manager = BluetoothManager()
            block(Context(manager), manager.adapter!!.bluetoothLeScanner!!)
        } finally { Build.VERSION.SDK_INT = previous }
    }
    @Test fun emptyDataFilterAndCallbackTypeValidationAcceptLateCameraWithoutFallback() = runScan { context, scanner -> runBlocking {
        val waits = mutableListOf<Long>()
        val report = BleScanner.scanWithWait(context, null, {}) {
            waits += it
            val callback = scanner.active!!
            callback.onScanResult(0, result("not-sony", company = 7))
            callback.onScanResult(0, result("short", byteArrayOf(3)))
            callback.onScanResult(0, result("wrong", byteArrayOf(4, 0)))
            // Arrival after the first 10 seconds is represented at the end of the 15s wait.
            callback.onBatchScanResults(mutableListOf(result("camera"), result("camera")))
        }
        val filter = scanner.filters.single().single()
        assertEquals(SONY_COMPANY, filter.manufacturerId); assertTrue(filter.manufacturerData.isEmpty())
        assertEquals(listOf(15_000L), waits)
        assertEquals(4, report.filteredSony); assertEquals(0, report.unfilteredSony)
        assertEquals(2, report.typeMismatch); assertEquals(1, report.devices.size)
        assertEquals(1, scanner.stopped); assertNull(scanner.active)
    } }
    @Test fun zeroFilteredCamerasRetriesUnfilteredOnceAndCountsEachPath() = runScan { context, scanner -> runBlocking {
        val waits = mutableListOf<Long>()
        scanner.started = { callback, pass ->
            if (pass == 1) callback.onScanResult(0, result("speaker", byteArrayOf(0, 0)))
            else {
                callback.onScanResult(0, result("camera"))
                callback.onScanResult(0, result("other", company = 7))
                callback.onScanResult(0, result("bad", byteArrayOf()))
            }
        }
        val report = BleScanner.scanWithWait(context, null, {}) { waits += it }
        assertEquals(listOf(15_000L, 15_000L), waits)
        assertTrue(scanner.filters[1].isEmpty()); assertEquals(2, scanner.stopped)
        assertEquals(1, report.filteredSony); assertEquals(2, report.unfilteredSony)
        assertEquals(2, report.typeMismatch); assertEquals("camera", report.devices.single().address)
        assertContains(report.diagnostic, "필터 스캔 Sony 광고 1")
        assertContains(report.diagnostic, "무필터 스캔 Sony 광고 2")
    } }
    @Test fun bondedCamerasArePublishedBeforeScanningAndDoNotSuppressFallback() = runScan { context, scanner -> runBlocking {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter!!
        adapter.bondedDevices = setOf(BluetoothDevice("one", "ILCE-7C"), BluetoothDevice("two", "DSC-RX"),
            BluetoothDevice("three", "ZV-E"), BluetoothDevice("four", "ILME-FX"),
            BluetoothDevice("saved", "Custom name"), BluetoothDevice("headset", "Headset"))
        var first: ScanReport? = null
        val report = BleScanner.scanWithWait(context, "saved", { if (first == null) first = it }) {}
        assertEquals(5, first!!.devices.size)
        assertEquals(5, report.devices.size); assertEquals(2, scanner.filters.size)
        assertEquals(0, report.filteredSony); assertEquals(0, report.unfilteredSony)
    } }
    @Test fun doubleEmptyStopsAfterTwoScansAndCancellationStopsWithoutFallback() = runScan { context, scanner -> runBlocking {
        val report = BleScanner.scanWithWait(context, null, {}) {}
        assertTrue(report.devices.isEmpty()); assertEquals(2, scanner.stopped)
        assertContains(BleScanner.EMPTY_GUIDANCE, "MENU→네트워크→Bluetooth 설정→페어링")
        assertContains(BleScanner.EMPTY_GUIDANCE, "다른 폰/IEM")
        assertFailsWith<CancellationException> {
            BleScanner.scanWithWait(context, null, {}) { throw CancellationException() }
        }
        assertEquals(3, scanner.stopped); assertEquals(3, scanner.filters.size); assertNull(scanner.active)
    } }
    @Test fun scanFailedIsReportedAndCanRecoverOnUnfilteredPass() = runScan { context, scanner -> runBlocking {
        scanner.started = { callback, pass ->
            if (pass == 1) callback.onScanFailed(2) else callback.onScanResult(0, result("camera"))
        }
        val report = BleScanner.scanWithWait(context, null, {}) {}
        assertFalse(report.failed); assertEquals(1, report.devices.size); assertEquals(2, scanner.stopped)
    } }
    @Test fun lateCallbacksFromStoppedScanCannotChangeFallbackCountsOrResults() = runScan { context, scanner -> runBlocking {
        var previous: ScanCallback? = null
        var pass = 0
        val report = BleScanner.scanWithWait(context, null, {}) {
            if (++pass == 1) previous = scanner.active
            else {
                previous!!.onScanResult(0, result("stale"))
                previous!!.onScanFailed(2)
                scanner.active!!.onScanResult(0, result("current"))
            }
        }
        assertEquals(0, report.filteredSony); assertEquals(1, report.unfilteredSony)
        assertEquals("current", report.devices.single().address); assertFalse(report.failed)
    } }
    @Test fun sharedLogContainsOnlyAllowlistedEventsAndNoScanPayload() = runScan { context, scanner -> runBlocking {
        scanner.started = { callback, _ -> callback.onScanResult(0, result("12:34:56:78:9A:BC")) }
        BleScanner.scanWithWait(context, null, {}) {}
        val lines = DiagnosticLog.shareText().lines()
        assertEquals("beta7compact 0.1.2 진단 로그", lines.first())
        val allowed = DiagnosticEvent.entries.map { it.text }.toSet()
        assertTrue(lines.drop(1).all { it in allowed })
        assertFalse(DiagnosticLog.shareText().contains("9A:BC"))
        repeat(500) {
            DiagnosticLog.record(if (it % 2 == 0) DiagnosticEvent.IMPORT_STARTED else DiagnosticEvent.IMPORT_DONE)
        }
        assertEquals(201, DiagnosticLog.shareText().lines().size)
    } }
}
