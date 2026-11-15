package dev.sukbeomh.beta7compact.ble

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.sukbeomh.beta7compact.*
import dev.sukbeomh.beta7compact.core.LocationPacket
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId

class LocationService : Service() {
    private val app get() = application as App
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var link: LocationLink? = null
    private var location: Location? = null // Volatile in-memory only. Never stored or logged.
    private val manager by lazy { getSystemService(LocationManager::class.java) }
    private val listener = LocationListener { location = it }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY
        if (!allowed()) { app.locationState.value = LocationStatus(message = "위치·Bluetooth 권한을 허용하세요"); stopSelf(); return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("location", "위치 동기화", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 20, Intent(this, LocationService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 21, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, "location").setSmallIcon(R.drawable.ic_camera)
                .setContentTitle("카메라 위치 동기화").setContentText("선택한 카메라에 위치와 시각을 전송합니다")
                .setContentIntent(open).addAction(0, "끄기", stop).setOngoing(true).build()
            startForeground(20, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            job = scope.launch {
                try {
                    check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "휴대폰 GPS를 켜세요" }
                    app.locationState.value = app.locationState.value.copy(running = true, message = "카메라 연결 중")
                    if (ContextCompat.checkSelfPermission(this@LocationService, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("위치 권한을 허용하세요")
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, listener, Looper.getMainLooper())
                    val address = checkNotNull(app.settings.getString("camera", null)) { "먼저 카메라를 선택하고 페어링하세요" }
                    val adapter = checkNotNull(getSystemService(BluetoothManager::class.java).adapter) { "Bluetooth를 사용할 수 없습니다" }
                    check(adapter.isEnabled) { "휴대폰 Bluetooth를 켜세요" }
                    val connection = LocationLink(this@LocationService).also { link = it }
                    val includeZone = connection.connect(adapter.getRemoteDevice(address))
                    app.locationState.value = app.locationState.value.copy(running = true, message = "GPS 수신 대기")
                    while (isActive) {
                        check(allowed()) { "위치·Bluetooth 권한이 해제되었습니다" }
                        check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "휴대폰 GPS를 켜세요" }
                        check(connection.enabled.value == true) { "카메라 위치정보 연동이 꺼져 있습니다" }
                        val fix = location
                        val fresh = fix != null && (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) in 0..60_000_000_000L &&
                            fix.hasAccuracy() && fix.accuracy <= 100f && !fix.isFromMockProvider
                        if (fresh) {
                            connection.send(LocationPacket.encode(fix!!.latitude, fix.longitude, Instant.now(), ZoneId.systemDefault(), includeZone))
                            app.locationState.value = LocationStatus(true, "위치·시각 전송 완료", System.currentTimeMillis())
                        } else app.locationState.value = app.locationState.value.copy(message = "정확한 GPS 수신 대기")
                        delay(app.settings.getInt("interval", 10).coerceIn(5, 60) * 1000L)
                    }
                } catch (e: TimeoutCancellationException) {
                    app.locationState.value = app.locationState.value.copy(running = false,
                        message = "BLE 응답 시간 초과. 카메라 위치 연동을 껐다 켜세요")
                } catch (e: CancellationException) { throw e }
                catch (e: SecurityException) {
                    app.locationState.value = app.locationState.value.copy(running = false, message = "위치·Bluetooth 권한을 허용하세요")
                }
                catch (e: Exception) {
                    val reason = when (e) {
                        is TimeoutCancellationException -> "BLE 응답 시간 초과. 카메라 위치 연동을 껐다 켜세요"
                        is SecurityException -> "위치·Bluetooth 권한을 허용하세요"
                        else -> e.message?.takeIf { it.any { char -> char in '가'..'힣' } } ?: "BLE 연결에 실패했습니다. 카메라 설정을 확인하세요"
                    }
                    app.locationState.value = app.locationState.value.copy(running = false, message = reason)
                } finally { stopSelf() }
            }
        } catch (e: Exception) {
            app.locationState.value = LocationStatus(message = "위치 서비스를 시작할 수 없습니다. 앱을 열고 권한을 확인하세요")
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun allowed() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
    override fun onDestroy() {
        job?.cancel(); scope.cancel()
        runCatching { manager.removeUpdates(listener) }
        runCatching { link?.close() }; link = null; location = null
        app.locationState.value = app.locationState.value.copy(running = false,
            message = if (app.locationState.value.running) "꺼짐" else app.locationState.value.message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
