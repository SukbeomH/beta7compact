package android.bluetooth.le

import android.bluetooth.BluetoothDevice

open class ScanCallback {
    open fun onScanResult(callbackType: Int, result: ScanResult) {}
    open fun onBatchScanResults(results: MutableList<ScanResult>) {}
    open fun onScanFailed(errorCode: Int) {}
}
class ScanRecord(private val data: Map<Int, ByteArray>) {
    fun getManufacturerSpecificData(company: Int): ByteArray? = data[company]
}
class ScanResult(val device: BluetoothDevice, val scanRecord: ScanRecord?)
class ScanFilter(val manufacturerId: Int, val manufacturerData: ByteArray) {
    class Builder {
        private var id = -1
        private var data = byteArrayOf()
        fun setManufacturerData(id: Int, data: ByteArray) = apply { this.id = id; this.data = data }
        fun build() = ScanFilter(id, data)
    }
}
class ScanSettings {
    companion object { const val SCAN_MODE_LOW_LATENCY = 2 }
    class Builder {
        fun setScanMode(mode: Int) = this
        fun build() = ScanSettings()
    }
}
class BluetoothLeScanner {
    val filters = mutableListOf<List<ScanFilter>>()
    var stopped = 0
    var active: ScanCallback? = null
    var started: (ScanCallback, Int) -> Unit = { _, _ -> }
    fun startScan(filters: List<ScanFilter>, settings: ScanSettings, callback: ScanCallback) {
        check(active == null) { "scans overlap" }
        this.filters.add(filters)
        active = callback
        started(callback, this.filters.size)
    }
    fun stopScan(callback: ScanCallback) { check(active === callback); active = null; stopped++ }
}
