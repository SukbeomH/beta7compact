package android.bluetooth

import android.bluetooth.le.BluetoothLeScanner

class BluetoothDevice(val address: String, val name: String? = null)
class BluetoothAdapter {
    var isEnabled = true
    var bluetoothLeScanner: BluetoothLeScanner? = BluetoothLeScanner()
    var bondedDevices: Set<BluetoothDevice> = emptySet()
}
class BluetoothManager(val adapter: BluetoothAdapter? = BluetoothAdapter())
