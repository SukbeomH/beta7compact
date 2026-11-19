package dev.sukbeomh.beta7compact

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import dev.sukbeomh.beta7compact.ui.theme.BetaTheme
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sukbeomh.beta7compact.ui.RecipeImportDialog
import dev.sukbeomh.beta7compact.recipe.RecipeShare
import dev.sukbeomh.beta7compact.core.LinkEvent
import dev.sukbeomh.beta7compact.ui.MainScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private var sharedRecipe by mutableStateOf<String?>(null)
    val refresh = MutableStateFlow(0)
    private val app get() = application as App
    private var permissionResult: ((Boolean) -> Unit)? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionResult?.invoke(it.values.all { granted -> granted }); permissionResult = null
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { app.cameraThumbnails.clear(); app.usb(LinkEvent.LOST); refresh.value++ }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> { app.cameraThumbnails.clear(); refresh.value++ }
                else -> refresh.value++
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.cameraThumbnails.clear()
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val filter = IntentFilter("$packageName.USB_PERMISSION").apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiveRecipe(intent)
        setContent { BetaTheme(settings = app.settings) {
            val dark = dev.sukbeomh.beta7compact.ui.theme.LocalAppTheme.current.dark
            androidx.compose.runtime.SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MainScreen(this, app)
            sharedRecipe?.let { code -> androidx.compose.runtime.key(code) {
                RecipeImportDialog(app,code,close={sharedRecipe=null})
            } }
        } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receiveRecipe(intent); refresh.value++ }
    private fun receiveRecipe(intent: Intent?) {
        if(intent?.action==Intent.ACTION_SEND && intent.type=="text/plain") {
            val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            sharedRecipe=if(text!=null && text.length<=RecipeShare.MAX_CODE_CHARS) text.toString() else "x".repeat(RecipeShare.MAX_CODE_CHARS+1)
            intent.removeExtra(Intent.EXTRA_TEXT)
            intent.action=null
        }
    }
    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }
    fun usbPermission() {
        val device = dev.sukbeomh.beta7compact.usb.UsbAccess.device(this)
        val manager = getSystemService(UsbManager::class.java)
        if (manager.hasPermission(device)) { refresh.value++; return }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        manager.requestPermission(device, PendingIntent.getBroadcast(this, 1, Intent("$packageName.USB_PERMISSION").setPackage(packageName), flags))
    }
    fun requestNotifications(callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) callback(true)
        else { permissionResult = callback; permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
    }

}
