package dev.sukbeomh.beta7compact.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission") // Callers request BLE and (API 29/30) location permissions first.
object BleScanner {
    suspend fun scan(context: Context): List<BluetoothDevice> {
        if (Build.VERSION.SDK_INT <= 30) check(context.getSystemService(LocationManager::class.java).isLocationEnabled) {
            "휴대폰 위치 기능을 켜세요"
        }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        check(adapter?.isEnabled == true) { "휴대폰 Bluetooth를 켜세요" }
        val scanner = checkNotNull(adapter.bluetoothLeScanner) { "BLE 검색을 사용할 수 없습니다" }
        val devices = ConcurrentHashMap<String, BluetoothDevice>()
        var failure: Int? = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(0x012d) ?: return
                // Manufacturer ID alone also includes non-camera Sony products.
                if (data.size >= 2 && data[0] == 3.toByte() && data[1] == 0.toByte())
                    devices[result.device.address] = result.device
            }
            override fun onScanFailed(errorCode: Int) { failure = errorCode }
        }
        try {
            scanner.startScan(listOf(ScanFilter.Builder().setManufacturerData(0x012d, byteArrayOf(3, 0)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            delay(8000)
        } finally { scanner.stopScan(callback) }
        check(failure == null) { "BLE 검색에 실패했습니다. Bluetooth를 확인하세요" }
        return devices.values.toList()
    }
}
