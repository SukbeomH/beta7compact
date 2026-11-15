package dev.sukbeomh.beta7compact

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.sukbeomh.beta7compact.core.LinkEvent
import dev.sukbeomh.beta7compact.ui.MainScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    val refresh = MutableStateFlow(0)
    private val app get() = application as App
    var permissionResult: ((Boolean) -> Unit)? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionResult?.invoke(it.values.all { granted -> granted }); permissionResult = null
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { app.usb(LinkEvent.LOST); refresh.value++ }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> { refresh.value++ }
                else -> refresh.value++
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter("$packageName.USB_PERMISSION").apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        setContent { MainScreen(this, app) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); refresh.value++ }
    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }
    fun usbPermission() {
        val device = dev.sukbeomh.beta7compact.usb.UsbAccess.device(this)
        val manager = getSystemService(UsbManager::class.java)
        if (manager.hasPermission(device)) { refresh.value++; return }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        manager.requestPermission(device, PendingIntent.getBroadcast(this, 1, Intent("$packageName.USB_PERMISSION").setPackage(packageName), flags))
    }
    fun requestBle(callback: (Boolean) -> Unit) {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) needed += listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        request(needed, callback)
    }
    fun requestNotifications(callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= 33) request(listOf(Manifest.permission.POST_NOTIFICATIONS), callback) else callback(true)
    }
    private fun request(needed: List<String>, callback: (Boolean) -> Unit) {
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) callback(true)
        else { permissionResult = callback; permissions.launch(missing.toTypedArray()) }
    }
}
