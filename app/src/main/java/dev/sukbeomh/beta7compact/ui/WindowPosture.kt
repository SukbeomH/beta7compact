package dev.sukbeomh.beta7compact.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun rememberTabletop(width: Float): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val configuration = LocalConfiguration.current
    var angle by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(context, lifecycle) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = if (Build.VERSION.SDK_INT >= 30) manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) else null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) { angle = event.values.firstOrNull() }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        fun register() { sensor?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> { manager.unregisterListener(listener); angle = null }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
        onDispose { lifecycle.removeObserver(observer); manager.unregisterListener(listener) }
    }
    @Suppress("DEPRECATION")
    val rotation = remember(configuration) {
        if (Build.VERSION.SDK_INT >= 30) context.display?.rotation
        else context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.rotation
    }
    return isTabletop(angle, rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270, width)
}
